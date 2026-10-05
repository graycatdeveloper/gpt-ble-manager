package gpt.ble.manager.internal.scan

import gpt.ble.manager.BleDevice
import gpt.ble.manager.DeviceNameSource

/**
 * Name sources are stored separately: Advertisement > GATT > System. An empty subsequent packet
 * neither erases a known name nor increases its confidence.
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
