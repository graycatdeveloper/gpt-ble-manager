package gpt.ble.manager

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single GATT session. All requests are serialized: concurrent calls wait their turn. A timeout
 * or cancellation of a running request terminates the session so that a late callback cannot be
 * mistaken for the next request's result. Notifications arrive independently through SharedFlow.
 */
interface BleConnection {
    val capabilities: BleCapabilities
        get() = BleCapabilities()

    /** Shared, reference-counted CCCD ownership. Collecting enables; cancellation disables. */
    fun observe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode = SubscriptionMode.Notify,
    ): Flow<BleBytes>

    suspend fun readRssi(): Int = unsupported("Remote RSSI")

    suspend fun readPhy(): PhyState = unsupported("PHY query")

    suspend fun setPreferredPhy(
        transmit: Set<BlePhy>,
        receive: Set<BlePhy>,
        coding: PhyCoding = PhyCoding.Any,
    ): PhyState = unsupported("PHY preference")

    /** Requests a preference; the peripheral and OS decide the actual parameters. */
    suspend fun requestConnectionPriority(priority: ConnectionPriority): Unit =
        unsupported("Connection priority")

    suspend fun requestPreferredConnectionParameters(
        parameters: PreferredConnectionParameters
    ): Unit = unsupported("Preferred connection parameters")

    val id: String
    val device: BleDevice
    /** Latest device snapshot, including a name learned from GATT. */
    val deviceDetails: StateFlow<BleDevice>
    val state: StateFlow<ConnectionState>
    val disconnectReason: StateFlow<BleException?>
    val services: StateFlow<List<GattService>>
    /** ATT MTU, updated from platform negotiation. Windows negotiates automatically. */
    val mtu: StateFlow<Int>
    /**
     * Subscribe before enabling CCCD. Overflow terminates the connection with an explicit error.
     */
    val notifications: SharedFlow<CharacteristicValue>

    /**
     * Returns this session's stable catalog. Subsequent successful calls reuse its snapshot. A
     * change to the published GATT database terminates the session and requires a new connection
     * and service discovery.
     */
    suspend fun discoverServices(): List<GattService>

    /**
     * Reads Generic Access / Device Name (1800/2a00); null if missing or blank. Errors propagate.
     */
    suspend fun readDeviceName(): String?

    /**
     * Reads a characteristic from the current catalog, checking its Read property and session
     * ownership.
     */
    suspend fun read(characteristic: GattCharacteristic): BleBytes

    /**
     * Copies bytes before enqueueing the request. Validates the write mode and a size of at most
     * MTU - 3; splitting commands into packets is defined by the application protocol.
     */
    suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode = WriteMode.WithResponse,
    )

    /** Reads a descriptor from the current session. Numeric IDs distinguish duplicate UUIDs. */
    suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes

    /**
     * Writes a copy of the data to a descriptor. Use subscribe for CCCD instead of writing
     * directly.
     */
    suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray)

    /**
     * Configures the local handler and remote CCCD. Start the notifications collector before
     * calling this method. A configuration error closes the session because the subscription state
     * can no longer be trusted.
     */
    suspend fun subscribe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode = SubscriptionMode.Notify,
    )

    /**
     * Accepts 23..517. Android sends a request; Windows returns the MTU negotiated by the OS. The
     * returned value may differ from the requested value.
     */
    suspend fun requestMtu(size: Int): Int

    /**
     * Idempotently terminates the session and pending operations, then releases platform resources.
     */
    fun close()
}

enum class ConnectionState {
    Connecting,
    Connected,
    Disconnected,
}
