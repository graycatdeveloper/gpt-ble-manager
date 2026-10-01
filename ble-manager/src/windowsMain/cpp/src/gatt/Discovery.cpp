#include "gatt/Discovery.hpp"
#include "bluetooth/BluetoothUtils.hpp"
#include "runtime/WinrtRuntime.hpp"
#include "state/Connection.hpp"
#include "state/Manager.hpp"
#include "state/Registry.hpp"
#include <winrt/Windows.Foundation.Collections.h>

namespace gpt::ble::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Bluetooth::GenericAttributeProfile;

jbyteArray readGattDeviceName(JNIEnv* env, jlong handle, jlong id)
{
    auto current = connection(handle, id);
    std::vector<GattDeviceService> services;
    {
        std::lock_guard lock(current->mutex);
        services = current->services;
    }
    const guid genericAccess{0x1800, 0, 0x1000, {0x80, 0, 0, 0x80, 0x5f, 0x9b, 0x34, 0xfb}};
    const guid deviceName{0x2a00, 0, 0x1000, {0x80, 0, 0, 0x80, 0x5f, 0x9b, 0x34, 0xfb}};
    auto limit = deadline();
    for (auto const& service : services)
    {
        if (service.Uuid() != genericAccess)
        {
            continue;
        }
        auto chars = await(
            service.GetCharacteristicsForUuidAsync(deviceName, BluetoothCacheMode::Uncached),
            limit
        );
        success(chars.Status(), "Find Generic Access / Device Name");
        for (auto const& characteristic : chars.Characteristics())
        {
            if ((characteristic.CharacteristicProperties() & GattCharacteristicProperties::Read) ==
                GattCharacteristicProperties::None)
            {
                continue;
            }
            auto value = await(characteristic.ReadValueAsync(BluetoothCacheMode::Uncached), limit);
            success(value.Status(), "Read Generic Access / Device Name");
            return byteArray(env, bytes(value.Value()));
        }
    }
    return nullptr;
}

jobjectArray discoverGatt(JNIEnv* env, jlong handle, jlong id)
{
    auto current = connection(handle, id);
    std::vector<GattDeviceService> services;
    {
        std::lock_guard lock(current->mutex);
        services = current->services;
    }
    std::vector<std::wstring> records;
    std::map<int, GattCharacteristic> characteristics;
    std::map<int, GattDescriptor> descriptors;
    // Один общий бюджет: число сервисов не умножает 12-секундный timeout.
    auto limit = deadline();
    for (auto const& service : services)
    {
        auto serviceId = std::to_wstring(service.AttributeHandle());
        records.push_back(L"S|" + serviceId + L"|" + uuid(service.Uuid()));
        auto chars = await(service.GetCharacteristicsAsync(BluetoothCacheMode::Uncached), limit);
        success(
            chars.Status(),
            "Discover characteristics for service " + to_string(uuid(service.Uuid()))
        );
        for (auto const& characteristic : chars.Characteristics())
        {
            int charHandle = characteristic.AttributeHandle();
            auto charId = std::to_wstring(charHandle);
            records.push_back(
                L"C|" + serviceId + L"|" + charId + L"|" + uuid(characteristic.Uuid()) + L"|" +
                std::to_wstring(static_cast<int>(characteristic.CharacteristicProperties()))
            );
            characteristics.emplace(charHandle, characteristic);
            auto descs =
                await(characteristic.GetDescriptorsAsync(BluetoothCacheMode::Uncached), limit);
            success(
                descs.Status(),
                "Discover descriptors for characteristic " + to_string(uuid(characteristic.Uuid()))
            );
            for (auto const& desc : descs.Descriptors())
            {
                int descHandle = desc.AttributeHandle();
                records.push_back(
                    L"D|" + charId + L"|" + std::to_wstring(descHandle) + L"|" + uuid(desc.Uuid())
                );
                descriptors.emplace(descHandle, desc);
            }
        }
    }
    {
        std::lock_guard lock(current->mutex);
        if (current->closed.load())
        {
            throw std::runtime_error("Connection ended during discovery");
        }
        // Публикация всех handles происходит только после полного успеха discovery.
        current->characteristics = std::move(characteristics);
        current->descriptors = std::move(descriptors);
        current->catalogReady.store(true);
    }
    return strings(env, records);
}

} // namespace gpt::ble::windows
