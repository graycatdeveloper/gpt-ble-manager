#include "jni/JniRuntime.hpp"
#include "runtime/WinrtRuntime.hpp"
#include <cwchar>
#include <new>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Storage::Streams;

JavaEnv::JavaEnv(JavaVM* value) : vm(value)
{
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
    {
        attached =
            vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) == JNI_OK;
    }
}

JavaEnv::~JavaEnv()
{
    if (attached)
    {
        vm->DetachCurrentThread();
    }
}

jstring text(JNIEnv* env, std::wstring_view value)
{
    static_assert(sizeof(wchar_t) == sizeof(jchar));
    return env->NewString(
        reinterpret_cast<const jchar*>(value.data()),
        static_cast<jsize>(value.size())
    );
}

std::wstring text(JNIEnv* env, jstring value)
{
    if (!value)
    {
        return {};
    }
    auto chars = env->GetStringChars(value, nullptr);
    if (!chars)
    {
        throw std::bad_alloc();
    }
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return result;
}

IBuffer buffer(JNIEnv* env, jbyteArray value)
{
    std::vector<uint8_t> data(env->GetArrayLength(value));
    if (!data.empty())
    {
        env->GetByteArrayRegion(
            value,
            0,
            static_cast<jsize>(data.size()),
            reinterpret_cast<jbyte*>(data.data())
        );
    }
    DataWriter writer;
    writer.WriteBytes(data);
    return writer.DetachBuffer();
}

jbyteArray byteArray(JNIEnv* env, const std::vector<uint8_t>& value)
{
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result && !value.empty())
    {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte*>(value.data())
        );
    }
    return result;
}

jobjectArray strings(JNIEnv* env, const std::vector<std::wstring>& values)
{
    auto cls = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(static_cast<jsize>(values.size()), cls, nullptr);
    for (size_t i = 0; result && i < values.size(); ++i)
    {
        auto value = text(env, values[i]);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(cls);
    return result;
}

void failure(JNIEnv* env) noexcept
{
    if (env->ExceptionCheck())
    {
        return;
    }
    std::wstring message;
    jint gattStatus = -1;
    jint hresultCode = 0;
    bool hasHresult = false;
    try
    {
        throw;
    }
    catch (GattFailure const& error)
    {
        message = to_hstring(error.what()).c_str();
        gattStatus = static_cast<jint>(error.status);
    }
    catch (hresult_error const& error)
    {
        hresultCode = static_cast<jint>(error.code().value);
        hasHresult = true;
        message = describeHresult(hresultCode);
    }
    catch (std::exception const& error)
    {
        message = to_hstring(error.what()).c_str();
    }
    catch (...)
    {
        message = L"Unknown Windows Bluetooth failure";
    }
    auto cls = env->FindClass(
        gattStatus >= 0 ? "gpt/ble/manager/windows/WindowsGattException"
        : hasHresult    ? "gpt/ble/manager/windows/WindowsHResultException"
                        : "java/lang/IllegalStateException"
    );
    if (!cls)
    {
        return;
    }
    auto constructor = env->GetMethodID(
        cls,
        "<init>",
        (gattStatus >= 0 || hasHresult) ? "(ILjava/lang/String;)V" : "(Ljava/lang/String;)V"
    );
    if (!constructor)
    {
        env->DeleteLocalRef(cls);
        return;
    }
    auto msg = text(env, message);
    auto error = static_cast<jthrowable>(
        gattStatus >= 0 ? env->NewObject(cls, constructor, gattStatus, msg)
        : hasHresult    ? env->NewObject(cls, constructor, hresultCode, msg)
                        : env->NewObject(cls, constructor, msg)
    );
    if (error)
    {
        env->Throw(error);
    }
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(msg);
    env->DeleteLocalRef(cls);
}
} // namespace gpt::ble::manager::windows
