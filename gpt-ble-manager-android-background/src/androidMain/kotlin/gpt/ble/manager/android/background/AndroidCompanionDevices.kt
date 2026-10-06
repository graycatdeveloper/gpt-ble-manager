@file:Suppress("MissingPermission", "DEPRECATION")

package gpt.ble.manager.android.background

import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException

/**
 * Opt-in companion integration. Association is independent of Bluetooth bonding. The Activity
 * launches the IntentSender supplied to its callback; the user keeps control over system consent.
 * Presence callbacks go to the app's manifest-declared CompanionDeviceService.
 *
 * @see <a
 *   href="https://developer.android.com/reference/android/companion/CompanionDeviceManager">CompanionDeviceManager</a>
 */
class AndroidCompanionDevices(context: Context) {
    private val manager =
        context.applicationContext.getSystemService(CompanionDeviceManager::class.java)
            ?: throw BleException(BleError.Unsupported, "Companion Device Manager is unavailable")

    fun associate(
        filter: ScanFilter,
        callback: CompanionDeviceManager.Callback,
        singleDevice: Boolean = true,
    ) {
        val request =
            AssociationRequest.Builder()
                .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(filter).build())
                .setSingleDevice(singleDevice)
                .build()
        manager.associate(request, callback, Handler(Looper.getMainLooper()))
    }

    /** Local associations owned by this application, normalized by the Android framework. */
    fun associatedAddresses(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        } else {
            manager.associations.toList()
        }

    fun disassociate(address: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            manager.myAssociations
                .filter { it.deviceMacAddress?.toString()?.equals(address, true) == true }
                .forEach { manager.disassociate(it.id) }
        } else {
            manager.disassociate(address)
        }
    }

    fun startObservingPresence(address: String) = presence(address, true)

    fun stopObservingPresence(address: String) = presence(address, false)

    private fun presence(address: String, start: Boolean) {
        if (Build.VERSION.SDK_INT < 31) {
            throw BleException(BleError.Unsupported, "Presence observation requires Android 12+")
        }
        if (Build.VERSION.SDK_INT >= 36) {
            val association =
                manager.myAssociations.singleOrNull {
                    it.deviceMacAddress?.toString()?.equals(address, true) == true
                }
                    ?: throw BleException(
                        BleError.Rejected,
                        "An unambiguous app-owned association is required",
                    )
            val request =
                ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build()
            if (start) {
                manager.startObservingDevicePresence(request)
            } else {
                manager.stopObservingDevicePresence(request)
            }
        } else {
            if (start) {
                manager.startObservingDevicePresence(address)
            } else {
                manager.stopObservingDevicePresence(address)
            }
        }
    }
}
