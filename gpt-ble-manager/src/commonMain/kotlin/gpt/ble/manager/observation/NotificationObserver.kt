package gpt.ble.manager.observation

import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleConnection
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.ConnectionState
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.SubscriptionMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared CCCD ownership for a connection, also usable by custom transports and test doubles. Each
 * collector attaches its listener before the first CCCD write. The last collector disables CCCD.
 * Concurrent Notify/Indicate requests for the same handle are rejected rather than changed behind
 * another collector's back. Do not mix managed observation and manual subscribe calls.
 *
 * @see <a
 *   href="https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/channel-flow.html">channelFlow</a>
 */
class NotificationObserver(private val connection: BleConnection) {
    private data class Entry(val mode: SubscriptionMode, var count: Int)

    private val mutex = Mutex()
    private val entries = mutableMapOf<GattCharacteristic, Entry>()

    fun observe(characteristic: GattCharacteristic, mode: SubscriptionMode): Flow<BleBytes> =
        channelFlow {
            require(mode != SubscriptionMode.Disabled)
            var acquired = false
            // UNDISPATCHED enters SharedFlow.collect before acquire can write CCCD.
            val listener =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    connection.notifications.collect { event ->
                        if (event.characteristic == characteristic) {
                            send(event.value)
                        }
                    }
                }
            val disconnection = launch {
                connection.state.first { it == ConnectionState.Disconnected }
                close(
                    connection.disconnectReason.value
                        ?: BleException(BleError.Disconnected, "Connection ended")
                )
            }
            try {
                mutex.withLock {
                    val current = entries[characteristic]
                    if (current != null) {
                        require(current.mode == mode) { "Conflicting subscription modes" }
                        current.count++
                    } else {
                        connection.subscribe(characteristic, mode)
                        entries[characteristic] = Entry(mode, 1)
                    }
                    acquired = true
                }
                awaitClose()
            } finally {
                // Cleanup is bounded by the connection's queue/execution budgets.
                withContext(NonCancellable) {
                    listener.cancelAndJoin()
                    disconnection.cancelAndJoin()
                    if (acquired)
                        mutex.withLock {
                            val current = entries.getValue(characteristic)
                            current.count--
                            if (current.count == 0) {
                                entries.remove(characteristic)
                                if (connection.state.value == ConnectionState.Connected) {
                                    try {
                                        connection.subscribe(
                                            characteristic,
                                            SubscriptionMode.Disabled,
                                        )
                                    } catch (error: Exception) {
                                        // A queued cleanup can time out before touching CCCD.
                                        // Closing
                                        // still releases its registration and prevents a leaked
                                        // stream.
                                        connection.close()
                                        throw error
                                    }
                                }
                            }
                        }
                }
            }
        }
}
