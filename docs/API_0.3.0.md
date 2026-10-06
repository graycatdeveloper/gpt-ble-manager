# 0.3.0 API guide

This is a development version, not a claim that 0.3.0 is available on Maven Central.
The source remains MIT licensed and is developed with ChatGPT assistance.

## Modules and local integration

| Module | Targets | Purpose |
| --- | --- | --- |
| `gpt-ble-manager` | Windows JVM x64, Android 26+ | BLE transport and common session APIs |
| `gpt-ble-manager-testing` | Windows JVM, Android host tests | Deterministic fake radio and peripherals |
| `gpt-ble-manager-android-background` | Android 26+ | PendingIntent scans and companion associations |

For an application in a neighboring folder, add a composite build in its settings:

```kotlin
includeBuild("../0.3.0") {
    dependencySubstitution {
        substitute(module("gpt.ble.manager:gpt-ble-manager")).using(project(":gpt-ble-manager"))
        substitute(module("gpt.ble.manager:gpt-ble-manager-testing")).using(project(":gpt-ble-manager-testing"))
        substitute(module("gpt.ble.manager:gpt-ble-manager-android-background"))
            .using(project(":gpt-ble-manager-android-background"))
    }
}
```

Use `implementation("gpt.ble.manager:gpt-ble-manager:0.3.0-local")` in `commonMain`
or a Windows JVM application's dependencies. Put the testing module in `commonTest`
and the background module in `androidMain`. These substitutions consume the working
tree directly. Build with JDK 21 and the SDK/toolchain described in the root README.

## Sessions and reconnection

```kotlin
import gpt.ble.manager.session.*

val session = manager.openSession(device, applicationScope,
    SessionOptions(reconnect = ReconnectPolicy(maxAttempts = 5)))
try {
    val connection = session.awaitReady()
    println(connection.readDeviceName())
} finally {
    session.closeAndJoin()
}
```

The caller owns the scope. `SessionState` reports Connecting, Ready, Reconnecting,
Failed and Closed. Retry delays grow exponentially up to the configured maximum.
`maxAttempts` is the total reconnection budget, excluding the initial attempt;
zero disables retries. The default retries Disconnected, Timeout and NotReady.
Permission, protocol and unsupported-feature errors stop the session by default.
Closing the session, the active connection, the manager or the parent scope stops it.
Cancellation during backoff prevents another attempt.

Every new connection discovers its own GATT catalog. Never reuse an old characteristic
or descriptor. Reads and writes are not automatically retried; a failed write may
already have reached the device. Applications decide whether a command is safe to retry.
`awaitReady()` waits for a usable session snapshot; a subsequent physical disconnect
can still make the next operation fail.

To follow a characteristic across replacement connections:

```kotlin
val battery = CharacteristicSelector(BleUuid.parse("180f"), BleUuid.parse("2a19"))
session.observe(battery).collect { bytes -> println(bytes) }
```

The selector resolves UUIDs again after discovery. `serviceInstance` and
`characteristicInstance` distinguish repeated UUIDs by catalog order. If order itself
can change, the application must select by its device protocol. Values lost while
disconnected are not replayed. Missing characteristics terminate that observer.

## Managed notifications

```kotlin
connection.observe(characteristic, SubscriptionMode.Notify).collect { bytes ->
    process(bytes.toByteArray())
}
```

A collector registers its listener before the first CCCD write. Collectors of the
same handle and mode share one remote subscription; cancelling the last disables it.
Conflicting Notify/Indicate modes are rejected. Disconnect ends collection with its
reason. A slow consumer can exhaust the bounded notification buffer and terminate
the connection with `NotificationOverflow`, rather than silently lose commands.
Do not mix `observe()` and manual `subscribe()` for the same characteristic.
The low-level notifications SharedFlow remains available for existing clients.

## Scanning

```kotlin
manager.startScan(ScanOptions(
    nameExact = "ExampleSensor",
    ignoreNameCase = true,
    minRssi = -80,
    manufacturerFilters = mapOf(0x1234 to DataFilter(
        value = BleBytes(byteArrayOf(0x20)),
        mask = BleBytes(byteArrayOf(0xf0.toByte())),
    )),
    lostTimeoutMillis = 10_000,
))
```

