# Нативная Windows-часть GPT BLE Manager

Это C++20 DLL для Windows x64, которую загружает Kotlin/JVM через JNI.
Публичный интерфейс по-прежнему задаёт `../kotlin/dev/gpt/ble/windows/NativeBridge.kt`.
Рефакторинг меняет организацию исходников, но сохраняет операции, их порядок,
JNI-сигнатуры, callback-протокол, статусы, сообщения ошибок и таймауты.

## Карта файлов

```text
cpp/
├── CMakeLists.txt                  Состав DLL, Windows SDK/JNI, optional CTest
├── .clang-format                  Единый вертикальный стиль C++
├── src/
│   ├── jni/
│   │   ├── NativeBridge.cpp        Все 17 экспортов Java_dev_gpt_ble_windows_...
│   │   └── JniRuntime.*            JNIEnv, UTF-16, Java-массивы, исключения
│   ├── runtime/
│   │   └── WinrtRuntime.*          COM apartment, deadline, await, GattFailure
│   ├── state/
│   │   ├── Registry.*              Числовые handles и владение менеджерами
│   │   ├── Manager.*               Сканеры, radio, callback и карта соединений
│   │   └── Connection.*            GATT-ресурсы, ATT handles, event tokens
│   ├── adapter/
│   │   └── Adapter.*               Состояние BLE-адаптера и события питания
│   ├── bluetooth/
│   │   └── BluetoothUtils.*        UUID, адреса и WinRT-буферы
│   ├── scan/
│   │   ├── Scanner.*              Advertising watcher и упаковка callback
│   │   ├── AdvertisementParser.*  UUID из ServiceUuids и Service Data
│   │   └── KnownDevices.*         Системные имена из AssociationEndpoint
│   ├── pairing/
│   │   └── Pairing.*              Состояние сопряжения, pair, unpair
│   └── gatt/
│       ├── ConnectionOperations.* Подключение, monitoring, отключение, MTU
│       ├── Discovery.*            Каталог GATT и отдельное чтение Device Name
│       ├── AttributeOperations.*  Чтение/запись характеристик и дескрипторов
│       └── Subscriptions.*        ValueChanged и CCCD
└── tests/
    └── NativeContractsTest.cpp    Форматы данных без Bluetooth-устройства
```

Заголовки `.hpp` содержат контракты функций и правила владения; `.cpp` — реализацию
и комментарии к существенным шагам. Все объявления находятся в `gpt::ble::windows`.
`using namespace` используется только внутри `.cpp`, а приватные вспомогательные
объекты скрыты в anonymous namespace или имеют внутреннее связывание.
Заголовки не устанавливаются как публичный C++ SDK: JNI — единственная внешняя граница.

## Как проходит вызов

1. Kotlin валидирует аргументы и выполняет блокирующий Windows-вызов на `Dispatchers.IO`.
2. `NativeBridge.cpp` подготавливает apartment текущего потока и вызывает нужный модуль.
3. `Registry` возвращает `shared_ptr`: объект остаётся живым после освобождения mutex реестра.
4. Модуль берёт необходимые WinRT-ссылки под mutex и выполняет запрос с ограниченным ожиданием.
5. Результат передаётся через JNI; C++-исключение переводится в Java-исключение.

Обратное направление: WinRT-событие → weak reference → `Manager::call` →
JNI callback → Kotlin. Сборка не создаёт собственного цикла событий, фонового
потока переподключения или отдельного BLE-адаптера.

## Владение и потоки

`Registry` владеет менеджерами через `shared_ptr`; `Manager` владеет соединениями.
Ссылка `Connection → Manager` и ссылки из event handlers слабые. Это предотвращает
цикл `владелец → WinRT event → владелец`. Callback получает временный strong reference
через `weak_ptr::lock`, а затем проверяет `closed`.

`close()` помечает объект закрытым. Уже начатый callback может ещё завершаться;
объект остаётся живым до освобождения его последней strong reference. Kotlin дополнительно
отбрасывает события сканирования с устаревшим `generation`.

Коллекции ресурсов и event tokens отсоединяются под mutex. Отписка от событий и
закрытие ресурсов выполняются после выхода из критической секции. Порядок обработки
ошибок при best-effort teardown сохранён; этот рефакторинг не добавляет новых гарантий
для ошибок драйвера в процессе закрытия.

`JavaVM*` допустимо хранить в менеджере, `JNIEnv*` относится к конкретному потоку.
`JavaEnv` присоединяет native callback-поток как daemon и отсоединяет только поток,
который присоединил сам. Java callback удерживается global reference; временные
JNI-ссылки callbacks очищаются через local frame. Ошибка callback логируется и
очищается, поскольку у асинхронного события нет ожидающего Java-вызова.

