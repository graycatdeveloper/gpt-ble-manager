package dev.gpt.ble.windows

import dev.gpt.ble.BleError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class WindowsGattExceptionTest {
    @Test
    fun accessDeniedUsesStatusEvenWithLocalizedMessage() {
        val native = WindowsGattException(3, "Доступ к сервису запрещён")
        val error = native.asBleException()
        assertEquals(BleError.PermissionDenied, error.code)
        assertEquals(native.message, error.message)
        assertSame(native, error.cause)
    }

    @Test
    fun protocolErrorsKeepTheirOwnClassification() {
        assertEquals(BleError.Protocol, WindowsGattException(2, "ATT error").asBleException().code)
    }

    @Test
    fun unreachableAndUnknownStatusesAreNotPermissionErrors() {
        for (status in listOf(1, 42)) {
            assertEquals(
                BleError.NativeFailure,
                WindowsGattException(status, "AccessDenied in diagnostic context")
                    .asBleException()
                    .code,
            )
        }
    }

    @Test
    fun anUnstructuredMessageIsNotParsedAsAGattStatus() {
        assertEquals(
            BleError.NativeFailure,
            IllegalStateException("AccessDenied (status=3)").asBleException().code,
        )
    }

    @Test
    fun existingTimeoutAndFallbackHandlingIsPreserved() {
        assertEquals(
            BleError.Timeout,
            IllegalStateException("TIMEOUT: operation expired").asBleException().code,
        )
        assertEquals(
            "Connecting failed",
            IllegalStateException().asBleException("Connecting failed").message,
        )
    }
}
