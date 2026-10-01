package dev.gpt.ble

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
