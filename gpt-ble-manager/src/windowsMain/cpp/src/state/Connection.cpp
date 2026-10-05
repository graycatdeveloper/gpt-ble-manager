#include "state/Connection.hpp"
#include "state/Manager.hpp"
#include <winrt/Windows.Foundation.h>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Bluetooth::GenericAttributeProfile;

void Connection::close() noexcept
{
    if (closed.exchange(true))
    {
        return;
    }
    std::map<int, std::pair<GattCharacteristic, event_token>> oldNotifications;
    std::vector<GattDeviceService> oldServices;
    event_token oldStatus{}, oldMtu{}, oldChanged{};
    {
        std::lock_guard lock(mutex);
        oldNotifications.swap(notifications);
        oldServices.swap(services);
        oldStatus = statusToken;
        oldMtu = mtuToken;
        oldChanged = servicesToken;
        statusToken = {};
        mtuToken = {};
        servicesToken = {};
        characteristics.clear();
        descriptors.clear();
    }
    try
    {
        for (auto& [_, entry] : oldNotifications)
        {
            entry.first.ValueChanged(entry.second);
        }
        if (device && oldStatus.value)
        {
            device.ConnectionStatusChanged(oldStatus);
        }
        if (device && oldChanged.value)
        {
            device.GattServicesChanged(oldChanged);
        }
        if (session && oldMtu.value)
        {
            session.MaxPduSizeChanged(oldMtu);
        }
        for (auto& service : oldServices)
        {
            try
            {
                service.Close();
            }
            catch (...)
            {
            }
        }
        if (session)
        {
            try
            {
                session.MaintainConnection(false);
            }
            catch (...)
            {
            }
            try
            {
                session.Close();
            }
            catch (...)
            {
            }
        }
        if (device)
        {
            try
            {
                device.Close();
            }
            catch (...)
            {
            }
        }
    }
    catch (...)
    {
        // Best-effort cleanup: the destructor must not propagate an exception through JNI.
    }
}

Connection::~Connection()
{
    close();
}

void disconnected(std::shared_ptr<Connection> const& current, const wchar_t* message)
{
    if (auto owner = current->manager.lock())
    {
        owner->call(
            [&](JNIEnv* env)
            {
                env->CallVoidMethod(
                    owner->callback,
                    owner->disconnected,
                    current->id,
                    text(env, message)
                );
            }
        );
    }
}
} // namespace gpt::ble::manager::windows
