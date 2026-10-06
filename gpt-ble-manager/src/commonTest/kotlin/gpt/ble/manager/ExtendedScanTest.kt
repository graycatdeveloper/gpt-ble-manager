package gpt.ble.manager

import gpt.ble.manager.internal.scan.Advertisement
import gpt.ble.manager.internal.scan.ScanStore
import gpt.ble.manager.internal.scan.decodeServiceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtendedScanTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val uuid = BleUuid.parse("180f")

    @Test
    fun maskedFiltersUseOffsetsAndRejectShortPayloads() {
        val filter =
            DataFilter(
                BleBytes(byteArrayOf(0xa0.toByte())),
                BleBytes(byteArrayOf(0xf0.toByte())),
                offset = 1,
            )
        assertTrue(filter.matches(BleBytes(byteArrayOf(0, 0xaf.toByte()))))
        assertFalse(filter.matches(BleBytes(byteArrayOf(0, 0x9f.toByte()))))
        assertFalse(filter.matches(BleBytes(byteArrayOf(0))))
        assertFalse(filter.matches(null))
        assertFailsWith<IllegalArgumentException> {
            DataFilter(BleBytes(byteArrayOf(0)), BleBytes(byteArrayOf()))
        }
    }

    @Test
    fun filtersComposeAfterMergingPackets() {
        val options =
            ScanOptions(
                nameExact = "sensor",
                ignoreNameCase = true,
                addresses = setOf(address.lowercase()),
                minRssi = -70,
                serviceDataFilters = mapOf(uuid to DataFilter(BleBytes(byteArrayOf(7)))),
                manufacturerFilters = mapOf(42 to DataFilter()),
            )
        val store = ScanStore(options)
        store.accept(Advertisement(BleDevice(address, "Sensor", -60)))
        assertTrue(store.devices.value.isEmpty())
        store.accept(
            Advertisement(
                BleDevice(
                    address,
                    serviceData = mapOf(uuid to BleBytes(byteArrayOf(7, 8))),
                    manufacturerData = mapOf(42 to BleBytes(byteArrayOf())),
                )
            )
        )
        assertEquals("Sensor", store.devices.value.single().name)
        store.accept(Advertisement(BleDevice(address, rssi = -90)))
        assertTrue(store.devices.value.isEmpty())
    }

    @Test
    fun packetsKeepTheirOwnPayloadWhileSnapshotsMergeAndExpireMonotonically() {
        var elapsed = 0L
        var epoch = 10_000L
        val events = mutableListOf<ScanEvent>()
        val store =
            ScanStore(ScanOptions(lostTimeoutMillis = 100), events::add, { epoch }, { elapsed })
        store.accept(
            Advertisement(BleDevice(address, serviceData = mapOf(uuid to BleBytes(byteArrayOf(1)))))
        )
        epoch = 5_000 // Wall clock adjustment must not expire a nearby device.
        elapsed = 50
        store.accept(Advertisement(BleDevice(address, "Sensor")))
        assertEquals(5_000, store.devices.value.single().lastSeenMillis)
        assertEquals(BleBytes(byteArrayOf(1)), store.devices.value.single().serviceData[uuid])
        assertTrue(
            events.filterIsInstance<ScanEvent.Packet>().last().advertisement.serviceData.isEmpty()
        )
        elapsed = 149
        store.expire()
        assertEquals(1, store.devices.value.size)
        elapsed = 150
        store.expire()
        assertTrue(store.devices.value.isEmpty())
        assertEquals(1, events.filterIsInstance<ScanEvent.Disappeared>().size)
    }

    @Test
    fun serviceDataDecodesAllUuidWidthsAndIgnoresTruncation() {
        val uuid128 = BleUuid.parse("00112233-4455-6677-8899-aabbccddeeff")
        val bytes128 =
            "ffeeddccbbaa99887766554433221100"
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        val result =
            decodeServiceData(
                arrayOf(
                    byteArrayOf(0x16, 0x0f, 0x18, 42),
                    byteArrayOf(0x20, 0x78, 0x56, 0x34, 0x12, 9),
                    byteArrayOf(0x21) + bytes128 + byteArrayOf(8),
                    byteArrayOf(0x16, 1),
                )
            )
        assertEquals(3, result.size)
        assertEquals(BleBytes(byteArrayOf(42)), result[uuid])
        assertEquals(BleBytes(byteArrayOf(9)), result[BleUuid.parse("12345678")])
        assertEquals(BleBytes(byteArrayOf(8)), result[uuid128])
    }
}
