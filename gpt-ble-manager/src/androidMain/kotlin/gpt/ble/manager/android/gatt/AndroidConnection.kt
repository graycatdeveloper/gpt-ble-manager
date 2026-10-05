@file:Suppress("MissingPermission", "DEPRECATION", "OVERRIDE_DEPRECATION")

package gpt.ble.manager.android.gatt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.BleUuid
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.GattDescriptor
import gpt.ble.manager.GattService
import gpt.ble.manager.SubscriptionMode
import gpt.ble.manager.WriteMode
import gpt.ble.manager.internal.gatt.ManagedConnection
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/**
 * Платформенная GATT-сессия Android. Общие проверки и отмена запросов находятся в
 * ManagedConnection. Числовые IDs связаны с объектами только этой сессии; releasePlatform
 * освобождает их и удаляет соединение из менеджера через callback removed.
 */
internal class AndroidConnection(
    private val context: Context,
    private val remote: BluetoothDevice,
    device: BleDevice,
    onDeviceName: (String) -> Unit,
    private val removed: (AndroidConnection) -> Unit,
) : ManagedConnection(UUID.randomUUID().toString(), device, onDeviceName = onDeviceName) {
    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var released = false
    private val connected = CompletableDeferred<Unit>()
    private var pending: PendingGattRequest? = null
    private val catalog = AndroidGattCatalog(id)

    suspend fun open(timeoutMillis: Long) =
        operations.execute(timeoutMillis) {
            synchronized(lock) {
                if (released) {
                    throw BleException(BleError.Closed, "Connection is closed")
                }
                gatt =
                    remote.connectGatt(
                        context,
                        false,
                        callback,
                        BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK,
                        Handler(Looper.getMainLooper()),
                    ) ?: throw BleException(BleError.NativeFailure, "connectGatt returned null")
            }
            connected.await()
            markConnected()
        }

    /**
     * Публикует Pending до вызова Android API: даже немедленный callback найдёт свой запрос.
     * Ожидание Deferred выполняется вне monitor. finally очищает только этот Pending, а не новый.
     * Сериализацию и закрытие сессии при timeout/cancellation обеспечивает OperationQueue.
     */
    private suspend fun request(
        kind: String,
        target: Any? = null,
        start: (BluetoothGatt) -> Boolean,
    ): ByteArray {
        val result = CompletableDeferred<ByteArray>()
        val request = PendingGattRequest(kind, target, result)
        synchronized(lock) {
            val active = gatt ?: throw BleException(BleError.Disconnected, "GATT handle is closed")
            check(pending == null) { "Only one GATT request may be active" }
            pending = request
            try {
                if (!start(active)) {
                    throw BleException(BleError.Rejected, "Android rejected $kind")
                }
            } catch (e: Exception) {
                pending = null
                throw if (e is SecurityException) {
                    BleException(BleError.PermissionDenied, "Bluetooth permission revoked", e)
                } else {
                    e
                }
            }
        }
        try {
            return result.await()
        } finally {
            synchronized(lock) {
                if (pending === request) {
                    pending = null
                }
            }
        }
    }

    /**
     * Принимает только callback текущего BluetoothGatt с совпадающими kind и identity target. Байты
     * копируются до завершения Deferred, поскольку Android может переиспользовать исходный буфер.
     */
    internal fun complete(
        active: BluetoothGatt,
        kind: String,
        target: Any?,
        status: Int,
        value: ByteArray = byteArrayOf(),
    ) {
        synchronized(lock) {
            if (active !== gatt || released) {
                return
            }
            val request = pending ?: return
            if (request.kind != kind || request.target !== target) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                request.result.complete(value.copyOf())
            } else {
                request.result.completeExceptionally(
                    BleException(BleError.Protocol, "$kind failed with GATT status $status")
                )
            }
        }
    }

    private val callback = AndroidGattCallback(this)

    internal fun connectionStateChanged(gatt: BluetoothGatt, status: Int, newState: Int) {
        synchronized(lock) {
            if (gatt !== this.gatt || released) {
                return
            }
            if (
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
            ) {
                connected.complete(Unit)
            } else if (
                status != BluetoothGatt.GATT_SUCCESS ||
                    newState == BluetoothProfile.STATE_DISCONNECTED
            ) {
                val error =
                    BleException(
                        BleError.Disconnected,
                        "Android connection ended: status=$status state=$newState",
                    )
                connected.completeExceptionally(error)
                terminate(error)
            }
        }
    }

    internal fun mtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        synchronized(lock) {
            if (gatt !== this.gatt || released) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mutableMtu.value = mtu
            }
            complete(gatt, "mtu", null, status)
        }
    }

    internal fun serviceChanged(gatt: BluetoothGatt) {
        synchronized(lock) {
            if (gatt === this.gatt && !released && services.value.isNotEmpty()) {
                terminate(
                    BleException(
                        BleError.Disconnected,
                        "GATT database changed; reconnect to rediscover handles",
                    )
                )
            }
        }
    }

    internal fun changed(
        active: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Unit =
        synchronized(lock) {
            if (active !== gatt || released) {
                return@synchronized
            }
            catalog.characteristicId(characteristic)?.let { emitValue(it, value) }
        }

    override suspend fun discoverServices(): List<GattService> = operation {
        // Keep a stable catalog for this connection; service-changed invalidates the session.
        if (services.value.isNotEmpty()) {
            return@operation services.value
        }
        request("discover") { it.discoverServices() }
        synchronized(lock) {
            val active = gatt ?: throw BleException(BleError.Disconnected, "GATT handle is closed")
            catalog.build(active.services).also { mutableServices.value = it }
        }
    }

    override suspend fun read(characteristic: GattCharacteristic): BleBytes = operation {
        checkCharacteristic(characteristic)
        require(characteristic.canRead) { "Read is not supported" }
        val native = synchronized(lock) { catalog.characteristic(characteristic.id) }
        val value = request("read", native) { it.readCharacteristic(native) }
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
            checkWrite(characteristic, bytes, mode)
            val native = synchronized(lock) { catalog.characteristic(characteristic.id) }
            val type =
                if (mode == WriteMode.WithResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
            request("write", native) { active ->
                if (Build.VERSION.SDK_INT >= 33) {
                    active.writeCharacteristic(native, bytes, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    native.writeType = type
                    native.value = bytes
                    active.writeCharacteristic(native)
                }
            }
        }
    }

    override suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes = operation {
        checkDescriptor(descriptor)
        val native = synchronized(lock) { catalog.descriptor(descriptor.id) }
        BleBytes(request("readDescriptor", native) { it.readDescriptor(native) })
    }

    override suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray) {
        val bytes = value.copyOf()
        require(descriptor.uuid != BleUuid.parse("2902")) { "Use subscribe() to configure CCCD" }
        operation {
            checkDescriptor(descriptor)
            require(bytes.size <= mtu.value - 3) { "Payload exceeds MTU - 3" }
            val native = synchronized(lock) { catalog.descriptor(descriptor.id) }
            writeNativeDescriptor(native, bytes)
        }
    }

    private suspend fun writeNativeDescriptor(
        descriptor: BluetoothGattDescriptor,
        bytes: ByteArray,
    ) {
        request("writeDescriptor", descriptor) { active ->
            if (Build.VERSION.SDK_INT >= 33) {
                active.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = bytes
                active.writeDescriptor(descriptor)
            }
        }
    }

    override suspend fun subscribe(characteristic: GattCharacteristic, mode: SubscriptionMode) =
        operation {
            checkSubscription(characteristic, mode)
            val native = synchronized(lock) { catalog.characteristic(characteristic.id) }
            val cccd =
                native.getDescriptor(UUID.fromString(BleUuid.parse("2902").value))
                    ?: throw BleException(BleError.Unsupported, "Characteristic has no CCCD")
            val active =
                synchronized(lock) { gatt }
                    ?: throw BleException(BleError.Disconnected, "GATT handle is closed")
            if (!active.setCharacteristicNotification(native, mode != SubscriptionMode.Disabled)) {
                throw BleException(BleError.Rejected, "Android rejected notification registration")
            }
            try {
                writeNativeDescriptor(
                    cccd,
                    when (mode) {
                        SubscriptionMode.Disabled ->
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        SubscriptionMode.Notify -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        SubscriptionMode.Indicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    },
                )
            } catch (e: Throwable) {
                // State of local registration and remote CCCD cannot be reconciled after failure.
                terminate(BleException(BleError.Protocol, "Unable to configure CCCD", e))
                throw e
            }
        }

    override suspend fun requestMtu(size: Int): Int = operation {
        require(size in 23..517)
        request("mtu") { it.requestMtu(size) }
        mtu.value
    }

    override fun releasePlatform() {
        synchronized(lock) {
            released = true
            val active = gatt
            gatt = null
            val error =
                disconnectReason.value ?: BleException(BleError.Disconnected, "Connection ended")
            connected.completeExceptionally(error)
            pending?.result?.completeExceptionally(error)
            pending = null
            catalog.clear()
            try {
                active?.disconnect()
            } catch (_: SecurityException) {
                /* Permission was revoked. */
            } finally {
                try {
                    active?.close()
                } catch (_: SecurityException) {
                    /* Release local bookkeeping even after permission revocation. */
                } finally {
                    removed(this)
                }
            }
        }
    }
}
