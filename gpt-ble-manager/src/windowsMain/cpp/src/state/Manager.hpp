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
 * Owns the resources of one WindowsBleManager. Each instance has its own scanners, callback, and
 * connections.
 */
struct Manager : std::enable_shared_from_this<Manager>
{
    JavaVM* vm = nullptr;
    // JNI global reference: outlives the original create() call.
    jobject callback = nullptr;
    jmethodID advertisement{};
    jmethodID scanStopped{};
    jmethodID knownDevice{};
    jmethodID nameLookupFailed{};
    jmethodID disconnected{};
    jmethodID notification{};
    jmethodID mtuChanged{};
    jmethodID adapterChanged{};
    // Protects watchers/tokens, the radio, and the connection map, not the entire JVM or OS.
    std::mutex mutex;
    std::atomic<bool> closed{false};
    winrt::Windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisementWatcher watcher{
        nullptr
    };
    winrt::event_token receivedToken{};
    winrt::event_token stoppedToken{};
    // A separate AEP watcher reports system names without fabricating radio packets.
    winrt::Windows::Devices::Enumeration::DeviceWatcher nameWatcher{nullptr};
    winrt::event_token addedToken{};
    winrt::event_token updatedToken{};
    winrt::event_token removedToken{};
    winrt::event_token namesStoppedToken{};
    winrt::Windows::Devices::Radios::Radio radio{nullptr};
    winrt::event_token radioToken{};
    std::map<jlong, std::shared_ptr<Connection>> connections;

    /**
     * Calls Kotlin from an arbitrary WinRT thread. Obtains JNIEnv for the current thread; the
     * local frame reserves capacity for at least 64 references. Logs and clears callback
     * exceptions because the event has no Java caller to receive them. closed rejects late events;
     * Kotlin additionally checks generation.
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
     * Detaches both scanners and their handlers; repeated stop calls are safe. Releases references
     * under the mutex, then calls WinRT Stop after releasing the mutex.
     */
    void stop();

    /**
     * Marks the manager closed, stops scanners, and closes connections. Events that already passed
     * the closed check may complete concurrently. The destructor releases the global reference
     * after the last shared_ptr owner is gone.
     */
    void close() noexcept;
    ~Manager();
};
} // namespace gpt::ble::manager::windows
