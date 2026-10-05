package gpt.ble.manager

/**
 * Общие категории ошибок платформ. Нативный статус и исходное исключение доступны через
 * сообщение/cause; успешное начало операции не считается её успешным завершением.
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

/** Ошибка BLE-операции с переносимой категорией [code] и сохранённой платформенной причиной. */
class BleException(val code: BleError, message: String, cause: Throwable? = null) :
    Exception(message, cause)
