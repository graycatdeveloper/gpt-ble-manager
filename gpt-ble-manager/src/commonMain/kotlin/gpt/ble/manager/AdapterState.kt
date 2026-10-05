package gpt.ble.manager

/**
 * Доступность адаптера для BLE-операций. Closed — окончательное состояние менеджера. Порядок
 * значений сохранён; native status переводится явно платформенным менеджером.
 */
enum class AdapterState {
    Ready,
    PoweredOff,
    PermissionRequired,
    Unsupported,
    Closed,
}
