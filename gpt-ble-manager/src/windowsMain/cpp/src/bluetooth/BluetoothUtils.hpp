/**
 * @file
 * BLE value conversions; these do not open devices or perform GATT requests.
 */
#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <winrt/Windows.Storage.Streams.h>

namespace gpt::ble::manager::windows
{
/**
 * Copies an IBuffer: DataReader reads exactly Length bytes, including the zero-length case.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.storage.streams.datareader.frombuffer
 */
std::vector<uint8_t> bytes(winrt::Windows::Storage::Streams::IBuffer const& buffer);

/**
 * UUID without surrounding braces, in the format consumed by Kotlin BleUuid.
 */
std::wstring uuid(winrt::guid const& value);

/**
 * Six Bluetooth address bytes in uppercase, separated by colons. The byte order differs from
 * little-endian advertising fields.
 */
std::wstring addressString(uint64_t address);
} // namespace gpt::ble::manager::windows
