#include "adapter/Adapter.hpp"
#include "gatt/AttributeOperations.hpp"
#include "gatt/ConnectionOperations.hpp"
#include "gatt/ConnectionPreferences.hpp"
#include "gatt/Discovery.hpp"
#include "gatt/Subscriptions.hpp"
#include "jni/JniRuntime.hpp"
#include "pairing/Pairing.hpp"
#include "runtime/WinrtRuntime.hpp"
#include "scan/Scanner.hpp"
#include "state/Registry.hpp"

/**
 * The DLL's only exported interface: the names and signatures declared in NativeBridge.kt. Each
 * function explicitly initializes the thread, calls its module, and translates exceptions. The
 * catch return value is the original JNI fallback, not a success indication: Java already has a
 * pending exception. No BEGIN/END macros hide the try/catch boundaries.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/design.html#resolving-native-method-names
 */
extern "C"
{

JNIEXPORT jlong JNICALL Java_gpt_ble_manager_windows_NativeBridge_create(JNIEnv* env, jobject self)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::createManager(env, self);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_gpt_ble_manager_windows_NativeBridge_destroy(JNIEnv* env, jobject, jlong handle)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        gpt::ble::manager::windows::destroyManager(handle);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
    }
}

JNIEXPORT jint JNICALL
Java_gpt_ble_manager_windows_NativeBridge_adapterState(JNIEnv* env, jobject, jlong handle)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::queryAdapterState(handle);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return 3;
    }
}

JNIEXPORT jint JNICALL Java_gpt_ble_manager_windows_NativeBridge_pairingState(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring address,
    jint type
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::queryPairingState(env, handle, address, type);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return -1;
    }
}

JNIEXPORT jint JNICALL Java_gpt_ble_manager_windows_NativeBridge_pair(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring address,
    jint type,
    jlong timeout
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::pairDevice(env, handle, address, type, timeout);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return -1;
    }
}

JNIEXPORT jint JNICALL Java_gpt_ble_manager_windows_NativeBridge_unpair(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring address,
    jint type,
    jlong timeout
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::unpairDevice(env, handle, address, type, timeout);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return -1;
    }
}

JNIEXPORT void JNICALL Java_gpt_ble_manager_windows_NativeBridge_startScan(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong generation
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        gpt::ble::manager::windows::startScan(handle, generation);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
    }
}

JNIEXPORT void JNICALL
Java_gpt_ble_manager_windows_NativeBridge_stopScan(JNIEnv* env, jobject, jlong handle)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        gpt::ble::manager::windows::stopScan(handle);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
    }
}

JNIEXPORT jlong JNICALL Java_gpt_ble_manager_windows_NativeBridge_connect(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring address,
    jint type,
    jlong timeout
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::openConnection(env, handle, address, type, timeout);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_gpt_ble_manager_windows_NativeBridge_monitor(JNIEnv* env, jobject, jlong handle, jlong id)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::monitorConnection(handle, id);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_gpt_ble_manager_windows_NativeBridge_disconnect(JNIEnv* env, jobject, jlong handle, jlong id)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        gpt::ble::manager::windows::closeConnection(handle, id);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
    }
}

JNIEXPORT jbyteArray JNICALL Java_gpt_ble_manager_windows_NativeBridge_readDeviceName(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong id,
    jlong timeout
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        gpt::ble::manager::windows::OperationBudget budget(timeout);
        return gpt::ble::manager::windows::readGattDeviceName(env, handle, id);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL Java_gpt_ble_manager_windows_NativeBridge_discover(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong id,
    jlong timeout
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        gpt::ble::manager::windows::OperationBudget budget(timeout);
        return gpt::ble::manager::windows::discoverGatt(env, handle, id);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL Java_gpt_ble_manager_windows_NativeBridge_read(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong id,
    jint attribute,
    jboolean descriptor,
    jlong timeout
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        gpt::ble::manager::windows::OperationBudget budget(timeout);
        return gpt::ble::manager::windows::readAttribute(env, handle, id, attribute, descriptor);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_gpt_ble_manager_windows_NativeBridge_write(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong id,
    jint attribute,
    jboolean descriptor,
    jbyteArray data,
    jboolean response,
    jlong timeout
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        gpt::ble::manager::windows::OperationBudget budget(timeout);
        gpt::ble::manager::windows::writeAttribute(
            env,
            handle,
            id,
            attribute,
            descriptor,
            data,
            response
        );
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
    }
}

JNIEXPORT void JNICALL Java_gpt_ble_manager_windows_NativeBridge_subscribe(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong id,
    jint attribute,
    jint mode,
    jlong timeout
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        gpt::ble::manager::windows::OperationBudget budget(timeout);
        gpt::ble::manager::windows::configureSubscription(handle, id, attribute, mode);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
    }
}

JNIEXPORT jint JNICALL
Java_gpt_ble_manager_windows_NativeBridge_mtu(JNIEnv* env, jobject, jlong handle, jlong id)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::currentMtu(handle, id);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return 23;
    }
}

JNIEXPORT jboolean JNICALL
Java_gpt_ble_manager_windows_NativeBridge_supportsPreferredParameters(JNIEnv* env, jobject)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::supportsPreferredParameters() ? JNI_TRUE : JNI_FALSE;
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return JNI_FALSE;
    }
}

JNIEXPORT jint JNICALL Java_gpt_ble_manager_windows_NativeBridge_preferredParameters(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong id,
    jint mode
)
{
    try
    {
        gpt::ble::manager::windows::apartment();
        return gpt::ble::manager::windows::requestPreferredParameters(handle, id, mode);
    }
    catch (...)
    {
        gpt::ble::manager::windows::failure(env);
        return 0;
    }
}
}
