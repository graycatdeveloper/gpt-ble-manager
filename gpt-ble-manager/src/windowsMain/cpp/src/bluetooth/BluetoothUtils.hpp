/**
 * @file
 * Преобразования значений BLE; не открывают устройства и не выполняют GATT-запросы.
 */
#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <winrt/Windows.Storage.Streams.h>

namespace gpt::ble::manager::windows
{
/**
 * Копия IBuffer: DataReader читает ровно Length байт, включая нулевой размер.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.storage.streams.datareader.frombuffer
 */
std::vector<uint8_t> bytes(winrt::Windows::Storage::Streams::IBuffer const& buffer);

/**
 * UUID без внешних фигурных скобок; формат потребляет Kotlin BleUuid.
 */
std::wstring uuid(winrt::guid const& value);

/**
 * Шесть байтов Bluetooth-адреса в верхнем регистре с двоеточиями.
 * Порядок байтов не совпадает с little-endian полями advertising.
 */
std::wstring addressString(uint64_t address);
} // namespace gpt::ble::manager::windows
