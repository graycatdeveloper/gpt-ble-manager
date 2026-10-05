/**
 * @file
 * Internal gatt/Discovery operations. JNI is exported only from jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Reads only Generic Access 1800 / Device Name 2a00. Failure of another service must not prevent
 * reading the name. nullptr means there is no readable characteristic; an empty byte[] remains an
 * empty value.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client
 */
jbyteArray readGattDeviceName(JNIEnv* env, jlong handle, jlong id);

/**
 * Builds the complete catalog in temporary maps and publishes it atomically. Preserves the
 * S|service|uuid, C|service|char|uuid|properties, D|char|desc|uuid protocol. Failure at any step
 * is not reported as a successful partial catalog.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client
 */
jobjectArray discoverGatt(JNIEnv* env, jlong handle, jlong id);
} // namespace gpt::ble::manager::windows
