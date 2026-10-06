/**
 * @file
 * Connection state is not part of the public C++ API; Kotlin accesses it through a numeric handle.
 */
#pragma once

#include <atomic>
#include <jni.h>
#include <map>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Devices.Bluetooth.h>

namespace gpt::ble::manager::windows
{
struct Manager;

/**
 * Resources for one connection: device/session, ATT handle catalog, and subscriptions. close is
 * idempotent; destroying the last shared_ptr also releases resources. Closed objects may remain
 * alive until a callback already in progress completes.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/cpp-winrt/weak-references
 */
struct Connection
{
    jlong id = 0;
    // The back-reference is weak because Manager already owns Connection through shared_ptr.
    std::weak_ptr<Manager> manager;
    winrt::Windows::Devices::Bluetooth::BluetoothLEDevice device{nullptr};
    winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattSession session{nullptr};
    winrt::Windows::Devices::Bluetooth::BluetoothLEPreferredConnectionParametersRequest
        preferredParameters{nullptr};
    // Protects the catalog and winrt::event_token; WinRT waits run outside this mutex.
    std::mutex mutex;
    std::atomic<bool> closed{false};
    // GattServicesChanged has nothing to invalidate while the catalog is being populated for the first time.
    std::atomic<bool> catalogReady{false};
    std::vector<winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattDeviceService>
        services;
    std::map<int, winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattCharacteristic>
        characteristics;
    std::map<int, winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattDescriptor>
        descriptors;
    std::map<
        int,
        std::pair<
            winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattCharacteristic,
            winrt::event_token>>
        notifications;
    winrt::event_token statusToken{};
    winrt::event_token mtuToken{};
    winrt::event_token servicesToken{};

    /**
     * Detaches collections under the mutex first, then revokes events and closes resources outside
     * it. Preserves the original implementation's best-effort cleanup order.
     */
    void close() noexcept;
    ~Connection();
};

/**
 * Sends a disconnection event to Kotlin, which terminates ManagedConnection. The callback itself
 * does not remove the registry entry.
 */
void disconnected(std::shared_ptr<Connection> const& current, wchar_t const* message);
} // namespace gpt::ble::manager::windows
