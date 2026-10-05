package gpt.ble.manager

/**
 * Фильтры применяются после объединения всех источников имени и рекламных данных. namePrefix
 * сравнивается с учётом регистра. Пустой набор UUID не ограничивает поиск. includeKnownDevices
 * добавляет системные записи, даже если свежего пакета ещё не было.
 */
data class ScanOptions(
    val serviceUuids: Set<BleUuid> = emptySet(),
    val namePrefix: String? = null,
    /** Include Windows-known / Android-bonded BLE devices even without an advertisement. */
    val includeKnownDevices: Boolean = false,
)

/**
 * Состояние текущего сканирования. nameResolutionError относится только к дополнительному источнику
 * системных имён и не подменяет ошибку основного advertising-сканера.
 */
data class ScanState(
    val scanning: Boolean = false,
    val error: BleException? = null,
    /** Supplemental system-name lookup failed; advertisement scanning can still continue. */
    val nameResolutionError: BleException? = null,
)
