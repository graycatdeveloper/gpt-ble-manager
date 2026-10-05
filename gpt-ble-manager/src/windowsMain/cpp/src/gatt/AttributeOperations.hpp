/**
 * @file
 * Internal gatt/AttributeOperations operations. JNI is exported only from jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Looks up the ATT handle under the mutex and reads a characteristic or descriptor using Uncached
 * mode. Kotlin serializes operations; the mutex is not held while waiting for WinRT.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcharacteristic
 */
jbyteArray readAttribute(JNIEnv* env, jlong handle, jlong id, jint attribute, jboolean descriptor);

/**
 * Copies Java bytes before sending. Preserves the WithResponse/WithoutResponse choice for
 * characteristics; descriptor writes use their dedicated WinRT API.
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
} // namespace gpt::ble::manager::windows
