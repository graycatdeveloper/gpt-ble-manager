package dev.gpt.ble.internal.names

/**
 * Системное имя не должно быть MAC-адресом, замаскированным под имя устройства. При сравнении
 * игнорируются разделители; исходное читаемое имя возвращается без замены.
 */
internal fun usableSystemName(address: String, name: String?): String? =
    name?.trim()?.takeIf {
        it.isNotEmpty() &&
            it.filter(Char::isLetterOrDigit).uppercase() !=
                address.filter(Char::isLetterOrDigit).uppercase()
    }

internal fun decodeDeviceName(bytes: ByteArray): String? = runCatching {
    bytes.decodeToString(throwOnInvalidSequence = true).trimEnd('\u0000').takeIf { it.isNotBlank() }
}
    .getOrNull()
