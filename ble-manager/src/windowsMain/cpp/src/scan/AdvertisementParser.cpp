#include "scan/AdvertisementParser.hpp"
#include "bluetooth/BluetoothUtils.hpp"
#include <cwchar>
#include <winrt/Windows.Foundation.Collections.h>

namespace gpt::ble::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth::Advertisement;

std::vector<std::wstring> advertisedServiceUuids(BluetoothLEAdvertisement const& advertisement)
{
    std::vector<std::wstring> uuids;
    for (auto const& item : advertisement.ServiceUuids())
    {
        uuids.push_back(uuid(item));
    }
    // Service Data содержит UUID даже при отсутствии отдельного списка ServiceUuids.
    for (auto const& section : advertisement.DataSections())
    {
        const auto type = section.DataType();
        auto data = bytes(section.Data());
        if (type == 0x16 && data.size() >= 2)
        {
            guid value{
                static_cast<uint32_t>(data[0] | data[1] << 8),
                0,
                0x1000,
                {0x80, 0, 0, 0x80, 0x5f, 0x9b, 0x34, 0xfb}
            };
            uuids.push_back(uuid(value));
        }
        else if (type == 0x20 && data.size() >= 4)
        {
            uint32_t first = static_cast<uint32_t>(data[0]) | static_cast<uint32_t>(data[1]) << 8 |
                             static_cast<uint32_t>(data[2]) << 16 |
                             static_cast<uint32_t>(data[3]) << 24;
            guid value{first, 0, 0x1000, {0x80, 0, 0, 0x80, 0x5f, 0x9b, 0x34, 0xfb}};
            uuids.push_back(uuid(value));
        }
        else if (type == 0x21 && data.size() >= 16)
        {
            wchar_t full[37];
            swprintf_s(
                full,
                L"%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                data[15],
                data[14],
                data[13],
                data[12],
                data[11],
                data[10],
                data[9],
                data[8],
                data[7],
                data[6],
                data[5],
                data[4],
                data[3],
                data[2],
                data[1],
                data[0]
            );
            uuids.emplace_back(full);
        }
    }

    return uuids;
}
} // namespace gpt::ble::windows
