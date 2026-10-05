package gpt.ble.manager.sample

import gpt.ble.manager.AddressType
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleManager
import gpt.ble.manager.DeviceNameSource
import gpt.ble.manager.ScanOptions
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Пример объединяет advertising и системные имена через публичный API. Listener запускается в том
 * же scope, что main; stopScan и cancelAndJoin остаются в finally.
 */
internal suspend fun CoroutineScope.findDevice(
    manager: BleManager,
    target: String?,
    args: Array<String>,
): BleDevice? {
    val seen = mutableMapOf<String, Triple<String?, DeviceNameSource?, Boolean>>()
    val printer = launch {
        manager.devices.collect { devices ->
            devices.forEach { device ->
                val details = Triple(device.name, device.nameSource, device.seenInCurrentScan)
                if (seen[device.address] != details) {
                    seen[device.address] = details
                    println(
                        "DEVICE ${device.address} name=${device.name ?: "<unknown>"} source=${device.nameSource} seen=${device.seenInCurrentScan} rssi=${device.rssi} type=${device.addressType}"
                    )
                }
            }
        }
    }
    if ("--direct" !in args) {
        val waitSeconds =
            if (target == null) {
                15
            } else {
                30
            }
        println("SCAN: waiting up to ${waitSeconds}s for advertisements")
        manager.startScan(
            ScanOptions(
                namePrefix = args.firstOrNull { it.startsWith("--prefix=") }?.substringAfter('='),
                includeKnownDevices = "--known" in args,
            )
        )
    }
    val device =
        try {
            if ("--direct" in args) {
                BleDevice(requireNotNull(target), addressType = AddressType.Public)
            } else if (target == null) {
                delay(15_000.milliseconds)
                null
            } else {
                withTimeoutOrNull(30_000.milliseconds) {
                    manager.devices
                        .mapNotNull { list ->
                            list.firstOrNull {
                                it.name?.contains(target, ignoreCase = true) == true ||
                                    it.address.equals(target, true)
                            }
                        }
                        .first()
                }
            }
        } finally {
            manager.stopScan()
            printer.cancelAndJoin()
        }
    if ("--direct" !in args) {
        println(
            "Scan finished: ${manager.devices.value.size} device(s), error=${manager.scanState.value.error}, nameError=${manager.scanState.value.nameResolutionError}"
        )
    }
    if (target != null && device == null) {
        error(
            "No device matching '$target' was found in 30s; connect() was not called. " +
                "Use --known to include Windows-known devices, or <address> --direct --inspect for a known public address."
        )
    }
    return device
}
