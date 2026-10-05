package gpt.ble.manager.internal.names

/**
 * A system name must not be a MAC address presented as a device name. Comparison ignores
 * separators; a valid readable name is returned unchanged.
 */
internal fun usableSystemName(address: String, name: String?): String? =
    name?.trim()?.takeIf {
        it.isNotEmpty() &&
            it.filter(Char::isLetterOrDigit).uppercase() !=
                address.filter(Char::isLetterOrDigit).uppercase()
    }

/**
 * Strictly decodes UTF-8 from Generic Access / Device Name. Removes trailing NULs; an empty or
 * whitespace-only name and malformed UTF-8 return null. Other name characters are preserved.
 */
internal fun decodeDeviceName(bytes: ByteArray): String? = runCatching {
    bytes.decodeToString(throwOnInvalidSequence = true).trimEnd('\u0000').takeIf { it.isNotBlank() }
}
    .getOrNull()
