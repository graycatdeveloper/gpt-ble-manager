package gpt.ble.manager.windows

import gpt.ble.manager.BleError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class WindowsGattExceptionTest {
    @Test
    fun hresultPreservesNumericCodeAndUnicodeText() {
        val native =
            WindowsHResultException(
                0x80070005.toInt(),
                "HRESULT 0x80070005: \u0414\u043e\u0441\u0442\u0443\u043f",
            )
        val error = native.asBleException()
        assertEquals(BleError.PermissionDenied, error.code)
        assertEquals(native.message, error.message)
        assertEquals(0x80070005.toInt(), (error.cause as WindowsHResultException).hresult)
    }

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
                if (status == 1) {
                    BleError.Disconnected
                } else {
                    BleError.NativeFailure
                },
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
