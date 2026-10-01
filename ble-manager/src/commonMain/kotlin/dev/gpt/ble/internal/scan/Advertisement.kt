package dev.gpt.ble.internal.scan

import dev.gpt.ble.BleDevice

/** Рекламный пакет; completeName различает Complete Local Name и Shortened Local Name. */
internal data class Advertisement(val device: BleDevice, val completeName: Boolean = true)
