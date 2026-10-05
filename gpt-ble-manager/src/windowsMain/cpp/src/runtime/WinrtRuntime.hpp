/**
 * @file
 * Shared Windows Runtime waiting and error contracts. This is an internal DLL API.
 */
#pragma once

#include <chrono>
#include <stdexcept>
#include <string>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Foundation.h>

namespace gpt::ble::manager::windows
{
using Clock = std::chrono::steady_clock;

/**
 * Initializes COM/WinRT once on the current thread. Every JNI entry point calls this before
 * accessing Windows APIs.
 *
 * @see https://learn.microsoft.com/en-us/windows/win32/api/roapi/nf-roapi-roinitialize
 */
void apartment();

/**
 * The total budget for a regular GATT operation is 12 seconds. All steps of a composite operation
 * share the same deadline.
 */
Clock::time_point deadline();

/**
 * Bounded synchronous WinRT wait on a Kotlin Dispatchers.IO worker thread. Requests OS
 * cancellation when the budget expires; preserves the TIMEOUT: prefix for Kotlin. GetResults
 * returns a value or raises the original WinRT error.
 *
 * @see https://learn.microsoft.com/en-us/windows/apps/develop/cpp-winrt/concurrency
 */
template <typename T>
auto await(T const& operation, Clock::time_point deadline)
{
    auto remaining = deadline - Clock::now();
    if (remaining <= Clock::duration::zero() ||
        operation.wait_for(
            std::chrono::duration_cast<winrt::Windows::Foundation::TimeSpan>(remaining)
        ) == winrt::Windows::Foundation::AsyncStatus::Started)
    {
        operation.Cancel();
        throw std::runtime_error("TIMEOUT: Windows Bluetooth operation timed out");
    }
    return operation.GetResults();
}

/**
 * Stores the numeric GATT status separately from the message. JNI converts it to
 * WindowsGattException, and Kotlin converts that to BleException.
 */
struct GattFailure : std::runtime_error
{
    winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattCommunicationStatus status;
    GattFailure(
        winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattCommunicationStatus value,
        std::string const& message
    );
};

/**
 * Passes Success through; converts all other statuses to GattFailure.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcommunicationstatus
 */
void success(
    winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattCommunicationStatus status,
    std::string const& context = "GATT operation"
);
} // namespace gpt::ble::manager::windows
