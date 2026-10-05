/**
 * @file
 * Состояние соединения не является публичным C++ API; Kotlin обращается по числовому handle.
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
 * Ресурсы одного соединения: device/session, каталог ATT handles, подписки.
 * close идемпотентен; удаление последней shared_ptr также освобождает ресурсы.
 * Закрытые объекты могут дожить до завершения уже начатого callback.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/cpp-winrt/weak-references
 */
struct Connection
{
    jlong id = 0;
    // Обратная ссылка слабая: Manager уже владеет Connection через shared_ptr.
    std::weak_ptr<Manager> manager;
    winrt::Windows::Devices::Bluetooth::BluetoothLEDevice device{nullptr};
    winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattSession session{nullptr};
    // Защищает каталог и winrt::event_token; ожидание WinRT выполняется вне этого mutex.
    std::mutex mutex;
    std::atomic<bool> closed{false};
    // GattServicesChanged при первом заполнении каталога ещё нечего инвалидировать.
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
     * Сначала отсоединяет коллекции под mutex, затем отзывает события и закрывает
     * ресурсы вне mutex. Порядок best-effort cleanup сохранён из исходной реализации.
     */
    void close() noexcept;
    ~Connection();
};

/**
 * Отправляет событие разрыва в Kotlin, где завершается ManagedConnection.
 * Сам callback не удаляет запись из реестра.
 */
void disconnected(std::shared_ptr<Connection> const& current, wchar_t const* message);
} // namespace gpt::ble::manager::windows
