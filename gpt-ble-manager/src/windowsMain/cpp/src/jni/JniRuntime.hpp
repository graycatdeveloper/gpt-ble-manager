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
 * JNI-инструменты: поток JVM, строки, массивы и перевод исключений.
 * JavaEnv присоединяет только чужой native-поток и отсоединяет только его.
 * Ссылки: https://docs.oracle.com/en/java/javase/17/docs/specs/jni/invocation.html#attaching-to-the-vm
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
 * UTF-16 на Windows совпадает по размеру с jchar.
 * Строки имён не проходят через modified UTF-8 и не теряют Unicode.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#string-operations
 */
jstring text(JNIEnv* env, std::wstring_view value);
std::wstring text(JNIEnv* env, jstring value);

/**
 * Копирует данные между Java-массивом и WinRT IBuffer; память JVM не удерживается
 * на время BLE-запроса. Созданные JNI-значения принадлежат текущему local frame.
 */
winrt::Windows::Storage::Streams::IBuffer buffer(JNIEnv* env, jbyteArray value);
jbyteArray byteArray(JNIEnv* env, std::vector<uint8_t> const& value);
jobjectArray strings(JNIEnv* env, std::vector<std::wstring> const& values);

/**
 * Вызывается только из catch: переводит текущее C++-исключение в pending Java exception.
 * Уже установленная ошибка JVM не заменяется; Kotlin получает исходный GATT-код.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/design.html#exceptions-and-error-codes
 */
void failure(JNIEnv* env) noexcept;
} // namespace gpt::ble::manager::windows
