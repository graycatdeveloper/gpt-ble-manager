/**
 * @file
 * Внутренние операции adapter/Adapter. JNI экспортируется только из jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Проверяет BLE-адаптер и radio; единожды регистрирует событие изменения питания.
 * Возвращает исходные коды Kotlin: 0 Ready, 1 PoweredOff, 2 PermissionRequired, 3 Unsupported.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothadapter
 */
jint queryAdapterState(jlong handle);
} // namespace gpt::ble::manager::windows
