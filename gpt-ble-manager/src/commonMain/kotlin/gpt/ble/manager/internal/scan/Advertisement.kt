package gpt.ble.manager.internal.scan

import gpt.ble.manager.BleDevice

/** Advertising packet; completeName distinguishes Complete Local Name from Shortened Local Name. */
internal data class Advertisement(val device: BleDevice, val completeName: Boolean = true)
