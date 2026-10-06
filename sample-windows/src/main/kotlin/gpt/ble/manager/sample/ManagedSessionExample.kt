package gpt.ble.manager.sample

import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleManager
import gpt.ble.manager.OperationTimeouts
import gpt.ble.manager.session.ReconnectPolicy
import gpt.ble.manager.session.SessionOptions
import gpt.ble.manager.session.openSession
import gpt.ble.manager.withOperationTimeouts
import kotlinx.coroutines.coroutineScope

/** Read-only managed-session example. Reconnection is useful while a longer-lived UI owns it. */
internal suspend fun inspectManagedSession(manager: BleManager, device: BleDevice?) =
    coroutineScope {
        if (device == null) {
            return@coroutineScope
        }
        val session =
            manager.openSession(
                device,
                this,
                SessionOptions(reconnect = ReconnectPolicy(maxAttempts = 2)),
            )
        try {
            val connection = session.awaitReady()
            println("Session: ${connection.id}, capabilities=${connection.capabilities}")
            val name =
                withOperationTimeouts(OperationTimeouts(executionMillis = 10_000)) {
                    connection.readDeviceName()
                }
            println("GATT name: $name")
            println("Manager owns ${manager.connections.value.size} connection(s)")
        } finally {
            session.closeAndJoin()
        }
    }
