#pragma once

#include <cstdint>
#include <jni.h>
#include <string>
#include <string_view>
#include <vector>
#include <winrt/Windows.Storage.Streams.h>

namespace gpt::ble::manager::windows
{
/**
 * JNI helpers for JVM threads, strings, arrays, and exception translation. JavaEnv attaches only a
 * foreign native thread and detaches only the thread it attached. Reference:
 * https://docs.oracle.com/en/java/javase/17/docs/specs/jni/invocation.html#attaching-to-the-vm
 */
struct JavaEnv
{
    JavaVM* vm;
    JNIEnv* env = nullptr;
    bool attached = false;
    explicit JavaEnv(JavaVM* value);
    ~JavaEnv();
    JavaEnv(JavaEnv const&) = delete;
    JavaEnv& operator=(JavaEnv const&) = delete;
};

/**
 * Windows UTF-16 code units have the same size as jchar. Device names do not pass through modified
 * UTF-8, preserving Unicode.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#string-operations
 */
jstring text(JNIEnv* env, std::wstring_view value);
std::wstring text(JNIEnv* env, jstring value);

/**
 * Copies data between a Java array and a WinRT IBuffer; JVM memory is not held for the duration of
 * a BLE request. Created JNI values belong to the current local frame.
 */
winrt::Windows::Storage::Streams::IBuffer buffer(JNIEnv* env, jbyteArray value);
jbyteArray byteArray(JNIEnv* env, std::vector<uint8_t> const& value);
jobjectArray strings(JNIEnv* env, std::vector<std::wstring> const& values);

/**
 * Called only from catch: translates the current C++ exception into a pending Java exception. Does
 * not replace an existing JVM exception; Kotlin receives the original GATT code.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/design.html#exceptions-and-error-codes
 */
void failure(JNIEnv* env) noexcept;
} // namespace gpt::ble::manager::windows
