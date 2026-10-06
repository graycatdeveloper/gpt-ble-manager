package gpt.ble.manager.testing

import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleConnection
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.BleUuid
import gpt.ble.manager.CharacteristicValue
import gpt.ble.manager.ConnectionState
import gpt.ble.manager.DeviceNameSource
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.GattDescriptor
import gpt.ble.manager.GattService
import gpt.ble.manager.SubscriptionMode
import gpt.ble.manager.WriteMode
import gpt.ble.manager.observation.NotificationObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Script outcomes by operation name (read, write, subscribe, discover, mtu, readDescriptor,
 * writeDescriptor).
 */
class FakeBleConnection
internal constructor(
    override val id: String,
    private val peripheral: FakePeripheral,
    private val removed: (FakeBleConnection) -> Unit,
) : BleConnection {
    private val details = MutableStateFlow(peripheral.device)
    override val device
        get() = details.value

    override val deviceDetails = details.asStateFlow()
    private val status = MutableStateFlow(ConnectionState.Connected)
    override val state = status.asStateFlow()
    private val reason = MutableStateFlow<BleException?>(null)
    override val disconnectReason = reason.asStateFlow()
    private val catalog = MutableStateFlow<List<GattService>>(emptyList())
    override val services = catalog.asStateFlow()
    private val negotiatedMtu = MutableStateFlow(peripheral.mtu)
    override val mtu = negotiatedMtu.asStateFlow()
    private val values = MutableSharedFlow<CharacteristicValue>(extraBufferCapacity = 128)
    override val notifications = values.asSharedFlow()
    private val observer = NotificationObserver(this)
    private val queue = Mutex()
    private val attributes = mutableMapOf<Int, BleBytes>()
    private val scripted = mutableMapOf<String, ArrayDeque<FakeOutcome>>()
    private val enabled = mutableMapOf<Int, SubscriptionMode>()
    val writes = mutableListOf<FakeWrite>()
    val subscriptions = mutableListOf<Pair<GattCharacteristic, SubscriptionMode>>()
    /** Optional immediate peripheral response during CCCD configuration. */
    var onSubscriptionChanged: ((GattCharacteristic, SubscriptionMode) -> Unit)? = null

    fun enqueue(operation: String, outcome: FakeOutcome) {
        scripted.getOrPut(operation) { ArrayDeque() }.addLast(outcome)
    }

    private suspend fun <T> operation(name: String, block: () -> T): T = queue.withLock {
        checkActive()
        val outcome = scripted[name]?.removeFirstOrNull() ?: FakeOutcome()
        delay(outcome.delayMillis)
        checkActive()
        outcome.error?.let { throw it }
        block()
    }

    private fun checkActive() {
        if (status.value == ConnectionState.Disconnected) {
            throw reason.value!!
        }
    }

    private fun validate(characteristic: GattCharacteristic) {
        require(
            characteristic.connectionId == id &&
                catalog.value.any { characteristic in it.characteristics }
        ) {
            "Stale characteristic"
        }
    }

    private fun validate(descriptor: GattDescriptor) {
        require(
            descriptor.connectionId == id &&
                catalog.value.any { s -> s.characteristics.any { descriptor in it.descriptors } }
        ) {
            "Stale descriptor"
        }
    }

    override suspend fun discoverServices(): List<GattService> =
        operation("discover") {
            if (catalog.value.isEmpty()) {
                var next = 0
                catalog.value =
                    peripheral.services.map { service ->
                        val serviceId = ++next
                        GattService(
                            serviceId,
                            service.uuid,
                            service.characteristics.map { source ->
                                val characteristicId = ++next
                                attributes[characteristicId] = source.value
                                GattCharacteristic(
                                    id,
                                    characteristicId,
                                    serviceId,
                                    source.uuid,
                                    source.properties,
                                    source.descriptors.map { descriptor ->
                                        val descriptorId = ++next
                                        attributes[descriptorId] = descriptor.value
                                        GattDescriptor(
                                            id,
                                            descriptorId,
                                            characteristicId,
                                            descriptor.uuid,
                                        )
                                    },
                                )
                            },
                        )
                    }
            }
            catalog.value
        }

    override suspend fun readDeviceName(): String? {
        val characteristic =
            discoverServices()
                .filter { it.uuid == BleUuid.parse("1800") }
                .flatMap { it.characteristics }
                .firstOrNull { it.uuid == BleUuid.parse("2a00") && it.canRead } ?: return null
        val name =
            read(characteristic).toByteArray().decodeToString().trimEnd('\u0000').takeIf {
                it.isNotBlank()
            }
        if (name != null && device.nameSource != DeviceNameSource.Advertisement)
            details.value = device.copy(name = name, nameSource = DeviceNameSource.Gatt)
        return name
    }

    override suspend fun read(characteristic: GattCharacteristic): BleBytes =
        operation("read") {
            validate(characteristic)
            require(characteristic.canRead)
            attributes.getValue(characteristic.id)
        }

    override suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode,
    ) {
        val bytes = BleBytes(value)
        operation("write") {
            validate(characteristic)
            require(
                if (mode == WriteMode.WithResponse) {
                    characteristic.canWrite
                } else characteristic.canWriteWithoutResponse
            )
            require(bytes.size <= mtu.value - 3)
            writes += FakeWrite(characteristic, bytes, mode)
            attributes[characteristic.id] = bytes
        }
    }

    override suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes =
        operation("readDescriptor") {
            validate(descriptor)
            attributes.getValue(descriptor.id)
        }

    override suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray) {
        val bytes = BleBytes(value)
        operation("writeDescriptor") {
            validate(descriptor)
            require(descriptor.uuid != BleUuid.parse("2902"))
            require(bytes.size <= mtu.value - 3)
            attributes[descriptor.id] = bytes
        }
    }

    override suspend fun subscribe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode,
    ): Unit =
        operation("subscribe") {
            validate(characteristic)
            require(mode != SubscriptionMode.Notify || characteristic.canNotify)
            require(mode != SubscriptionMode.Indicate || characteristic.canIndicate)
            if (mode == SubscriptionMode.Disabled) {
                enabled.remove(characteristic.id)
            } else enabled[characteristic.id] = mode
            subscriptions += characteristic to mode
            onSubscriptionChanged?.invoke(characteristic, mode)
        }

    override fun observe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode,
    ): Flow<BleBytes> = observer.observe(characteristic, mode)

    fun notify(characteristic: GattCharacteristic, value: ByteArray) {
        checkActive()
        validate(characteristic)
        check(characteristic.id in enabled) { "CCCD is disabled" }
        if (!values.tryEmit(CharacteristicValue(characteristic, BleBytes(value)))) {
            disconnect(BleException(BleError.NotificationOverflow, "Fake notification overflow"))
        }
    }

    override suspend fun requestMtu(size: Int): Int =
        operation("mtu") {
            require(size in 23..517)
            minOf(size, peripheral.mtu).also { negotiatedMtu.value = it }
        }

    fun disconnect(
        error: BleException = BleException(BleError.Disconnected, "Simulated link loss")
    ) {
        if (status.value == ConnectionState.Disconnected) {
            return
        }
        reason.value = error
        status.value = ConnectionState.Disconnected
        catalog.value = emptyList()
        enabled.clear()
        removed(this)
    }

    override fun close() = disconnect(BleException(BleError.Closed, "Connection closed by caller"))
}
