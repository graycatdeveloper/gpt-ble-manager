package gpt.ble.manager.internal.scan

import gpt.ble.manager.BleDevice

/** Рекламный пакет; completeName различает Complete Local Name и Shortened Local Name. */
internal data class Advertisement(val device: BleDevice, val completeName: Boolean = true)
