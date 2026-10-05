@file:Suppress("MissingPermission", "DEPRECATION", "OVERRIDE_DEPRECATION")

package gpt.ble.manager.android.gatt

import kotlinx.coroutines.CompletableDeferred

/**
 * A single request: kind and target identity associate the callback with its CompletableDeferred. A
 * UUID alone is insufficient because the GATT catalog may contain duplicate characteristics.
 */
internal data class PendingGattRequest(
    val kind: String,
    val target: Any?,
    val result: CompletableDeferred<ByteArray>,
)
