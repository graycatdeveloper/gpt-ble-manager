# Kotlin architecture

The public package is `gpt.ble.manager`. The common module contains models,
interfaces, and session invariants. Platform managers implement OS access.
`.editorconfig` defines the style: four-space indentation, expanded control-flow
blocks, and explicit imports. Source KDoc also explains key contracts and design decisions.

## Source map

Paths below are relative to `gpt-ble-manager/src`:

| Source set / directory | Responsibility |
| --- | --- |
| `commonMain/kotlin/gpt/ble/manager` | BleManager, BleConnection, UUIDs, bytes, devices, GATT models, errors, and states |
| `commonMain/.../internal/scan` | ScanStore, ScanEntry, advertising packets |
| `commonMain/.../internal/names` | System name validation and GATT Device Name decoding |
| `commonMain/.../internal/gatt` | ManagedConnection and OperationQueue |
| `commonMain/.../internal/pairing` | Waiting for the final pairing state |
| `windowsMain/.../windows` | Public manager, NativeBridge, WindowsGattException |
| `windowsMain/.../windows/scan` | Scan generations and native callback merging |
| `windowsMain/.../windows/pairing` | Address reservation, pair/unpair, WinRT status mapping |
| `windowsMain/.../windows/gatt` | Connection operations and JNI catalog decoding |
| `windowsMain/.../windows/jni` | Loading the DLL from JAR resources |
| `androidMain/.../android` | Public AndroidBleManager |
| `androidMain/.../android/adapter` | Adapter availability and runtime permissions |
| `androidMain/.../android/scan` | ScanCallback, ScanRecord, and AD section parsing |
| `androidMain/.../android/pairing` | System broadcasts and pairing management |
| `androidMain/.../android/gatt` | Connection, BluetoothGattCallback, catalog, and pending request |

`sample-windows` contains separate scanning, GATT, and pairing scenarios.
`buildSrc/.../buildlogic` contains CMake discovery and the DLL build task.
All external dependency/plugin coordinates and versions are in
`gradle/libs.versions.toml`; Gradle manages the Wrapper and built-in plugin versions.
The settings toolchain resolver plugin reads its version from the same TOML before
generated `libs` accessors are available.

## Resource ownership

The manager owns the scanner, pairing controller, and connection map. The scanner
and pairing controller use the same monitor as connect/close. Extracting these
classes therefore does not introduce independent locks for previously shared resources.

A connection owns its catalog and one operation queue. `ManagedConnection.terminate`
atomically marks the session closed, publishes the reason, stops the queue, and
releases platform resources. Repeated close calls do not release resources twice.

## Operations and callbacks

`OperationQueue` serializes requests with a coroutine Mutex. The lock covers both
starting the operation and waiting for the OS result. A timeout or cancellation of
an operation that has already started closes the connection; otherwise a late
response could complete the next request.

On Android, Pending is registered before calling the BluetoothGatt API. The callback
checks BluetoothGatt identity, the operation kind, and target identity. `Deferred.await`
runs outside the monitor. API 33+ uses callback overloads with a separate `value`;
older APIs read the mutable characteristic/descriptor field. The Android catalog
does not lock itself: the connection monitor protects it.

On Windows, blocking JNI requests run on `Dispatchers.IO`. C++ enforces its own
deadline. After connect returns, coroutine cancellation is checked; an unaccepted
connection is closed. Native errors are translated into BleException while retaining
the numeric status and cause. Windows determines the MTU.

Start the notifications collector before enabling CCCD. SharedFlow has a buffer
of 128 values; overflow terminates the session with `NotificationOverflow`.

## Names, catalog, and pairing

ScanStore merges records by normalized address. Advertised, GATT, and system names
are stored separately, and filters run after merging. A missing name in a subsequent
packet does not erase a previously received name. Only an advertising packet sets
`seenInCurrentScan=true`. Old Windows callbacks are rejected by generation; Android
callbacks are rejected by the active callback's identity.

Numeric IDs distinguish GATT objects because UUIDs may repeat. The catalog remains
stable until the connection closes. A change to the published database requires a
new connection. On Windows, the separate `1800/2a00` name request does not require
discovery of vendor services.

Pairing reserves the address against concurrent connect calls. On Android, the
listener is registered before reading the state and starting the operation.
Acceptance of createBond is not confirmation of pairing: the final broadcast must
arrive. Cancelling the wait does not guarantee rollback of the OS action. Windows
supports ConfirmOnly through custom pairing.

## JNI contract

When changing the Kotlin/C++ boundary, check the following together:

- `gpt.ble.manager.windows.NativeBridge` and all `Java_gpt_ble_manager_windows_NativeBridge_*` exports.
- NativeBridge private callbacks and `GetMethodID` descriptors in `Registry.cpp`.
- `gpt/ble/manager/windows/WindowsGattException` in `JniRuntime.cpp`.
- The numeric order of AddressType/SubscriptionMode and the GATT string protocol `S|...`, `C|...`, `D|...`.

Classes bound by name through JNI must not be moved or obfuscated independently.
Native resource ownership is described in the
[C++ module README](../gpt-ble-manager/src/windowsMain/cpp/README.md).

## API documentation

- [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- [Coroutine cancellation and timeouts](https://kotlinlang.org/docs/cancellation-and-timeouts.html).
- [Mutex](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/).
- [Android BluetoothGattCallback](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback).
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- [Windows GATT client](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client).
- [JNI design](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html).
- [Gradle version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html).
