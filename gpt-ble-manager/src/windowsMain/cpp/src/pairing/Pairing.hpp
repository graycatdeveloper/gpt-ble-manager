/**
 * @file
 * Internal pairing/Pairing operations. JNI is exported only from jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>

namespace gpt::ble::manager::windows
{
/**
 * Reads the system pairing state without creating a GATT session. 0 means NotPaired, 1 means
 * Paired, and -1 means Windows could not resolve the address.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/pair-devices
 */
jint queryPairingState(JNIEnv* env, jlong handle, jstring address, jint type);

/**
 * ConfirmOnly pairing with a consent handler; PIN requests are never accepted blindly. Returns the
 * original DevicePairingResultStatus for classification in Kotlin.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/pair-devices
 */
jint pairDevice(JNIEnv* env, jlong handle, jstring address, jint type, jlong timeout);

/**
 * Removes the local bond through the OS and returns DeviceUnpairingResultStatus. Address
 * resolution and UnpairAsync share one timeout budget.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/pair-devices
 */
jint unpairDevice(JNIEnv* env, jlong handle, jstring address, jint type, jlong timeout);
} // namespace gpt::ble::manager::windows
