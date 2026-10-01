package dev.gpt.ble.internal.gatt

import dev.gpt.ble.BleBytes
import dev.gpt.ble.BleConnection
import dev.gpt.ble.BleDevice
import dev.gpt.ble.BleError
import dev.gpt.ble.BleException
import dev.gpt.ble.BleUuid
import dev.gpt.ble.CharacteristicValue
import dev.gpt.ble.ConnectionState
import dev.gpt.ble.DeviceNameSource
import dev.gpt.ble.GattCharacteristic
import dev.gpt.ble.GattDescriptor
import dev.gpt.ble.GattService
import dev.gpt.ble.SubscriptionMode
import dev.gpt.ble.WriteMode
import dev.gpt.ble.internal.names.decodeDeviceName
import dev.gpt.ble.internal.scan.Advertisement
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal abstract class ManagedConnection(
    final override val id: String,
    device: BleDevice,
    private val operationTimeoutMillis: Long = 15_000,
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

    protected suspend fun <T> operation(block: suspend () -> T): T =
        operations.execute(operationTimeoutMillis) {
            if (state.value != ConnectionState.Connected) {
                throw BleException(BleError.Disconnected, "Connection is not active")
            }
            block()
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
