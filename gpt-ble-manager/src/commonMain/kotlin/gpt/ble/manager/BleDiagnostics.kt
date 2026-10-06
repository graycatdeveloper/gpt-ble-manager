package gpt.ble.manager

/** Machine-readable context. No characteristic values or credentials are included. */
data class BleErrorDetails(
    val operation: String? = null,
    val platform: String? = null,
    val platformStatus: Int? = null,
    val connectionId: String? = null,
    val serviceUuid: BleUuid? = null,
    val characteristicUuid: BleUuid? = null,
    val descriptorUuid: BleUuid? = null,
    val phase: OperationPhase? = null,
)

enum class OperationPhase {
    Queued,
    Started,
    Succeeded,
    Failed,
    Cancelled,
}

data class BleDiagnosticEvent(
    val details: BleErrorDetails,
    val phase: OperationPhase,
    /** Monotonic elapsed time since enqueue, including queue wait. */
    val elapsedMillis: Long,
    val error: BleException? = null,
)

/** Called on the operation thread. Exceptions from a sink cannot break Bluetooth operations. */
fun interface BleDiagnosticSink {
    fun record(event: BleDiagnosticEvent)
}
