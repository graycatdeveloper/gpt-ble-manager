# gpt-ble-manager native Windows implementation

This is a C++20 DLL for Windows x64, loaded by Kotlin/JVM through JNI.
`../kotlin/gpt/ble/manager/windows/NativeBridge.kt` defines the Kotlin boundary.
JNI signatures, callback protocols, status codes, and timeouts are coordinated with
the Kotlin layer; changes to this boundary require both implementations to be updated together.

## File map

```text
cpp/
├── CMakeLists.txt                  DLL sources, Windows SDK/JNI, optional CTest
├── .clang-format                  Consistent vertical C++ style
├── src/
│   ├── jni/
│   │   ├── NativeBridge.cpp        All 17 Java_gpt_ble_manager_windows_... exports
│   │   └── JniRuntime.*            JNIEnv, UTF-16, Java arrays, exceptions
│   ├── runtime/
│   │   └── WinrtRuntime.*          COM apartment, deadline, await, GattFailure
│   ├── state/
│   │   ├── Registry.*              Numeric handles and manager ownership
│   │   ├── Manager.*               Scanners, radio, callback, connection map
│   │   └── Connection.*            GATT resources, ATT handles, event tokens
│   ├── adapter/
│   │   └── Adapter.*               BLE adapter state and power events
│   ├── bluetooth/
│   │   └── BluetoothUtils.*        UUIDs, addresses, WinRT buffers
│   ├── scan/
│   │   ├── Scanner.*               Advertisement watcher and callback encoding
│   │   ├── AdvertisementParser.*   UUIDs from ServiceUuids and Service Data
│   │   └── KnownDevices.*          System names from AssociationEndpoint
│   ├── pairing/
│   │   └── Pairing.*               Pairing state, pair, unpair
│   └── gatt/
│       ├── ConnectionOperations.*  Connect, monitoring, disconnect, MTU
│       ├── Discovery.*             GATT catalog and separate Device Name read
│       ├── AttributeOperations.*   Characteristic and descriptor reads/writes
│       └── Subscriptions.*         ValueChanged and CCCD
└── tests/
    └── NativeContractsTest.cpp     Data formats without a Bluetooth device
```

`.hpp` headers define function contracts and ownership rules; `.cpp` files contain
implementations and comments on significant steps. All declarations are in
`gpt::ble::manager::windows`. `using namespace` appears only in `.cpp` files; private
helpers live in anonymous namespaces or have internal linkage. Headers are not
installed as a public C++ SDK: JNI is the only external boundary.

## Call flow

1. Kotlin validates arguments and runs the blocking Windows call on `Dispatchers.IO`.
2. `NativeBridge.cpp` initializes the current thread's apartment and calls the relevant module.
3. `Registry` returns a `shared_ptr`, keeping the object alive after the registry mutex is released.
4. The module obtains the required WinRT references under a mutex and executes a request with a bounded wait.
5. The result crosses JNI; C++ exceptions are translated into Java exceptions.

The reverse direction is: WinRT event → weak reference → `Manager::call` →
JNI callback → Kotlin. The implementation does not create its own event loop,
background reconnection thread, or separate BLE adapter.

## Ownership and threading

`Registry` owns managers through `shared_ptr`; `Manager` owns connections.
The `Connection → Manager` reference and references captured by event handlers are
weak, preventing an `owner → WinRT event → owner` cycle. A callback obtains a
temporary strong reference through `weak_ptr::lock` and then checks `closed`.

`close()` marks the object closed. A callback already in progress may still finish;
the object remains alive until its last strong reference is released. Kotlin also
rejects scan events with an outdated `generation`.

Resource collections and event tokens are detached under the mutex. Event revocation
and resource closure happen outside the critical section. The original best-effort
teardown error handling order is preserved; the refactor does not add new guarantees
for driver failures during cleanup.

A manager may retain `JavaVM*`, while `JNIEnv*` belongs to a specific thread.
`JavaEnv` attaches a native callback thread as a daemon and detaches only a thread
it attached itself. A global reference retains the Java callback; a local frame
cleans up temporary JNI callback references. Callback exceptions are logged and
cleared because an asynchronous event has no waiting Java call to receive them.

