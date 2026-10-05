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

    // FindAllAsync(AEP) ожидал radio-enumeration и давал timeout на известном устройстве.
    // Прямое разрешение адреса использует Windows metadata без открытия GATT-сессии.
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
    // На проверенном JVM host обычный PairAsync возвращал Failed (19).
    // Custom ConfirmOnly сработал. PIN-сценарии не входят в поддерживаемые ceremonies.
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
    // Повторная проверка после разрешения адреса: manager мог закрыться за время await.
    manager(handle);
    auto result = await(info.Pairing().UnpairAsync(), limit);
    return static_cast<jint>(result.Status());
}

} // namespace gpt::ble::manager::windows
