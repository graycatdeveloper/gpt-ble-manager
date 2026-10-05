/**
 * @file
 * Внутренние операции gatt/Discovery. JNI экспортируется только из jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Читает только Generic Access 1800 / Device Name 2a00.
 * Отказ другого сервиса не должен препятствовать чтению имени.
 * nullptr означает отсутствие читаемой характеристики; пустой byte[] остаётся пустым значением.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client
 */
jbyteArray readGattDeviceName(JNIEnv* env, jlong handle, jlong id);

/**
 * Строит полный каталог во временных картах и публикует его атомарно.
 * Сохраняет протокол S|service|uuid, C|service|char|uuid|properties, D|char|desc|uuid.
 * Ошибка любого шага не выдаётся за успешный неполный каталог.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client
 */
jobjectArray discoverGatt(JNIEnv* env, jlong handle, jlong id);
} // namespace gpt::ble::manager::windows
