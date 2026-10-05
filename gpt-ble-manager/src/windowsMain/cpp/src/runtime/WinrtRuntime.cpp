#include "runtime/WinrtRuntime.hpp"
#include <roapi.h>
#include <windows.h>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Devices::Bluetooth::GenericAttributeProfile;

namespace
{
struct Apartment
{
    // RPC_E_CHANGED_MODE preserves the thread's existing apartment model; we do not uninitialize it.
    HRESULT result = RoInitialize(RO_INIT_MULTITHREADED);

    Apartment()
    {
        if (FAILED(result) && result != RPC_E_CHANGED_MODE)
        {
            check_hresult(result);
        }
    }

    ~Apartment()
    {
        if (SUCCEEDED(result))
        {
            RoUninitialize();
        }
    }
};

} // namespace

void apartment()
{
    thread_local Apartment current;
}

Clock::time_point deadline()
{
    return Clock::now() + std::chrono::seconds(12);
}

GattFailure::GattFailure(GattCommunicationStatus value, std::string const& message)
    : std::runtime_error(message), status(value)
{
}

void success(GattCommunicationStatus status, std::string const& context)
{
    if (status != GattCommunicationStatus::Success)
    {
        const auto reason = status == GattCommunicationStatus::AccessDenied    ? "AccessDenied"
                            : status == GattCommunicationStatus::Unreachable   ? "Unreachable"
                            : status == GattCommunicationStatus::ProtocolError ? "ProtocolError"
                                                                               : "Unknown";
        throw GattFailure(
            status,
            context + " failed: " + reason +
                " (status=" + std::to_string(static_cast<int>(status)) + ")"
        );
    }
}
} // namespace gpt::ble::manager::windows
