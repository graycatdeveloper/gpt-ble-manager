package dev.gpt.ble

/** A value with content equality that never exposes its mutable backing array. */
class BleBytes(bytes: ByteArray) {

    private val data = bytes.copyOf()

    val size: Int get() = data.size

    fun toByteArray(): ByteArray = data.copyOf()

    override fun equals(other: Any?): Boolean =
        other is BleBytes && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = data.joinToString(" ") {
        (it.toInt() and 255)
            .toString(16)
            .padStart(2, '0')
    }

}
