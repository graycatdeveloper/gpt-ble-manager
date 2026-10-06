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
    val nameExact: String? = null,
    val ignoreNameCase: Boolean = false,
    val addresses: Set<String> = emptySet(),
    val minRssi: Int? = null,
    val manufacturerFilters: Map<Int, DataFilter> = emptyMap(),
    val serviceDataFilters: Map<BleUuid, DataFilter> = emptyMap(),
    /** Null keeps results until the next scan. Expiration is based on monotonic time. */
    val lostTimeoutMillis: Long? = null,
) {
    init {
        require(lostTimeoutMillis == null || lostTimeoutMillis > 0)
        require(minRssi == null || minRssi in -127..20)
        require(manufacturerFilters.keys.all { it in 0..65535 })
        addresses.forEach { gpt.ble.manager.internal.canonicalAddress(it) }
    }

    /** All filter categories are ANDed; serviceUuids matches any listed UUID. */
    fun matches(device: BleDevice): Boolean =
        (device.seenInCurrentScan || includeKnownDevices) &&
            (serviceUuids.isEmpty() || device.serviceUuids.any { it in serviceUuids }) &&
            (namePrefix == null || device.name?.startsWith(namePrefix, ignoreNameCase) == true) &&
            (nameExact == null || device.name?.equals(nameExact, ignoreNameCase) == true) &&
            (addresses.isEmpty() ||
                addresses.any { it.equals(device.address, ignoreCase = true) }) &&
            (minRssi == null || device.rssi?.let { it >= minRssi } == true) &&
            manufacturerFilters.all { (id, filter) ->
                filter.matches(device.manufacturerData[id])
            } &&
            serviceDataFilters.all { (uuid, filter) -> filter.matches(device.serviceData[uuid]) }
}

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
