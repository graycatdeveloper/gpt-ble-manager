package gpt.ble.manager.windows.gatt

import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleCapabilities
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleErrorDetails
import gpt.ble.manager.BleException
import gpt.ble.manager.BleManagerOptions
import gpt.ble.manager.BleUuid
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.GattDescriptor
import gpt.ble.manager.GattService
import gpt.ble.manager.PreferredConnectionParameters
import gpt.ble.manager.SubscriptionMode
import gpt.ble.manager.WriteMode
import gpt.ble.manager.internal.gatt.ManagedConnection
import gpt.ble.manager.internal.names.decodeDeviceName
import gpt.ble.manager.windows.NativeBridge
import gpt.ble.manager.windows.asBleException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Windows GATT session. Shared validation and request cancellation live in ManagedConnection.
 * Numeric IDs refer only to objects in this session; releasePlatform releases them and removes the
 * connection from the manager through the removed callback.
 */
internal class WindowsConnection(
    private val native: NativeBridge,
    private val manager: Long,
    private val session: Long,
    device: BleDevice,
    onDeviceName: (String) -> Unit,
    options: BleManagerOptions,
    override val capabilities: BleCapabilities,
    private val removed: () -> Unit,
) :
    ManagedConnection(
        UUID.randomUUID().toString(),
        device,
        onDeviceName = onDeviceName,
        options = options,
    ) {
    fun connected() {
        mutableMtu.value = native.mtu(manager, session)
        markConnected()
    }

    fun notification(attribute: Int, value: ByteArray) = emitValue(attribute, value)

    fun mtuChanged(value: Int) {
        mutableMtu.value = value
    }

    private suspend fun <T> call(block: (Long) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block(executionTimeoutMillis())
            } catch (e: IllegalStateException) {
                val error = e.asBleException()
                if (error.code == BleError.Timeout) {
                    terminate(error)
                }
                throw error
            }
        }

    override suspend fun readDeviceName(): String? =
        operation("readDeviceName") {
            // Name lookup must not require access to every vendor service or its descriptors.
            val value =
                call { native.readDeviceName(manager, session, it) } ?: return@operation null
            recordGattName(value)
            decodeDeviceName(value)
        }

    override suspend fun discoverServices(): List<GattService> =
        operation("discoverServices") {
            if (services.value.isNotEmpty()) {
                return@operation services.value
            }
            decodeWindowsGattCatalog(id, call { native.discover(manager, session, it) }).also {
                mutableServices.value = it
            }
        }

    override suspend fun read(characteristic: GattCharacteristic): BleBytes =
        operation("read", characteristic) {
            checkCharacteristic(characteristic)
            require(characteristic.canRead) { "Read is not supported" }
            val value = call { native.read(manager, session, characteristic.id, false, it) }
            recordDeviceName(characteristic, value)
            BleBytes(value)
        }

    override suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode,
    ) {
        val bytes = value.copyOf()
        operation("write", characteristic) {
            mutableMtu.value = native.mtu(manager, session)
            checkWrite(characteristic, bytes, mode)
            call {
                native.write(
                    manager,
                    session,
                    characteristic.id,
                    false,
                    bytes,
                    mode == WriteMode.WithResponse,
                    it,
                )
            }
        }
    }

    override suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes =
        operation("readDescriptor", descriptor = descriptor) {
            checkDescriptor(descriptor)
            BleBytes(call { native.read(manager, session, descriptor.id, true, it) })
        }

    override suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray) {
        val bytes = value.copyOf()
        require(descriptor.uuid != BleUuid.parse("2902")) { "Use subscribe() to configure CCCD" }
        operation("writeDescriptor", descriptor = descriptor) {
            checkDescriptor(descriptor)
            require(bytes.size <= mtu.value - 3) { "Payload exceeds MTU - 3" }
            call { native.write(manager, session, descriptor.id, true, bytes, true, it) }
        }
    }

    override suspend fun subscribe(characteristic: GattCharacteristic, mode: SubscriptionMode) =
        operation("subscribe", characteristic) {
            checkSubscription(characteristic, mode)
            try {
                call { native.subscribe(manager, session, characteristic.id, mode.ordinal, it) }
            } catch (e: BleException) {
                terminate(e)
                throw e
            }
        }

    override suspend fun requestMtu(size: Int): Int =
        operation("requestMtu") {
            require(size in 23..517)
            // Windows owns MTU negotiation. Return the real negotiated value, not the requested
            // one.
            native.mtu(manager, session).also { mutableMtu.value = it }
        }

    override suspend fun requestPreferredConnectionParameters(
        parameters: PreferredConnectionParameters
    ): Unit =
        operation("preferredConnectionParameters") {
            if (!capabilities.preferredConnectionParameters) {
                throw BleException(
                    BleError.Unsupported,
                    "Preferred parameters require Windows 11 or later",
                )
            }
            val status = call { native.preferredParameters(manager, session, parameters.ordinal) }
            if (status != 1) {
                throw BleException(
                    if (status == 3) {
                        BleError.PermissionDenied
                    } else {
                        BleError.Rejected
                    },
                    "Windows rejected preferred connection parameters: $status",
                    details = BleErrorDetails(platform = "Windows", platformStatus = status),
                )
            }
        }

    override fun releasePlatform() {
        try {
            native.disconnect(manager, session)
        } catch (_: IllegalStateException) {
            /* Manager may have closed concurrently. */
        } finally {
            removed()
        }
    }
}
