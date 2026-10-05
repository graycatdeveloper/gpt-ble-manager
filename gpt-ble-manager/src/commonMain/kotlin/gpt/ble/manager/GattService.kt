package gpt.ble.manager

/** IDs are unique within a connection, even when the device repeats a UUID. */
data class GattService(
    val id: Int,
    val uuid: BleUuid,
    val characteristics: List<GattCharacteristic>,
)

/**
 * A characteristic belonging to a specific connection. Its ID distinguishes identical UUIDs in one
 * catalog. Objects from a closed connection cannot be reused: validation checks connectionId and
 * membership in the published catalog. properties holds the original GATT bit mask.
 */
data class GattCharacteristic(
    val connectionId: String,
    val id: Int,
    val serviceId: Int,
    val uuid: BleUuid,
    val properties: Int,
    val descriptors: List<GattDescriptor> = emptyList(),
) {
    val canRead: Boolean
        get() = properties and 0x02 != 0

    val canWrite: Boolean
        get() = properties and 0x08 != 0

    val canWriteWithoutResponse: Boolean
        get() = properties and 0x04 != 0

    val canNotify: Boolean
        get() = properties and 0x10 != 0

    val canIndicate: Boolean
        get() = properties and 0x20 != 0
}

/**
 * A descriptor belongs to a characteristic through characteristicId, not just its UUID. CCCD (2902)
 * is changed through subscribe to keep the local subscription and device in sync.
 */
data class GattDescriptor(
    val connectionId: String,
    val id: Int,
    val characteristicId: Int,
    val uuid: BleUuid,
)

/** Notification with an immutable copy of the bytes; later callbacks cannot change its value. */
data class CharacteristicValue(val characteristic: GattCharacteristic, val value: BleBytes)

enum class WriteMode {
    WithResponse,
    WithoutResponse,
}

enum class SubscriptionMode {
    Disabled,
    Notify,
    Indicate,
}
