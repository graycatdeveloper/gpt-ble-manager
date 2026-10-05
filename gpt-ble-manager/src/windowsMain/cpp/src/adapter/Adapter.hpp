/**
 * @file
 * Internal adapter/Adapter operations. JNI is exported only from jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Checks the BLE adapter and radio; registers the power state change event once. Returns the
 * original Kotlin codes: 0 Ready, 1 PoweredOff, 2 PermissionRequired, 3 Unsupported.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothadapter
 */
jint queryAdapterState(jlong handle);
} // namespace gpt::ble::manager::windows
