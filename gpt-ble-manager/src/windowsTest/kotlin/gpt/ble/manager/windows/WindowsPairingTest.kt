package gpt.ble.manager.windows

import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.PairResult
import gpt.ble.manager.UnpairResult
import gpt.ble.manager.windows.pairing.pairResult
import gpt.ble.manager.windows.pairing.unpairResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsPairingTest {
    @Test
    fun successfulAndIdempotentResultsAreDistinct() {
        assertEquals(PairResult.Paired, pairResult(0))
        assertEquals(PairResult.AlreadyPaired, pairResult(3))
        assertEquals(UnpairResult.Unpaired, unpairResult(0))
        assertEquals(UnpairResult.AlreadyUnpaired, unpairResult(1))
    }

    @Test
    fun failuresAreTypedAndNeverSuccess() {
        assertEquals(
            BleError.PermissionDenied,
            assertFailsWith<BleException> { pairResult(12) }.code,
        )
        assertEquals(
            BleError.PermissionDenied,
            assertFailsWith<BleException> { unpairResult(3) }.code,
        )
        assertEquals(BleError.Timeout, assertFailsWith<BleException> { pairResult(7) }.code)
        assertEquals(BleError.Rejected, assertFailsWith<BleException> { pairResult(14) }.code)
        for (status in -1..19) {
            if (status !in setOf(0, 3)) {
                assertFailsWith<BleException> { pairResult(status) }
            }
        }
        for (status in listOf(-1, 2, 3, 4, 999)) {
            assertFailsWith<BleException> {
                unpairResult(status)
            }
        }
        assertEquals(BleError.NativeFailure, assertFailsWith<BleException> { pairResult(999) }.code)
    }

    @Test
    fun missingDeviceIsNotReportedAsAlreadyUnpaired() {
        assertEquals(BleError.Rejected, assertFailsWith<BleException> { unpairResult(-1) }.code)
    }

    @Test
    fun unsupportedCeremonyProvidesAnActionableError() {
        val error = assertFailsWith<BleException> { pairResult(16) }
        assertEquals(BleError.Unsupported, error.code)
        assertContains(error.message.orEmpty(), "ConfirmOnly")
        assertContains(error.message.orEmpty(), "Windows Bluetooth settings")
    }
}
