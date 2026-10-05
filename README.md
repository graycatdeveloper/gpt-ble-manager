# gpt-ble-manager

Kotlin Multiplatform BLE-клиент для **Windows x64 (JVM + C++/WinRT)** и
**Android 8.0+ (API 26+)**. Общий API находится в пакете `gpt.ble.manager`.

**Проект создаётся и развивается с помощью ChatGPT.** ChatGPT используется при
написании кода, рефакторинге, подготовке тестов и документации. Решения о принятии
изменений и выпуске версий остаются за сопровождающими проекта.

Лицензия основного кода — **[MIT](LICENSE)**. Проект находится в активной разработке;
текущая версия для локальной сборки — `0.2.3-local`.

## Возможности

- Сканирование BLE с фильтрами по имени и UUID сервиса.
- Объединение рекламного, системного и GATT-имени устройства.
- Подключение, обнаружение сервисов, характеристик и дескрипторов.
- Чтение и запись с ответом или без ответа.
- Notifications, indications и управление CCCD.
- Состояние сопряжения, pair/unpair с учётом возможностей ОС.
- Состояния через `StateFlow`, уведомления через `SharedFlow`.
- Последовательное выполнение GATT-запросов, таймауты и освобождение ресурсов.

| Платформа | Реализация | Особенности |
| --- | --- | --- |
| Windows x64 | Kotlin/JVM, JNI, C++20/WinRT | DLL входит в JAR; MTU согласует Windows |
| Android API 26+ | Android Bluetooth API | Runtime-разрешения запрашивает приложение |

Режим периферии/GATT-server, автоматическое переподключение, iOS, Linux и macOS
в текущий API не входят.

## Сборка

Версии инструментов и зависимостей заданы в
[`gradle/libs.versions.toml`](gradle/libs.versions.toml): Kotlin 2.4.20,
Android Gradle Plugin 9.4.1, JDK 21, compile SDK 37. Версия Gradle задана в
[`gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties).

Для полной сборки Windows и Android нужны:

1. Windows x64 и JDK 21.
2. Android SDK с платформой 37. Путь задаётся через `ANDROID_HOME` или `sdk.dir`
   в локальном `local.properties`.
3. Visual Studio 2022 с инструментами C++, Windows SDK с C++/WinRT и CMake 3.20+.

Из корня репозитория в PowerShell:

```powershell
.\gradlew.bat :gpt-ble-manager:allTests :gpt-ble-manager:assemble
.\gradlew.bat :gpt-ble-manager:publishToMavenLocal
```

Gradle ищет CMake в `PATH`, стандартной установке и Visual Studio. Путь можно переопределить:

```powershell
.\gradlew.bat :gpt-ble-manager:buildWindowsNative -PcmakeExecutable="C:/tools/cmake/bin/cmake.exe"
```

Android собирается отдельно на поддерживаемой AGP системе:
`bash ./gradlew :gpt-ble-manager:assembleAndroidMain :gpt-ble-manager:testAndroidHostTest`.
Для Windows-артефакта нужна сборка DLL на Windows.

## Подключение

После `publishToMavenLocal` добавьте локальный репозиторий в настройки приложения:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal {
            content { includeGroup("gpt.ble.manager") }
        }
        google()
        mavenCentral()
    }
}
```

Kotlin Multiplatform:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("gpt.ble.manager:gpt-ble-manager:0.2.3-local")
        }
    }
}
```

Обычное JVM-приложение на Windows:

```kotlin
dependencies {
    implementation("gpt.ble.manager:gpt-ble-manager-windows:0.2.3-local")
}
```

Эти команды используют локальную публикацию. Наличие версии в Maven Central или
GitHub Packages не предполагается. После повторной публикации той же версии
обновите зависимости потребителя через `--refresh-dependencies`.

Задача `:gpt-ble-manager:publish` также публикует в `build/repository` этого репозитория.
Другую папку можно задать через `-PlocalRepositoryPath=<путь>`.

## Создание менеджера

В Windows source set:

```kotlin
import gpt.ble.manager.BleManager
import gpt.ble.manager.windows.WindowsBleManager

val manager: BleManager = WindowsBleManager()
```

В Android source set:

```kotlin
import gpt.ble.manager.android.AndroidBleManager

val manager = AndroidBleManager(context.applicationContext)
val permissions = manager.requiredPermissions()
// Activity запрашивает отсутствующие разрешения до startScan/connect.
```

На Android 12+ нужны `BLUETOOTH_SCAN` и `BLUETOOTH_CONNECT`, на Android 8–11 —
`ACCESS_FINE_LOCATION` и включённая геолокация. Библиотека не запрашивает runtime-разрешения
самостоятельно. Системный интерфейс сопряжения может показывать сама ОС.

Manifest использует `neverForLocation`. Приложению, определяющему местоположение по BLE,
нужно настроить собственный merged manifest. Этот флаг также может ограничивать
обнаружение некоторых маяков.
[Справка Android](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

## Поиск и подключение

Пример для `commonMain`; передайте префикс имени своего устройства:

```kotlin
import gpt.ble.manager.AdapterState
import gpt.ble.manager.BleConnection
import gpt.ble.manager.BleManager
import gpt.ble.manager.ScanOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout

