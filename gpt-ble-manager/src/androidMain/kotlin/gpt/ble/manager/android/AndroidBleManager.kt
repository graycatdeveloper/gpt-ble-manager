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
import gpt.ble.manager.BleCapabilities
import gpt.ble.manager.BleConnection
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.BleManager
import gpt.ble.manager.BleManagerOptions
import gpt.ble.manager.PairResult
import gpt.ble.manager.PairingState
import gpt.ble.manager.ScanOptions
import gpt.ble.manager.UnpairResult
import gpt.ble.manager.android.adapter.AndroidAdapterState
import gpt.ble.manager.android.gatt.AndroidConnection
import gpt.ble.manager.android.pairing.AndroidPairingController
import gpt.ble.manager.android.scan.AndroidScanner
import gpt.ble.manager.internal.canonicalAddress
import gpt.ble.manager.internal.diagnose
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Public Android entry point. The scanner and pairing controller share the manager's monitor, while
 * sessions own their GATT resources. close stops work and closes sessions; a power state change
 * terminates active sessions with an explicit reason.
 */
class AndroidBleManager(
    context: Context,
    private val options: BleManagerOptions = BleManagerOptions(),
) : BleManager {
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
    private val activeConnections = MutableStateFlow<List<BleConnection>>(emptyList())
    override val connections = activeConnections.asStateFlow()
    override val scanEvents
        get() = scanController.scanEvents

    override val droppedScanEvents
        get() = scanController.droppedScanEvents

    private val sessions = ConcurrentHashMap<String, AndroidConnection>()
    override val capabilities: BleCapabilities
        get() =
            BleCapabilities(
                readRemoteRssi = true,
                readPhy = true,
                phy2M = runCatching { adapter?.isLe2MPhySupported == true }.getOrDefault(false),
                phyCoded =
                    runCatching { adapter?.isLeCodedPhySupported == true }.getOrDefault(false),
                connectionPriority = true,
                backgroundScan = true,
                companionAssociation = true,
            )

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
    private val pairing = AndroidPairingController(this.context, adapter, closed, lock, sessions)

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                adapterStatus.value = currentState()
                if (adapterStatus.value != AdapterState.Ready) {
                    stopScan()
                    sessions.values.toList().forEach {
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

    /** The Activity requests permissions; the required set depends on the Android version. */
    fun requiredPermissions(): List<String> = adapterStateReader.requiredPermissions()

    private fun currentState(): AdapterState = adapterStateReader.currentState()

    override suspend fun refreshAdapterState(): AdapterState =
        currentState().also { adapterStatus.value = it }

    override suspend fun getPairingState(device: BleDevice): PairingState =
        diagnose(options, "pairingState", "Android") { pairing.getPairingState(device) }

    override suspend fun pair(device: BleDevice, timeoutMillis: Long): PairResult =
        diagnose(options, "pair", "Android") { pairing.pair(device, timeoutMillis) }

    override suspend fun unpair(device: BleDevice, timeoutMillis: Long): UnpairResult =
        diagnose(options, "unpair", "Android") { pairing.unpair(device, timeoutMillis) }

    override suspend fun startScan(options: ScanOptions) = scanController.startScan(options)

    override fun stopScan() = scanController.stopScan()

    override suspend fun connect(device: BleDevice, timeoutMillis: Long): BleConnection =
        diagnose(options, "connect", "Android") { connectSession(device, timeoutMillis) }

    private suspend fun connectSession(device: BleDevice, timeoutMillis: Long): BleConnection {
        checkReady()
        require(timeoutMillis > 0)
        val address = canonicalAddress(device.address)
        val connection =
            AndroidConnection(
                context,
                adapter!!.getRemoteDevice(address),
                device.copy(address = address),
                onDeviceName = { name -> scanController.updateGattName(address, name) },
                options = options,
                capabilities = capabilities,
            ) {
                synchronized(lock) {
                    sessions.remove(address, it)
                    activeConnections.value = sessions.values.toList()
                }
            }
        synchronized(lock) {
            if (closed.get()) {
                throw BleException(BleError.Closed, "Manager is closed")
            }
            if (pairing.hasPending(address) || sessions.putIfAbsent(address, connection) != null) {
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
            synchronized(lock) { activeConnections.value = sessions.values.toList() }
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
        sessions.values.toList().forEach { it.close() }
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
