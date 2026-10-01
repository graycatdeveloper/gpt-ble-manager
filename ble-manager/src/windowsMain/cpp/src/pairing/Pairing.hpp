/**
 * @file
 * Внутренние операции pairing/Pairing. JNI экспортируется только из jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::windows
{
/**
 * Читает системное сопряжение без создания GATT-сессии.
 * 0 — NotPaired, 1 — Paired, -1 — адрес не разрешён Windows.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/pair-devices
 */
jint queryPairingState(JNIEnv* env, jlong handle, jstring address, jint type);

/**
 * Сопряжение ConfirmOnly с обработчиком consent; PIN никогда не принимается вслепую.
 * Возвращает исходный DevicePairingResultStatus для классификации в Kotlin.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/pair-devices
 */
jint pairDevice(JNIEnv* env, jlong handle, jstring address, jint type, jlong timeout);

/**
 * Удаляет локальное сопряжение через ОС и возвращает DeviceUnpairingResultStatus.
 * На разрешение адреса и UnpairAsync расходуется один общий timeout.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/pair-devices
 */
jint unpairDevice(JNIEnv* env, jlong handle, jstring address, jint type, jlong timeout);
} // namespace gpt::ble::windows
