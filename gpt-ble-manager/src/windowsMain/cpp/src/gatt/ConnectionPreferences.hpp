/** @file Windows 11 connection preferences; acceptance does not imply negotiation completed. */
#pragma once
#include <jni.h>

namespace gpt::ble::manager::windows
{
bool supportsPreferredParameters();
jint requestPreferredParameters(jlong handle, jlong id, jint mode);
} // namespace gpt::ble::manager::windows
