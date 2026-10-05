/**
 * @file
 * Внутренние операции state/Registry. JNI экспортируется только из jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>
#include <memory>

namespace gpt::ble::manager::windows
{
struct Manager;
struct Connection;

/**
 * Единый монотонный счётчик manager и connection handles.
 * Handle — не указатель, освобождённое значение не переиспользуется.
 */
jlong allocateHandle();

/**
 * Возвращают strong reference под mutex: объект остаётся живым после выхода
 * из реестра. Закрытый/неизвестный handle даёт исходное runtime_error.
 */
std::shared_ptr<Manager> manager(jlong handle);
std::shared_ptr<Connection> connection(jlong handle, jlong id);

/**
 * Создаёт manager и привязывает точные Kotlin callback-дескрипторы.
 * Global reference живёт до уничтожения последнего владельца manager.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#global-and-local-references
 */
jlong createManager(JNIEnv* env, jobject self);

/**
 * Удаляет handle до закрытия ресурсов; повторное уничтожение ничего не делает.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#global-and-local-references
 */
void destroyManager(jlong handle);
} // namespace gpt::ble::manager::windows
