package gpt.ble.manager

/** Канонический Bluetooth UUID: принимает SIG 16/32-bit или полный 128-bit UUID. */
@ConsistentCopyVisibility
data class BleUuid private constructor(val value: String) {

    override fun toString(): String = value

    companion object {

        internal const val SUFFIX = "0000-1000-8000-00805f9b34fb"

        fun parse(value: String): BleUuid {
            val input = value.trim().lowercase().removePrefix("0x")
            val full =
                when (input.length) {
                    4 -> "0000$input-$SUFFIX"
                    8 -> "$input-$SUFFIX"
                    else -> input
                }
            require(
                Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(full)
            ) {
                "Invalid Bluetooth UUID: $value"
            }
            return BleUuid(full)
        }
    }
}
