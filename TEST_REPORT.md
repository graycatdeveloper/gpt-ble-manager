# Проверка gpt-ble-manager

Дата: **5 октября 2026 года**. Версия: `0.2.3-local`.

## Среда

Windows x64, JDK 21, Gradle 9.7.1, Kotlin 2.4.20, AGP 9.4.1,
Android compile SDK 37 / min SDK 26, Visual Studio 2022, MSVC 19.44,
Windows SDK 10.0.26100.0. Версии зависимостей заданы в `gradle/libs.versions.toml`.

## Автоматические проверки

| Проверка | Результат |
| --- | --- |
| Windows JVM tests | 57 выполнено, 0 ошибок, 0 пропусков |
| Android host tests | 47 выполнено, 0 ошибок, 0 пропусков |
| CMake / CTest | 1 executable, 6 проверок, успешно |
| Windows DLL / JAR, Android AAR, KMP metadata | Собраны |
| Консольный пример Windows | Скомпилирован и запущен |
| Публикация | Успешно в `build/repository` и Maven Local |
| Форматирование | ktfmt 0.64 Kotlin style и clang-format 19, без расхождений |

Общие тесты выполняются на обеих платформах, поэтому 104 выполнения не означают
104 различных сценария. Они проверяют имена устройств, объединение результатов
сканирования, фильтры, сопряжение, очередь GATT, таймауты, отмену и контракт соединения.
Платформенные тесты проверяют разбор Android advertising, состояния сопряжения,
Windows GATT-каталог, перевод ошибок и жизненный цикл JNI-менеджеров.

C++-проверки охватывают MAC, копирование IBuffer, UUID16/32/128 в Service Data,
повреждённые AD-секции, порядок повторяющихся UUID и контекст GATT-ошибок.

## Упаковка и переименование

Проверены публикации `gpt.ble.manager:gpt-ble-manager`,
`gpt.ble.manager:gpt-ble-manager-windows` и `gpt.ble.manager:gpt-ble-manager-android`.
POM содержит новое имя, группу и MIT-лицензию. Текст лицензии включён в
`META-INF/gpt-ble-manager/LICENSE` бинарных и исходных JAR; в AAR он находится
внутри `classes.jar`.

Все 17 JNI-экспортов DLL совпадают с native-методами
`gpt.ble.manager.windows.NativeBridge`. DLL внутри Windows JAR побайтово совпадает
с результатом нативной сборки. Пути классов и ресурсов соответствуют новому пакету.

## Проверка сканирования

Консольный пример загрузил DLL из ресурсов JAR, получил `AdapterState.Ready` и
завершил 15-секундное сканирование. Обнаружено 15 устройств;
`scanState.error` и `scanState.nameResolutionError` равны `null`.
Адреса и имена окружающих устройств в публичный отчёт не включены.

## Повторение проверок

Из корня проекта в PowerShell:

```powershell
.\gradlew.bat :gpt-ble-manager:allTests :gpt-ble-manager:assemble :sample-windows:classes --warning-mode all
.\gradlew.bat :gpt-ble-manager:publish
.\gradlew.bat :gpt-ble-manager:publishToMavenLocal
.\gradlew.bat :sample-windows:run
```

Настройка CMake и команда CTest описаны в
[README нативного модуля](gpt-ble-manager/src/windowsMain/cpp/README.md).

## Ограничения

В этой проверке не выполнялись подключение к периферии, GATT-чтение/запись,
уведомления и pair/unpair на физическом устройстве. Android проверен сборкой и
host-тестами; аппаратные тесты на телефоне не выполнялись. Эти результаты
подтверждают сборку, проверенные контракты, упаковку и работу Windows-сканирования,
но не совместимость со всеми адаптерами, прошивками и Bluetooth-устройствами.
