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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Один запрос на сессию, включая время ожидания ответа ОС. Mutex сериализует callers; stopped
 * пробуждает текущую операцию. Timeout/отмена делают сессию непригодной для новых запросов.
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

    suspend fun <T> execute(timeoutMillis: Long, block: suspend () -> T): T = mutex.withLock {
        require(timeoutMillis > 0)
        if (stopped.isCompleted) {
            throw stopped.await()
        }
        try {
            withTimeout(timeoutMillis.milliseconds) {
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
    }
}
