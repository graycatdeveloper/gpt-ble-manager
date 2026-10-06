package gpt.ble.manager.sample

import gpt.ble.manager.AdapterState
import gpt.ble.manager.windows.WindowsBleManager
import kotlinx.coroutines.runBlocking

/** Reads GATT metadata; --notify changes CCCD; explicit --pair/--unpair changes the OS bond. */
fun main(args: Array<String>) = runBlocking {
    if ("--console-check" in args) {
        printConsoleEncoding()
        return@runBlocking
    }
    val target = args.firstOrNull { !it.startsWith("--") }
    require(!("--pair" in args && "--unpair" in args)) { "Choose either --pair or --unpair" }
    if (args.any { it in setOf("--pair", "--unpair", "--pairing-state") }) {
        requireNotNull(target) { "Specify a device name or address" }
    }
    val manager = WindowsBleManager()
    try {
        println("Adapter: ${manager.refreshAdapterState()}")
        check(manager.adapterState.value == AdapterState.Ready) {
            "Enable Bluetooth before scanning"
        }
        val device = findDevice(manager, target, args)
        inspectPairing(manager, device, args)
        if ("--managed" in args) {
            inspectManagedSession(manager, device)
        } else {
            inspectGatt(manager, device, args)
        }
    } finally {
        manager.close()
    }
}
