package gpt.ble.manager

/**
 * Local OS pairing state, independent of the connection and GATT access. Unknown means no reliable
 * snapshot is available, not that the device is confirmed to be unpaired.
 */
enum class PairingState {
    Unknown,
    NotPaired,
    Pairing,
    Paired,
}

enum class PairResult {
    Paired,
    AlreadyPaired,
}

enum class UnpairResult {
    Unpaired,
    AlreadyUnpaired,
}
