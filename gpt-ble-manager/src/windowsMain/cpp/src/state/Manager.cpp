#include "state/Manager.hpp"
#include "state/Connection.hpp"

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth::Advertisement;
using namespace winrt::Windows::Devices::Enumeration;
using namespace winrt::Windows::Devices::Radios;

void Manager::stop()
{
    BluetoothLEAdvertisementWatcher old{nullptr};
    event_token received{}, stopped{};
    DeviceWatcher oldNames{nullptr};
    event_token added{}, updated{}, removed{}, namesStopped{};
    {
        std::lock_guard lock(mutex);
        old = watcher;
        watcher = nullptr;
        received = receivedToken;
        stopped = stoppedToken;
        receivedToken = {};
        stoppedToken = {};
        oldNames = nameWatcher;
        nameWatcher = nullptr;
        added = addedToken;
        updated = updatedToken;
        removed = removedToken;
        namesStopped = namesStoppedToken;
        addedToken = {};
        updatedToken = {};
        removedToken = {};
        namesStoppedToken = {};
    }
    if (old)
    {
        if (received.value)
        {
            old.Received(received);
        }
        if (stopped.value)
        {
            old.Stopped(stopped);
        }
        if (old.Status() == BluetoothLEAdvertisementWatcherStatus::Started)
        {
            old.Stop();
        }
    }
    if (oldNames)
    {
        if (added.value)
        {
            oldNames.Added(added);
        }
        if (updated.value)
        {
            oldNames.Updated(updated);
        }
        if (removed.value)
        {
            oldNames.Removed(removed);
        }
        if (namesStopped.value)
        {
            oldNames.Stopped(namesStopped);
        }
        auto status = oldNames.Status();
        if (status == DeviceWatcherStatus::Started ||
            status == DeviceWatcherStatus::EnumerationCompleted)
        {
            oldNames.Stop();
        }
    }
}

void Manager::close() noexcept
{
    if (closed.exchange(true))
    {
        return;
    }
    try
    {
        stop();
    }
    catch (...)
    {
    }
    std::map<jlong, std::shared_ptr<Connection>> old;
    Radio oldRadio{nullptr};
    event_token oldRadioToken{};
    {
        std::lock_guard lock(mutex);
        old.swap(connections);
        oldRadio = radio;
        oldRadioToken = radioToken;
        radio = nullptr;
        radioToken = {};
    }
    if (oldRadio && oldRadioToken.value)
    {
        oldRadio.StateChanged(oldRadioToken);
    }
    for (auto& [_, connection] : old)
    {
        connection->close();
    }
}

Manager::~Manager()
{
    close();
    JavaEnv attachment(vm);
    if (attachment.env && callback)
    {
        attachment.env->DeleteGlobalRef(callback);
    }
}
} // namespace gpt::ble::manager::windows
