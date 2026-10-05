# Contributing

This project is developed with the help of ChatGPT. Changes written by hand or with
AI assistance undergo the same review: contributors should understand the code,
describe its behavior, and provide validation results. Contributions are accepted
under the project's MIT license.

## Code changes

1. For a bug, provide a reproducible scenario and the expected result.
2. Keep each change focused on one topic; identify breaking API changes explicitly.
3. Update dependency and external plugin versions in `gradle/libs.versions.toml`.
4. Follow `.editorconfig` for Kotlin and `.clang-format` for C++.
5. Use English for comments and documentation. Explain ownership, OS constraints,
   and operation ordering rather than obvious syntax.

Do not change lock ordering, callback filters, or cancellation handling without
checking the affected scenarios. When changing JNI, update Kotlin, C++ exports,
and string descriptors together. Do not replace numeric GATT handles with UUID keys.

## Checks before a pull request

```powershell
.\gradlew.bat :gpt-ble-manager:allTests :gpt-ble-manager:assemble :sample-windows:classes
```

Run native tests through CMake/CTest as described in the
[C++ README](gpt-ble-manager/src/windowsMain/cpp/README.md).
Use `:gpt-ble-manager:publish` to check publication metadata: by default, its output
goes to `build/repository`, not an external service.

State which platforms and devices were actually tested. Android host tests do not
replace Bluetooth stack testing on a phone. Do not send unknown write commands to
a device; use its documented protocol and an explicitly agreed test scenario.

Do not commit `local.properties`, keys, build directories, dumps containing personal
data, or your devices' addresses. Preserve license notices for bundled tools and
dependencies when updating them.
