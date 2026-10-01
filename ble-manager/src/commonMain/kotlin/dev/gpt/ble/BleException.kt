package dev.gpt.ble

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

class BleException(val code: BleError, message: String, cause: Throwable? = null) :
    Exception(message, cause)
