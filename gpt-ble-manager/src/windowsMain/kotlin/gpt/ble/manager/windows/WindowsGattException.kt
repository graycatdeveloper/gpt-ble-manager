package gpt.ble.manager.windows

import gpt.ble.manager.BleError
import gpt.ble.manager.BleException

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
                    1 -> BleError.Disconnected
                    3 -> BleError.PermissionDenied
                    2 -> BleError.Protocol
                    else -> BleError.NativeFailure
                }
            this is WindowsHResultException && hresult == 0x80070005.toInt() ->
                BleError.PermissionDenied
            message?.startsWith("TIMEOUT:") == true -> BleError.Timeout
            else -> BleError.NativeFailure
        }
    return BleException(
        code,
        message ?: fallbackMessage,
        this,
        gpt.ble.manager.BleErrorDetails(
            platform = "Windows",
            platformStatus =
                (this as? WindowsGattException)?.status
                    ?: (this as? WindowsHResultException)?.hresult,
        ),
    )
}