References: [JNI threads](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/invocation.html#attaching-to-the-vm),
[JNI local/global references](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#global-and-local-references),
[C++/WinRT lifetime](https://learn.microsoft.com/en-us/windows/apps/develop/cpp-winrt/weak-references),
[C++ Core Guidelines: resource management](https://isocpp.github.io/CppCoreGuidelines/CppCoreGuidelines#S-resource).

## Stable contracts

- The registry issues monotonic `jlong` handles. These are not C++ object addresses.
- Strings use UTF-16. MAC addresses look like `AA:BB:CC:DD:EE:01`; UUIDs have no braces.
- Address types: `0 = Unknown`, `1 = Public`, `2 = Random`. Unknown uses the WinRT overload without a type.
- Scanning is active: a scan response may provide a name after the first advertising packet.
- UUIDs are read from ServiceUuids and Service Data AD types `0x16`, `0x20`, and `0x21`.
  Truncated sections are skipped; order and duplicates are preserved until Kotlin processes them.
- Manufacturer data callbacks contain a two-byte little-endian CompanyId followed by the payload.
- The system-name source does not fabricate RSSI and does not count as an advertising packet.
- GATT requests use `Uncached`. A composite operation shares one deadline across all steps.
  The regular budget is 12 seconds; connect/pair/unpair receive their timeout from Kotlin.
- `await` requests Cancel on timeout. WinRT cancellation does not promise to roll back completed OS operations.
- The catalog uses `S|service|uuid`, `C|service|char|uuid|properties`, and `D|char|desc|uuid` records.
  Keys are actual ATT AttributeHandle values, not UUIDs.
- The catalog is published only after discovery fully succeeds. An initial `GattServicesChanged`
  does not terminate the connection while `catalogReady` is still unset.
- Device Name is read separately from `1800/2a00`, without discovering other services.
- ValueChanged is registered before writing CCCD. A CCCD failure rolls back the new subscription;
  enabling an existing subscription again does not create a second handler.
- For CCCD, `0 = Disabled`, `1 = Notify`, and all other values retain the Indicate behavior.
- Windows negotiates MTU. The native layer reports `GattSession.MaxPduSize`.
- `GattFailure` retains the numeric status. Java receives `WindowsGattException`;
  other native errors remain `IllegalStateException`. The `TIMEOUT:` prefix is preserved.
- JNI catch blocks return the original fallback (`0`, `-1`, `3` for the adapter, `nullptr`,
  `JNI_FALSE`, or `23` for MTU) while setting a Java exception. A fallback is not a successful response.
- Pair uses `Custom.PairAsync(ConfirmOnly, Default)`. PIN entry or comparison is not
  automatically confirmed. Unpair uses the original `UnpairAsync`.

Protocol/API references: [GATT client](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client),
[GATT status](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcommunicationstatus),
[MaintainConnection](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection),
[Bluetooth Assigned Numbers](https://www.bluetooth.com/specifications/assigned-numbers/),
[DeviceWatcher](https://learn.microsoft.com/en-us/uwp/api/windows.devices.enumeration.devicewatcher),
[Pairing](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/pair-devices).

## Building and CLion

Requires the Visual Studio 2022 C++ x64 toolchain, a Windows SDK with `cppwinrt`,
CMake 3.20+, and a JDK. Open this directory in CLion; under Toolchains, select
Visual Studio and the amd64 architecture, then assign that toolchain to the active
CMake profile. Ninja is supported; bundled MinGW is not the toolchain for this
implementation. Reset the CMake cache after changing compilers.
See [CLion toolchain documentation](https://www.jetbrains.com/help/clion/how-to-create-toolchain-in-clion.html).

From the KMP project root:

```powershell
.\gradlew.bat :gpt-ble-manager:buildWindowsNative
.\gradlew.bat :gpt-ble-manager:allTests :gpt-ble-manager:assemble
```

Gradle tracks `src/**/*.cpp`, `src/**/*.hpp`, `tests/**/*.cpp`, and CMakeLists.txt.
CLion directories and generated build files are excluded from native task inputs.
CMake lists sources explicitly to prevent an accidental file in a build directory
from entering the DLL. Update this list when adding a `.cpp` file.

For a standalone build and native tests from this directory, provide your JDK path:

```powershell
cmake -S . -B cmake-build-contracts -G "Visual Studio 17 2022" -A x64 `
    -DJAVA_HOME="<path to JDK>" -DGPT_BLE_BUILD_NATIVE_TESTS=ON
cmake --build cmake-build-contracts --config Release --target gpt-ble-native-tests
ctest --test-dir cmake-build-contracts -C Release --output-on-failure
```

Native tests cover byte order, truncated AD sections, duplicate UUIDs, addresses,
buffers, and the GATT error contract. They do not enable the radio or change pairing.
Kotlin tests also cover JNI lifecycle and public contracts. Actual read/write/notification
scenarios require an available peripheral.

## Style and future changes

`.clang-format` targets clang-format 19: four-space indentation, Allman braces,
expanded `if`/loop blocks, and no single-line short functions or lambdas. Long
documentation links are exempt from the line length limit.

Keep `using namespace` out of headers, do not hold a mutex during `await`, and do
not capture an owner by strong reference in a long-lived event handler. When changing
JNI, check `NativeBridge.kt`, DLL exports, and `GetMethodID` descriptors together.
Keep behavioral fixes separate from structural moves so their effects can be checked
on a device.
