/**
 * @file
 * Internal gatt/ConnectionOperations operations. JNI is exported only from jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Resolves the address, retains GattSession, and retrieves services using Uncached mode. One
 * timeout covers all steps; the handle is published only after Connected.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection
 */
jlong openConnection(JNIEnv* env, jlong handle, jstring address, jint type, jlong timeout);

/**
 * Subscribes to disconnection, GATT database changes, and MTU changes after the Kotlin session is
 * registered. Revokes newly installed handlers if registration races with closure.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection
 */
jboolean monitorConnection(jlong handle, jlong id);

/**
 * Removes the connection from the map under the mutex and closes WinRT resources outside it.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection
 */
void closeConnection(jlong handle, jlong id);

/**
 * Returns the session's actual MaxPduSize; Windows negotiates the MTU itself.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maintainconnection
 */
jint currentMtu(jlong handle, jlong id);
} // namespace gpt::ble::manager::windows
