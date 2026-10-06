package gpt.ble.manager

/**
 * Shared error categories across platforms. The native status and original exception are available
 * through the message/cause; successfully starting an operation does not mean it completed
 * successfully.
 */
enum class BleError {
    NotReady,
    PermissionDenied,
    Disconnected,
    Timeout,
    Rejected,
    Protocol,
    Unsupported,
    Closed,
    NativeFailure,
    NotificationOverflow,
}

/** BLE operation error with a portable [code] category and the original platform cause. */
class BleException(
    val code: BleError,
    message: String,
    cause: Throwable? = null,
    val details: BleErrorDetails = BleErrorDetails(),
) : Exception(message, cause)
