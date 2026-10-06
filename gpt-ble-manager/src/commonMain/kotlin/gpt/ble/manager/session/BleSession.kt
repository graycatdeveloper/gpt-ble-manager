package gpt.ble.manager.session

import gpt.ble.manager.AdapterState
import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleConnection
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.BleManager
import gpt.ble.manager.BleUuid
import gpt.ble.manager.ConnectionState
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.GattService
import gpt.ble.manager.SubscriptionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Resolves a fresh handle after every discovery; indexes distinguish duplicate UUIDs. */
data class CharacteristicSelector(
    val serviceUuid: BleUuid,
    val characteristicUuid: BleUuid,
    val serviceInstance: Int = 0,
    val characteristicInstance: Int = 0,
) {
    init {
        require(serviceInstance >= 0 && characteristicInstance >= 0)
    }

    fun resolve(services: List<GattService>): GattCharacteristic =
        services
            .filter { it.uuid == serviceUuid }
            .getOrNull(serviceInstance)
            ?.characteristics
            ?.filter { it.uuid == characteristicUuid }
            ?.getOrNull(characteristicInstance)
            ?: throw BleException(
                BleError.Unsupported,
                "Requested characteristic instance is absent",
            )
}

data class ReconnectPolicy(
    /** Total retry budget during this session. Zero disables reconnection. */
    val maxAttempts: Int = 5,
    val initialDelayMillis: Long = 500,
    val maxDelayMillis: Long = 10_000,
    val multiplier: Double = 2.0,
    val retryableErrors: Set<BleError> =
        setOf(BleError.Disconnected, BleError.Timeout, BleError.NotReady),
) {
    init {
        require(maxAttempts >= 0)
        require(initialDelayMillis > 0 && maxDelayMillis >= initialDelayMillis)
        require(multiplier.isFinite() && multiplier >= 1.0)
    }
}

data class SessionOptions(
    val connectTimeoutMillis: Long = 20_000,
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
) {
    init {
        require(connectTimeoutMillis in 1..120_000)
    }
}

sealed interface SessionState {
    data object Connecting : SessionState

    data class Ready(val connection: BleConnection) : SessionState

    data class Reconnecting(val attempt: Int, val reason: BleException) : SessionState

    data class Failed(val reason: BleException) : SessionState

    data object Closed : SessionState
}

/**
 * Owns replacement connections inside the caller's scope. Each attempt discovers a new catalog.
 * Closing this session, its current connection, the manager, or the parent scope stops retries.
 * Writes and reads are NEVER replayed. Use awaitReady again after an operation fails.
 */
class BleSession
internal constructor(
    private val manager: BleManager,
    private val device: BleDevice,
    scope: CoroutineScope,
    private val options: SessionOptions,
) {
    private val status = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> = status.asStateFlow()
    private val current = MutableStateFlow<BleConnection?>(null)
    val connection: StateFlow<BleConnection?> = current.asStateFlow()
    private val stopped = MutableStateFlow(false)
    private val job = scope.launch { run() }

    init {
        // An already-cancelled scope may prevent run() from entering its finally block.
        job.invokeOnCompletion {
            if (status.value !is SessionState.Failed) {
                status.value = SessionState.Closed
            }
        }
    }

    private suspend fun run() {
        var attempts = 0
        var retryDelay = options.reconnect.initialDelayMillis
        try {
            while (!stopped.value) {
                var opened: BleConnection? = null
                val failure =
                    try {
                        if (manager.refreshAdapterState() == AdapterState.Closed) {
                            throw BleException(BleError.Closed, "Manager is closed")
                        }
                        opened = manager.connect(device, options.connectTimeoutMillis)
                        opened.discoverServices()
                        current.value = opened
                        status.value = SessionState.Ready(opened)
                        opened.state.first { it == ConnectionState.Disconnected }
                        opened.disconnectReason.value
                            ?: BleException(BleError.Disconnected, "Link lost")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: BleException) {
                        error
                    } catch (error: Exception) {
                        BleException(BleError.NativeFailure, "Session setup failed", error)
                    } finally {
                        current.value = null
                        opened?.close()
                    }
                if (stopped.value || failure.code == BleError.Closed) {
                    break
                }
                if (
                    attempts >= options.reconnect.maxAttempts ||
                        failure.code !in options.reconnect.retryableErrors
                ) {
                    status.value = SessionState.Failed(failure)
                    return
                }
                attempts++
                status.value = SessionState.Reconnecting(attempts, failure)
                delay(retryDelay)
                retryDelay =
                    (retryDelay * options.reconnect.multiplier)
                        .coerceAtMost(options.reconnect.maxDelayMillis.toDouble())
                        .toLong()
            }
        } finally {
            current.value?.close()
            current.value = null
            if (status.value !is SessionState.Failed) {
                status.value = SessionState.Closed
            }
        }
    }

    suspend fun awaitReady(): BleConnection =
        when (
            val result = state.first {
                it is SessionState.Ready || it is SessionState.Failed || it == SessionState.Closed
            }
        ) {
            is SessionState.Ready -> result.connection
            is SessionState.Failed -> throw result.reason
            else -> throw BleException(BleError.Closed, "Session is closed")
        }

    /** Resubscribes by UUID/instance on each replacement connection. Gaps are not replayed. */
    fun observe(
        selector: CharacteristicSelector,
        mode: SubscriptionMode = SubscriptionMode.Notify,
    ): Flow<BleBytes> = channelFlow {
        val lifecycle = launch {
            when (
                val terminal = state.first {
                    it is SessionState.Failed || it == SessionState.Closed
                }
            ) {
                is SessionState.Failed -> close(terminal.reason)
                else -> close()
            }
        }
        val values = launch {
            connection.collectLatest { active ->
                if (active != null) {
                    try {
                        active.observe(selector.resolve(active.services.value), mode).collect {
                            send(it)
                        }
                    } catch (error: BleException) {
                        if (active.state.value != ConnectionState.Disconnected) {
                            throw error
                        }
                    }
                }
            }
        }
        awaitClose {
            lifecycle.cancel()
            values.cancel()
        }
    }

    fun close() {
        if (stopped.compareAndSet(false, true)) {
            current.value?.close()
            job.cancel()
            status.value = SessionState.Closed
        }
    }

    /** Waits for pending platform calls and cleanup to finish after close. */
    suspend fun closeAndJoin() {
        close()
        job.join()
    }
}

fun BleManager.openSession(
    device: BleDevice,
    scope: CoroutineScope,
    options: SessionOptions = SessionOptions(),
): BleSession = BleSession(this, device, scope, options)
