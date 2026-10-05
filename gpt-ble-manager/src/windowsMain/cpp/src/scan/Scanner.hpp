/**
 * @file
 * Внутренние операции scan/Scanner. JNI экспортируется только из jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Запускает активное advertising-сканирование и отдельный поиск системных имён.
 * Оба watcher используют generation; объединение пакетов и фильтрация остаются в Kotlin.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.advertisement.bluetoothleadvertisementwatcher?view=winrt-26100
 */
void startScan(jlong handle, jlong generation);

/**
 * Останавливает advertising и поиск системных имён текущего manager.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.advertisement.bluetoothleadvertisementwatcher?view=winrt-26100
 */
void stopScan(jlong handle);
} // namespace gpt::ble::manager::windows
