package gpt.ble.manager

import gpt.ble.manager.internal.scan.Advertisement
import gpt.ble.manager.internal.scan.ScanStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanStoreTest {
    private val address = "11:22:33:44:55:66"
    private val service = BleUuid.parse("180f")

    @Test
    fun separateNameAndServicePacketsAreMerged() {
        val store = ScanStore(ScanOptions(serviceUuids = setOf(service)))
        store.accept(Advertisement(BleDevice(address, serviceUuids = setOf(service))))
        val before = store.devices.value.single()
        assertNull(before.name)
        store.accept(Advertisement(BleDevice(address, name = "ExampleSensor")))
        assertEquals("ExampleSensor", store.devices.value.single().name)
        assertNull(before.name, "Previously emitted snapshots must stay unchanged")
    }

    @Test
    fun nameArrivingBeforeServiceIsRetained() {
        val store = ScanStore(ScanOptions(serviceUuids = setOf(service)))
        store.accept(Advertisement(BleDevice(address, name = "ExampleSensor")))
        assertTrue(store.devices.value.isEmpty())
        store.accept(Advertisement(BleDevice(address, serviceUuids = setOf(service))))
        assertEquals("ExampleSensor", store.devices.value.single().name)
    }

    @Test
    fun completeNameWinsOverShortenedAndEmptyNames() {
        val store = ScanStore(ScanOptions())
        store.accept(Advertisement(BleDevice(address, "Example"), false))
        store.accept(Advertisement(BleDevice(address, "ExampleSensor \uD83D\uDD0B"), true))
        store.accept(Advertisement(BleDevice(address, "Example"), false))
        store.accept(Advertisement(BleDevice(address, ""), true))
        assertEquals("ExampleSensor \uD83D\uDD0B", store.devices.value.single().name)
        store.accept(Advertisement(BleDevice(address, "New name"), true))
        assertEquals("New name", store.devices.value.single().name)
    }

    @Test
    fun manufacturerDataAndConnectabilitySurviveScanResponses() {
        val store = ScanStore(ScanOptions())
        store.accept(
            Advertisement(
                BleDevice(
                    address,
                    connectable = true,
                    manufacturerData = mapOf(76 to BleBytes(byteArrayOf(1, 2))),
                )
            )
        )
        store.accept(Advertisement(BleDevice(address, "ExampleSensor", connectable = false)))
        assertEquals(true, store.devices.value.single().connectable)
        assertEquals(BleBytes(byteArrayOf(1, 2)), store.devices.value.single().manufacturerData[76])
    }

    @Test
    fun restartAndOtherAddressesDoNotInheritNames() {
        val store = ScanStore(ScanOptions())
        store.accept(Advertisement(BleDevice(address, "ExampleSensor")))
        store.accept(Advertisement(BleDevice("AA:BB:CC:DD:EE:FF")))
        assertNull(store.devices.value.last().name)
        assertTrue(ScanStore(ScanOptions()).devices.value.isEmpty())
    }

    @Test
    fun filtersUseAnyRequestedServiceAndOptionalNamePrefix() {
        val store = ScanStore(ScanOptions(setOf(service, BleUuid.parse("180d")), "Example"))
        store.accept(
            Advertisement(
                BleDevice(address, "ExampleSensor", serviceUuids = setOf(BleUuid.parse("180d")))
            )
        )
        assertEquals(1, store.devices.value.size)
        store.accept(
            Advertisement(BleDevice("AA:BB:CC:DD:EE:FF", "Other", serviceUuids = setOf(service)))
        )
        assertEquals(1, store.devices.value.size)
    }

    @Test
    fun byteValuesAreImmutableAndComparedByContent() {
        val input = byteArrayOf(1, 2)
        val bytes = BleBytes(input)
        input[0] = 7
        bytes.toByteArray()[1] = 8
        assertEquals(BleBytes(byteArrayOf(1, 2)), bytes)
    }

    @Test
    fun uuidFormsNormalizeToSameValue() {
        assertEquals(BleUuid.parse("180F"), BleUuid.parse("0000180f-${BleUuid.SUFFIX}"))
        assertEquals(BleUuid.parse("0x180f"), BleUuid.parse("0000180f"))
        assertFailsWith<IllegalArgumentException> { BleUuid.parse("xyz") }
    }

    @Test
    fun systemNameResolvesAnAdvertisedDeviceBeforeApplyingNameFilter() {
        val store = ScanStore(ScanOptions(namePrefix = "ExampleSensor"))
        store.accept(Advertisement(BleDevice(address, rssi = -60)))
        assertTrue(store.devices.value.isEmpty())
        store.rememberSystemDevice(BleDevice(address, "ExampleSensorBLE"))
        val device = store.devices.value.single()
        assertEquals("ExampleSensorBLE", device.name)
        assertEquals(DeviceNameSource.System, device.nameSource)
        assertEquals(-60, device.rssi)
        assertTrue(device.seenInCurrentScan)
    }

    @Test
    fun cachedNameBeforeAdvertisementIsHiddenUntilPacketArrives() {
        val store = ScanStore(ScanOptions())
        store.rememberSystemDevice(BleDevice(address, "ExampleSensorBLE"))
        assertTrue(store.devices.value.isEmpty())
        store.accept(Advertisement(BleDevice(address)))
        assertEquals("ExampleSensorBLE", store.devices.value.single().name)
    }

    @Test
    fun optInKnownDevicesNeverInventsRssiOrAnAdvertisement() {
        val store = ScanStore(ScanOptions(includeKnownDevices = true))
        store.rememberSystemDevice(
            BleDevice(address, "ExampleSensorBLE", rssi = -1, seenInCurrentScan = true)
        )
        val cached = store.devices.value.single()
        assertFalse(cached.seenInCurrentScan)
        assertNull(cached.rssi)
        store.accept(Advertisement(BleDevice(address, rssi = -52)))
        assertTrue(store.devices.value.single().seenInCurrentScan)
        assertFalse(cached.seenInCurrentScan)
    }

    @Test
    fun advertisedNameWinsOverSystemAndGattRegardlessOfArrivalOrder() {
        val store = ScanStore(ScanOptions())
        store.rememberSystemDevice(BleDevice(address, "Cached"))
        store.accept(Advertisement(BleDevice(address, "Advertised")))
        store.updateGattName(address, "Gatt")
        store.rememberSystemDevice(BleDevice(address, "Stale cache"))
        assertEquals("Advertised", store.devices.value.single().name)
        assertEquals(DeviceNameSource.Advertisement, store.devices.value.single().nameSource)
    }

    @Test
    fun freshGattNameReplacesSystemNameAndReappliesFilter() {
        val store = ScanStore(ScanOptions(namePrefix = "ExampleSensor", includeKnownDevices = true))
        store.rememberSystemDevice(BleDevice(address, "Old"))
        assertTrue(store.devices.value.isEmpty())
        store.updateGattName(address, "ExampleSensorBLE")
        assertEquals(DeviceNameSource.Gatt, store.devices.value.single().nameSource)
        store.rememberSystemDevice(BleDevice(address, "Stale"))
        assertEquals("ExampleSensorBLE", store.devices.value.single().name)
    }

    @Test
    fun systemAddressPlaceholderAndBlankNamesAreNotRealNames() {
        val store = ScanStore(ScanOptions(includeKnownDevices = true))
        store.rememberSystemDevice(BleDevice(address, address.lowercase().replace(':', '-')))
        assertNull(store.devices.value.single().name)
        store.rememberSystemDevice(BleDevice(address, "Real"))
        store.rememberSystemDevice(BleDevice(address, "  "))
        assertEquals("Real", store.devices.value.single().name)
    }

    @Test
    fun systemRecordsMergeByCanonicalAddressWithoutCrossDeviceNames() {
        val store = ScanStore(ScanOptions(includeKnownDevices = true))
        store.rememberSystemDevice(BleDevice("aa:bb:cc:dd:ee:ff", "ExampleSensorBLE"))
        store.accept(Advertisement(BleDevice("AA:BB:CC:DD:EE:FF", rssi = -40)))
        store.accept(Advertisement(BleDevice(address, "ExampleSensor")))
        assertEquals(2, store.devices.value.size)
        assertEquals("ExampleSensorBLE", store.devices.value.first().name)
        assertEquals("ExampleSensor", store.devices.value.last().name)
    }

    @Test
    fun knownDevicesDoNotBypassServiceFilter() {
        val store =
            ScanStore(ScanOptions(serviceUuids = setOf(service), includeKnownDevices = true))
        store.rememberSystemDevice(BleDevice(address, "ExampleSensorBLE"))
        assertTrue(store.devices.value.isEmpty())
        store.accept(Advertisement(BleDevice(address, serviceUuids = setOf(service))))
        assertEquals(1, store.devices.value.size)
    }
}
