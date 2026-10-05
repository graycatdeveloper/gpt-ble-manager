# gpt-ble-manager

[![Maven Central: publication pending](https://img.shields.io/badge/Maven_Central-publication_pending-lightgrey?logo=apachemaven)](docs/PUBLISHING.md)

A Kotlin Multiplatform BLE client for **Windows x64 (JVM + C++/WinRT)** and
**Android 8.0+ (API 26+)**. The shared API is in the `gpt.ble.manager` package.

**This project is created and developed with the help of ChatGPT.** ChatGPT assists
with implementation, refactoring, tests, and documentation. Project maintainers
remain responsible for accepting changes and releasing versions.

The main code is licensed under **[MIT](LICENSE)**. The project is actively developed;
the current local build version is `0.2.3-local`.

## Features

- BLE scanning with name and service UUID filters.
- Merging advertised, system, and GATT device names.
- Connections and discovery of services, characteristics, and descriptors.
- Reads and writes, with or without a response.
- Notifications, indications, and CCCD management.
- Pairing state, pair/unpair subject to OS capabilities.
- State through `StateFlow`, notifications through `SharedFlow`.
- Serialized GATT requests, timeouts, and resource cleanup.

| Platform | Implementation | Notes |
| --- | --- | --- |
| Windows x64 | Kotlin/JVM, JNI, C++20/WinRT | DLL bundled in the JAR; Windows negotiates MTU |
| Android API 26+ | Android Bluetooth API | The application requests runtime permissions |

Peripheral/GATT server mode, automatic reconnection, iOS, Linux, and macOS are
outside the current API's scope.

## Building

Tool and dependency versions are defined in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml): Kotlin 2.4.20,
Android Gradle Plugin 9.4.1, JDK 21, and compile SDK 37. The Gradle version is set in
[`gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties).

A complete Windows and Android build requires:

1. Windows x64 and JDK 21.
2. Android SDK platform 37. Set its location through `ANDROID_HOME` or `sdk.dir`
   in your local `local.properties`.
3. Visual Studio 2022 with C++ tools, a Windows SDK containing C++/WinRT, and CMake 3.20+.

From the repository root in PowerShell:

```powershell
.\gradlew.bat :gpt-ble-manager:allTests :gpt-ble-manager:assemble
.\gradlew.bat :gpt-ble-manager:publishToMavenLocal
```

Gradle searches for CMake in `PATH`, its standard installation, and Visual Studio.
To override its location:

```powershell
.\gradlew.bat :gpt-ble-manager:buildWindowsNative -PcmakeExecutable="C:/tools/cmake/bin/cmake.exe"
```

Android can be built separately on a system supported by AGP:
`bash ./gradlew :gpt-ble-manager:assembleAndroidMain :gpt-ble-manager:testAndroidHostTest`.
The Windows artifact requires its DLL to be built on Windows.

## Adding the dependency

After `publishToMavenLocal`, add the local repository to your application's settings:

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

A regular JVM application on Windows:

```kotlin
dependencies {
    implementation("gpt.ble.manager:gpt-ble-manager-windows:0.2.3-local")
}
```

These instructions use a local publication. They do not imply that this version is
available on Maven Central or GitHub Packages. After republishing the same version,
refresh the consumer's dependencies with `--refresh-dependencies`.

The `:gpt-ble-manager:publish` task also publishes to this repository's `build/repository`.
Override the directory with `-PlocalRepositoryPath=<path>`.

For public releases, see [Publishing to Maven Central](docs/PUBLISHING.md).
The header badge remains marked as pending until the first release is available.

## Creating a manager

In the Windows source set:

```kotlin
import gpt.ble.manager.BleManager
import gpt.ble.manager.windows.WindowsBleManager

val manager: BleManager = WindowsBleManager()
```

In the Android source set:

```kotlin
import gpt.ble.manager.android.AndroidBleManager

val manager = AndroidBleManager(context.applicationContext)
val permissions = manager.requiredPermissions()
// The Activity requests missing permissions before startScan/connect.
```

Android 12+ requires `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; Android 8–11 requires
`ACCESS_FINE_LOCATION` and location services to be enabled. The library does not
request runtime permissions itself. The OS may display its own pairing UI.

The manifest uses `neverForLocation`. Applications that derive location from BLE
must configure their merged manifest accordingly. This flag may also restrict
discovery of some beacons.
See [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

## Scanning and connecting

Example for `commonMain`; pass your device's name prefix:

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

Use an existing manager from a suspend function:

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

Create a new manager after `manager.close()`. Remote disconnection is reflected in
`connection.state` and `connection.disconnectReason`.

### Device names

A name may arrive in a separate scan response after the first unnamed packet.
`namePrefix` is case-sensitive and is applied after merging the data.
Source priority: **Advertisement → GATT → System**.

`device.name` remains `null` until a name is known; only `displayName` falls back to
the address for display. An address returned by the OS as a name is discarded.

With `ScanOptions(includeKnownDevices = true)`, results also include Windows-known
devices or bonded Android LE/dual-mode devices. Such a record may be unreachable:
check `seenInCurrentScan` and nullable `rssi`. Read the GATT name using
`readDeviceName()` after connecting; scanning does not automatically connect to
every unnamed device.

### GATT and notifications

Obtain characteristics from `connection.discoverServices()`. UUIDs may repeat:
`id`, `serviceId`, and `connectionId` establish identity.
Objects from a previous connection cannot be reused.

```kotlin
val services = connection.discoverServices()
val readable = services.flatMap { it.characteristics }.first { it.canRead }
val bytes = connection.read(readable).toByteArray()
```

Start collecting `connection.notifications` before calling `subscribe(characteristic)`.
Select a characteristic with `canNotify` or `canIndicate`; disable the subscription
with `SubscriptionMode.Disabled`. Use `subscribe` for CCCD `2902`.
See [`GattExample.kt`](sample-windows/src/main/kotlin/gpt/ble/manager/sample/GattExample.kt)
for listener/CCCD ordering.

A write accepts at most `MTU - 3` bytes. Command fragmentation depends on the device
protocol. Windows returns the actually negotiated MTU; on Android, `requestMtu`
asks the OS to change it.

### Pairing and errors

```kotlin
val state = manager.getPairingState(device)
val paired = manager.pair(device)
val unpaired = manager.unpair(device)
```

Close this manager's connection to the device before pair/unpair. Windows supports
`ConfirmOnly`; use system settings for PIN/passkey scenarios. Android unpair requires
API 36+ and a `CompanionDeviceManager` association owned by the application;
otherwise it returns `BleError.Unsupported`. No hidden Android APIs are used.

`Paired` does not guarantee access to every GATT service. `BleException.code` holds
a portable error category, while `cause` retains the platform cause. A GATT timeout
or cancellation terminates the session to prevent a late response from completing
the next request. Cancelling pair/unpair does not guarantee cancellation of an
OS operation already in progress.

## Windows console example

```powershell
.\gradlew.bat :sample-windows:run
.\gradlew.bat :sample-windows:run --args="ExampleSensor --known --name-only"
.\gradlew.bat :sample-windows:run --args="AA:BB:CC:DD:EE:01 --direct --inspect"
```

`--known` includes system records, `--prefix=<name>` filters the scan, and `--inspect`
reads the catalog and standard attributes. `--notify=<uuid>` checks notifications
for five seconds. `--pairing-state` only reads the state; `--pair` and `--unpair`
change it. The example name and address are placeholders; replace them with your
device's values.

## Development and support

- [Kotlin architecture](docs/KOTLIN_ARCHITECTURE.md).
- [C++/WinRT, JNI, and CLion setup](gpt-ble-manager/src/windowsMain/cpp/README.md).
- [Contributing guidelines](CONTRIBUTING.md).
- [Validation and its limitations](TEST_REPORT.md).
- [Publishing to Maven Central](docs/PUBLISHING.md).

Include the OS, library version, reproduction steps, `BleException.code`, and stack
trace when reporting an issue. Remove device addresses and other personal data
before publishing logs.

## License

The main source code and documentation are distributed under the [MIT License](LICENSE).
Gradle Wrapper retains its Apache-2.0 license; see [NOTICE](NOTICE) and the
[Wrapper license text](licenses/Apache-2.0.txt). Dependencies retain their own licenses.
