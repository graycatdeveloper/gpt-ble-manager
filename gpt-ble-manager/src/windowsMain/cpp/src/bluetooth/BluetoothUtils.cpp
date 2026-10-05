#include "bluetooth/BluetoothUtils.hpp"
#include <iomanip>
#include <sstream>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Storage::Streams;

std::vector<uint8_t> bytes(IBuffer const& buffer)
{
    std::vector<uint8_t> result(buffer.Length());
    if (!result.empty())
    {
        DataReader::FromBuffer(buffer).ReadBytes(result);
    }
    return result;
}

std::wstring uuid(guid const& value)
{
    std::wstring result = to_hstring(value).c_str();
    if (!result.empty() && result.front() == L'{')
    {
        result = result.substr(1, result.size() - 2);
    }
    return result;
}

std::wstring addressString(uint64_t address)
{
    std::wostringstream result;
    result << std::hex << std::uppercase << std::setfill(L'0');
    for (int shift = 40; shift >= 0; shift -= 8)
    {
        if (shift != 40)
        {
            result << L':';
        }
        result << std::setw(2) << ((address >> shift) & 255);
    }
    return result.str();
}
} // namespace gpt::ble::manager::windows
