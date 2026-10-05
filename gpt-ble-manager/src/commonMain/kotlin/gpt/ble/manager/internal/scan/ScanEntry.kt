package gpt.ble.manager.internal.scan

import gpt.ble.manager.BleDevice
import gpt.ble.manager.DeviceNameSource

/**
 * Источники имени хранятся раздельно: Advertisement > GATT > System. Пустой следующий пакет не
 * стирает уже известное имя и не повышает его достоверность.
 */
internal data class ScanEntry(
    val device: BleDevice,
    val advertisedName: String? = null,
    val completeName: Boolean = false,
    val systemName: String? = null,
    val gattName: String? = null,
) {
    fun resolved(): BleDevice =
        device.copy(
            name = advertisedName ?: gattName ?: systemName,
            nameSource =
                when {
                    advertisedName != null -> DeviceNameSource.Advertisement
                    gattName != null -> DeviceNameSource.Gatt
                    systemName != null -> DeviceNameSource.System
                    else -> null
                },
        )
}
