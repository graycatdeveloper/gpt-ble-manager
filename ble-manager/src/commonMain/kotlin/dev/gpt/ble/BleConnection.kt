package dev.gpt.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BleConnection {
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

    suspend fun discoverServices(): List<GattService>

    /**
     * Reads Generic Access / Device Name (1800/2a00); null if missing or blank. Errors propagate.
     */
    suspend fun readDeviceName(): String?

    suspend fun read(characteristic: GattCharacteristic): BleBytes

    suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode = WriteMode.WithResponse,
    )

    suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes

    suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray)

    suspend fun subscribe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode = SubscriptionMode.Notify,
    )

    suspend fun requestMtu(size: Int): Int

    fun close()
}

enum class ConnectionState {
    Connecting,
    Connected,
    Disconnected,
}
