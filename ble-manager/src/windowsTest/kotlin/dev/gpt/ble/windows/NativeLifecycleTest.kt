package dev.gpt.ble.windows

import dev.gpt.ble.AdapterState
import dev.gpt.ble.BleDevice
import dev.gpt.ble.BleError
import dev.gpt.ble.BleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

class NativeLifecycleTest {
    @Test
    fun closedManagerRejectsPairingWithoutTouchingDevices() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            return@runBlocking
        }
        val manager = WindowsBleManager()
        manager.close()
        val device = BleDevice("00:00:00:00:00:01")
        assertEquals(
            BleError.Closed,
            assertFailsWith<BleException> { manager.getPairingState(device) }.code,
        )
        assertEquals(BleError.Closed, assertFailsWith<BleException> { manager.pair(device) }.code)
        assertEquals(BleError.Closed, assertFailsWith<BleException> { manager.unpair(device) }.code)
    }

    @Test
    fun nativeManagersAreIndependentAndCloseIsIdempotent() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            return@runBlocking
        }
        val first = WindowsBleManager()
        val second = WindowsBleManager()
        try {
            first.close()
            first.close()
            assertEquals(AdapterState.Closed, first.refreshAdapterState())
            assertNotEquals(AdapterState.Closed, second.refreshAdapterState())
        } finally {
            first.close()
            second.close()
        }
    }
}
