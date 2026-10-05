/**
 * @file
 * Отдельный источник метаданных Windows; сам по себе не подтверждает присутствие устройства рядом.
 */
#pragma once

#include <jni.h>
#include <memory>
#include <string_view>

namespace gpt::ble::manager::windows
{
struct Manager;

/**
 * AssociationEndpoint watcher дополняет advertising именами из Windows.
 * Added хранит снимок; Updated сначала применяет delta через Update.
 * Событие Removed удаляет только системную запись, а не результат radio-сканирования.
 *
 * @see https://learn.microsoft.com/en-us/uwp/api/windows.devices.enumeration.devicewatcher
 */
void startNameWatcher(std::shared_ptr<Manager> const& owner, jlong generation);

/**
 * Ошибка дополнительного поиска имени передаётся отдельным callback.
 * Успешное advertising-сканирование из-за неё не останавливается.
 */
void nameLookupFailed(
    std::weak_ptr<Manager> const& weak,
    jlong generation,
    std::wstring_view message
);
} // namespace gpt::ble::manager::windows
