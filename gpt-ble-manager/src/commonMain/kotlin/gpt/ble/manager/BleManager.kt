package gpt.ble.manager

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns platform scanning and connections. The application chooses WindowsBleManager or
 * AndroidBleManager. Create a new instance after close. States are exposed as read-only StateFlow
 * values; the library publishes updates.
 */
interface BleManager {
    val capabilities: BleCapabilities
        get() = BleCapabilities()

    /** Connections owned by this manager; disconnected sessions are removed. */
    val connections: StateFlow<List<BleConnection>>
    /** Bounded live event stream. Subscribe before startScan; no replay to late subscribers. */
    val scanEvents: SharedFlow<ScanEvent>
    /** Number of events rejected because a subscriber could not keep up. */
    val droppedScanEvents: StateFlow<Long>

    fun connectionFor(address: String): BleConnection? =
        connections.value.firstOrNull {
            it.device.address.equals(address, ignoreCase = true)
        }

    /** Closes all currently registered sessions; the manager remains usable. */
    fun disconnectAll() {
        connections.value.toList().forEach { it.close() }
    }

    val adapterState: StateFlow<AdapterState>
    val scanState: StateFlow<ScanState>
    val devices: StateFlow<List<BleDevice>>

    /**
     * Refreshes the OS availability snapshot; the application remains responsible for requesting
     * Android runtime permissions.
     */
    suspend fun refreshAdapterState(): AdapterState

    /**
     * Fresh local OS snapshot; does not connect or pair. Unknown if the OS cannot resolve the
     * state.
     */
    suspend fun getPairingState(device: BleDevice): PairingState

    /**
     * Pairs through the OS; close this manager's connection first. Windows supports ConfirmOnly
     * with system consent; PIN/passkey ceremonies require Windows settings. Android uses its system
     * pairing UI. A timeout/cancellation does not guarantee rollback by the OS.
     */
    suspend fun pair(device: BleDevice, timeoutMillis: Long = 60_000): PairResult

    /**
     * Removes the local pairing. Close this manager's connection to the device first. Android
     * requires API 36+ and an existing CompanionDeviceManager association owned by this app;
     * otherwise throws BleError.Unsupported (use system Bluetooth settings). Failures throw
     * BleException. A timeout/cancellation does not guarantee rollback by the OS.
     */
    suspend fun unpair(device: BleDevice, timeoutMillis: Long = 20_000): UnpairResult

    /** Starts a fresh scan, clearing the previous results. Repeated calls restart it. */
    suspend fun startScan(options: ScanOptions = ScanOptions())

    /** Stops the current scan. Published results remain available until the next startScan. */
    fun stopScan()

    /**
     * Opens one session for an address. The timeout bounds the wait; errors and cancellation
     * release resources. An address already used by this manager's connection or pairing operation
     * cannot be reused.
     */
    suspend fun connect(device: BleDevice, timeoutMillis: Long = 20_000): BleConnection

    /** Idempotent. Stops scanning, disconnects all connections and releases native resources. */
    fun close()
}