All filter categories are ANDed; the UUID set matches any listed service. Data filters
match a prefix at an optional offset, with an optional mask; an empty value requires
that the data key exist. Name filtering uses resolved names after advertisement,
GATT and system records have been merged. A device that never advertises its name may
still need `includeKnownDevices` or a direct address; scanning cannot read a remote
GATT name without a connection.

`BleDevice.serviceData` preserves payloads indexed by 16/32/128-bit service UUID.
`lastSeenMillis` is the host receipt time in Unix milliseconds, not an OS-cache timestamp.
Known-only devices have no last-seen value. Expiration uses a separate monotonic clock
so wall-clock changes cannot create false disappearance. A timer checks expiry at most
once per second while scanning. Stopping retains the last snapshot and stops expiration.

Subscribe to `scanEvents` before scanning. It emits Appeared, Updated, Disappeared and
Packet. Packet contains only that received advertisement, while `devices` merges
packets. Packet events are emitted for devices whose merged snapshot matches filters;
a nameless scan-response packet may therefore be included for a named device.
Disappeared also covers devices that no longer match a filter. Timestamp changes can
produce Updated events. The event queue is bounded (256); `droppedScanEvents` reports
overflow for active subscribers. Late subscribers get the current StateFlow snapshot,
not a replay of past packets.

## Connections, timeouts and diagnostics

`manager.connections` exposes owned sessions, including setup where the platform has
already allocated one. `connectionFor(address)` performs a case-insensitive lookup.
`disconnectAll()` closes the registered snapshot; the manager remains usable. An
unregistered concurrent connect attempt is not a member of that snapshot.

```kotlin
val options = BleManagerOptions(
    timeouts = OperationTimeouts(executionMillis = 20_000, queueWaitMillis = 5_000),
    diagnostics = BleDiagnosticSink { event -> logger(event) },
)
val manager = WindowsBleManager(options) // AndroidBleManager(context, options)

val bytes = withOperationTimeouts(OperationTimeouts(8_000, 1_000)) {
    connection.read(characteristic)
}
```

Queue timeout/cancellation leaves the running operation intact. Timeout/cancellation
after transport execution starts closes the connection to reject late callbacks.
Windows JNI receives the same execution budget and shares it across composite WinRT
steps. WinRT cancellation is cooperative: returning from a blocking native call can
take longer than a Kotlin cancellation request. `closeAndJoin()` waits for cleanup.
Connect/pair/unpair retain their explicit timeout parameters.

`BleException.details` carries the operation, platform/status when available, connection
ID and relevant GATT UUIDs. Status domains depend on the operation: Android GATT callbacks
use GATT status, immediate writes use BluetoothStatusCodes, Windows GATT uses
GattCommunicationStatus and pairing uses DevicePairingResultStatus. See the preserved
message/cause for additional native context.

The diagnostic sink receives GATT queue/start/success/failure/cancellation and manager
connect/pair/unpair events with monotonic elapsed time. It runs on the caller/operation
thread and must be fast; enqueue work into your logger if necessary. A failing sink
does not break BLE. Events contain metadata, not characteristic payloads.

## Chunked transfers

```kotlin
import gpt.ble.manager.transfer.*

connection.writeChunks(characteristic, payload,
    ChunkedWriteOptions(maxChunkBytes = 100, pacingMillis = 5)
).collect { progress -> println("${progress.bytesSent}/${progress.totalBytes}") }
```

This is opt-in and only valid for a protocol that accepts consecutive fragments.
Each chunk is limited to current MTU minus three and the configured payload limit.
For packet headers/checksums, reserve `framingOverheadBytes` and supply a `frame`
callback receiving `TransferChunk(index, offset, totalBytes, payload)`. The framed
packet is validated against MTU before transmission. The library cannot infer framing,
acknowledgement, firmware-update semantics or retries for arbitrary peripherals.

The flow copies input on creation and is cold: collecting it twice sends twice.
Progress counts completed platform writes and payload bytes, excluding framing.
WithoutResponse progress is not a remote application acknowledgement. Cancellation
between packets leaves the connection active; cancellation during a write closes it.
Other callers may enqueue operations between chunks; use one application transfer
owner when the protocol requires exclusive ordering. No packet is silently retried.

## Platform capabilities

