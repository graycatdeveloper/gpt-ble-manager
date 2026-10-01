/**
 * @file
 * Внутренние операции gatt/ConnectionOperations. JNI экспортируется только из jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::windows
{
/**
 * Разрешает адрес, удерживает GattSession и получает сервисы Uncached.
 * Один timeout охватывает все шаги; handle публикуется только после Connected.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection
 */
jlong openConnection(JNIEnv* env, jlong handle, jstring address, jint type, jlong timeout);

/**
 * Подписывается на разрыв, изменение GATT-базы и MTU после регистрации Kotlin-сессии.
 * При гонке с закрытием отзывает только что установленные обработчики.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection
 */
jboolean monitorConnection(jlong handle, jlong id);

/**
 * Удаляет соединение из карты под mutex и закрывает WinRT-ресурсы вне mutex.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection
 */
void closeConnection(jlong handle, jlong id);

/**
 * Возвращает фактический MaxPduSize сессии; MTU согласует сама Windows.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection
 */
jint currentMtu(jlong handle, jlong id);
} // namespace gpt::ble::windows
