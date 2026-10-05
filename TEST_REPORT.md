# gpt-ble-manager validation

Date: **October 5, 2026**. Version: `0.2.3-local`.

## Environment

Windows x64, JDK 21, Gradle 9.7.1, Kotlin 2.4.20, AGP 9.4.1,
Android compile SDK 37 / min SDK 26, Visual Studio 2022, MSVC 19.44,
Windows SDK 10.0.26100.0. Dependency versions are defined in `gradle/libs.versions.toml`.

## Automated checks

| Check | Result |
| --- | --- |
| Windows JVM tests | 57 executed, 0 failures, 0 skipped |
| Android host tests | 47 executed, 0 failures, 0 skipped |
| CMake / CTest | 1 executable, 6 checks, passed |
| Windows DLL / JAR, Android AAR, KMP metadata | Built |
| Windows console example | Compiled and run |
| Publication | Succeeded to `build/repository` and Maven Local |
| Formatting | ktfmt 0.64 Kotlin style and clang-format 19, no differences |

Common tests run on both platforms, so 104 executions do not represent 104 distinct
scenarios. They cover device names, scan result merging, filters, pairing, the GATT
queue, timeouts, cancellation, and the connection contract. Platform tests cover
Android advertising parsing, pairing states, the Windows GATT catalog, error mapping,
and JNI manager lifecycle.

C++ checks cover MAC addresses, IBuffer copying, UUID16/32/128 in Service Data,
malformed AD sections, duplicate UUID ordering, and GATT error context.

## Packaging and renaming

Validated publications: `gpt.ble.manager:gpt-ble-manager`,
`gpt.ble.manager:gpt-ble-manager-windows`, and `gpt.ble.manager:gpt-ble-manager-android`.
The POM contains the new name, group, and MIT license. The license text is included
at `META-INF/gpt-ble-manager/LICENSE` in binary and source JARs; inside the AAR it is
in `classes.jar`.

All 17 DLL JNI exports match the native methods in
`gpt.ble.manager.windows.NativeBridge`. The DLL bundled in the Windows JAR is
byte-for-byte identical to the native build output. Class and resource paths match
the new package.

## Scan check

The console example loaded the bundled DLL, reported `AdapterState.Ready`, and
completed a 15-second scan. It discovered 15 devices; `scanState.error` and
`scanState.nameResolutionError` were both `null`. Nearby devices' addresses and
names are not included in this public report.

## Reproducing the checks

From the project root in PowerShell:

```powershell
.\gradlew.bat :gpt-ble-manager:allTests :gpt-ble-manager:assemble :sample-windows:classes --warning-mode all
.\gradlew.bat :gpt-ble-manager:publish
.\gradlew.bat :gpt-ble-manager:publishToMavenLocal
.\gradlew.bat :sample-windows:run
```

CMake setup and the CTest command are described in the
[native module README](gpt-ble-manager/src/windowsMain/cpp/README.md).

## Limitations

This validation did not include connecting to a peripheral, GATT reads/writes,
notifications, or pair/unpair on a physical device. Android was checked through
builds and host tests; hardware tests on a phone were not run. These results confirm
the build, tested contracts, packaging, and Windows scanning, but do not establish
compatibility with every adapter, firmware, or Bluetooth device.
