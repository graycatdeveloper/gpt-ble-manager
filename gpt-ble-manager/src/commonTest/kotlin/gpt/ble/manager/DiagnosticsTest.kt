@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package gpt.ble.manager

import gpt.ble.manager.internal.gatt.ManagedConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class DiagnosticsTest {
    @Test
    fun overrideExecutionTimeoutClosesOnlyThatConnectionAndRecordsContext() = runTest {
        val events = mutableListOf<BleDiagnosticEvent>()
        val connection =
            DiagnosticConnection(BleManagerOptions(diagnostics = BleDiagnosticSink(events::add)))
        val error =
            assertFailsWith<BleException> {
                withOperationTimeouts(OperationTimeouts(executionMillis = 10)) {
                    connection.read(connection.attribute)
                }
            }
        assertEquals(BleError.Timeout, error.code)
        assertEquals(ConnectionState.Disconnected, connection.state.value)
        assertEquals(BleUuid.parse("180f"), error.details.serviceUuid)
        assertEquals(connection.attribute.uuid, error.details.characteristicUuid)
        assertEquals("read", error.details.operation)
        assertEquals(
            listOf(OperationPhase.Queued, OperationPhase.Started, OperationPhase.Failed),
            events.map { it.phase },
        )
    }

    @Test
    fun queueBudgetDoesNotTerminateRunningReadAndCanBeOverriddenPerCall() = runTest {
        val connection = DiagnosticConnection(BleManagerOptions())
        val first = async { connection.read(connection.attribute) }
        runCurrent()
        val failure =
            assertFailsWith<BleException> {
                withOperationTimeouts(OperationTimeouts(queueWaitMillis = 5)) {
                    connection.read(connection.attribute)
                }
            }
        assertEquals(OperationPhase.Queued, failure.details.phase)
        assertEquals(ConnectionState.Connected, connection.state.value)
        assertEquals(BleBytes(byteArrayOf(42)), first.await())
        connection.close()
    }

    @Test
    fun brokenSinkCannotBreakGattAndNumericStatusSurvivesWrapping() = runTest {
        val connection =
            DiagnosticConnection(
                BleManagerOptions(diagnostics = BleDiagnosticSink { error("Logging failed") })
            )
        connection.failure =
            BleException(
                BleError.Protocol,
                "Denied",
                details = BleErrorDetails(platform = "Test", platformStatus = 5),
            )
        val error = assertFailsWith<BleException> { connection.read(connection.attribute) }
        assertEquals(5, error.details.platformStatus)
        assertEquals("Test", error.details.platform)
        assertEquals(ConnectionState.Connected, connection.state.value)
        connection.close()
    }
}

private class DiagnosticConnection(options: BleManagerOptions) :
    ManagedConnection(
        "diagnostic",
        BleDevice("AA:BB:CC:DD:EE:FF"),
        options = options,
    ) {
    val attribute = GattCharacteristic(id, 2, 1, BleUuid.parse("2a19"), 2)
    var failure: BleException? = null

    init {
        mutableServices.value = listOf(GattService(1, BleUuid.parse("180f"), listOf(attribute)))
        markConnected()
    }

    override suspend fun discoverServices(): List<GattService> = services.value

    override suspend fun read(characteristic: GattCharacteristic): BleBytes =
        operation("read", characteristic) {
            checkCharacteristic(characteristic)
            delay(100)
            failure?.let { throw it }
            BleBytes(byteArrayOf(42))
        }

    override suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode,
    ): Unit = error("unused")

    override suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes = error("unused")

    override suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray): Unit =
        error("unused")

    override suspend fun subscribe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode,
    ): Unit = error("unused")

    override suspend fun requestMtu(size: Int): Int = error("unused")

    override fun releasePlatform() = Unit
}
