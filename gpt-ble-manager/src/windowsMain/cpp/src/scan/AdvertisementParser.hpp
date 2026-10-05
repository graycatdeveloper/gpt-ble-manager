/**
 * @file
 * Decodes advertising UUIDs independently of watcher events and JNI.
 */
#pragma once

#include <string>
#include <vector>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>

namespace gpt::ble::manager::windows
{
/**
 * Collects UUIDs from ServiceUuids and AD Service Data 0x16/0x20/0x21. Preserves order and
 * duplicates; Kotlin merges them into a set. Skips sections shorter than 2/4/16 bytes; service
 * UUIDs use little-endian encoding.
 *
 * @see https://www.bluetooth.com/specifications/assigned-numbers/
 */
std::vector<std::wstring> advertisedServiceUuids(
    winrt::Windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisement const& advertisement
);
} // namespace gpt::ble::manager::windows
