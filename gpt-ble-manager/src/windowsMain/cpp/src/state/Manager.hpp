#pragma once

#include "jni/JniRuntime.hpp"
#include <atomic>
#include <map>
#include <memory>
#include <mutex>
#include <windows.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Devices.Enumeration.h>
#include <winrt/Windows.Devices.Radios.h>

namespace gpt::ble::manager::windows
{
struct Connection;

/**
 * Владелец ресурсов одного WindowsBleManager.
 * У каждого экземпляра собственные сканеры, callback и соединения.
 */
struct Manager : std::enable_shared_from_this<Manager>
{
    JavaVM* vm = nullptr;
    // JNI global reference: живёт дольше исходного вызова create().
    jobject callback = nullptr;
    jmethodID advertisement{};
    jmethodID scanStopped{};
    jmethodID knownDevice{};
    jmethodID nameLookupFailed{};
    jmethodID disconnected{};
    jmethodID notification{};
    jmethodID mtuChanged{};
    jmethodID adapterChanged{};
    // Защищает watcher/token, radio и карту соединений; не JVM и не ОС целиком.
    std::mutex mutex;
    std::atomic<bool> closed{false};
    winrt::Windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementWatcher watcher{
        nullptr
    };
    winrt::event_token receivedToken{};
    winrt::event_token stoppedToken{};
    // Отдельный AEP watcher сообщает системные имена без фиктивных radio-пакетов.
    winrt::Windows::Devices::Enumeration::DeviceWatcher nameWatcher{nullptr};
    winrt::event_token addedToken{};
    winrt::event_token updatedToken{};
    winrt::event_token removedToken{};
    winrt::event_token namesStoppedToken{};
    winrt::Windows::Devices::Radios::Radio radio{nullptr};
    winrt::event_token radioToken{};
    std::map<jlong, std::shared_ptr<Connection>> connections;

    /**
     * Вызов Kotlin с произвольного WinRT-потока. JNIEnv берётся для текущего потока,
     * локальный frame резервирует ёмкость как минимум для 64 ссылок. Исключение callback логируется
     * и очищается: его нельзя вернуть Java-вызывающему, которого у события нет.
     * closed отсекает поздние события; generation дополнительно проверяется в Kotlin.
     *
     * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#pushlocalframe
     */
    template <typename F>
    void call(F function) noexcept
    {
        if (closed.load())
        {
            return;
        }
        JavaEnv attachment(vm);
        auto env = attachment.env;
        if (!env || env->PushLocalFrame(64) != JNI_OK)
        {
            return;
        }
        try
        {
            function(env);
        }
        catch (...)
        {
            OutputDebugStringW(L"GPT BLE: native event callback failed\n");
        }
        if (env->ExceptionCheck())
        {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->PopLocalFrame(nullptr);
    }

    /**
     * Отсоединяет оба сканера и их обработчики; повторный stop безопасен.
     * Ссылки снимаются под mutex, WinRT Stop вызывается после освобождения mutex.
     */
    void stop();

    /**
     * Помечает manager закрытым, останавливает сканеры, закрывает соединения.
     * События, уже прошедшие проверку closed, могут завершаться параллельно.
     * Global reference освобождает деструктор после завершения владельцев shared_ptr.
     */
    void close() noexcept;
    ~Manager();
};
} // namespace gpt::ble::manager::windows
