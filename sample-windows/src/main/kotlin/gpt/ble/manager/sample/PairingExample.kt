package gpt.ble.manager.sample

import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleManager

/** Сопряжение меняется только при явных CLI-флагах; простой запрос состояния — read-only. */
internal suspend fun inspectPairing(manager: BleManager, device: BleDevice?, args: Array<String>) {
    if (device != null && args.any { it in setOf("--pair", "--unpair", "--pairing-state") }) {
        println("PAIRING ${device.address}: ${manager.getPairingState(device)}")
        if ("--pair" in args) {
            println("PAIR RESULT: ${manager.pair(device)}")
        }
        if ("--unpair" in args) {
            println("UNPAIR RESULT: ${manager.unpair(device)}")
        }
        if ("--pair" in args || "--unpair" in args) {
            println("PAIRING AFTER: ${manager.getPairingState(device)}")
        }
    }
}
