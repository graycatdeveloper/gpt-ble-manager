# GPT BLE Manager

Kotlin Multiplatform BLE-клиент для **Windows x64 (JVM/WinRT)** и **Android 8+ (API 26+)**.
Текущая локальная версия: `0.2.3-local`.

Реализованы сканирование, подключение, обнаружение GATT-сервисов/характеристик/дескрипторов,
чтение, запись с ответом и без ответа, уведомления/индикации, MTU и закрытие соединений.
Windows DLL упакована в JAR и загружается автоматически. На Android JNI не используется.

Устройство нативной Windows-части, правила владения ресурсами, документация WinRT/JNI,
сборка C++ и настройка CLion описаны в
[README нативного модуля](ble-manager/src/windowsMain/cpp/README.md).

## Подключение из локального Maven

В `settings.gradle.kts` приложения:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("dev.gpt.ble") } }
        google()
        mavenCentral()
    }
}
```

В KMP-модуле приложения:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.gpt.ble:gpt-ble-manager:0.2.3-local")
        }
    }
}
```

Для обычного Kotlin/JVM-проекта можно указать Windows-артефакт непосредственно:

```kotlin
dependencies {
    implementation("dev.gpt.ble:gpt-ble-manager-windows:0.2.3-local")
}
```

Используйте Kotlin 2.3+ и JDK 17+. После повторной публикации той же локальной версии
обновляйте зависимости приложения через `--refresh-dependencies`.
При переходе с 0.1.0 обновите зависимость и пересоберите приложение: модель устройства
и интерфейс соединения дополнены новыми свойствами.

## Создание менеджера

Windows, в `windowsMain`/`jvmMain`:

```kotlin
import dev.gpt.ble.BleManager
import dev.gpt.ble.windows.WindowsBleManager

val manager: BleManager = WindowsBleManager()
```

Android, в `androidMain`:

```kotlin
import dev.gpt.ble.android.AndroidBleManager

val manager = AndroidBleManager(context.applicationContext)
val permissions = manager.requiredPermissions()
// Activity должна запросить недостающие runtime-разрешения из этого списка.
// После выдачи разрешений: manager.refreshAdapterState(), затем manager.startScan().
```

