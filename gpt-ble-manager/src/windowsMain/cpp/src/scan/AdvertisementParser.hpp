/**
 * @file
 * Декодирование UUID advertising отдельно от событий watcher и JNI.
 */
#pragma once

#include <string>
#include <vector>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>

namespace gpt::ble::manager::windows
{
/**
 * Собирает UUID из ServiceUuids и AD Service Data 0x16/0x20/0x21.
 * Сохраняет порядок и повторы: объединение в множество выполняет Kotlin.
 * Секции короче 2/4/16 байт пропускаются; служебные UUID имеют little-endian формат.
 *
 * @see https://www.bluetooth.com/specifications/assigned-numbers/
 */
std::vector<std::wstring> advertisedServiceUuids(
    winrt::Windows::Devices::Bluetooth::Advertisement::BluetoothLEAdvertisement const& advertisement
);
} // namespace gpt::ble::manager::windows
