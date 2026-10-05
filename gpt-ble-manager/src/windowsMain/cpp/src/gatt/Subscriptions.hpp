/**
 * @file
 * Внутренние операции gatt/Subscriptions. JNI экспортируется только из jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Регистрирует ValueChanged до записи CCCD, чтобы не потерять первое уведомление.
 * 0 отключает подписку, 1 включает Notify, остальные значения сохраняют Indicate.
 * При ошибке записи CCCD новый обработчик снимается; существующий сохраняется.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcharacteristic.writeclientcharacteristicconfigurationdescriptorasync
 */
void configureSubscription(jlong handle, jlong id, jint attribute, jint mode);
} // namespace gpt::ble::manager::windows
