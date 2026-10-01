/**
 * @file
 * Внутренние операции gatt/AttributeOperations. JNI экспортируется только из jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::windows
{
/**
 * Находит ATT handle под mutex и читает характеристику или дескриптор Uncached.
 * Kotlin сериализует операции; mutex не удерживается на время WinRT-ожидания.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcharacteristic
 */
jbyteArray readAttribute(JNIEnv* env, jlong handle, jlong id, jint attribute, jboolean descriptor);

/**
 * Копирует Java-байты перед отправкой. Для характеристики сохраняет выбор
 * WithResponse/WithoutResponse; запись дескриптора использует собственный WinRT API.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcharacteristic
 */
void writeAttribute(
    JNIEnv* env,
    jlong handle,
    jlong id,
    jint attribute,
    jboolean descriptor,
    jbyteArray data,
    jboolean response
);
} // namespace gpt::ble::windows
