package gpt.ble.manager.internal.gatt

import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleConnection
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleDiagnosticEvent
import gpt.ble.manager.BleError
import gpt.ble.manager.BleErrorDetails
import gpt.ble.manager.BleException
import gpt.ble.manager.BleManagerOptions
import gpt.ble.manager.BleUuid
import gpt.ble.manager.CharacteristicValue
import gpt.ble.manager.ConnectionState
import gpt.ble.manager.DeviceNameSource
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.GattDescriptor
import gpt.ble.manager.GattService
import gpt.ble.manager.OperationPhase
import gpt.ble.manager.SubscriptionMode
import gpt.ble.manager.TimeoutOverride
import gpt.ble.manager.WriteMode
import gpt.ble.manager.internal.names.decodeDeviceName
import gpt.ble.manager.observation.NotificationObserver
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared Android and Windows invariants: snapshots, handle validation, MTU, and the request queue.
 * terminate uses compareAndSet so that releasePlatform runs exactly once. Notification buffer
 * overflow explicitly terminates the session instead of silently dropping packets.
 *
 * @see <a
 *   href="https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-mutable-shared-flow/">MutableSharedFlow</a>
 */
internal abstract class ManagedConnection(
    final override val id: String,
    device: BleDevice,
    private val options: BleManagerOptions = BleManagerOptions(),
    private val onDeviceName: (String) -> Unit = {},
) : BleConnection {
    private val details = MutableStateFlow(device)
    final override val deviceDetails = details.asStateFlow()
    final override val device: BleDevice
        get() = details.value

    protected val mutableState = MutableStateFlow(ConnectionState.Connecting)
    protected val mutableServices = MutableStateFlow<List<GattService>>(emptyList())
    protected val mutableMtu = MutableStateFlow(23)
    private val reason = MutableStateFlow<BleException?>(null)
    private val values = MutableSharedFlow<CharacteristicValue>(extraBufferCapacity = 128)
    final override val state = mutableState.asStateFlow()
    final override val services = mutableServices.asStateFlow()
    final override val mtu = mutableMtu.asStateFlow()
    final override val disconnectReason = reason.asStateFlow()
    final override val notifications = values.asSharedFlow()
    private val closed = MutableStateFlow(false)
    protected val operations = OperationQueue { terminate(it) }

    private val observer = NotificationObserver(this)

    final override fun observe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode,
    ): Flow<BleBytes> = observer.observe(characteristic, mode)

    protected suspend fun executionTimeoutMillis(): Long =
        (currentCoroutineContext()[TimeoutOverride]?.timeouts ?: options.timeouts).executionMillis

    protected suspend fun <T> operation(
        name: String = "gatt",
        characteristic: GattCharacteristic? = null,
        descriptor: GattDescriptor? = null,
        block: suspend () -> T,
    ): T {
        val start = TimeSource.Monotonic.markNow()
        val target =
            characteristic
                ?: services.value
                    .flatMap { it.characteristics }
                    .firstOrNull { it.id == descriptor?.characteristicId }
        val details =
            BleErrorDetails(
                operation = name,
                connectionId = id,
                serviceUuid = services.value.firstOrNull { it.id == target?.serviceId }?.uuid,
                characteristicUuid = target?.uuid,
                descriptorUuid = descriptor?.uuid,
            )
        var phase = OperationPhase.Queued
        fun report(next: OperationPhase, error: BleException? = null) {
            runCatching {
                options.diagnostics.record(
                    BleDiagnosticEvent(details, next, start.elapsedNow().inWholeMilliseconds, error)
                )
            }
        }
        report(phase)
        val budgets = currentCoroutineContext()[TimeoutOverride]?.timeouts ?: options.timeouts
        try {
            return operations
                .execute(budgets.executionMillis, budgets.queueWaitMillis) {
                    phase = OperationPhase.Started
                    report(phase)
                    if (state.value != ConnectionState.Connected) {
                        throw BleException(BleError.Disconnected, "Connection is not active")
                    }
                    block()
                }
                .also { report(OperationPhase.Succeeded) }
        } catch (cancelled: CancellationException) {
            report(OperationPhase.Cancelled)
            throw cancelled
        } catch (error: Exception) {
            val ble =
                if (error is BleException)
                    BleException(
                        error.code,
                        error.message.orEmpty(),
                        error,
                        details.copy(
                            platform = error.details.platform,
                            platformStatus = error.details.platformStatus,
                            phase = phase,
                        ),
                    )
                else
                    BleException(
                        BleError.Rejected,
                        error.message ?: "Operation failed",
                        error,
                        details.copy(phase = phase),
                    )
            report(OperationPhase.Failed, ble)
            // Preserve argument-validation exceptions for existing API callers.
            if (error is IllegalArgumentException) {
                throw error
            }
            throw ble
        }
    }

    override suspend fun readDeviceName(): String? {
        val characteristic =
            discoverServices()
                .filter { it.uuid == BleUuid.parse("1800") }
                .flatMap { it.characteristics }
                .firstOrNull { it.uuid == BleUuid.parse("2a00") && it.canRead } ?: return null
        val value = read(characteristic).toByteArray()
        return decodeDeviceName(value)
    }

    protected fun recordDeviceName(characteristic: GattCharacteristic, value: ByteArray) {
        if (
            closed.value ||
                characteristic.uuid != BleUuid.parse("2a00") ||
                services.value.none {
                    it.id == characteristic.serviceId && it.uuid == BleUuid.parse("1800")
                }
        ) {
            return
        }
        recordGattName(value)
    }

    /** For a platform operation that specifically reads Generic Access / Device Name. */
    protected fun recordGattName(value: ByteArray) {
        if (closed.value) {
            return
        }
        val name = decodeDeviceName(value) ?: return
        details.update { old ->
            if (old.nameSource == DeviceNameSource.Advertisement && !old.name.isNullOrBlank()) {
                old
            } else {
                old.copy(name = name, nameSource = DeviceNameSource.Gatt)
            }
        }
        onDeviceName(name)
    }

    protected fun checkCharacteristic(characteristic: GattCharacteristic) {
        require(
            characteristic.connectionId == id &&
                services.value.any { characteristic in it.characteristics }
        ) {
            "Characteristic is stale or belongs to another connection"
        }
    }

    protected fun checkDescriptor(descriptor: GattDescriptor) {
        require(
            descriptor.connectionId == id &&
                services.value.any { s -> s.characteristics.any { descriptor in it.descriptors } }
        ) {
            "Descriptor is stale or belongs to another connection"
        }
    }

    protected fun checkWrite(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode,
    ) {
        checkCharacteristic(characteristic)
        require(
            if (mode == WriteMode.WithResponse) {
                characteristic.canWrite
            } else {
                characteristic.canWriteWithoutResponse
            }
        ) {
            "Write mode is not supported"
        }
        require(value.size <= mtu.value - 3) {
            "Payload exceeds MTU - 3; split it using your device protocol"
        }
    }

    protected fun checkSubscription(characteristic: GattCharacteristic, mode: SubscriptionMode) {
        checkCharacteristic(characteristic)
        require(mode != SubscriptionMode.Notify || characteristic.canNotify) {
            "Notify is not supported"
        }
        require(mode != SubscriptionMode.Indicate || characteristic.canIndicate) {
            "Indicate is not supported"
        }
    }

    protected fun emitValue(characteristicId: Int, value: ByteArray) {
        if (closed.value) {
            return
        }
        val characteristic =
            services.value.flatMap { it.characteristics }.find { it.id == characteristicId }
                ?: return
        if (!values.tryEmit(CharacteristicValue(characteristic, BleBytes(value)))) {
            terminate(
                BleException(BleError.NotificationOverflow, "Notification consumer is too slow")
            )
        }
    }

    protected fun markConnected() {
        if (!closed.value) {
            mutableState.compareAndSet(ConnectionState.Connecting, ConnectionState.Connected)
        }
    }

    /**
     * Publishes the reason and Disconnected first, then wakes the queue and releases platform
     * resources. This order lets pending requests receive the closure reason; repeated calls do
     * nothing.
     */
    internal fun terminate(error: BleException) {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        reason.value = error
        mutableState.value = ConnectionState.Disconnected
        mutableServices.value = emptyList()
        operations.stop(error)
        releasePlatform()
    }

    final override fun close() =
        terminate(BleException(BleError.Closed, "Connection closed by caller"))

    protected abstract fun releasePlatform()
}