suspend fun connectToDevice(manager: BleManager, namePrefix: String): BleConnection {
    check(manager.refreshAdapterState() == AdapterState.Ready)
    manager.startScan(ScanOptions(namePrefix = namePrefix))
    val device = try {
        withTimeout(30_000) {
            manager.devices.mapNotNull { devices -> devices.firstOrNull() }.first()
        }
    } finally {
        manager.stopScan()
    }
    return manager.connect(device, timeoutMillis = 20_000)
}
```

Использование уже созданного менеджера из suspend-функции:

```kotlin
try {
    val connection = connectToDevice(manager, "ExampleSensor")
    try {
        println(connection.readDeviceName())
        connection.discoverServices().forEach { service ->
            println("${service.uuid}: ${service.characteristics.size} characteristics")
        }
    } finally {
        connection.close()
    }
} finally {
    manager.close()
}
```

После `manager.close()` создайте новый менеджер. Удалённое отключение отражается в
`connection.state` и `connection.disconnectReason`.

### Имена устройств

Имя может прийти отдельным scan response после первого безымянного пакета.
`namePrefix` применяется после объединения данных и учитывает регистр.
Приоритет источников: **Advertisement → GATT → System**.

`device.name` остаётся `null`, пока имя неизвестно; `displayName` подставляет адрес
только для отображения. Адрес, возвращённый ОС вместо имени, отбрасывается.

С `ScanOptions(includeKnownDevices = true)` в результат также попадают известные
Windows устройства или сопряжённые Android LE/dual-mode устройства. Такая запись
может быть недоступна: проверяйте `seenInCurrentScan` и nullable `rssi`.
GATT-имя читается через `readDeviceName()` после подключения; поиск не подключается
автоматически к каждому безымянному устройству.

### GATT и уведомления

Получайте характеристики из `connection.discoverServices()`. UUID может повторяться:
для идентификации используются `id`, `serviceId` и `connectionId`.
Объекты предыдущего соединения повторно использовать нельзя.

```kotlin
val services = connection.discoverServices()
val readable = services.flatMap { it.characteristics }.first { it.canRead }
val bytes = connection.read(readable).toByteArray()
```

Перед `subscribe(characteristic)` запустите collector `connection.notifications`.
Выбирайте характеристику с `canNotify` или `canIndicate`; отключайте подписку через
`SubscriptionMode.Disabled`. Для CCCD `2902` используйте `subscribe`.
Пример с порядком listener/CCCD есть в
[`GattExample.kt`](sample-windows/src/main/kotlin/gpt/ble/manager/sample/GattExample.kt).

Запись принимает не более `MTU - 3` байт. Разбиение команды определяется протоколом
устройства. Windows возвращает фактически согласованный MTU;
`requestMtu` на Android запрашивает изменение у ОС.

### Сопряжение и ошибки

```kotlin
val state = manager.getPairingState(device)
val paired = manager.pair(device)
val unpaired = manager.unpair(device)
```

Перед pair/unpair закройте соединение этого менеджера с устройством. Windows поддерживает
`ConfirmOnly`; PIN/passkey-сценарии выполняйте через системные настройки. Android unpair
требует API 36+ и ассоциацию `CompanionDeviceManager`, принадлежащую приложению;
иначе возвращается `BleError.Unsupported`. Скрытые Android API не используются.

`Paired` не гарантирует доступ к каждому GATT-сервису. `BleException.code` содержит
переносимую категорию ошибки, а `cause` — платформенную причину. Таймаут или отмена
GATT-запроса завершают сессию, чтобы поздний ответ не попал в следующий запрос.
Отмена pair/unpair не гарантирует отмену уже выполняющегося действия ОС.

## Консольный пример Windows

```powershell
.\gradlew.bat :sample-windows:run
.\gradlew.bat :sample-windows:run --args="ExampleSensor --known --name-only"
.\gradlew.bat :sample-windows:run --args="AA:BB:CC:DD:EE:01 --direct --inspect"
```

`--known` включает системные записи, `--prefix=<имя>` фильтрует сканирование,
`--inspect` читает каталог и стандартные атрибуты. `--notify=<uuid>` проверяет уведомления
пять секунд. `--pairing-state` только читает состояние; `--pair` и `--unpair` меняют его.
Имя и адрес в примерах условные — замените их значениями своего устройства.

## Разработка и поддержка

- [Архитектура Kotlin](docs/KOTLIN_ARCHITECTURE.md).
- [C++/WinRT, JNI и настройка CLion](gpt-ble-manager/src/windowsMain/cpp/README.md).
- [Правила внесения изменений](CONTRIBUTING.md).
- [Проверки и их ограничения](TEST_REPORT.md).

В issue укажите ОС, версию библиотеки, шаги воспроизведения, `BleException.code` и stack trace.
Перед публикацией логов удаляйте адреса устройств и другие личные сведения.

## Лицензия

Основной код и документация распространяются под [MIT License](LICENSE).
Gradle Wrapper сохраняет собственную Apache-2.0 лицензию; см. [NOTICE](NOTICE) и
[текст лицензии Wrapper](licenses/Apache-2.0.txt). Зависимости сохраняют свои лицензии.
