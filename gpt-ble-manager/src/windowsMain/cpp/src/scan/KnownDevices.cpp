#include "scan/KnownDevices.hpp"
#include "state/Manager.hpp"
#include <utility>
#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Foundation.Collections.h>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Enumeration;

// Updated содержит только изменения: сохраняем полную DeviceInformation между событиями.
struct KnownDevices
{
    std::mutex mutex;
    std::map<std::wstring, DeviceInformation> devices;
};

void nameLookupFailed(
    std::weak_ptr<Manager> const& weak,
    jlong generation,
    std::wstring_view message
)
{
    if (auto owner = weak.lock())
    {
        owner->call(
            [&](JNIEnv* env)
            {
                env->CallVoidMethod(
                    owner->callback,
                    owner->nameLookupFailed,
                    generation,
                    text(env, message)
                );
            }
        );
    }
}

// Отсутствующий AEP-адрес остаётся пустым: такую запись нельзя объединить с advertising.
static std::pair<hstring, hstring> knownDetails(DeviceInformation const& info)
{
    auto properties = info.Properties();
    auto address =
        properties.HasKey(L"System.Devices.Aep.DeviceAddress")
            ? unbox_value_or<hstring>(properties.Lookup(L"System.Devices.Aep.DeviceAddress"), L"")
            : hstring{};
    return {address, info.Name()};
}

// Отдельный callback переносит только метаданные; RSSI и факт обнаружения не выдумываются.
static void publishKnown(
    std::weak_ptr<Manager> const& weak,
    jlong generation,
    std::pair<hstring, hstring> const& details
)
{
    if (details.first.empty())
    {
        return;
    }
    if (auto owner = weak.lock())
    {
        owner->call(
            [&](JNIEnv* env)
            {
                env->CallVoidMethod(
                    owner->callback,
                    owner->knownDevice,
                    generation,
                    text(env, details.first.c_str()),
                    text(env, details.second.c_str())
                );
            }
        );
    }
}

void startNameWatcher(std::shared_ptr<Manager> const& owner, jlong generation)
{
    std::weak_ptr<Manager> weak = owner;
    // AEP даёт системные имена; GATT-подключения для этого не открываются.
    auto watcher = DeviceInformation::CreateWatcher(
        BluetoothLEDevice::GetDeviceSelector(),
        {L"System.Devices.Aep.DeviceAddress"},
        DeviceInformationKind::AssociationEndpoint
    );
    auto known = std::make_shared<KnownDevices>();
    auto added = watcher.Added(
        [weak, known, generation](auto&&, DeviceInformation const& info)
        {
            try
            {
                std::pair<hstring, hstring> details;
                {
                    std::lock_guard lock(known->mutex);
                    known->devices.insert_or_assign(std::wstring(info.Id()), info);
                    details = knownDetails(info);
                }
                publishKnown(weak, generation, details);
            }
            catch (hresult_error const& e)
            {
                nameLookupFailed(weak, generation, e.message().c_str());
            }
        }
    );
    auto updated = watcher.Updated(
        [weak, known, generation](auto&&, DeviceInformationUpdate const& update)
        {
            try
            {
                std::pair<hstring, hstring> details;
                {
                    std::lock_guard lock(known->mutex);
                    auto it = known->devices.find(std::wstring(update.Id()));
                    if (it == known->devices.end())
                    {
                        return;
                    }
                    it->second.Update(update);
                    details = knownDetails(it->second);
                }
                publishKnown(weak, generation, details);
            }
            catch (hresult_error const& e)
            {
                nameLookupFailed(weak, generation, e.message().c_str());
            }
        }
    );
    auto removed = watcher.Removed(
        [known](auto&&, DeviceInformationUpdate const& update)
        {
            std::lock_guard lock(known->mutex);
            known->devices.erase(std::wstring(update.Id()));
        }
    );
    auto stopped = watcher.Stopped(
        [weak, generation](DeviceWatcher const& sender, auto&&)
        {
            if (sender.Status() == DeviceWatcherStatus::Aborted)
            {
                nameLookupFailed(weak, generation, L"Windows device-name discovery aborted");
            }
        }
    );
    {
        std::lock_guard lock(owner->mutex);
        if (owner->closed.load())
        {
            throw std::runtime_error("Manager is closed");
        }
        owner->nameWatcher = watcher;
        owner->addedToken = added;
        owner->updatedToken = updated;
        owner->removedToken = removed;
        owner->namesStoppedToken = stopped;
    }
    watcher.Start();
}
} // namespace gpt::ble::manager::windows
