package dev.gpt.ble

/** IDs are unique within a connection, even when a device repeats a UUID. */
data class GattService(
    val id: Int,
    val uuid: BleUuid,
    val characteristics: List<GattCharacteristic>,
)

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

data class GattDescriptor(
    val connectionId: String,
    val id: Int,
    val characteristicId: Int,
    val uuid: BleUuid,
)

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
