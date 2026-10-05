/**
 * @file
 * Internal state/Registry operations. JNI is exported only from jni/NativeBridge.cpp.
 */
#pragma once

#include <jni.h>
#include <memory>

namespace gpt::ble::manager::windows
{
struct Manager;
struct Connection;

/**
 * A single monotonic counter for manager and connection handles. A handle is not a pointer, and
 * released values are not reused.
 */
jlong allocateHandle();

/**
 * Return strong references under the mutex so that objects remain alive after registry access
 * ends. A closed or unknown handle raises the original runtime_error.
 */
std::shared_ptr<Manager> manager(jlong handle);
std::shared_ptr<Connection> connection(jlong handle, jlong id);

/**
 * Creates a manager and binds the exact Kotlin callback descriptors. The global reference lives
 * until the manager's last owner is destroyed.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#global-and-local-references
 */
jlong createManager(JNIEnv* env, jobject self);

/**
 * Removes the handle before closing resources; repeated destruction does nothing.
 *
 * @see https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#global-and-local-references
 */
void destroyManager(jlong handle);
} // namespace gpt::ble::manager::windows
