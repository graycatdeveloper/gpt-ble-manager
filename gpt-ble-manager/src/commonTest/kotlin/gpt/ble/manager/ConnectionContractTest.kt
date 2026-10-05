@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package gpt.ble.manager

import gpt.ble.manager.internal.gatt.ManagedConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class ConnectionContractTest {
    @Test
    fun duplicateUuidsKeepTheirOwnIdentityAndRejectCrossConnectionHandles() = runTest {
        val first = FakeConnection("first")
        val second = FakeConnection("second")
        val chars = first.discoverServices().flatMap { it.characteristics }
        assertEquals(chars[0].uuid, chars[1].uuid)
        assertNotEquals(chars[0].id, chars[1].id)
        first.read(chars[1])
        assertEquals(chars[1].id, first.lastRead)
        assertFailsWith<IllegalArgumentException> { second.read(chars[0]) }
    }

    @Test
    fun writeValidatesPropertiesAndMtuBeforeTouchingTheTransport() = runTest {
        val connection = FakeConnection("first")
        val characteristic = connection.discoverServices().single().characteristics.first()
        connection.write(characteristic, ByteArray(20))
        assertEquals(1, connection.writes)
        assertFailsWith<IllegalArgumentException> {
            connection.write(characteristic, ByteArray(21))
        }
        assertFailsWith<IllegalArgumentException> {
            connection.write(characteristic, byteArrayOf(1), WriteMode.WithoutResponse)
        }
        assertEquals(1, connection.writes)
    }

    @Test
    fun closingIsIdempotentAndLateConnectCannotResurrectSession() = runTest {
        val connection = FakeConnection("first")
        val characteristic = connection.discoverServices().single().characteristics.first()
        connection.close()
        connection.close()
        connection.lateConnect()
        assertEquals(1, connection.releases)
        assertEquals(ConnectionState.Disconnected, connection.state.value)
        assertTrue(connection.services.value.isEmpty())
        assertFailsWith<BleException> { connection.read(characteristic) }
    }

    @Test
    fun notificationPayloadsAreCopiedAndIgnoredAfterClose() = runTest {
        val connection = FakeConnection("first")
        val characteristic = connection.discoverServices().single().characteristics.first()
        val events = mutableListOf<CharacteristicValue>()
        val collector =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                connection.notifications.toList(events)
            }
        val source = byteArrayOf(1, 2)
        connection.emit(characteristic.id, source)
        source[0] = 9
        connection.close()
        connection.emit(characteristic.id, byteArrayOf(3))
        assertEquals(1, events.size)
        assertEquals(BleBytes(byteArrayOf(1, 2)), events.single().value)
        collector.cancel()
    }
}

private class FakeConnection(id: String) : ManagedConnection(id, BleDevice("11:22:33:44:55:66")) {
    var lastRead = 0
    var writes = 0
    var releases = 0

    init {
        markConnected()
    }

    fun lateConnect() = markConnected()

    fun emit(attribute: Int, bytes: ByteArray) = emitValue(attribute, bytes)

    override suspend fun discoverServices(): List<GattService> = operation {
        listOf(
                GattService(
                    1,
                    BleUuid.parse("180f"),
                    listOf(
                        GattCharacteristic(id, 2, 1, BleUuid.parse("2a19"), 0x1a),
                        GattCharacteristic(id, 3, 1, BleUuid.parse("2a19"), 0x1a),
                    ),
                )
            )
            .also { mutableServices.value = it }
    }

    override suspend fun read(characteristic: GattCharacteristic): BleBytes = operation {
        checkCharacteristic(characteristic)
        lastRead = characteristic.id
        BleBytes(byteArrayOf(42))
    }

    override suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode,
    ) = operation {
        checkWrite(characteristic, value, mode)
        writes++
        Unit
    }

    override suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes =
        error("Not used by this fake")

    override suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray): Unit =
        error("Not used by this fake")

    override suspend fun subscribe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode,
    ): Unit = error("Not used by this fake")

    override suspend fun requestMtu(size: Int): Int = error("Not used by this fake")

    override fun releasePlatform() {
        releases++
    }
}
