package gpt.ble.manager.windows.pairing

import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.PairResult
import gpt.ble.manager.UnpairResult

internal fun pairResult(status: Int): PairResult {
    if (status == 0) {
        return PairResult.Paired
    }
    if (status == 3) {
        return PairResult.AlreadyPaired
    }
    val reason =
        when (status) {
            -1 -> "Device not found; scan first and check the address type"
            1 -> "NotReadyToPair"
            2 -> "NotPaired"
            4 -> "ConnectionRejected"
            5 -> "TooManyConnections"
            6 -> "HardwareFailure"
            7 -> "AuthenticationTimeout"
            8 -> "AuthenticationNotAllowed"
            9 -> "AuthenticationFailure"
            10 -> "NoSupportedProfiles"
            11 -> "ProtectionLevelCouldNotBeMet"
            12 -> "AccessDenied"
            13 -> "InvalidCeremonyData"
            14 -> "PairingCanceled"
            15 -> "OperationAlreadyInProgress"
            16 ->
                "RequiredHandlerNotRegistered; this Windows client supports ConfirmOnly pairing. For PIN/passkey devices use Windows Bluetooth settings"
            17 -> "RejectedByHandler"
            18 -> "RemoteDeviceHasAssociation"
            else -> "Failed"
        }
    val code =
        when (status) {
            1 -> BleError.NotReady
            7 -> BleError.Timeout
            8,
            10,
            11,
            16 -> BleError.Unsupported
            12 -> BleError.PermissionDenied
            -1,
            2,
            4,
            5,
            9,
            13,
            14,
            15,
            17,
            18 -> BleError.Rejected
            else -> BleError.NativeFailure
        }
    throw BleException(
        code,
        "Windows pair failed: $reason (status=$status)",
        details =
            gpt.ble.manager.BleErrorDetails(
                operation = "pair",
                platform = "Windows",
                platformStatus = status,
            ),
    )
}

/** WinRT DeviceUnpairingResultStatus; -1 means no matching DeviceInformation record. */
internal fun unpairResult(status: Int): UnpairResult =
    when (status) {
        0 -> UnpairResult.Unpaired
        1 -> UnpairResult.AlreadyUnpaired
        -1 ->
            throw BleException(
                BleError.Rejected,
                "Windows cannot find this BLE device; check the address and address type",
            )
        2 ->
            throw BleException(
                BleError.Rejected,
                "A Windows pairing or unpairing operation is already in progress",
            )
        3 ->
            throw BleException(
                BleError.PermissionDenied,
                "Windows denied permission to unpair the device",
            )
        else -> throw BleException(BleError.NativeFailure, "Windows unpair failed (status=$status)")
    }
