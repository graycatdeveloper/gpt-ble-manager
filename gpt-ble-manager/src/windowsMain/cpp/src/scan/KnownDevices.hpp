/**
 * @file
 * A separate Windows metadata source; by itself, it does not confirm that a device is nearby.
 */
#pragma once

#include <jni.h>
#include <memory>
#include <string_view>

namespace gpt::ble::manager::windows
{
struct Manager;

/**
 * An AssociationEndpoint watcher supplements advertising data with Windows names. Added stores a
 * snapshot; Updated first applies the delta through Update. Removed deletes only the system
 * record, not the radio scan result.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.enumeration.devicewatcher
 */
void startNameWatcher(std::shared_ptr<Manager> const& owner, jlong generation);

/**
 * Reports supplementary name lookup failures through a separate callback. Such a failure does not
 * stop a successful advertisement scan.
 */
void nameLookupFailed(
    std::weak_ptr<Manager> const& weak,
    jlong generation,
    std::wstring_view message
);
} // namespace gpt::ble::manager::windows
