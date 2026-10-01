package dev.gpt.ble.windows.gatt

import dev.gpt.ble.BleBytes
import dev.gpt.ble.BleDevice
import dev.gpt.ble.BleError
import dev.gpt.ble.BleException
import dev.gpt.ble.BleUuid
import dev.gpt.ble.GattCharacteristic
import dev.gpt.ble.GattDescriptor
import dev.gpt.ble.GattService
import dev.gpt.ble.SubscriptionMode
import dev.gpt.ble.WriteMode
import dev.gpt.ble.internal.gatt.ManagedConnection
import dev.gpt.ble.internal.names.decodeDeviceName
import dev.gpt.ble.windows.NativeBridge
import dev.gpt.ble.windows.asBleException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WindowsConnection(
    private val native: NativeBridge,
    private val manager: Long,
    private val session: Long,
    device: BleDevice,
    onDeviceName: (String) -> Unit,
    private val removed: () -> Unit,
) : ManagedConnection(UUID.randomUUID().toString(), device, onDeviceName = onDeviceName) {
    fun connected() {
        mutableMtu.value = native.mtu(manager, session)
        markConnected()
    }

    fun notification(attribute: Int, value: ByteArray) = emitValue(attribute, value)

    fun mtuChanged(value: Int) {
        mutableMtu.value = value
    }

    private suspend fun <T> call(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: IllegalStateException) {
                val error = e.asBleException()
                if (error.code == BleError.Timeout) {
                    terminate(error)
                }
                throw error
            }
        }

    override suspend fun readDeviceName(): String? = operation {
        // Name lookup must not require access to every vendor service or its descriptors.
        val value = call { native.readDeviceName(manager, session) } ?: return@operation null
        recordGattName(value)
        decodeDeviceName(value)
    }

    override suspend fun discoverServices(): List<GattService> = operation {
        if (services.value.isNotEmpty()) {
            return@operation services.value
        }
        decodeWindowsGattCatalog(id, call { native.discover(manager, session) }).also {
            mutableServices.value = it
        }
    }

    override suspend fun read(characteristic: GattCharacteristic): BleBytes = operation {
        checkCharacteristic(characteristic)
        require(characteristic.canRead) { "Read is not supported" }
        val value = call { native.read(manager, session, characteristic.id, false) }
        recordDeviceName(characteristic, value)
        BleBytes(value)
    }

    override suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode,
    ) {
        val bytes = value.copyOf()
        operation {
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
                )
            }
        }
    }

    override suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes = operation {
        checkDescriptor(descriptor)
        BleBytes(call { native.read(manager, session, descriptor.id, true) })
    }

    override suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray) {
        val bytes = value.copyOf()
        require(descriptor.uuid != BleUuid.parse("2902")) { "Use subscribe() to configure CCCD" }
        operation {
            checkDescriptor(descriptor)
            require(bytes.size <= mtu.value - 3) { "Payload exceeds MTU - 3" }
            call { native.write(manager, session, descriptor.id, true, bytes, true) }
        }
    }

    override suspend fun subscribe(characteristic: GattCharacteristic, mode: SubscriptionMode) =
        operation {
            checkSubscription(characteristic, mode)
            try {
                call { native.subscribe(manager, session, characteristic.id, mode.ordinal) }
            } catch (e: BleException) {
                terminate(e)
                throw e
            }
        }

    override suspend fun requestMtu(size: Int): Int = operation {
        require(size in 23..517)
        // Windows owns MTU negotiation. Return the real negotiated value, not the requested one.
        native.mtu(manager, session).also { mutableMtu.value = it }
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
