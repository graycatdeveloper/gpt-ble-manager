package gpt.ble.manager.internal

/**
 * Проверяет адрес до передачи платформе. Нормализация сохраняет двоеточия и не меняет тип адреса:
 * Public/Random — отдельное свойство, его нельзя вывести из строки MAC.
 */
internal fun canonicalAddress(address: String): String {
    val result = address.uppercase()
    require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(result)) {
        "Invalid Bluetooth address: $address"
    }
    return result
}
