package gpt.ble.manager

import gpt.ble.manager.internal.gatt.ManagedConnection
import gpt.ble.manager.internal.scan.Advertisement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class DeviceNameTest {
    @Test
    fun readingGattNameUpdatesConnectionAndNotifiesManager() = runTest {
        var reported: String? = null
        val connection = NameConnection(onName = { reported = it })
        val before = connection.device
        assertEquals("ExampleSensorBLE", connection.readDeviceName())
        assertEquals("ExampleSensorBLE", connection.device.name)
        assertEquals(DeviceNameSource.Gatt, connection.deviceDetails.value.nameSource)
        assertEquals("ExampleSensorBLE", reported)
        assertNull(before.name)
    }

    @Test
    fun ordinaryReadOfDeviceNameAlsoUpdatesTheName() = runTest {
        val connection = NameConnection()
        connection.read(connection.discoverServices().single().characteristics.single())
        assertEquals("ExampleSensorBLE", connection.device.name)
    }

    @Test
    fun advertisementNameIsPreservedButActualGattNameIsReturned() = runTest {
        val connection =
            NameConnection(
                initial =
                    BleDevice(
                        "11:22:33:44:55:66",
                        "ExampleSensor",
                        nameSource = DeviceNameSource.Advertisement,
                    )
            )
        assertEquals("ExampleSensorBLE", connection.readDeviceName())
        assertEquals("ExampleSensor", connection.device.name)
        assertEquals(DeviceNameSource.Advertisement, connection.device.nameSource)
    }

    @Test
    fun sameCharacteristicUuidInAnotherServiceIsNotADeviceName() = runTest {
        val connection = NameConnection(service = "ffb0")
        assertNull(connection.readDeviceName())
        connection.read(connection.discoverServices().single().characteristics.single())
        assertNull(connection.device.name)
    }

    @Test
    fun blankAndMalformedValuesDoNotEraseTheSystemName() = runTest {
        for (bytes in
            listOf(byteArrayOf(0), "  ".encodeToByteArray(), byteArrayOf(0xc3.toByte(), 0x28))) {
            val connection =
                NameConnection(
                    value = bytes,
                    initial =
                        BleDevice(
                            "11:22:33:44:55:66",
                            "Cached",
                            nameSource = DeviceNameSource.System,
                        ),
                )
            assertNull(connection.readDeviceName())
            assertEquals("Cached", connection.device.name)
        }
    }

    @Test
    fun utf8NameAndTrailingNulAreHandled() = runTest {
        val connection = NameConnection(value = "Ментарис 🔋\u0000".encodeToByteArray())
        assertEquals("Ментарис 🔋", connection.readDeviceName())
    }

    @Test
    fun aClosedConnectionRejectsNameReads() = runTest {
        val connection = NameConnection()
        connection.close()
        assertFailsWith<BleException> { connection.readDeviceName() }
        assertNull(connection.device.name)
    }
}

private class NameConnection(
    private val value: ByteArray = "ExampleSensorBLE".encodeToByteArray(),
    private val service: String = "1800",
    initial: BleDevice = BleDevice("11:22:33:44:55:66"),
    onName: (String) -> Unit = {},
) : ManagedConnection("name-test", initial, onDeviceName = onName) {
    init {
        markConnected()
    }

    override suspend fun discoverServices(): List<GattService> = operation {
        listOf(
                GattService(
                    1,
                    BleUuid.parse(service),
                    listOf(GattCharacteristic(id, 2, 1, BleUuid.parse("2a00"), 2)),
                )
            )
            .also { mutableServices.value = it }
    }

    override suspend fun read(characteristic: GattCharacteristic): BleBytes = operation {
        checkCharacteristic(characteristic)
        recordDeviceName(characteristic, value)
        BleBytes(value)
    }

    override suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode,
    ): Unit = error("Unused")

    override suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes = error("Unused")

    override suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray): Unit =
        error("Unused")

    override suspend fun subscribe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode,
    ): Unit = error("Unused")

    override suspend fun requestMtu(size: Int): Int = error("Unused")

    override fun releasePlatform() = Unit
}
