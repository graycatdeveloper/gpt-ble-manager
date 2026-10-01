#include "adapter/Adapter.hpp"
#include "runtime/WinrtRuntime.hpp"
#include "state/Manager.hpp"
#include "state/Registry.hpp"

namespace gpt::ble::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Radios;

jint queryAdapterState(jlong handle)
{
    auto owner = manager(handle);
    auto limit = deadline();
    auto adapter = await(BluetoothAdapter::GetDefaultAsync(), limit);
    if (!adapter || !adapter.IsLowEnergySupported())
    {
        return 3;
    }
    auto radio = await(adapter.GetRadioAsync(), limit);
    if (!radio)
    {
        return 2;
    }
    {
        std::lock_guard lock(owner->mutex);
        if (!owner->closed.load() && !owner->radio)
        {
            std::weak_ptr<Manager> weak = owner;
            owner->radio = radio;
            owner->radioToken = radio.StateChanged(
                [weak](Radio const& value, auto&&)
                {
                    if (auto target = weak.lock())
                    {
                        target->call(
                            [&](JNIEnv* callbackEnv)
                            {
                                callbackEnv->CallVoidMethod(
                                    target->callback,
                                    target->adapterChanged,
                                    static_cast<jint>(value.State() == RadioState::On ? 0 : 1)
                                );
                            }
                        );
                    }
                }
            );
        }
    }
    return radio.State() == RadioState::On ? 0 : 1;
}

} // namespace gpt::ble::windows
