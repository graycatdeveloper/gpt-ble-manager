@file:Suppress("MissingPermission", "DEPRECATION")

package gpt.ble.manager.android.adapter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import gpt.ble.manager.AdapterState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapter availability snapshot that accounts for runtime permissions on API 31+. Does not request
 * permissions itself: the Activity decides when to show the UI.
 *
 * @see <a
 *   href="https://developer.android.com/develop/connectivity/bluetooth/bt-permissions">Bluetooth
 *   permissions</a>
 */
internal class AndroidAdapterState(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val closed: AtomicBoolean,
) {
    /** Request these from your Activity before scanning. The library never opens UI. */
    fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun currentState(): AdapterState =
        try {
            when {
                closed.get() -> AdapterState.Closed
                adapter == null -> AdapterState.Unsupported
                requiredPermissions().any {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                } -> AdapterState.PermissionRequired
                !adapter.isEnabled -> AdapterState.PoweredOff
                else -> AdapterState.Ready
            }
        } catch (_: SecurityException) {
            AdapterState.PermissionRequired
        }
}
