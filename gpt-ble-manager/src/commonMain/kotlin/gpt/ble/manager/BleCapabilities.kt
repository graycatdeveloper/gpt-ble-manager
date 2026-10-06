package gpt.ble.manager

/** Feature availability of this transport/adapter, not a guarantee that a peripheral accepts it. */
data class BleCapabilities(
    val readRemoteRssi: Boolean = false,
    val readPhy: Boolean = false,
    val phy2M: Boolean = false,
    val phyCoded: Boolean = false,
    val connectionPriority: Boolean = false,
    val preferredConnectionParameters: Boolean = false,
    val backgroundScan: Boolean = false,
    val companionAssociation: Boolean = false,
)

enum class BlePhy {
    Le1M,
    Le2M,
    LeCoded,
}

data class PhyState(val transmit: BlePhy, val receive: BlePhy)

enum class PhyCoding {
    Any,
    S2,
    S8,
}

enum class ConnectionPriority {
    Balanced,
    High,
    LowPower,
}

enum class PreferredConnectionParameters {
    Balanced,
    Throughput,
    PowerSaving,
}

internal fun unsupported(feature: String): Nothing =
    throw BleException(BleError.Unsupported, "$feature is not supported by this transport")
