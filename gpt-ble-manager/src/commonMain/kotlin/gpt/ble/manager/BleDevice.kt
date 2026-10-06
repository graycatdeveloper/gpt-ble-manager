package gpt.ble.manager

enum class AddressType {
    Unknown,
    Public,
    Random,
}

enum class DeviceNameSource {
    Advertisement,
    System,
    Gatt,
}

/**
 * Immutable device snapshot merged by normalized MAC address. name stays null when unknown; only
 * displayName falls back to the address for the UI. A system record does not guarantee that the
 * device is nearby: check seenInCurrentScan. The name and RSSI may change after the next
 * advertising or scan response packet.
 */
data class BleDevice(
    val address: String,
    val name: String? = null,
    val rssi: Int? = null,
    val addressType: AddressType = AddressType.Unknown,
    val connectable: Boolean? = null,
    val serviceUuids: Set<BleUuid> = emptySet(),
    val manufacturerData: Map<Int, BleBytes> = emptyMap(),
    val nameSource: DeviceNameSource? = null,
    /** At least one advertising/scan-response packet arrived during this scan. */
    val seenInCurrentScan: Boolean = false,
    val serviceData: Map<BleUuid, BleBytes> = emptyMap(),
    /** Host receipt time in Unix milliseconds; null for system-only records. */
    val lastSeenMillis: Long? = null,
) {
    val displayName: String
        get() = name ?: address
}
