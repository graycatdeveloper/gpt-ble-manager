#include "runtime/WinrtRuntime.hpp"
#include <cwchar>
#include <roapi.h>
#include <windows.h>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Devices::Bluetooth::GenericAttributeProfile;

namespace
{
thread_local Clock::time_point operationDeadline{};

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

std::wstring describeHresult(int32_t code)
{
    wchar_t prefix[32];
    swprintf_s(prefix, L"HRESULT 0x%08X: ", static_cast<unsigned int>(code));
    wchar_t buffer[2048]{};
    const auto length = FormatMessageW(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr,
        static_cast<DWORD>(code),
        MAKELANGID(LANG_ENGLISH, SUBLANG_ENGLISH_US),
        buffer,
        static_cast<DWORD>(std::size(buffer)),
        nullptr
    );
    std::wstring message = prefix;
    if (length > 0)
    {
        message.append(buffer, length);
        while (!message.empty() &&
               (message.back() == L'\r' || message.back() == L'\n' || message.back() == L' '))
        {
            message.pop_back();
        }
    }
    else
    {
        // Some WinRT/provider HRESULTs have no system message or no English resource installed.
        message +=
            L"Windows Bluetooth operation failed; no English system description is available.";
    }
    return message;
}

Clock::time_point deadline()
{
    return operationDeadline == Clock::time_point{} ? Clock::now() + std::chrono::seconds(12)
                                                    : operationDeadline;
}

OperationBudget::OperationBudget(int64_t milliseconds) : previous(operationDeadline)
{
    if (milliseconds <= 0 || milliseconds > 120000)
    {
        throw std::invalid_argument("Invalid operation timeout");
    }
    operationDeadline = Clock::now() + std::chrono::milliseconds(milliseconds);
}

OperationBudget::~OperationBudget()
{
    operationDeadline = previous;
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
