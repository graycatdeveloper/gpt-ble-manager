/**
 * @file
 * Общий контракт ожидания и ошибок Windows Runtime. Это внутренний API DLL.
 */
#pragma once

#include <chrono>
#include <stdexcept>
#include <string>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Foundation.h>

namespace gpt::ble::windows
{
using Clock = std::chrono::steady_clock;

/**
 * Инициализирует COM/WinRT один раз на текущем потоке.
 * Вызывается каждой JNI-точкой входа до обращения к Windows.
 *
 * @see https://learn.microsoft.com/en-us/windows/win32/api/roapi/nf-roapi-roinitialize
 */
void apartment();

/**
 * Общий бюджет обычной GATT-операции — 12 секунд.
 * В составной операции один deadline передаётся всем шагам.
 */
Clock::time_point deadline();

/**
 * Ограниченное синхронное ожидание WinRT на рабочем потоке Kotlin Dispatchers.IO.
 * Отмена запрашивается у ОС при исчерпании бюджета; TIMEOUT: сохраняется для Kotlin.
 * GetResults возвращает значение либо поднимает исходную WinRT-ошибку.
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
 * Сохраняет числовой GATT-статус отдельно от сообщения.
 * JNI преобразует его в WindowsGattException, а Kotlin — в BleException.
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
 * Success пропускается; остальные статусы превращаются в GattFailure.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcommunicationstatus
 */
void success(
    winrt::Windows::Devices::Bluetooth::GenericAttributeProfile::GattCommunicationStatus status,
    std::string const& context = "GATT operation"
);
} // namespace gpt::ble::windows
