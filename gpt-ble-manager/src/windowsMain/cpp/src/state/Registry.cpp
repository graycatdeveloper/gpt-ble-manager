#include "state/Registry.hpp"
#include "state/Connection.hpp"
#include "state/Manager.hpp"
#include <stdexcept>

namespace gpt::ble::manager::windows
{

namespace
{
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<Manager>> managers;
std::atomic<jlong> nextId{1};
} // namespace

jlong allocateHandle()
{
    return nextId.fetch_add(1);
}

std::shared_ptr<Manager> manager(jlong handle)
{
    std::lock_guard lock(registryMutex);
    auto it = managers.find(handle);
    if (it == managers.end() || it->second->closed.load())
    {
        throw std::runtime_error("Manager is closed");
    }
    return it->second;
}

std::shared_ptr<Connection> connection(jlong handle, jlong id)
{
    auto owner = manager(handle);
    std::lock_guard lock(owner->mutex);
    auto it = owner->connections.find(id);
    if (it == owner->connections.end() || it->second->closed.load())
    {
        throw std::runtime_error("Connection is closed");
    }
    return it->second;
}

jlong createManager(JNIEnv* env, jobject self)
{
    auto owner = std::make_shared<Manager>();
    env->GetJavaVM(&owner->vm);
    owner->callback = env->NewGlobalRef(self);
    if (!owner->callback)
    {
        throw std::bad_alloc();
    }
    // Descriptors are part of the JNI contract, including argument order and types.
    // A mismatch leaves a pending Java exception; a partially initialized manager is not published.
    auto cls = env->GetObjectClass(self);
    owner->advertisement = env->GetMethodID(
        cls,
        "onAdvertisement",
        "(JLjava/lang/String;Ljava/lang/String;IIZZ[Ljava/lang/String;[[B)V"
    );
    owner->scanStopped = env->GetMethodID(cls, "onScanStopped", "(JLjava/lang/String;)V");
    owner->knownDevice =
        env->GetMethodID(cls, "onKnownDevice", "(JLjava/lang/String;Ljava/lang/String;)V");
    owner->nameLookupFailed = env->GetMethodID(cls, "onNameLookupFailed", "(JLjava/lang/String;)V");
    owner->disconnected = env->GetMethodID(cls, "onDisconnected", "(JLjava/lang/String;)V");
    owner->notification = env->GetMethodID(cls, "onNotification", "(JI[B)V");
    owner->mtuChanged = env->GetMethodID(cls, "onMtuChanged", "(JI)V");
    owner->adapterChanged = env->GetMethodID(cls, "onAdapterStateChanged", "(I)V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck())
    {
        return 0;
    }
    auto id = allocateHandle();
    {
        std::lock_guard lock(registryMutex);
        managers.emplace(id, owner);
    }
    return id;
}

void destroyManager(jlong handle)
{
    std::shared_ptr<Manager> owner;
    {
        std::lock_guard lock(registryMutex);
        auto it = managers.find(handle);
        if (it == managers.end())
        {
            return;
        }
        owner = it->second;
        managers.erase(it);
    }
    owner->close();
}

} // namespace gpt::ble::manager::windows
