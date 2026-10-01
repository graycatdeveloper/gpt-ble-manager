package dev.gpt.ble

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
) {
    val displayName: String
        get() = name ?: address
}
