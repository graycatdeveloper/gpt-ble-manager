package gpt.ble.manager.internal.gatt

import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/**
 * One request per session, including the wait for the OS response. Mutex serializes callers;
 * stopped wakes the current operation. A timeout or cancellation makes the session unusable for new
 * requests.
 *
 * @see <a href="https://kotlinlang.org/docs/cancellation-and-timeouts.html">Cancellation and
 *   timeouts</a>
 */
internal class OperationQueue(private val onAbort: (BleException) -> Unit) {
    private val mutex = Mutex()
    private val stopped = CompletableDeferred<BleException>()

    fun stop(error: BleException) {
        stopped.complete(error)
    }

    suspend fun <T> execute(
        timeoutMillis: Long,
        queueWaitMillis: Long = 15_000,
        block: suspend () -> T,
    ): T {
        require(timeoutMillis > 0 && queueWaitMillis > 0)
        // Waiting cancellation/timeout does not invalidate the in-flight request.
        // withTimeoutOrNull distinguishes this local deadline from a caller's outer timeout.
        var acquired = false
        try {
            val entered =
                kotlinx.coroutines.withTimeoutOrNull(queueWaitMillis) {
                    mutex.lock()
                    acquired = true
                    true
                } ?: false
            if (!entered) {
                throw BleException(BleError.Timeout, "GATT queue wait timed out")
            }
            if (stopped.isCompleted) {
                throw stopped.await()
            }
            try {
                return withTimeout(timeoutMillis.milliseconds) {
                    coroutineScope {
                        val operation = async { block() }
                        select {
                            operation.onAwait { it }
                            stopped.onAwait { throw it }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                val error = BleException(BleError.Timeout, "GATT operation timed out", e)
                stop(error)
                onAbort(error)
                throw error
            } catch (e: CancellationException) {
                val error =
                    BleException(BleError.Disconnected, "In-flight GATT operation was cancelled", e)
                stop(error)
                onAbort(error)
                throw e
            }
        } finally {
            if (acquired) {
                mutex.unlock()
            }
        }
    }
}
