# Kotlin-архитектура

Публичный пакет — `gpt.ble.manager`. Общий модуль содержит модели, интерфейсы и
инварианты сессии. Платформенные менеджеры реализуют доступ к ОС. Формат кода задаёт
`.editorconfig`: отступ четыре пробела, развёрнутые блоки управления, явные imports.
Ключевые контракты и причины решений описаны также в KDoc исходников.

## Карта исходников

Пути ниже относительны `gpt-ble-manager/src`:

| Source set / папка | Назначение |
| --- | --- |
| `commonMain/kotlin/gpt/ble/manager` | BleManager, BleConnection, UUID, байты, устройства, GATT-модели, ошибки и состояния |
| `commonMain/.../internal/scan` | ScanStore, ScanEntry, рекламный пакет |
| `commonMain/.../internal/names` | Проверка системного имени и декодирование GATT Device Name |
| `commonMain/.../internal/gatt` | ManagedConnection и OperationQueue |
| `commonMain/.../internal/pairing` | Ожидание конечного состояния сопряжения |
| `windowsMain/.../windows` | Публичный менеджер, NativeBridge, WindowsGattException |
| `windowsMain/.../windows/scan` | Поколения сканирования и объединение native callbacks |
| `windowsMain/.../windows/pairing` | Резервирование адреса, pair/unpair, перевод статусов WinRT |
| `windowsMain/.../windows/gatt` | Операции соединения и декодирование каталога JNI |
| `windowsMain/.../windows/jni` | Загрузка DLL из ресурсов JAR |
| `androidMain/.../android` | Публичный AndroidBleManager |
| `androidMain/.../android/adapter` | Доступность адаптера и runtime-разрешения |
| `androidMain/.../android/scan` | ScanCallback, обработка ScanRecord и AD-секций |
| `androidMain/.../android/pairing` | Системные broadcast-события и управление сопряжением |
| `androidMain/.../android/gatt` | Соединение, BluetoothGattCallback, каталог, ожидающий запрос |

`sample-windows` содержит отдельные сценарии сканирования, GATT и сопряжения.
В `buildSrc/.../buildlogic` находятся поиск CMake и задача сборки DLL.
Все внешние координаты и версии прикладных зависимостей/плагинов находятся в
`gradle/libs.versions.toml`; версии Wrapper и встроенных Gradle-плагинов задаёт Gradle.
Settings-плагин toolchain resolver читает свою версию из этого же TOML до появления
сгенерированных `libs` accessors.

## Владение ресурсами

Менеджер владеет сканером, контроллером сопряжения и картой соединений. Scanner и
pairing-controller используют тот же monitor, что connect/close. Поэтому выделение
классов не создаёт независимых блокировок на ранее общих ресурсах.

Соединение владеет каталогом и одной очередью операций. `ManagedConnection.terminate`
атомарно помечает сессию закрытой, публикует причину, прекращает очередь и вызывает
освобождение платформы. Повторный close не освобождает ресурсы второй раз.

## Операции и callbacks

`OperationQueue` сериализует запросы через coroutine Mutex. Он охватывает запуск и
ожидание результата ОС. Timeout или отмена уже начатой операции закрывают соединение:
иначе запоздавший ответ мог бы завершить следующий запрос.

На Android Pending регистрируется до вызова BluetoothGatt API. Callback сверяет
identity BluetoothGatt, вид операции и identity target. `Deferred.await` выполняется
вне monitor. На API 33+ используются overloads callbacks с отдельным `value`;
чтение изменяемого поля characteristic/descriptor сохраняется для старых API.
Каталог Android не блокирует себя самостоятельно: его защищает monitor соединения.

На Windows блокирующие JNI-запросы выполняются на `Dispatchers.IO`. В C++ действует
собственный deadline. После возвращения из connect проверяется отмена корутины;
непринятое соединение закрывается. Native-ошибки переводятся в BleException с сохранением
числового статуса и причины. MTU определяет сама Windows.

Коллектор уведомлений нужно запустить до включения CCCD. SharedFlow содержит буфер
128 значений; переполнение завершает сессию с `NotificationOverflow`.

## Имена, каталог и сопряжение

ScanStore объединяет записи по нормализованному адресу. Рекламное, GATT- и системное
имена хранятся отдельно, фильтры применяются после объединения. Отсутствующее имя
в следующем пакете не стирает ранее полученное. Только рекламный пакет ставит
`seenInCurrentScan=true`. Старые Windows callbacks отбрасываются по generation,
Android callbacks — по identity активного callback.

GATT-объекты различаются числовыми IDs: UUID может повторяться. Каталог стабилен до
закрытия соединения. Изменение опубликованной базы требует нового подключения.
На Windows отдельный запрос имени `1800/2a00` не требует discovery vendor-сервисов.

Сопряжение резервирует адрес против параллельного connect. На Android listener
регистрируется до чтения состояния и запуска операции. Принятие createBond не является
подтверждением сопряжения: ожидается конечный broadcast. Отмена ожидания не гарантирует
откат действия ОС. Windows поддерживает ConfirmOnly через custom pairing.

## JNI-контракт

При изменении границы Kotlin/C++ совместно проверяются:

- `gpt.ble.manager.windows.NativeBridge` и все `Java_gpt_ble_manager_windows_NativeBridge_*` exports;
- private callbacks NativeBridge и дескрипторы `GetMethodID` в `Registry.cpp`;
- `gpt/ble/manager/windows/WindowsGattException` в `JniRuntime.cpp`;
- числовой порядок AddressType/SubscriptionMode и строковый протокол GATT `S|...`, `C|...`, `D|...`.

Классы, привязанные по имени из JNI, нельзя независимо переносить или обфусцировать.
Описание владения native-ресурсами находится в
[README C++-модуля](../gpt-ble-manager/src/windowsMain/cpp/README.md).

## Документация API

- [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- [Coroutine cancellation and timeouts](https://kotlinlang.org/docs/cancellation-and-timeouts.html).
- [Mutex](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/).
- [Android BluetoothGattCallback](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback).
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- [Windows GATT client](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client).
- [JNI design](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html).
- [Gradle version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html).
