#include "adapter/Adapter.hpp"
#include "gatt/AttributeOperations.hpp"
#include "gatt/ConnectionOperations.hpp"
#include "gatt/Discovery.hpp"
#include "gatt/Subscriptions.hpp"
#include "jni/JniRuntime.hpp"
#include "pairing/Pairing.hpp"
#include "runtime/WinrtRuntime.hpp"
#include "scan/Scanner.hpp"
#include "state/Registry.hpp"

/**
 * Единственная экспортируемая поверхность DLL: имена и сигнатуры NativeBridge.kt.
 * Каждая функция явно инициализирует поток, вызывает модуль и переводит исключение.
 * Значение в catch — исходный JNI fallback, а не признак успеха: Java уже имеет exception.
 * Здесь нет макросов BEGIN/END, скрывающих границы try/catch.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/design.html#resolving-native-method-names
 */
extern "C"
{

JNIEXPORT jlong JNICALL Java_dev_gpt_ble_windows_NativeBridge_create(JNIEnv* env, jobject self)
{
    try
    {
        gpt::ble::windows::apartment();
        return gpt::ble::windows::createManager(env, self);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_dev_gpt_ble_windows_NativeBridge_destroy(JNIEnv* env, jobject, jlong handle)
{
    try
    {
        gpt::ble::windows::apartment();
        gpt::ble::windows::destroyManager(handle);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
    }
}

JNIEXPORT jint JNICALL
Java_dev_gpt_ble_windows_NativeBridge_adapterState(JNIEnv* env, jobject, jlong handle)
{
    try
    {
        gpt::ble::windows::apartment();
        return gpt::ble::windows::queryAdapterState(handle);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return 3;
    }
}

JNIEXPORT jint JNICALL Java_dev_gpt_ble_windows_NativeBridge_pairingState(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring address,
    jint type
)
{
    try
    {
        gpt::ble::windows::apartment();
        return gpt::ble::windows::queryPairingState(env, handle, address, type);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return -1;
    }
}

JNIEXPORT jint JNICALL Java_dev_gpt_ble_windows_NativeBridge_pair(
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
        gpt::ble::windows::apartment();
        return gpt::ble::windows::pairDevice(env, handle, address, type, timeout);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return -1;
    }
}

JNIEXPORT jint JNICALL Java_dev_gpt_ble_windows_NativeBridge_unpair(
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
        gpt::ble::windows::apartment();
        return gpt::ble::windows::unpairDevice(env, handle, address, type, timeout);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return -1;
    }
}

JNIEXPORT void JNICALL Java_dev_gpt_ble_windows_NativeBridge_startScan(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong generation
)
{
    try
    {
        gpt::ble::windows::apartment();
        gpt::ble::windows::startScan(handle, generation);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
    }
}

JNIEXPORT void JNICALL
Java_dev_gpt_ble_windows_NativeBridge_stopScan(JNIEnv* env, jobject, jlong handle)
{
    try
    {
        gpt::ble::windows::apartment();
        gpt::ble::windows::stopScan(handle);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
    }
}

JNIEXPORT jlong JNICALL Java_dev_gpt_ble_windows_NativeBridge_connect(
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
        gpt::ble::windows::apartment();
        return gpt::ble::windows::openConnection(env, handle, address, type, timeout);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_gpt_ble_windows_NativeBridge_monitor(JNIEnv* env, jobject, jlong handle, jlong id)
{
    try
    {
        gpt::ble::windows::apartment();
        return gpt::ble::windows::monitorConnection(handle, id);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_dev_gpt_ble_windows_NativeBridge_disconnect(JNIEnv* env, jobject, jlong handle, jlong id)
{
    try
    {
        gpt::ble::windows::apartment();
        gpt::ble::windows::closeConnection(handle, id);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_dev_gpt_ble_windows_NativeBridge_readDeviceName(JNIEnv* env, jobject, jlong handle, jlong id)
{
    try
    {
        gpt::ble::windows::apartment();
        return gpt::ble::windows::readGattDeviceName(env, handle, id);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_dev_gpt_ble_windows_NativeBridge_discover(JNIEnv* env, jobject, jlong handle, jlong id)
{
    try
    {
        gpt::ble::windows::apartment();
        return gpt::ble::windows::discoverGatt(env, handle, id);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL Java_dev_gpt_ble_windows_NativeBridge_read(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong id,
    jint attribute,
    jboolean descriptor
)
{
    try
    {
        gpt::ble::windows::apartment();
        return gpt::ble::windows::readAttribute(env, handle, id, attribute, descriptor);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_dev_gpt_ble_windows_NativeBridge_write(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong id,
    jint attribute,
    jboolean descriptor,
    jbyteArray data,
    jboolean response
)
{
    try
    {
        gpt::ble::windows::apartment();
        gpt::ble::windows::writeAttribute(env, handle, id, attribute, descriptor, data, response);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
    }
}

JNIEXPORT void JNICALL Java_dev_gpt_ble_windows_NativeBridge_subscribe(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong id,
    jint attribute,
    jint mode
)
{
    try
    {
        gpt::ble::windows::apartment();
        gpt::ble::windows::configureSubscription(handle, id, attribute, mode);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
    }
}

JNIEXPORT jint JNICALL
Java_dev_gpt_ble_windows_NativeBridge_mtu(JNIEnv* env, jobject, jlong handle, jlong id)
{
    try
    {
        gpt::ble::windows::apartment();
        return gpt::ble::windows::currentMtu(handle, id);
    }
    catch (...)
    {
        gpt::ble::windows::failure(env);
        return 23;
    }
}
}
