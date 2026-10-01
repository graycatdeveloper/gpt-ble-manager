package dev.gpt.ble.sample

import dev.gpt.ble.BleDevice
import dev.gpt.ble.BleException
import dev.gpt.ble.BleManager
import dev.gpt.ble.BleUuid
import dev.gpt.ble.SubscriptionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Читает стандартные атрибуты и опционально проверяет уведомления. Listener ставится до CCCD; после
 * проверки CCCD отключается, затем закрывается соединение. Команды в vendor write-характеристики
 * этот пример не отправляет.
 */
internal suspend fun CoroutineScope.inspectGatt(
    manager: BleManager,
    device: BleDevice?,
    args: Array<String>,
) {
    if (device != null && ("--inspect" in args || "--name-only" in args)) {
        println("CONNECT ${device.displayName} ${device.address}")
        val connection = manager.connect(device)
        try {
            println("Connected=${connection.state.value} MTU=${connection.mtu.value}")
            println(
                "GATT NAME: ${connection.readDeviceName()}; resolved=${connection.device.name}; source=${connection.device.nameSource}"
            )
            manager.devices.value
                .firstOrNull { it.address == device.address }
                ?.let {
                    println(
                        "MANAGER NAME: ${it.name}; source=${it.nameSource}; seen=${it.seenInCurrentScan}"
                    )
                }
            if ("--name-only" in args) {
                return
            }
            println("DISCOVER GATT")
            val services = connection.discoverServices()
            for (service in services) {
                println("SERVICE ${service.uuid} id=${service.id}")
                for (characteristic in service.characteristics) {
                    println(
                        "  CHARACTERISTIC ${characteristic.uuid} id=${characteristic.id} read=${characteristic.canRead} write=${characteristic.canWrite} notify=${characteristic.canNotify} indicate=${characteristic.canIndicate}"
                    )
                    characteristic.descriptors.forEach {
                        println("    DESCRIPTOR ${it.uuid} id=${it.id}")
                    }
                }
            }
            val safeReads = setOf("2a00", "2a19", "2a24", "2a29").map(BleUuid::parse).toSet()
            for (characteristic in
                services
                    .flatMap { it.characteristics }
                    .filter { it.canRead && it.uuid in safeReads }) {
                try {
                    val value = connection.read(characteristic)
                    val display =
                        if (characteristic.uuid == BleUuid.parse("2a19")) {
                            value.toString()
                        } else {
                            value.toByteArray().decodeToString()
                        }
                    println("READ ${characteristic.uuid}: $display")
                } catch (e: BleException) {
                    println("READ ${characteristic.uuid} failed: ${e.code} ${e.message}")
                }
            }
            args
                .firstOrNull { it.startsWith("--notify=") }
                ?.substringAfter('=')
                ?.let { requested ->
                    val characteristic =
                        services
                            .flatMap { it.characteristics }
                            .single { it.uuid == BleUuid.parse(requested) }
                    var received = 0
                    val listener =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            connection.notifications.collect { event ->
                                if (event.characteristic.id == characteristic.id) {
                                    received++
                                    println(
                                        "NOTIFICATION ${characteristic.uuid}: ${event.value.size} bytes"
                                    )
                                }
                            }
                        }
                    try {
                        connection.subscribe(characteristic)
                        val cccd =
                            characteristic.descriptors.single { it.uuid == BleUuid.parse("2902") }
                        println("CCCD enabled: ${connection.readDescriptor(cccd)}")
                        delay(5_000)
                        connection.subscribe(characteristic, SubscriptionMode.Disabled)
                        println(
                            "CCCD disabled: ${connection.readDescriptor(cccd)}; received=$received"
                        )
                    } finally {
                        listener.cancelAndJoin()
                    }
                }
        } finally {
            connection.close()
            println("Disconnected=${connection.state.value}")
        }
    }
}
