package dev.gpt.ble.windows

import dev.gpt.ble.BleError
import dev.gpt.ble.BleException

/** Constructed by JNI with the numeric WinRT GattCommunicationStatus. */
internal class WindowsGattException(val status: Int, message: String) :
    IllegalStateException(message)

internal fun IllegalStateException.asBleException(
    fallbackMessage: String = "WinRT operation failed"
): BleException {
    val code =
        when {
            this is WindowsGattException ->
                when (status) {
                    3 -> BleError.PermissionDenied
                    2 -> BleError.Protocol
                    else -> BleError.NativeFailure
                }
            message?.startsWith("TIMEOUT:") == true -> BleError.Timeout
            else -> BleError.NativeFailure
        }
    return BleException(code, message ?: fallbackMessage, this)
}
