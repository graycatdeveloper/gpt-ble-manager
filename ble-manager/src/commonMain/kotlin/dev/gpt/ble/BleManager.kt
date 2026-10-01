package dev.gpt.ble

import kotlinx.coroutines.flow.StateFlow

interface BleManager {
    val adapterState: StateFlow<AdapterState>
    val scanState: StateFlow<ScanState>
    val devices: StateFlow<List<BleDevice>>

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

    fun stopScan()

    suspend fun connect(device: BleDevice, timeoutMillis: Long = 20_000): BleConnection

    /** Idempotent. Stops scanning, disconnects all connections and releases native resources. */
    fun close()
}
