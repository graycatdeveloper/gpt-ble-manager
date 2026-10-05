#include "gatt/AttributeOperations.hpp"
#include "bluetooth/BluetoothUtils.hpp"
#include "runtime/WinrtRuntime.hpp"
#include "state/Connection.hpp"
#include "state/Manager.hpp"
#include "state/Registry.hpp"
#include <winrt/Windows.Foundation.Collections.h>

namespace gpt::ble::manager::windows
{
using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Bluetooth::GenericAttributeProfile;

jbyteArray readAttribute(JNIEnv* env, jlong handle, jlong id, jint attribute, jboolean descriptor)
{
    auto current = connection(handle, id);
    GattReadResult result{nullptr};
    if (descriptor)
    {
        GattDescriptor target{nullptr};
        {
            std::lock_guard lock(current->mutex);
            target = current->descriptors.at(attribute);
        }
        result = await(target.ReadValueAsync(BluetoothCacheMode::Uncached), deadline());
    }
    else
    {
        GattCharacteristic target{nullptr};
        {
            std::lock_guard lock(current->mutex);
            target = current->characteristics.at(attribute);
        }
        result = await(target.ReadValueAsync(BluetoothCacheMode::Uncached), deadline());
    }
    success(result.Status());
    return byteArray(env, bytes(result.Value()));
}

void writeAttribute(
    JNIEnv* env,
    jlong handle,
    jlong id,
    jint attribute,
    jboolean descriptor,
    jbyteArray data,
    jboolean response
)
{
    auto current = connection(handle, id);
    auto value = buffer(env, data);
    if (descriptor)
    {
        GattDescriptor target{nullptr};
        {
            std::lock_guard lock(current->mutex);
            target = current->descriptors.at(attribute);
        }
        success(await(target.WriteValueAsync(value), deadline()));
    }
    else
    {
        GattCharacteristic target{nullptr};
        {
            std::lock_guard lock(current->mutex);
            target = current->characteristics.at(attribute);
        }
        success(await(
            target.WriteValueAsync(
                value,
                response ? GattWriteOption::WriteWithResponse
                         : GattWriteOption::WriteWithoutResponse
            ),
            deadline()
        ));
    }
}

} // namespace gpt::ble::manager::windows