На Android 12+ нужны `BLUETOOTH_SCAN` и `BLUETOOTH_CONNECT`; на Android 8–11 —
`ACCESS_FINE_LOCATION` и включённая геолокация. Библиотека не открывает системные диалоги.
Manifest библиотеки использует `neverForLocation`: если приложение определяет местоположение
по BLE, ему нужно скорректировать merged manifest и собственный запрос разрешений.
Этот флаг также может ограничивать обнаружение некоторых beacon-устройств.
См. [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

## Найти Mentaris и подключиться

Код для `commonMain`:

```kotlin
import dev.gpt.ble.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

suspend fun inspectMentaris(manager: BleManager) {
    check(manager.refreshAdapterState() == AdapterState.Ready)
    manager.startScan(ScanOptions(namePrefix = "MentarisBLE", includeKnownDevices = true))
    val device = try {
        withTimeout(30_000) {
            // Выбираем MentarisBLE по точному имени, без заранее известного MAC.
            manager.devices.map { list ->
                list.firstOrNull { it.name == "MentarisBLE" }
            }.filterNotNull().first()
        }
    } finally {
        manager.stopScan()
    }

    println("${device.name} / ${device.address}; source=${device.nameSource}; seen=${device.seenInCurrentScan}")
    val connection = manager.connect(device)
    try {
        println("GATT name: ${connection.readDeviceName()}")
        println("Resolved name: ${connection.device.name}; source=${connection.device.nameSource}")
    } finally {
        connection.close()
    }
}
```

Владелец менеджера обязан вызвать `manager.close()`, когда он больше не нужен.
На Android связывайте время жизни менеджера с владельцем BLE-сессии, а не с каждым
перерисовыванием Compose. Не создавайте несколько менеджеров для одного экрана.

Если строка с именем и адресом не появляется, пример ещё ждёт результат поиска:
`connect()` пока не вызван. `namePrefix` проверяется после объединения рекламных
и системных сведений по MAC-адресу.

По умолчанию `includeKnownDevices = false`: в результат попадают только адреса,
от которых пришёл пакет в текущем сканировании. Системное имя всё равно используется,
если рекламное отсутствует. С `includeKnownDevices = true` Windows также показывает
известные системе BLE-устройства, а Android — сопряжённые LE/dual-mode устройства.
Такая запись может быть недоступна: у неё `seenInCurrentScan = false` и `rssi = null`.
Только полученный рекламный пакет переключает `seenInCurrentScan` в `true`.

`nameSource` показывает источник выбранного имени: `Advertisement`, `System` или `Gatt`.
Приоритет: непустое рекламное имя → прочитанное в этой сессии GATT-имя → системное имя.
Полное рекламное имя имеет приоритет над сокращённым. Пустые значения и системное
имя, совпадающее с MAC-адресом, не заменяют настоящее имя.

`readDeviceName()` читает `1800/2A00` и обновляет `connection.device`, поток
`connection.deviceDetails` и соответствующую запись `manager.devices`, в том числе
после `stopScan()`. Обычное `read()` этой характеристики также обновляет имя.
Если рекламное имя уже есть, оно сохраняется в `device.name`, а метод возвращает
фактическое GATT-имя. На Windows чтение имени не требует открытия характеристик
всех остальных сервисов. Метод не скрывает ошибки доступа и не запускает подключения
к другим устройствам. Если характеристики нет или имя пустое/некорректное UTF-8,
результат — `null`. Ошибки дополнительного системного поиска видны в
`scanState.value.nameResolutionError`; рекламный сканер продолжает работать.

Для Mentaris, уже обнаруженного на этом Windows-компьютере, вместо блока сканирования
можно использовать известный публичный адрес:

```kotlin
val device = BleDevice(
    address = "A0:6C:65:41:61:F6",
    addressType = AddressType.Public,
)
val connection = manager.connect(device, timeoutMillis = 20_000)
```

Не задавайте `name = "Mentaris"` вручную: настоящее GATT-имя читается из `2a00` после
подключения. Этот адрес относится к проверенному устройству пользователя, а не ко всем Mentaris.
На новом компьютере может потребоваться первоначальное обнаружение устройства:
[Windows FromBluetoothAddressAsync](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothledevice.frombluetoothaddressasync).

Аппаратный пример запускайте через `main`/`runBlocking`. `runTest` использует виртуальное
время и по умолчанию ограничивает тело теста 60 секундами; он не заменяет ожидание реальных
Bluetooth-событий. В unit-тестах бесконечные сборщики Flow нужно отменять или запускать
в `backgroundScope`: [документация runTest](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/run-test.html).

## Сопряжение: статус, pair и unpair

С версии `0.2.2-local` доступны suspend-функции `getPairingState`, `pair` и `unpair`.
`Paired` означает сохранённое ОС сопряжение. Это не состояние подключения и не
гарантия доступа к каждому GATT-сервису. `connection.close()` не удаляет сопряжение.

```kotlin
val device = BleDevice("A0:6C:65:41:61:F6", addressType = AddressType.Public)
println(manager.getPairingState(device)) // Unknown / NotPaired / Pairing / Paired

// В обработчике кнопки «Сопряжение», до connect():
val result: PairResult = manager.pair(device, timeoutMillis = 60_000)
println(result) // Paired или AlreadyPaired

// В отдельном обработчике кнопки «Удалить сопряжение»:
// connection.close() — если соединение ранее открыто этим менеджером
val removed: UnpairResult = manager.unpair(device, timeoutMillis = 20_000)
println(removed) // Unpaired или AlreadyUnpaired
println(manager.getPairingState(device))
```

`pair`/`unpair` — отдельные пользовательские действия; не вызывайте их подряд при
каждом подключении. Открытое этим менеджером соединение нужно закрыть до изменения
сопряжения. Одновременное подключение или изменение pairing для того же адреса
отклоняется с `BleError.Rejected`. Ошибки ОС передаются как `BleException`;
`PermissionDenied`, `Rejected`, `Timeout`, `Unsupported` можно обрабатывать по `code`.
Windows может показать системное подтверждение при `pair`. Поддержан сценарий
`ConfirmOnly`; для PIN/passkey-сопряжения используйте настройки Windows. Библиотека
не подтверждает сравнение PIN и не вводит PIN автоматически. Android использует
стандартный системный интерфейс сопряжения, в том числе для PIN.
После таймаута или отмены сопряжение могло успеть измениться — повторно запросите статус.

| Платформа | Проверка | Pair | Unpair |
| --- | --- | --- | --- |
| Windows | `DeviceInformation.Pairing.IsPaired`, без GATT-подключения | WinRT `Custom.PairAsync(ConfirmOnly, Default)`, системное согласие | WinRT `UnpairAsync` |
| Android API 26+ | `BluetoothDevice.bondState` | `createBond`, ожидание завершения по событиям ОС | API 36+ и существующая `CompanionDeviceManager`-ассоциация этого приложения |

Если Windows не нашла устройство по адресу/типу, статус — `Unknown`; `pair`/`unpair`
вернут ошибку, а не фиктивный успех. Сначала выполните поиск устройства.
Windows проверка возвращает `Paired`/`NotPaired`; промежуточное `Pairing` доступно на Android.
На Android API 31+ нужен `BLUETOOTH_CONNECT`; для этих операций не требуются разрешение
сканирования или геолокация. При выключенном Bluetooth запрос возвращает `Unknown`.
Android `bondState` — общий статус устройства; на dual-mode периферии он не различает
Classic и LE. Отдельный transport-specific API 37 в эту сборку с compileSdk 36 не входит.

Для Android `unpair` библиотека находит существующую ассоциацию текущего приложения
по MAC и вызывает `CompanionDeviceManager.removeBond`, затем ждёт `BOND_NONE`.
Создание ассоциации с пользовательским выбором остаётся в приложении.
На старых Android или без ассоциации возвращается `BleError.Unsupported`.
Если устройство уже не сопряжено, возвращается `AlreadyUnpaired` без удаления.
В качестве доступного на всех поддерживаемых Android варианта откройте настройки:

```kotlin
context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
})
```

Импорты для этого фрагмента: `android.content.Intent`, `android.provider.Settings`.
После возвращения из настроек снова вызовите `getPairingState(device)`.

Команды Windows-примера (каждая выполняется отдельно):

```powershell
.\gradlew.bat :sample-windows:run --args="A0:6C:65:41:61:F6 --direct --pairing-state"
.\gradlew.bat :sample-windows:run --args="A0:6C:65:41:61:F6 --direct --pair"
.\gradlew.bat :sample-windows:run --args="A0:6C:65:41:61:F6 --direct --unpair"
```

В `0.2.2-local` использовался обычный `PairAsync`: он возвращал `Failed (19)`
для MentarisBLE в обычном Windows JVM-процессе. В `0.2.3-local` используется
`Custom.PairAsync` с обработчиком `PairingRequested`, принимающим только `ConfirmOnly`.
Уровень защиты остаётся `Default`. Обработчик снимается при любом завершении вызова.
Если устройство требует другой сценарий, ошибка не заменяется успехом.

Справка: [Windows pairing](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/pair-devices),
[Android bondState](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#getBondState()),
[Android removeBond](https://developer.android.com/reference/android/companion/CompanionDeviceManager#removeBond(int)).

## Обработка отказа в доступе к GATT

Начиная с `0.2.1-local`, Windows `GattCommunicationStatus.AccessDenied` передаётся
как `BleException` с `code == BleError.PermissionDenied`. JNI передаёт числовой статус:
разбирать текст `message` или вложенное `Caused by` не нужно. В `0.2.0-local` эта
ошибка ещё классифицировалась как `NativeFailure` — обновите зависимость.

```kotlin
val connection = manager.connect(device)
try {
    try {
        val services = connection.discoverServices()
        println("Сервисов: ${services.size}")
    } catch (e: BleException) {
        if (e.code == BleError.PermissionDenied) {
            println("Windows запретил доступ к GATT: ${e.message}")
        } else {
            throw e
        }
    }
} finally {
    connection.close()
}
```

Проверяется результат конкретного запроса: успешное подключение не гарантирует доступ
ко всем сервисам. Соединение с устройством может остаться рабочим после отказа в доступе
к одному сервису. Проверьте доступ после освобождения сервиса другим BLE-клиентом;
сам `AccessDenied` не указывает, какое приложение или ограничение вызвало отказ.
Библиотека не повторяет такой запрос бесконечно и не выдаёт неполный каталог за полный.
Протокольные ошибки WinRT (`ProtocolError`) передаются как `BleError.Protocol`.
См. [Microsoft GattCommunicationStatus](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcommunicationstatus).

## Уведомления Mentaris

У проверенного устройства сервис `ffb0`, характеристика уведомлений `ffb1`.
Подписчик Flow должен быть запущен **до** включения CCCD:

```kotlin
suspend fun receiveMentaris(connection: BleConnection) = coroutineScope {
    val characteristic = connection.discoverServices()
        .filter { it.uuid == BleUuid.parse("ffb0") }
        .flatMap { it.characteristics }
        .single { it.uuid == BleUuid.parse("ffb1") }

    val listener = launch(start = CoroutineStart.UNDISPATCHED) {
        connection.notifications.collect { event ->
            if (event.characteristic.id == characteristic.id) {
                println("Получено ${event.value.size} байт")
            }
        }
    }
    try {
        connection.subscribe(characteristic, SubscriptionMode.Notify)
        delay(5_000)
        connection.subscribe(characteristic, SubscriptionMode.Disabled)
    } finally {
        listener.cancelAndJoin()
        connection.close()
    }
}
```

Для записи: `connection.write(characteristic, bytes, WriteMode.WithResponse)`.
Выбирайте характеристику внутри нужного сервиса, проверяйте её свойства и используйте
известный протокол периферии. Максимальный размер одного write — `connection.mtu.value - 3`;
библиотека не разбивает команды на части автоматически.

## Сборка и запуск

Нужны JDK 17, Android SDK 36, Visual Studio 2022 с C++ и Windows SDK, CMake 3.20+.
Текущая сборка проверена с Gradle 9.7.1, Kotlin 2.3.0 и Android KMP Plugin 9.1.1.
В `local.properties` укажите `sdk.dir`; файл исключён из Git.
Сборка автоматически ищет CMake в PATH, стандартной установке CMake и Visual Studio
(через `vswhere`, в том числе при нестандартном пути установки). Это работает и при
запуске Gradle из IDE, без настройки PATH. Для явного выбора версии можно задать
`CMAKE_EXECUTABLE` либо Gradle-свойство `cmakeExecutable`; явная настройка имеет приоритет.

Устаревшие делегаты `registering`/`getting` в скриптах заменены на `register`/`named`.
Gradle 9.7.1 ещё сообщает об использовании `archives` внутри Kotlin-плагина и
`Project` как dependency notation внутри Android-плагина при настройке host-тестов.
Сборка и публикация проходят; совместимость этих версий плагинов с Gradle 10 не заявляется.
Источники предупреждений и результаты проверки описаны в `TEST_REPORT.md`.

```powershell
cd D:\Dev\Projects\gpt-ble-manager
$env:JAVA_HOME = 'C:\Users\kotlov.s\.jdks\liberica-17.0.17'

.\gradlew.bat :ble-manager:allTests :ble-manager:assemble
.\gradlew.bat :ble-manager:publishToMavenLocal
.\gradlew.bat :sample-windows:run --args="Mentaris --inspect"
```

Поиск по системному имени и отдельное чтение GATT Device Name:

```powershell
.\gradlew.bat :sample-windows:run --args="MentarisBLE --known --prefix=Mentaris --name-only"
```

Для полного каталога замените `--name-only` на `--inspect`. Если Windows возвращает
`AccessDenied` для сервиса, проверьте, не открыт ли он другим BLE-клиентом.

Проверка уведомлений и чтение CCCD:

```powershell
.\gradlew.bat :sample-windows:run --args="Mentaris --inspect --notify=ffb1"
```

Если Windows уже знает адрес, можно подключиться напрямую без повторного сканирования:

```powershell
.\gradlew.bat :sample-windows:run --args="A0:6C:65:41:61:F6 --direct --inspect --notify=ffb1"
```

`--direct` в этом диагностическом примере использует публичный адрес. Для случайного BLE-адреса
передавайте в `manager.connect()` `BleDevice`, полученный сканированием: он содержит тип адреса.

## Поведение и границы

- `BleDevice.name` — обнаруженное имя или `null`; `displayName` использует адрес при отсутствии имени.
  Рекламное имя дополняется системным и прочитанным GATT-именем. У пользователя два устройства:
  `CC:78:AB:83:36:02` передавал `Mentaris`, а у `A0:6C:65:41:61:F6` прочитано GATT-имя
  `MentarisBLE`. Эти наблюдения относятся к разным адресам.
- Advertising и scan response объединяются. Полное имя имеет приоритет над сокращённым;
  пустые пакеты не стирают имя. Фильтры учитывают любой из переданных UUID и service data.
- Результаты сканирования — снимки со сравнением по содержимому. Изменение имени вызывает
  новое событие StateFlow без подмены имени MAC-адресом.
- GATT-запросы сериализованы на каждом соединении. Таймаут или отмена активной операции
  закрывают соединение: поздний callback не сможет завершить следующий запрос.
- Windows-вызовы выполняются на `Dispatchers.IO`; ожидание WinRT ограничено временем.
  Отмена не прерывает нативный вызов мгновенно, но освобождение завершится в пределах его таймаута.
- У характеристик и дескрипторов есть идентификаторы экземпляров и соединения. Одинаковые
  UUID не объединяются; объекты из старой сессии не принимаются новой.
- Windows сам согласует MTU: `requestMtu()` возвращает фактическое значение. На Android
  отправляется запрос согласования MTU, результат приходит из callback.
- `notifications` — горячий поток без replay. При отсутствии подписчиков данные не сохраняются.
  Переполнение буфера активного медленного подписчика закрывает соединение с явной ошибкой.
- После изменения уже обнаруженной GATT-базы нужно переподключиться. Автоматического
  reconnect, собственного PIN-интерфейса, BLE advertising/GATT-server и выбора нескольких Windows-адаптеров нет.
- Артефакт Windows содержит x64 DLL. ARM64, x86 и другие ОС не заявлены.

## Проверки

Результаты аппаратной проверки Mentaris и автоматических тестов находятся в `TEST_REPORT.md`.
Android-реализация собрана и проверена host-тестами; тест на физическом Android-устройстве
остаётся необходимым перед выпуском приложения. Проверка одной периферии не означает
совместимость со всеми BLE-устройствами и драйверами.

Исходная архитектура изучалась на примере Blue Falcon. BLE-реализация здесь написана заново;
лицензия и происхождение скриптов Gradle указаны в `LICENSE` и `NOTICE`.
