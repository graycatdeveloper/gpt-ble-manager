package gpt.ble.manager.internal

/**
 * Validates the address before passing it to the platform. Normalization preserves colons and does
 * not change the address type: Public/Random is a separate property that cannot be inferred from
 * the MAC string.
 */
internal fun canonicalAddress(address: String): String {
    val result = address.uppercase()
    require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(result)) {
        "Invalid Bluetooth address: $address"
    }
    return result
}
