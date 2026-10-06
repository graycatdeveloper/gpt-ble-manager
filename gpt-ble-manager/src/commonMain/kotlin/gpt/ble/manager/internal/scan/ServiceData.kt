package gpt.ble.manager.internal.scan

import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleUuid

/**
 * AD types 0x16/0x20/0x21 encode little-endian UUIDs followed by arbitrary payload bytes.
 *
 * @see <a href="https://www.bluetooth.com/specifications/assigned-numbers/">Assigned Numbers</a>
 */
internal fun decodeServiceData(sections: Array<ByteArray>): Map<BleUuid, BleBytes> = buildMap {
    for (section in sections) {
        val length =
            when (section.firstOrNull()?.toInt()?.and(255)) {
                0x16 -> 2
                0x20 -> 4
                0x21 -> 16
                else -> continue
            }
        if (section.size < length + 1) {
            continue
        }
        val hex =
            (length downTo 1).joinToString("") {
                (section[it].toInt() and 255).toString(16).padStart(2, '0')
            }
        val uuid =
            if (length == 16) {
                listOf(
                        hex.substring(0, 8),
                        hex.substring(8, 12),
                        hex.substring(12, 16),
                        hex.substring(16, 20),
                        hex.substring(20),
                    )
                    .joinToString("-")
            } else {
                hex
            }
        put(BleUuid.parse(uuid), BleBytes(section.copyOfRange(length + 1, section.size)))
    }
}
