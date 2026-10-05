#include "bluetooth/BluetoothUtils.hpp"
#include "runtime/WinrtRuntime.hpp"
#include "scan/AdvertisementParser.hpp"

#include <iostream>
#include <stdexcept>
#include <string_view>
#include <winrt/Windows.Foundation.Collections.h>

namespace
{
using namespace gpt::ble::manager::windows;
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth::Advertisement;
using namespace winrt::Windows::Devices::Bluetooth::GenericAttributeProfile;
using namespace winrt::Windows::Storage::Streams;

// Тестовые пакеты строятся в памяти через WinRT: ни radio, ни GATT не открываются.
// Проверки работают в Release: assert не используется, поскольку NDEBUG отключает его.
void require(bool condition, char const* message)
{
    if (!condition)
    {
        throw std::runtime_error(message);
    }
}

void appendSection(
    BluetoothLEAdvertisement const& advertisement,
    uint8_t type,
    std::vector<uint8_t> const& payload
)
{
    DataWriter writer;
    writer.WriteBytes(payload);
    advertisement.DataSections().Append(
        BluetoothLEAdvertisementDataSection(type, writer.DetachBuffer())
    );
}

void addressFormattingPreservesLeadingZeroes()
{
    require(addressString(0x000102030405) == L"00:01:02:03:04:05", "Address padding/order changed");
    require(addressString(0xAABBCCDDEE01) == L"AA:BB:CC:DD:EE:01", "Address case changed");
}

void bufferCopyPreservesAllByteValues()
{
    DataWriter empty;
    require(bytes(empty.DetachBuffer()).empty(), "Empty IBuffer changed");
    DataWriter writer;
    const std::vector<uint8_t> expected{0, 1, 127, 128, 255};
    writer.WriteBytes(expected);
    require(bytes(writer.DetachBuffer()) == expected, "IBuffer byte values changed");
}

void serviceDataDecodesEveryUuidWidth()
{
    BluetoothLEAdvertisement advertisement;
    appendSection(advertisement, 0x16, {0x0f, 0x18, 0xff});
    appendSection(advertisement, 0x20, {0x78, 0x56, 0x34, 0x12, 0xaa});
    appendSection(
        advertisement,
        0x21,
        {0xff,
         0xee,
         0xdd,
         0xcc,
         0xbb,
         0xaa,
         0x99,
         0x88,
         0x77,
         0x66,
         0x55,
         0x44,
         0x33,
         0x22,
         0x11,
         0x00,
         0x42}
    );
    const std::vector<std::wstring> expected{
        L"0000180f-0000-1000-8000-00805f9b34fb",
        L"12345678-0000-1000-8000-00805f9b34fb",
        L"00112233-4455-6677-8899-aabbccddeeff"
    };
    require(
        advertisedServiceUuids(advertisement) == expected,
        "Service Data UUID byte order changed"
    );
}

void malformedAndUnrelatedSectionsAreIgnored()
{
    BluetoothLEAdvertisement advertisement;
    appendSection(advertisement, 0x16, {0x18});
    appendSection(advertisement, 0x20, {1, 2, 3});
    appendSection(advertisement, 0x21, std::vector<uint8_t>(15, 0));
    appendSection(advertisement, 0xff, std::vector<uint8_t>(16, 0));
    require(advertisedServiceUuids(advertisement).empty(), "Malformed section interpreted as UUID");
}

void explicitUuidsAndRepeatedServiceDataKeepTheirOrder()
{
    BluetoothLEAdvertisement advertisement;
    const guid battery{L"0000180f-0000-1000-8000-00805f9b34fb"};
    advertisement.ServiceUuids().Append(battery);
    appendSection(advertisement, 0x16, {0x0f, 0x18});
    appendSection(advertisement, 0x16, {0x0f, 0x18});
    require(
        advertisedServiceUuids(advertisement) == std::vector<std::wstring>(3, uuid(battery)),
        "Parser deduplicated or lost service UUIDs"
    );
}

void gattErrorsKeepStatusAndContext()
{
    success(GattCommunicationStatus::Success);
    try
    {
        success(GattCommunicationStatus::AccessDenied, "Test FFB0");
        throw std::runtime_error("AccessDenied did not throw");
    }
    catch (GattFailure const& failure)
    {
        require(failure.status == GattCommunicationStatus::AccessDenied, "GattFailure lost status");
        require(
            std::string_view(failure.what()) == "Test FFB0 failed: AccessDenied (status=3)",
            "GATT error contract changed"
        );
    }
}
} // namespace

int main()
{
    try
    {
        apartment();
        addressFormattingPreservesLeadingZeroes();
        bufferCopyPreservesAllByteValues();
        serviceDataDecodesEveryUuidWidth();
        malformedAndUnrelatedSectionsAreIgnored();
        explicitUuidsAndRepeatedServiceDataKeepTheirOrder();
        gattErrorsKeepStatusAndContext();
        std::cout << "6 native contract checks passed\n";
        return 0;
    }
    catch (winrt::hresult_error const& error)
    {
        std::cerr << winrt::to_string(error.message()) << '\n';
    }
    catch (std::exception const& error)
    {
        std::cerr << error.what() << '\n';
    }
    return 1;
}
