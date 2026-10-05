#include "pairing/Pairing.hpp"
#include "runtime/WinrtRuntime.hpp"
#include "state/Manager.hpp"
#include "state/Registry.hpp"
#include <algorithm>
#include <winrt/Windows.Devices.Enumeration.h>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Enumeration;

namespace
{
DeviceInformation pairingDevice(JNIEnv* env, jstring address, jint type, Clock::time_point limit)
{
    auto input = text(env, address);
    input.erase(std::remove(input.begin(), input.end(), L':'), input.end());
    auto numeric = std::stoull(input, nullptr, 16);

    // FindAllAsync(AEP) waited for radio enumeration and timed out for a known device.
    // Direct address resolution uses Windows metadata without opening a GATT session.
    struct DeviceScope
    {
        BluetoothLEDevice value;

        ~DeviceScope()
        {
            if (value)
            {
                try
                {
                    value.Close();
                }
                catch (...)
                {
                }
            }
        }
    } remote{
        type == 0 ? await(BluetoothLEDevice::FromBluetoothAddressAsync(numeric), limit)
                  : await(
                        BluetoothLEDevice::FromBluetoothAddressAsync(
                            numeric,
                            type == 2 ? BluetoothAddressType::Random : BluetoothAddressType::Public
                        ),
                        limit
                    )
    };

    return remote.value ? remote.value.DeviceInformation() : DeviceInformation{nullptr};
}
} // namespace

jint queryPairingState(JNIEnv* env, jlong handle, jstring address, jint type)
{
    auto owner = manager(handle);
    auto info = pairingDevice(env, address, type, deadline());
    if (!info)
    {
        return -1;
    }
    return info.Pairing().IsPaired() ? 1 : 0;
}

jint pairDevice(JNIEnv* env, jlong handle, jstring address, jint type, jlong timeout)
{
    auto owner = manager(handle);
    auto limit = Clock::now() + std::chrono::milliseconds(timeout);
    auto info = pairingDevice(env, address, type, limit);
    if (!info)
    {
        return -1;
    }
    manager(handle);
    // On the tested JVM host, regular PairAsync returned Failed (19).
    // Custom ConfirmOnly succeeded. PIN scenarios are not among the supported pairing ceremonies.
    auto pairing = info.Pairing().Custom();
    std::weak_ptr<Manager> weak = owner;
    auto requested = pairing.PairingRequested(
        auto_revoke,
        [weak](DeviceInformationCustomPairing const&, DevicePairingRequestedEventArgs const& args)
        {
            if (auto active = weak.lock(); active && !active->closed.load() &&
                                           args.PairingKind() == DevicePairingKinds::ConfirmOnly)
            {
                args.Accept();
            }
        }
    );
    auto result = await(
        pairing.PairAsync(DevicePairingKinds::ConfirmOnly, DevicePairingProtectionLevel::Default),
        limit
    );
    return static_cast<jint>(result.Status());
}

jint unpairDevice(JNIEnv* env, jlong handle, jstring address, jint type, jlong timeout)
{
    auto owner = manager(handle);
    auto limit = Clock::now() + std::chrono::milliseconds(timeout);
    auto info = pairingDevice(env, address, type, limit);
    if (!info)
    {
        return -1;
    }
    // Recheck after address resolution: the manager may have closed during await.
    manager(handle);
    auto result = await(info.Pairing().UnpairAsync(), limit);
    return static_cast<jint>(result.Status());
}

} // namespace gpt::ble::manager::windows
