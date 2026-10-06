package gpt.ble.manager

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Independent budgets: waiting for the queue never consumes the transport's execution budget. */
data class OperationTimeouts(
    val executionMillis: Long = 15_000,
    val queueWaitMillis: Long = 15_000,
) {
    init {
        require(executionMillis in 1..120_000)
        require(queueWaitMillis > 0)
    }
}

/** Defaults for each connection created by a manager. The sink must return promptly. */
data class BleManagerOptions(
    val timeouts: OperationTimeouts = OperationTimeouts(),
    val diagnostics: BleDiagnosticSink = BleDiagnosticSink {},
)

internal class TimeoutOverride(val timeouts: OperationTimeouts) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TimeoutOverride>
}

/** Overrides budgets for operations invoked by [block], including its child coroutines. */
suspend fun <T> withOperationTimeouts(timeouts: OperationTimeouts, block: suspend () -> T): T =
    withContext(TimeoutOverride(timeouts)) { block() }
