package gpt.ble.manager

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Одна GATT-сессия. Все запросы сериализованы: параллельные вызовы ждут своей очереди. Таймаут или
 * отмена выполняющегося запроса завершают сессию, чтобы запоздавший callback не был принят как
 * результат следующего запроса. Уведомления идут независимо через SharedFlow.
 */
interface BleConnection {
    val id: String
    val device: BleDevice
    /** Latest device snapshot, including a name learned from GATT. */
    val deviceDetails: StateFlow<BleDevice>
    val state: StateFlow<ConnectionState>
    val disconnectReason: StateFlow<BleException?>
    val services: StateFlow<List<GattService>>
    /** ATT MTU, updated from platform negotiation. Windows negotiates automatically. */
    val mtu: StateFlow<Int>
    /**
     * Subscribe before enabling CCCD. Overflow terminates the connection with an explicit error.
     */
    val notifications: SharedFlow<CharacteristicValue>

    /**
     * Возвращает стабильный каталог этой сессии. Повторный успешный вызов использует его снимок.
     * Изменение уже опубликованной GATT-базы завершает сессию: нужны новое подключение и discovery.
     */
    suspend fun discoverServices(): List<GattService>

    /**
     * Reads Generic Access / Device Name (1800/2a00); null if missing or blank. Errors propagate.
     */
    suspend fun readDeviceName(): String?

    /**
     * Читает характеристику из текущего каталога; проверяет свойство Read и принадлежность сессии.
     */
    suspend fun read(characteristic: GattCharacteristic): BleBytes

    /**
     * Копирует байты до постановки в очередь. Проверяет режим записи и размер не более MTU − 3;
     * разбиение команд на пакеты определяется протоколом приложения.
     */
    suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        mode: WriteMode = WriteMode.WithResponse,
    )

    /** Читает дескриптор текущей сессии. Повторяющиеся UUID различаются числовыми ID. */
    suspend fun readDescriptor(descriptor: GattDescriptor): BleBytes

    /** Записывает копию данных в дескриптор. Для CCCD используйте subscribe, а не прямую запись. */
    suspend fun writeDescriptor(descriptor: GattDescriptor, value: ByteArray)

    /**
     * Настраивает локальный обработчик и удалённый CCCD. Запустите collector notifications до
     * вызова. Ошибка настройки закрывает сессию, так как состояние подписки становится
     * недостоверным.
     */
    suspend fun subscribe(
        characteristic: GattCharacteristic,
        mode: SubscriptionMode = SubscriptionMode.Notify,
    )

    /**
     * Принимает 23..517. Android отправляет запрос; Windows возвращает согласованный ОС MTU.
     * Возвращаемое значение может отличаться от запрошенного.
     */
    suspend fun requestMtu(size: Int): Int

    /** Идемпотентно завершает сессию, ожидающие операции и освобождает платформенные ресурсы. */
    fun close()
}

enum class ConnectionState {
    Connecting,
    Connected,
    Disconnected,
}
