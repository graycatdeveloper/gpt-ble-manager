#include "gatt/ConnectionOperations.hpp"
#include "runtime/WinrtRuntime.hpp"
#include "state/Connection.hpp"
#include "state/Manager.hpp"
#include "state/Registry.hpp"
#include <algorithm>
#include <winrt/Windows.Foundation.Collections.h>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Bluetooth::GenericAttributeProfile;

jlong openConnection(JNIEnv* env, jlong handle, jstring address, jint type, jlong timeout)
{
    auto owner = manager(handle);
    auto input = text(env, address);
    input.erase(std::remove(input.begin(), input.end(), L':'), input.end());
    auto numeric = std::stoull(input, nullptr, 16);
    auto limit = Clock::now() + std::chrono::milliseconds(timeout);
    auto current = std::make_shared<Connection>();
    current->id = allocateHandle();
    current->manager = owner;
    current->device =
        type == 0 ? await(BluetoothLEDevice::FromBluetoothAddressAsync(numeric), limit)
                  : await(
                        BluetoothLEDevice::FromBluetoothAddressAsync(
                            numeric,
                            type == 2 ? BluetoothAddressType::Random : BluetoothAddressType::Public
                        ),
                        limit
                    );
    if (!current->device)
    {
        throw std::runtime_error("Windows cannot resolve this BLE address; scan for it first");
    }
    current->session =
        await(GattSession::FromDeviceIdAsync(current->device.BluetoothDeviceId()), limit);
    if (!current->session)
    {
        throw std::runtime_error("Windows could not create a GATT session");
    }
    current->session.MaintainConnection(true);
    auto result = await(current->device.GetGattServicesAsync(BluetoothCacheMode::Uncached), limit);
    success(result.Status());
    for (auto const& service : result.Services())
    {
        current->services.push_back(service);
    }
    if (current->device.ConnectionStatus() != BluetoothConnectionStatus::Connected)
    {
        throw std::runtime_error("Device is not connected");
    }
    {
        std::lock_guard lock(owner->mutex);
        if (owner->closed.load())
        {
            throw std::runtime_error("Manager was closed during connection");
        }
        owner->connections.emplace(current->id, current);
    }
    return current->id;
}

jboolean monitorConnection(jlong handle, jlong id)
{
    auto current = connection(handle, id);
    std::weak_ptr<Connection> weak = current;
    auto status = current->device.ConnectionStatusChanged(
        [weak](BluetoothLEDevice const& device, auto&&)
        {
            if (auto value = weak.lock();
                value && !value->closed.load() &&
                device.ConnectionStatus() == BluetoothConnectionStatus::Disconnected)
            {
                disconnected(value, L"Remote device disconnected");
            }
        }
    );
    auto changed = current->device.GattServicesChanged(
        [weak](auto&&, auto&&)
        {
            // Windows raises this while populating the first uncached GATT catalog.
            // There are no published handles to invalidate until discovery completes.
            if (auto value = weak.lock();
                value && !value->closed.load() && value->catalogReady.load())
            {
                disconnected(value, L"GATT database changed; reconnect to rediscover handles");
            }
        }
    );
    auto mtuToken = current->session.MaxPduSizeChanged(
        [weak](GattSession const& session, auto&&)
        {
            auto value = weak.lock();
            if (!value || value->closed.load())
            {
                return;
            }
            if (auto owner = value->manager.lock())
            {
                owner->call(
                    [&](JNIEnv* callbackEnv)
                    {
                        callbackEnv->CallVoidMethod(
                            owner->callback,
                            owner->mtuChanged,
                            value->id,
                            static_cast<jint>(session.MaxPduSize())
                        );
                    }
                );
            }
        }
    );
    {
        std::lock_guard lock(current->mutex);
        if (!current->closed.load())
        {
            current->statusToken = status;
            current->servicesToken = changed;
            current->mtuToken = mtuToken;
        }
        else
        {
            current->device.ConnectionStatusChanged(status);
            current->device.GattServicesChanged(changed);
            current->session.MaxPduSizeChanged(mtuToken);
            return JNI_FALSE;
        }
    }
    return current->device.ConnectionStatus() == BluetoothConnectionStatus::Connected ? JNI_TRUE
                                                                                      : JNI_FALSE;
}

void closeConnection(jlong handle, jlong id)
{
    auto owner = manager(handle);
    std::shared_ptr<Connection> current;
    {
        std::lock_guard lock(owner->mutex);
        auto it = owner->connections.find(id);
        if (it == owner->connections.end())
        {
            return;
        }
        current = it->second;
        owner->connections.erase(it);
    }
    current->close();
}

jint currentMtu(jlong handle, jlong id)
{
    return connection(handle, id)->session.MaxPduSize();
}

} // namespace gpt::ble::manager::windows
