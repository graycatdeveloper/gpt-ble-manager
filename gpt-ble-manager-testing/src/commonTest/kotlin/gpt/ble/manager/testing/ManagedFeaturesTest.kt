@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package gpt.ble.manager.testing

import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.BleUuid
import gpt.ble.manager.ConnectionState
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.SubscriptionMode
import gpt.ble.manager.session.CharacteristicSelector
import gpt.ble.manager.session.ReconnectPolicy
import gpt.ble.manager.session.SessionOptions
import gpt.ble.manager.session.SessionState
import gpt.ble.manager.session.openSession
import gpt.ble.manager.transfer.ChunkedWriteOptions
import gpt.ble.manager.transfer.writeChunks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ManagedFeaturesTest {
    private val device = BleDevice("AA:BB:CC:DD:EE:FF", "Sensor")
    private val service = BleUuid.parse("180f")
    private val attribute = BleUuid.parse("2a19")
    private val selector = CharacteristicSelector(service, attribute)

    private fun manager() =
        FakeBleManager(
            listOf(
                FakePeripheral(
                    device,
                    listOf(
                        FakeService(
                            service,
                            listOf(FakeCharacteristic(attribute, properties = 0x3e)),
                        )
                    ),
                )
            )
        )

    private suspend fun connection(
        manager: FakeBleManager
    ): Pair<FakeBleConnection, GattCharacteristic> {
        val connection = manager.connect(device) as FakeBleConnection
        return connection to selector.resolve(connection.discoverServices())
    }

    @Test
    fun immediateNotificationDuringSubscribeIsNotLost() = runTest {
        val (connection, char) = connection(manager())
        connection.onSubscriptionChanged = { c, mode ->
            if (mode != SubscriptionMode.Disabled) {
                connection.notify(c, byteArrayOf(42))
            }
        }
        assertEquals(BleBytes(byteArrayOf(42)), connection.observe(char).first())
        assertEquals(
            listOf(SubscriptionMode.Notify, SubscriptionMode.Disabled),
            connection.subscriptions.map { it.second },
        )
    }

    @Test
    fun twoCollectorsShareCccdAndOnlyLastOneDisablesIt() = runTest {
        val (connection, char) = connection(manager())
        val first = mutableListOf<BleBytes>()
        val second = mutableListOf<BleBytes>()
        val a =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                connection.observe(char).toList(first)
            }
        val b =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                connection.observe(char).toList(second)
            }
        runCurrent()
        connection.notify(char, byteArrayOf(1))
        runCurrent()
        assertEquals(first, second)
        assertEquals(1, first.size)
        a.cancelAndJoin()
        assertEquals(1, connection.subscriptions.size)
        b.cancelAndJoin()
        assertEquals(SubscriptionMode.Disabled, connection.subscriptions.last().second)
    }

    @Test
    fun conflictingModesFailWithoutDisablingExistingCollector() = runTest {
        val (connection, char) = connection(manager())
        val first = backgroundScope.launch { connection.observe(char).collect() }
        runCurrent()
        assertFailsWith<IllegalArgumentException> {
            connection.observe(char, SubscriptionMode.Indicate).first()
        }
        assertEquals(1, connection.subscriptions.size)
        first.cancelAndJoin()
    }

    @Test
    fun disconnectionTerminatesObservationWithReason() = runTest {
        val (connection, char) = connection(manager())
        val result = async { runCatching { connection.observe(char).first() } }
        runCurrent()
        connection.disconnect()
        assertEquals(BleError.Disconnected, (result.await().exceptionOrNull() as BleException).code)
    }

    @Test
    fun chunkingCopiesInputRespectsMtuAndReportsProgress() = runTest {
        val (connection, char) = connection(manager())
        val data = ByteArray(45) { it.toByte() }
        val transfer = connection.writeChunks(char, data, ChunkedWriteOptions(pacingMillis = 5))
        data.fill(99)
        val progress = transfer.toList()
        assertEquals(listOf(20, 20, 5), connection.writes.map { it.value.size })
        assertEquals(0, connection.writes.first().value.toByteArray()[0].toInt())
        assertTrue(progress.last().complete)
        assertEquals(10, testScheduler.currentTime)
    }

    @Test
    fun cancellingBetweenPacketsStopsTransferWithoutReplay() = runTest {
        val (connection, char) = connection(manager())
        connection.writeChunks(char, ByteArray(100)).take(2).collect()
        assertEquals(1, connection.writes.size)
        assertEquals(ConnectionState.Connected, connection.state.value)
    }

    @Test
    fun framingReservesHeaderSpaceAndRejectsOversizedPackets() = runTest {
        val (connection, char) = connection(manager())
        connection
            .writeChunks(char, ByteArray(40), ChunkedWriteOptions(framingOverheadBytes = 2)) { chunk
                ->
                byteArrayOf(chunk.index.toByte(), chunk.payload.size.toByte()) +
                    chunk.payload.toByteArray()
            }
            .collect()
        assertEquals(listOf(20, 20, 6), connection.writes.map { it.value.size })
        assertEquals(2, connection.writes.last().value.toByteArray()[0].toInt())
        assertFailsWith<IllegalArgumentException> {
            connection.writeChunks(char, byteArrayOf(1)) { ByteArray(21) }.collect()
        }
    }

    @Test
    fun reconnectDiscoversFreshHandlesAndRestoresObservers() = runTest {
        val manager = manager()
        val session =
            manager.openSession(
                device,
                backgroundScope,
                SessionOptions(reconnect = ReconnectPolicy(initialDelayMillis = 10)),
            )
        val values = mutableListOf<BleBytes>()
        val collector = backgroundScope.launch { session.observe(selector).toList(values) }
        val first = session.awaitReady() as FakeBleConnection
        runCurrent()
        val old = selector.resolve(first.services.value)
        first.notify(old, byteArrayOf(1))
        runCurrent()
        first.disconnect()
        runCurrent()
        assertIs<SessionState.Reconnecting>(session.state.value)
        advanceTimeBy(10)
        runCurrent()
        val second = session.awaitReady() as FakeBleConnection
        assertNotEquals(first.id, second.id)
        assertFailsWith<IllegalArgumentException> { second.read(old) }
        second.notify(selector.resolve(second.services.value), byteArrayOf(2))
        runCurrent()
        assertEquals(listOf(BleBytes(byteArrayOf(1)), BleBytes(byteArrayOf(2))), values)
        assertEquals(1, manager.connections.value.size)
        session.closeAndJoin()
        collector.join()
        assertTrue(manager.connections.value.isEmpty())
    }

    @Test
    fun manualDisconnectAndManagerCloseDoNotReconnect() = runTest {
        val manager = manager()
        val session = manager.openSession(device, backgroundScope)
        session.awaitReady().close()
        runCurrent()
        assertEquals(SessionState.Closed, session.state.value)
        advanceTimeBy(100_000)
        assertEquals(1, manager.connectAttempts)
        val other = manager.openSession(device, backgroundScope)
        other.awaitReady()
        manager.close()
        runCurrent()
        assertEquals(SessionState.Closed, other.state.value)
    }

    @Test
    fun retryBudgetAndNonRetryableErrorsAreRespected() = runTest {
        val manager = manager()
        repeat(4) {
            manager.connectOutcomes.add(
                FakeOutcome(error = BleException(BleError.Disconnected, "Unavailable"))
            )
        }
        val session =
            manager.openSession(
                device,
                this,
                SessionOptions(
                    reconnect = ReconnectPolicy(maxAttempts = 2, initialDelayMillis = 10)
                ),
            )
        assertFailsWith<BleException> { session.awaitReady() }
        assertEquals(3, manager.connectAttempts)
        assertEquals(30, testScheduler.currentTime)
        manager.connectOutcomes.clear()
        manager.connectOutcomes.add(
            FakeOutcome(error = BleException(BleError.PermissionDenied, "Denied"))
        )
        val other = manager.openSession(device, this)
        assertFailsWith<BleException> { other.awaitReady() }
        assertEquals(4, manager.connectAttempts)
    }

    @Test
    fun failedWriteIsNeverRetriedBySession() = runTest {
        val manager = manager()
        val session = manager.openSession(device, backgroundScope)
        val connection = session.awaitReady() as FakeBleConnection
        connection.enqueue("write", FakeOutcome(error = BleException(BleError.Protocol, "Denied")))
        assertFailsWith<BleException> {
            connection.write(selector.resolve(connection.services.value), byteArrayOf(1))
        }
        runCurrent()
        assertTrue(connection.writes.isEmpty())
        assertEquals(1, manager.connectAttempts)
        session.closeAndJoin()
    }

    @Test
    fun cancelledScopePublishesClosedEvenBeforeFirstAttempt() = runTest {
        val scope =
            CoroutineScope(Job().also { it.cancel() } + StandardTestDispatcher(testScheduler))
        val session = manager().openSession(device, scope)
        runCurrent()
        assertEquals(SessionState.Closed, session.state.value)
    }
}