| Operation | Android | Windows |
| --- | --- | --- |
| `readRssi()` | Connected RSSI | Unsupported; scan RSSI remains available |
| `readPhy()`, `setPreferredPhy()` | API 26+, adapter-dependent PHY support | Unsupported |
| `requestConnectionPriority()` | OS preference | Unsupported |
| `requestPreferredConnectionParameters()` | Unsupported | Windows 11+ preference |
| `requestMtu()` | Android negotiation | Returns OS-negotiated MTU |

Check `manager.capabilities` or `connection.capabilities`. Unsupported methods raise
`BleError.Unsupported`. Capability means the API/adapter can request the feature;
the peripheral and OS may reject it or choose different values. The Windows request
object stays alive for the connection, is replaced on the next request and is released
on disconnect. Restore Balanced after a Throughput preference.

Sources: [Android BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt),
[Windows preferred parameters](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothledevice.requestpreferredconnectionparameters).

## Android background and companion integration

The optional module leaves application lifecycle and user consent under app control.
Declare a non-exported receiver in your manifest and create an explicit intent:

```kotlin
val pending = AndroidBackgroundScanner.pendingIntent(context, MyBleReceiver::class.java)
val scanner = AndroidBackgroundScanner(context)
scanner.start(pending, listOf(ScanFilter.Builder().setDeviceAddress(address).build()))
// In MyBleReceiver.onReceive: AndroidBackgroundScanner.results(intent)
// Recreate the same PendingIntent identity after process restart to stop:
scanner.stop(pending)
```

Background scanning uses Android hardware filters, not the foreground merged-name
filters. Never use FLAG_CANCEL_CURRENT. Permission/location/background execution
requirements still apply. A receiver cannot keep a connection alive indefinitely;
use a permitted connected-device foreground service or CompanionDeviceService for
long-lived work, with app-provided notification and manifest entries.

`AndroidCompanionDevices.associate(filter, callback)` starts system association.
Handle `onDeviceFound` on older Android or `onAssociationPending` on API 33+, launch
the supplied IntentSender in your Activity, then inspect `associatedAddresses()`.
Association and Bluetooth bonding are different operations. `disassociate` removes
only the app association; `BleManager.unpair` retains its platform requirements.

For presence observation, call `startObservingPresence(address)` on API 31+ after
association. Declare your own service extending Android `CompanionDeviceService`:

```xml
<service android:name=".MyCompanionService"
    android:exported="true"
    android:permission="android.permission.BIND_COMPANION_DEVICE_SERVICE">
    <intent-filter>
        <action android:name="android.companion.CompanionDeviceService" />
    </intent-filter>
</service>
```

Handle the framework's presence callbacks for your supported Android versions,
including `onDevicePresenceEvent` on API 36+. The helper uses the association-ID request
on API 36 and the address API on API 31–35. Stop observing when no longer needed.
The helper does not launch Activities or foreground services from a background callback.

Sources: [Background BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/background),
[CompanionDeviceManager](https://developer.android.com/reference/android/companion/CompanionDeviceManager),
[PendingIntent scanning](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner).

## Testing and migration

Build `FakePeripheral` from `FakeService`/`FakeCharacteristic` templates and pass it
to `FakeBleManager`. Call `advertise`, `disappear`, `setAdapterState` and
`FakeBleConnection.disconnect` to control the radio. `connectOutcomes` and
`enqueue(operation, FakeOutcome(...))` script failures/delays with coroutine time.
Inspect `writes` and `subscriptions`; `notify` delivers only when CCCD is enabled.
Use one test dispatcher: the simulator's control API is intentionally thread-confined.
It simulates application policy and does not claim to emulate Android/WinRT timing,
permissions or radio behavior. Core queue contracts are tested separately.

Existing application calls remain source-compatible. Custom implementations of
BleManager must provide connections/scan events; custom BleConnection implementations
must provide observe (NotificationObserver is reusable). Recompile consumers for 0.3.0:
new data-class constructor fields and JNI signatures are not binary compatible with
0.2.3. Never pair the old DLL with the new Kotlin classes. Devices are still nullable-name
snapshots; displayName alone falls back to the address.

Run `./gradlew.bat allTests assemble`. The common behavior tests execute both on the
Windows JVM and Android host-test target. Hardware-free tests cover retry budgets,
manual disconnect, subscription races, stale handles, filters, data decoding, timeout
phases, and transfer framing/cancellation. Android background/PHY behavior and Windows
11 preference negotiation need matching physical hardware/OS validation.
