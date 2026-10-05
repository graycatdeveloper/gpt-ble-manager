/**
 * @file
 * Internal gatt/Subscriptions operations. JNI is exported only from jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Registers ValueChanged before writing CCCD to avoid losing the first notification. 0 disables
 * the subscription, 1 enables Notify, and other values retain the Indicate behavior. If the CCCD
 * write fails, the new handler is removed and the existing one is preserved.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcharacteristic.writeclientcharacteristicconfigurationdescriptorasync
 */
void configureSubscription(jlong handle, jlong id, jint attribute, jint mode);
} // namespace gpt::ble::manager::windows
