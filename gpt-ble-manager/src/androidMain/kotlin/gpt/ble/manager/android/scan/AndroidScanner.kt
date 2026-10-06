@file:Suppress("MissingPermission", "DEPRECATION")

package gpt.ble.manager.android.scan

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import gpt.ble.manager.AdapterState
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.ScanOptions
import gpt.ble.manager.ScanState
import gpt.ble.manager.internal.scan.ScanEventPublisher
import gpt.ble.manager.internal.scan.ScanStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns BluetoothLeScanner and a single ScanCallback. Callback identity checks discard events after
 * stop/restart. Sharing the manager monitor preserves the ordering of scanning, GATT name updates,
 * and resource cleanup.
 *
 * @see <a
 *   href="https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner">BluetoothLeScanner</a>
 */
internal class AndroidScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val closed: AtomicBoolean,
    private val lock: Any,
    private val adapterStatus: MutableStateFlow<AdapterState>,
    private val currentState: () -> AdapterState,
    private val checkReady: () -> Unit,
) {
    private val scanning = MutableStateFlow(ScanState())
    private val found = MutableStateFlow<List<BleDevice>>(emptyList())
    private var callback: ScanCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanStore: ScanStore? = null
    val scanState = scanning.asStateFlow()
    val devices = found.asStateFlow()
    private val publisher = ScanEventPublisher()
    val scanEvents = publisher.events
    val droppedScanEvents = publisher.dropped
    private var expiry: Job? = null

    private fun startExpiry(options: ScanOptions, store: ScanStore) {
        val ttl = options.lostTimeoutMillis ?: return
        expiry =
            CoroutineScope(Dispatchers.Default).launch {
                while (true) {
                    delay(minOf(ttl, 1_000))
                    synchronized(lock) {
                        if (!scanning.value.scanning) {
                            return@launch
                        }
                        store.expire()
                        found.value = store.devices.value
                    }
                }
            }
    }

    fun updateGattName(address: String, name: String) =
        synchronized(lock) {
            if (!closed.get()) {
                scanStore?.let {
                    it.updateGattName(address, name)
                    found.value = it.devices.value
                }
            }
        }

    suspend fun startScan(options: ScanOptions) {
        checkReady()
        if (Build.VERSION.SDK_INT < 31) {
            val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val enabled =
                if (Build.VERSION.SDK_INT >= 28) {
                    location.isLocationEnabled
                } else {
                    location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }
            if (!enabled) {
                throw BleException(
                    BleError.NotReady,
                    "Enable Android location services to scan on Android 8-11",
                )
            }
        }
        synchronized(lock) {
            stopScan()
            checkReady()
            found.value = emptyList()
            val store = ScanStore(options, publisher::emit)
            scanStore = store
            if (options.includeKnownDevices) {
                adapter
                    ?.bondedDevices
                    .orEmpty()
                    .filter {
                        it.type == BluetoothDevice.DEVICE_TYPE_LE ||
                            it.type == BluetoothDevice.DEVICE_TYPE_DUAL
                    }
                    .forEach { store.rememberSystemDevice(BleDevice(it.address, it.name)) }
                found.value = store.devices.value
            }
            val activeScanner =
                adapter?.bluetoothLeScanner
                    ?: throw BleException(BleError.NotReady, "BLE scanner is unavailable")
            val activeCallback =
                object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) =
                        accept(result)

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        results.forEach(::accept)
                    }

                    private fun accept(result: ScanResult) =
                        synchronized(lock) {
                            if (callback !== this || closed.get()) {
                                return@synchronized
                            }
                            acceptAndroidAdvertisement(store, result)
                            found.value = store.devices.value
                        }

                    override fun onScanFailed(errorCode: Int) =
                        synchronized(lock) {
                            if (callback !== this) {
                                return@synchronized
                            }
                            callback = null
                            scanner = null
                            scanning.value =
                                ScanState(
                                    error =
                                        BleException(
                                            BleError.NativeFailure,
                                            "Android scan failed: $errorCode",
                                        )
                                )
                        }
                }
            callback = activeCallback
            scanner = activeScanner
            scanning.value = ScanState(scanning = true)
            try {
                activeScanner.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    activeCallback,
                )
                startExpiry(options, store)
            } catch (e: Exception) {
                callback = null
                scanner = null
                val error =
                    BleException(
                        if (e is SecurityException) {
                            BleError.PermissionDenied
                        } else {
                            BleError.NativeFailure
                        },
                        "Unable to start scan",
                        e,
                    )
                scanning.value = ScanState(error = error)
                throw error
            }
        }
    }

    fun stopScan() =
        synchronized(lock) {
            expiry?.cancel()
            expiry = null
            val old = callback
            callback = null
            try {
                if (old != null) {
                    scanner?.stopScan(old)
                }
            } catch (_: SecurityException) {
                adapterStatus.value = AdapterState.PermissionRequired
            } catch (_: IllegalStateException) {
                adapterStatus.value = currentState()
            } finally {
                scanner = null
                scanning.value = ScanState()
            }
        }
}
