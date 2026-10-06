#include "gatt/ConnectionPreferences.hpp"
#include "state/Connection.hpp"
#include "state/Registry.hpp"
#include <winrt/Windows.Foundation.Metadata.h>

namespace gpt::ble::manager::windows
{
using namespace winrt::Windows::Devices::Bluetooth;

bool supportsPreferredParameters()
{
    return winrt::Windows::Foundation::Metadata::ApiInformation::IsMethodPresent(
        L"Windows.Devices.Bluetooth.BluetoothLEDevice",
        L"RequestPreferredConnectionParameters"
    );
}

// Keep the request alive until replaced or disconnected. Restore Balanced after a bulk transfer.
// https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothledevice.requestpreferredconnectionparameters
jint requestPreferredParameters(jlong handle, jlong id, jint mode)
{
    auto current = connection(handle, id);
    if (!supportsPreferredParameters())
    {
        throw std::runtime_error("Preferred connection parameters require Windows 11");
    }
    auto parameters = mode == 1   ? BluetoothLEPreferredConnectionParameters::ThroughputOptimized()
                      : mode == 2 ? BluetoothLEPreferredConnectionParameters::PowerOptimized()
                                  : BluetoothLEPreferredConnectionParameters::Balanced();
    auto request = current->device.RequestPreferredConnectionParameters(parameters);
    const auto status = request.Status();
    if (status != BluetoothLEPreferredConnectionParametersRequestStatus::Success)
    {
        request.Close();
        return static_cast<jint>(status);
    }
    BluetoothLEPreferredConnectionParametersRequest previous{nullptr};
    {
        std::lock_guard lock(current->mutex);
        if (current->closed.load())
        {
            request.Close();
            throw std::runtime_error("Connection closed while applying preferences");
        }
        previous = std::exchange(current->preferredParameters, request);
    }
    if (previous)
    {
        previous.Close();
    }
    return static_cast<jint>(status);
}
} // namespace gpt::ble::manager::windows
