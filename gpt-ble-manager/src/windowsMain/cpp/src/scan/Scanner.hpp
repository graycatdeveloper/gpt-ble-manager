/**
 * @file
 * Internal scan/Scanner operations. JNI is exported only from jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Starts active advertisement scanning and a separate system-name lookup. Both watchers use
 * generation; Kotlin still handles packet merging and filtering.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.advertisement.bluetoothleadvertisementwatcher?view=winrt-26100
 */
void startScan(jlong handle, jlong generation);

/**
 * Stops advertisement scanning and system-name lookup for the current manager.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.advertisement.bluetoothleadvertisementwatcher?view=winrt-26100
 */
void stopScan(jlong handle);
} // namespace gpt::ble::manager::windows
