package gpt.ble.manager

/** IDs уникальны внутри соединения, даже если устройство повторяет один UUID. */
data class GattService(
    val id: Int,
    val uuid: BleUuid,
    val characteristics: List<GattCharacteristic>,
)

/**
 * Характеристика конкретного соединения. ID различает одинаковые UUID в одном каталоге. После
 * закрытия соединения старые объекты не используются: validation проверяет connectionId и
 * принадлежность к опубликованному каталогу. properties содержит исходную GATT bit mask.
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
 * Дескриптор принадлежит характеристике по characteristicId, а не только по UUID. CCCD (2902)
 * изменяется через subscribe, чтобы согласовать локальную подписку и устройство.
 */
data class GattDescriptor(
    val connectionId: String,
    val id: Int,
    val characteristicId: Int,
    val uuid: BleUuid,
)

/** Уведомление с immutable-копией байтов; последующий callback не меняет полученное значение. */
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
