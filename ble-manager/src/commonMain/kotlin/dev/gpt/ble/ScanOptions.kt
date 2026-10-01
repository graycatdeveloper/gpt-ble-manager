package dev.gpt.ble

data class ScanOptions(
    val serviceUuids: Set<BleUuid> = emptySet(),
    val namePrefix: String? = null,
    /** Include Windows-known / Android-bonded BLE devices even without an advertisement. */
    val includeKnownDevices: Boolean = false,
)

data class ScanState(
    val scanning: Boolean = false,
    val error: BleException? = null,
    /** Supplemental system-name lookup failed; advertisement scanning can still continue. */
    val nameResolutionError: BleException? = null,
)
