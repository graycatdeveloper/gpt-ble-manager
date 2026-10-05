package gpt.ble.manager

/**
 * Adapter availability for BLE operations. Closed is the manager's terminal state. The value order
 * is preserved; the platform manager explicitly maps native status values.
 */
enum class AdapterState {
    Ready,
    PoweredOff,
    PermissionRequired,
    Unsupported,
    Closed,
}
