package gpt.ble.manager

/**
 * Состояние локального сопряжения ОС. Оно независимо от подключения и доступа к GATT. Unknown
 * означает отсутствие достоверного снимка, а не доказанное отсутствие сопряжения.
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