Обоснование: [JNI threads](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/invocation.html#attaching-to-the-vm),
[JNI local/global references](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#global-and-local-references),
[C++/WinRT lifetime](https://learn.microsoft.com/en-us/windows/apps/develop/cpp-winrt/weak-references),
[C++ Core Guidelines: resource management](https://isocpp.github.io/CppCoreGuidelines/CppCoreGuidelines#S-resource).

## Неизменяемые контракты

- Реестр выдаёт монотонные `jlong` handles. Они не являются адресами C++-объектов.
- Строки передаются через UTF-16. MAC имеет вид `A0:6C:65:41:61:F6`; UUID — без скобок.
- Тип адреса: `0 = Unknown`, `1 = Public`, `2 = Random`. Unknown использует WinRT overload без типа.
- Сканирование активно: scan response может принести имя после первого рекламного пакета.
- UUID читаются из ServiceUuids и Service Data AD `0x16`, `0x20`, `0x21`.
  Укороченные секции пропускаются; порядок и повторы сохраняются до обработки в Kotlin.
- Manufacturer data callback содержит сначала двухбайтовый CompanyId в little-endian, затем payload.
- Источник системного имени не создаёт RSSI и не считается рекламным пакетом.
- Запросы GATT используют `Uncached`. Составная операция использует один общий deadline;
  обычный бюджет — 12 секунд, connect/pair/unpair получают таймаут от Kotlin.
- `await` запрашивает Cancel при timeout. Отмена WinRT не обещает откат уже выполненной операции ОС.
- Каталог передаётся строками `S|service|uuid`, `C|service|char|uuid|properties`,
  `D|char|desc|uuid`. Ключи — реальные ATT AttributeHandle, а не UUID.
- Каталог публикуется после полного успеха discovery. Начальный `GattServicesChanged`
  не завершает соединение, пока `catalogReady` ещё не установлен.
- Device Name читается отдельно из `1800/2a00`, без discovery остальных сервисов.
- ValueChanged регистрируется до записи CCCD. Ошибка CCCD откатывает новую подписку;
  повторное включение существующей не создаёт второй handler.
- Для CCCD `0 = Disabled`, `1 = Notify`, остальные значения дают Indicate, как прежде.
- Windows согласует MTU. Native-слой сообщает `GattSession.MaxPduSize`.
- `GattFailure` сохраняет числовой статус. Java получает `WindowsGattException`;
  другие native-ошибки остаются `IllegalStateException`. Префикс `TIMEOUT:` сохранён.
- JNI catch возвращает прежний fallback (`0`, `-1`, `3` для адаптера, `nullptr`, `JNI_FALSE`, `23` для MTU),
  но при этом устанавливает Java exception. Fallback нельзя трактовать как успешный ответ.
- Pair использует `Custom.PairAsync(ConfirmOnly, Default)`. Ввод или сравнение PIN
  автоматически не подтверждаются. Unpair использует исходный `UnpairAsync`.

Ссылки на протоколы/API: [GATT client](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client),
[GATT status](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcommunicationstatus),
[MaintainConnection](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection),
[Bluetooth Assigned Numbers](https://www.bluetooth.com/specifications/assigned-numbers/),
[DeviceWatcher](https://learn.microsoft.com/en-us/uwp/api/windows.devices.enumeration.devicewatcher),
[Pairing](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/pair-devices).

## Сборка и CLion

Нужны Visual Studio 2022 C++ toolchain x64, Windows SDK с `cppwinrt`, CMake 3.20+ и JDK.
В CLion откройте эту папку; в Toolchains выберите Visual Studio, архитектуру amd64,
затем назначьте toolchain активному CMake-профилю. Ninja допустим; bundled MinGW
не является toolchain этой реализации. После смены компилятора сбросьте CMake cache.
[Документация CLion](https://www.jetbrains.com/help/clion/how-to-create-toolchain-in-clion.html).

Из корня KMP-проекта:

```powershell
.\gradlew.bat :ble-manager:buildWindowsNative
.\gradlew.bat :ble-manager:allTests :ble-manager:assemble
```

Gradle отслеживает `src/**/*.cpp`, `src/**/*.hpp`, `tests/**/*.cpp` и CMakeLists.txt.
Каталоги CLion и сгенерированные сборочные файлы во входы native-задачи не входят.
CMake содержит явный список исходников, чтобы случайный файл из build-каталога
не попал в DLL. После добавления нового `.cpp` обновляйте этот список.

Для самостоятельной сборки и native-тестов из этой папки, указав установленный JDK:

```powershell
cmake -S . -B cmake-build-contracts -G "Visual Studio 17 2022" -A x64 `
    -DJAVA_HOME="<путь к JDK>" -DGPT_BLE_BUILD_NATIVE_TESTS=ON
cmake --build cmake-build-contracts --config Release --target gpt-ble-native-tests
ctest --test-dir cmake-build-contracts -C Release --output-on-failure
```

Native-тесты проверяют byte order, короткие AD-секции, дубликаты UUID, адреса,
буферы и контракт GATT-ошибки. Они не включают radio и не меняют сопряжения.
Существующие Kotlin-тесты дополнительно проверяют JNI lifecycle и публичные контракты.
Реальные read/write/notification-сценарии требуют доступной периферии.

## Стиль и дальнейшие изменения

`.clang-format` рассчитан на clang-format 19: отступ 4 пробела, Allman braces,
блоки `if`/циклов всегда развёрнуты, короткие функции и лямбды не сжимаются в строку.
Исключение из ограничения ширины — длинные ссылки в комментариях.

Не переносите `using namespace` в заголовки, не удерживайте mutex во время `await`,
не захватывайте владельца сильной ссылкой в долгоживущий event handler. При изменении
JNI одновременно сверяйте `NativeBridge.kt`, экспорт DLL и дескрипторы `GetMethodID`.
Поведенческие исправления оформляйте отдельно от структурного переноса: это позволяет
проверить причину изменения на устройстве.
