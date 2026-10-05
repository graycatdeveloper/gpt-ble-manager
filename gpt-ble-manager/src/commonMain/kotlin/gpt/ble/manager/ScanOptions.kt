package gpt.ble.manager

/**
 * Filters are applied after merging all name sources and advertising data. namePrefix is
 * case-sensitive. An empty UUID set does not restrict scanning. includeKnownDevices adds system
 * records even when no recent packet has arrived.
 */
data class ScanOptions(
    val serviceUuids: Set<BleUuid> = emptySet(),
    val namePrefix: String? = null,
    /** Include Windows-known / Android-bonded BLE devices even without an advertisement. */
    val includeKnownDevices: Boolean = false,
)

/**
 * Current scan state. nameResolutionError applies only to the supplementary system-name source and
 * does not replace the primary advertisement scanner's error.
 */
data class ScanState(
    val scanning: Boolean = false,
    val error: BleException? = null,
    /** Supplemental system-name lookup failed; advertisement scanning can still continue. */
    val nameResolutionError: BleException? = null,
)
