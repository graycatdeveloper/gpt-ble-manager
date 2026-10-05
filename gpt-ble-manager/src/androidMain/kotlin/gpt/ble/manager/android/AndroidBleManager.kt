@file:Suppress("MissingPermission", "DEPRECATION")

package gpt.ble.manager.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import gpt.ble.manager.AdapterState
import gpt.ble.manager.BleConnection
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.BleManager
import gpt.ble.manager.PairResult
import gpt.ble.manager.PairingState
import gpt.ble.manager.ScanOptions
import gpt.ble.manager.UnpairResult
import gpt.ble.manager.android.adapter.AndroidAdapterState
import gpt.ble.manager.android.gatt.AndroidConnection
import gpt.ble.manager.android.pairing.AndroidPairingController
import gpt.ble.manager.android.scan.AndroidScanner
import gpt.ble.manager.internal.canonicalAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Публичная точка входа Android. Scanner и pairing-controller разделяют monitor менеджера, а
 * соединения владеют собственными GATT-ресурсами. close останавливает работу и закрывает сессии;
 * изменение питания завершает активные соединения с явной причиной.
 */
class AndroidBleManager(context: Context) : BleManager {
    private val context = context.applicationContext
    private val adapter =
        (this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val adapterStatus = MutableStateFlow(AdapterState.Unsupported)
    override val adapterState = adapterStatus.asStateFlow()
    override val scanState
        get() = scanController.scanState

    override val devices
        get() = scanController.devices

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val connections = ConcurrentHashMap<String, AndroidConnection>()
    private val adapterStateReader = AndroidAdapterState(this.context, adapter, closed)
    private val scanController =
        AndroidScanner(
            this.context,
            adapter,
            closed,
            lock,
            adapterStatus,
            ::currentState,
            ::checkReady,
        )
    private val pairing = AndroidPairingController(this.context, adapter, closed, lock, connections)

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                adapterStatus.value = currentState()
                if (adapterStatus.value != AdapterState.Ready) {
                    stopScan()
                    connections.values.toList().forEach {
                        it.terminate(
                            BleException(BleError.NotReady, "Bluetooth adapter is unavailable")
                        )
                    }
                }
            }
        }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            this.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            this.context.registerReceiver(receiver, filter)
        }
        adapterStatus.value = currentState()
    }

    /** Разрешения запрашивает Activity; набор зависит от версии Android. */
    fun requiredPermissions(): List<String> = adapterStateReader.requiredPermissions()

    private fun currentState(): AdapterState = adapterStateReader.currentState()

    override suspend fun refreshAdapterState(): AdapterState =
        currentState().also { adapterStatus.value = it }

    override suspend fun getPairingState(device: BleDevice): PairingState =
        pairing.getPairingState(device)

    override suspend fun pair(device: BleDevice, timeoutMillis: Long): PairResult =
        pairing.pair(device, timeoutMillis)

    override suspend fun unpair(device: BleDevice, timeoutMillis: Long): UnpairResult =
        pairing.unpair(device, timeoutMillis)

    override suspend fun startScan(options: ScanOptions) = scanController.startScan(options)

    override fun stopScan() = scanController.stopScan()

    override suspend fun connect(device: BleDevice, timeoutMillis: Long): BleConnection {
        checkReady()
        require(timeoutMillis > 0)
        val address = canonicalAddress(device.address)
        val connection =
            AndroidConnection(
                context,
                adapter!!.getRemoteDevice(address),
                device.copy(address = address),
                onDeviceName = { name -> scanController.updateGattName(address, name) },
            ) {
                connections.remove(address, it)
            }
        synchronized(lock) {
            if (closed.get()) {
                throw BleException(BleError.Closed, "Manager is closed")
            }
            if (
                pairing.hasPending(address) || connections.putIfAbsent(address, connection) != null
            ) {
                throw BleException(
                    BleError.Rejected,
                    "A connection or pairing operation for $address already exists",
                )
            }
        }
        try {
            if (closed.get()) {
                throw BleException(BleError.Closed, "Manager is closed")
            }
            connection.open(timeoutMillis)
            return connection
        } catch (e: Throwable) {
            connection.close()
            throw e
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        synchronized(lock) {
            pairing.stopPending()
        }
        stopScan()
        connections.values.toList().forEach { it.close() }
        context.unregisterReceiver(receiver)
        adapterStatus.value = AdapterState.Closed
    }

    private fun checkReady() {
        val state = currentState().also { adapterStatus.value = it }
        if (state != AdapterState.Ready) {
            throw BleException(
                when (state) {
                    AdapterState.PermissionRequired -> BleError.PermissionDenied
                    AdapterState.Closed -> BleError.Closed
                    else -> BleError.NotReady
                },
                "Bluetooth adapter is $state",
            )
        }
    }
}
