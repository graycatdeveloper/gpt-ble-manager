package gpt.ble.manager.windows.scan

import gpt.ble.manager.AddressType
import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.BleUuid
import gpt.ble.manager.ScanOptions
import gpt.ble.manager.ScanState
import gpt.ble.manager.internal.scan.Advertisement
import gpt.ble.manager.internal.scan.ScanStore
import gpt.ble.manager.windows.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Owns the scan generation and merges advertising, system, and GATT names. Uses the same monitor as
 * manager.close(): late native callbacks cannot publish data into a new scan. JNI calls and
 * synchronized boundaries are preserved.
 */
internal class WindowsScanner(
    private val lock: Any,
    private val native: NativeBridge,
    private val handle: () -> Long,
    private val activeHandle: () -> Long,
) {
    private var generation = 0L
    private var scan: ScanStore? = null
    private val scanning = MutableStateFlow(ScanState())
    private val found = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanState = scanning.asStateFlow()
    val devices = found.asStateFlow()

    fun startScan(options: ScanOptions) {
        synchronized(lock) {
            stopScan()
            val manager = activeHandle()
            scan = ScanStore(options)
            found.value = emptyList()
            scanning.value = ScanState(scanning = true)
            try {
                native.startScan(manager, ++generation)
            } catch (e: Exception) {
                scan = null
                val error = BleException(BleError.NativeFailure, "Unable to start Windows scan", e)
                scanning.value = ScanState(error = error)
                throw error
            }
        }
    }

    fun stopScan() =
        synchronized(lock) {
            generation++
            try {
                if (handle() != 0L) {
                    native.stopScan(handle())
                }
                scanning.value = scanning.value.copy(scanning = false)
            } catch (e: IllegalStateException) {
                scanning.value =
                    ScanState(
                        error =
                            BleException(BleError.NativeFailure, "Unable to stop Windows scan", e)
                    )
            }
        }

    internal fun advertisement(
        token: Long,
        address: String,
        name: String?,
        rssi: Int,
        addressType: Int,
        connectable: Boolean,
        completeName: Boolean,
        services: Array<String>,
        manufacturer: Array<ByteArray>,
    ) =
        synchronized(lock) {
            if (token != generation || handle() == 0L || !scanning.value.scanning) {
                return@synchronized
            }
            val store = scan ?: return@synchronized
            val mfr =
                manufacturer
                    .filter { it.size >= 2 }
                    .associate { bytes ->
                        ((bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)) to
                            BleBytes(bytes.copyOfRange(2, bytes.size))
                    }
            store.accept(
                Advertisement(
                    BleDevice(
                        address,
                        name,
                        rssi,
                        AddressType.entries.getOrElse(addressType) { AddressType.Unknown },
                        connectable,
                        services.map { BleUuid.parse(it) }.toSet(),
                        mfr,
                    ),
                    completeName,
                )
            )
            found.value = store.devices.value
        }

    internal fun scanStopped(token: Long, error: String?) =
        synchronized(lock) {
            if (token != generation) {
                return@synchronized
            }
            scanning.value =
                scanning.value.copy(
                    scanning = false,
                    error = error?.let { BleException(BleError.NativeFailure, it) },
                )
        }

    internal fun knownDevice(token: Long, address: String, name: String?) =
        synchronized(lock) {
            if (token != generation || handle() == 0L || !scanning.value.scanning) {
                return@synchronized
            }
            val store = scan ?: return@synchronized
            store.rememberSystemDevice(BleDevice(address, name))
            found.value = store.devices.value
        }

    internal fun nameLookupFailed(token: Long, message: String) =
        synchronized(lock) {
            if (token != generation || handle() == 0L || !scanning.value.scanning) {
                return@synchronized
            }
            scanning.value =
                scanning.value.copy(
                    nameResolutionError = BleException(BleError.NativeFailure, message)
                )
        }

    fun updateGattName(address: String, name: String) =
        synchronized(lock) {
            if (handle() == 0L) {
                return@synchronized
            }
            scan?.let {
                it.updateGattName(address, name)
                found.value = it.devices.value
            }
        }
}
