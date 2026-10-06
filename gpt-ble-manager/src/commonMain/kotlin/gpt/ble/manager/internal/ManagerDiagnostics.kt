package gpt.ble.manager.internal

import gpt.ble.manager.BleDiagnosticEvent
import gpt.ble.manager.BleError
import gpt.ble.manager.BleErrorDetails
import gpt.ble.manager.BleException
import gpt.ble.manager.BleManagerOptions
import gpt.ble.manager.OperationPhase
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException

/** Records manager operations after their own platform-specific cleanup has completed. */
internal suspend fun <T> diagnose(
    options: BleManagerOptions,
    name: String,
    platform: String,
    block: suspend () -> T,
): T {
    val start = TimeSource.Monotonic.markNow()
    val details = BleErrorDetails(operation = name, platform = platform)
    fun report(phase: OperationPhase, error: BleException? = null) {
        runCatching {
            options.diagnostics.record(
                BleDiagnosticEvent(details, phase, start.elapsedNow().inWholeMilliseconds, error)
            )
        }
    }
    report(OperationPhase.Started)
    try {
        return block().also { report(OperationPhase.Succeeded) }
    } catch (error: CancellationException) {
        report(OperationPhase.Cancelled)
        throw error
    } catch (error: BleException) {
        val contextual =
            BleException(
                error.code,
                error.message.orEmpty(),
                error,
                error.details.copy(
                    operation = error.details.operation ?: name,
                    platform = error.details.platform ?: platform,
                ),
            )
        report(OperationPhase.Failed, contextual)
        throw contextual
    } catch (error: Exception) {
        report(
            OperationPhase.Failed,
            BleException(BleError.NativeFailure, error.message.orEmpty(), error, details),
        )
        throw error
    }
}
