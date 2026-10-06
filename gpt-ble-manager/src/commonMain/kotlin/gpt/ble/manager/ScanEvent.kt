package gpt.ble.manager

/**
 * Events complement the merged device StateFlow. Packet contains only this advertisement's data.
 */
sealed interface ScanEvent {
    data class Packet(val advertisement: BleDevice, val timestampMillis: Long) : ScanEvent

    data class Appeared(val device: BleDevice) : ScanEvent

    data class Updated(val device: BleDevice) : ScanEvent

    data class Disappeared(val device: BleDevice) : ScanEvent
}

/** Matches a payload prefix, optionally at an offset and with a per-byte bit mask. */
data class DataFilter(
    val value: BleBytes = BleBytes(byteArrayOf()),
    val mask: BleBytes? = null,
    val offset: Int = 0,
) {
    init {
        require(offset >= 0)
        require(mask == null || mask.size == value.size)
    }

    fun matches(bytes: BleBytes?): Boolean {
        if (bytes == null || offset > bytes.size || value.size > bytes.size - offset) {
            return false
        }
        val data = bytes.toByteArray()
        val expected = value.toByteArray()
        val bits = mask?.toByteArray()
        return expected.indices.all { i ->
            val bitMask = bits?.get(i)?.toInt() ?: 255
            (data[offset + i].toInt() and bitMask) == (expected[i].toInt() and bitMask)
        }
    }
}
