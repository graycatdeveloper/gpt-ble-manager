#include "scan/Scanner.hpp"
#include "bluetooth/BluetoothUtils.hpp"
#include "scan/AdvertisementParser.hpp"
#include "scan/KnownDevices.hpp"
#include "state/Manager.hpp"
#include "state/Registry.hpp"
#include <winrt/Windows.Foundation.Collections.h>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Bluetooth::Advertisement;

void startScan(jlong handle, jlong generation)
{
    auto owner = manager(handle);
    owner->stop();
    BluetoothLEAdvertisementWatcher watcher;
    watcher.ScanningMode(BluetoothLEScanningMode::Active);
    std::weak_ptr<Manager> weak = owner;
    auto received = watcher.Received(
        [weak, generation](auto&&, BluetoothLEAdvertisementReceivedEventArgs const& args)
        {
            auto target = weak.lock();
            if (!target)
            {
                return;
            }
            target->call(
                [&](JNIEnv* callbackEnv)
                {
                    auto advertisement = args.Advertisement();
                    auto uuids = advertisedServiceUuids(advertisement);
                    auto arrayClass = callbackEnv->FindClass("[B");
                    auto manufacturers = advertisement.ManufacturerData();
                    auto manufacturerArray = callbackEnv->NewObjectArray(
                        static_cast<jsize>(manufacturers.Size()),
                        arrayClass,
                        nullptr
                    );
                    for (uint32_t i = 0; i < manufacturers.Size(); ++i)
                    {
                        auto entry = manufacturers.GetAt(i);
                        // The first two bytes are CompanyId in little-endian order; Kotlin separates them from the payload.
                        std::vector<uint8_t> data{
                            static_cast<uint8_t>(entry.CompanyId() & 255),
                            static_cast<uint8_t>(entry.CompanyId() >> 8)
                        };
                        auto payload = bytes(entry.Data());
                        data.insert(data.end(), payload.begin(), payload.end());
                        auto item = byteArray(callbackEnv, data);
                        callbackEnv
                            ->SetObjectArrayElement(manufacturerArray, static_cast<jsize>(i), item);
                        callbackEnv->DeleteLocalRef(item);
                    }
                    const auto type = args.AdvertisementType();
                    const bool connectable =
                        type == BluetoothLEAdvertisementType::ConnectableUndirected ||
                        type == BluetoothLEAdvertisementType::ConnectableDirected;
                    const auto addressType = args.BluetoothAddressType();
                    const jint addressKind = addressType == BluetoothAddressType::Public   ? 1
                                             : addressType == BluetoothAddressType::Random ? 2
                                                                                           : 0;
                    auto name = advertisement.LocalName();
                    const bool complete =
                        advertisement
                            .GetSectionsByType(BluetoothLEAdvertisementDataTypes::CompleteLocalName(
                            ))
                            .Size() > 0;
                    callbackEnv->CallVoidMethod(
                        target->callback,
                        target->advertisement,
                        generation,
                        text(callbackEnv, addressString(args.BluetoothAddress())),
                        name.empty() ? nullptr : text(callbackEnv, name.c_str()),
                        static_cast<jint>(args.RawSignalStrengthInDBm()),
                        addressKind,
                        static_cast<jboolean>(connectable),
                        static_cast<jboolean>(complete),
                        strings(callbackEnv, uuids),
                        manufacturerArray
                    );
                }
            );
        }
    );
    auto stopped = watcher.Stopped(
        [weak, generation](auto&&, BluetoothLEAdvertisementWatcherStoppedEventArgs const& args)
        {
            if (auto target = weak.lock())
            {
                target->call(
                    [&](JNIEnv* callbackEnv)
                    {
                        auto message = L"Windows scan stopped, error=" +
                                       std::to_wstring(static_cast<int>(args.Error()));
                        callbackEnv->CallVoidMethod(
                            target->callback,
                            target->scanStopped,
                            generation,
                            args.Error() == BluetoothError::Success ? nullptr
                                                                    : text(callbackEnv, message)
                        );
                    }
                );
            }
        }
    );
    {
        std::lock_guard lock(owner->mutex);
        if (owner->closed.load())
        {
            throw std::runtime_error("Manager is closed");
        }
        owner->watcher = watcher;
        owner->receivedToken = received;
        owner->stoppedToken = stopped;
    }
    watcher.Start();
    try
    {
        startNameWatcher(owner, generation);
    }
    catch (hresult_error const& e)
    {
        nameLookupFailed(weak, generation, e.message().c_str());
    }
    catch (std::exception const& e)
    {
        nameLookupFailed(weak, generation, to_hstring(e.what()).c_str());
    }
}

void stopScan(jlong handle)
{
    manager(handle)->stop();
}

} // namespace gpt::ble::manager::windows
