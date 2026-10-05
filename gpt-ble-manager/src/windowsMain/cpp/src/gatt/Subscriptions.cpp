#include "gatt/Subscriptions.hpp"
#include "bluetooth/BluetoothUtils.hpp"
#include "runtime/WinrtRuntime.hpp"
#include "state/Connection.hpp"
#include "state/Manager.hpp"
#include "state/Registry.hpp"
#include <winrt/Windows.Foundation.Collections.h>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Bluetooth::GenericAttributeProfile;

void configureSubscription(jlong handle, jlong id, jint attribute, jint mode)
{
    auto current = connection(handle, id);
    GattCharacteristic characteristic{nullptr};
    bool registered = false;
    {
        std::lock_guard lock(current->mutex);
        characteristic = current->characteristics.at(attribute);
        registered = current->notifications.contains(attribute);
    }
    // The new subscription can be revoked separately, preserving the old one if the CCCD write fails.
    event_token fresh{};
    if (mode != 0 && !registered)
    {
        std::weak_ptr<Connection> weak = current;
        fresh = characteristic.ValueChanged(
            [weak, attribute](auto&&, GattValueChangedEventArgs const& args)
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
                                owner->notification,
                                value->id,
                                attribute,
                                byteArray(callbackEnv, bytes(args.CharacteristicValue()))
                            );
                        }
                    );
                }
            }
        );
    }
    try
    {
        auto setting = mode == 0   ? GattClientCharacteristicConfigurationDescriptorValue::None
                       : mode == 1 ? GattClientCharacteristicConfigurationDescriptorValue::Notify
                                   : GattClientCharacteristicConfigurationDescriptorValue::Indicate;
        success(await(
            characteristic.WriteClientCharacteristicConfigurationDescriptorAsync(setting),
            deadline()
        ));
    }
    catch (...)
    {
        if (fresh.value)
        {
            characteristic.ValueChanged(fresh);
        }
        throw;
    }
    event_token remove{};
    {
        std::lock_guard lock(current->mutex);
        if (current->closed.load())
        {
            remove = fresh;
        }
        else if (mode == 0 && registered)
        {
            remove = current->notifications.at(attribute).second;
            current->notifications.erase(attribute);
        }
        else if (fresh.value)
        {
            current->notifications.emplace(attribute, std::make_pair(characteristic, fresh));
        }
    }
    if (remove.value)
    {
        characteristic.ValueChanged(remove);
    }
}

} // namespace gpt::ble::manager::windows
