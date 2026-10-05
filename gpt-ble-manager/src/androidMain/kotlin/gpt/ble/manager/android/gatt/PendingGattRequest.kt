@file:Suppress("MissingPermission", "DEPRECATION", "OVERRIDE_DEPRECATION")

package gpt.ble.manager.android.gatt

import kotlinx.coroutines.CompletableDeferred

/**
 * Один запрос: kind и identity target связывают callback с его CompletableDeferred. Адрес UUID
 * недостаточен: в GATT-каталоге допустимы повторяющиеся характеристики.
 */
internal data class PendingGattRequest(
    val kind: String,
    val target: Any?,
    val result: CompletableDeferred<ByteArray>,
)
