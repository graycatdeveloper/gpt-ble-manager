@file:Suppress("MissingPermission", "DEPRECATION")

package gpt.ble.manager.android.background

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleErrorDetails
import gpt.ble.manager.BleException
import gpt.ble.manager.BleUuid
import gpt.ble.manager.DeviceNameSource

/**
 * OS-owned PendingIntent scan, independent from an in-process BleManager scan. Recreate the same
 * explicit PendingIntent to stop it after a process restart. Never use FLAG_CANCEL_CURRENT. The app
 * must request runtime permissions and declare its receiver; no service is started here.
 *
 * @see <a
 *   href="https://developer.android.com/develop/connectivity/bluetooth/ble/background">Background
 *   BLE</a>
 */
class AndroidBackgroundScanner(context: Context) {
    private val context = context.applicationContext

    private fun scanner(): BluetoothLeScanner =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?.bluetoothLeScanner
            ?: throw BleException(BleError.NotReady, "BLE scanner is unavailable")

    /** Uses Android hardware filters; unlike foreground namePrefix these cannot merge packets. */
    fun start(
        intent: PendingIntent,
        filters: List<ScanFilter>,
        settings: ScanSettings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
    ) {
        require(filters.isNotEmpty()) { "Background scanning requires a targeted hardware filter" }
        try {
            val status = scanner().startScan(filters, settings, intent)
            if (status != 0)
                throw BleException(
                    BleError.Rejected,
                    "Background scan rejected: $status",
                    details =
                        BleErrorDetails(
                            operation = "backgroundScan",
                            platform = "Android",
                            platformStatus = status,
                        ),
                )
        } catch (error: SecurityException) {
            throw BleException(
                BleError.PermissionDenied,
                "Background scan permission denied",
                error,
            )
        }
    }

    fun stop(intent: PendingIntent) {
        try {
            scanner().stopScan(intent)
        } catch (error: SecurityException) {
            throw BleException(BleError.PermissionDenied, "Cannot stop background scan", error)
        }
    }

    companion object {
        /** Explicit mutable intent: Android fills scan-result extras before delivery. */
        fun pendingIntent(
            context: Context,
            receiver: Class<out BroadcastReceiver>,
            requestCode: Int = 0,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, receiver).setAction("${context.packageName}.GPT_BLE_SCAN"),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )

        fun results(intent: Intent): List<BleDevice> {
            val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
            if (error != 0)
                throw BleException(
                    BleError.NativeFailure,
                    "Background scan failed: $error",
                    details =
                        BleErrorDetails(
                            operation = "backgroundScan",
                            platform = "Android",
                            platformStatus = error,
                        ),
                )
            val results =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(
                        BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                        ScanResult::class.java,
                    )
                } else {
                    intent.getParcelableArrayListExtra<ScanResult>(
                        BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
                    )
                }
            return results.orEmpty().map { result ->
                val record = result.scanRecord
                val serviceData =
                    record?.serviceData.orEmpty().entries.associate { (key, bytes) ->
                        BleUuid.parse(key.uuid.toString()) to BleBytes(bytes)
                    }
                val manufacturer = buildMap {
                    record?.manufacturerSpecificData?.let { values ->
                        for (index in 0 until values.size()) {
                            put(
                                values.keyAt(index),
                                BleBytes(values.valueAt(index)),
                            )
                        }
                    }
                }
                BleDevice(
                    address = result.device.address,
                    name = record?.deviceName,
                    rssi = result.rssi,
                    connectable = result.isConnectable,
                    serviceUuids =
                        record
                            ?.serviceUuids
                            .orEmpty()
                            .map { BleUuid.parse(it.uuid.toString()) }
                            .toSet() + serviceData.keys,
                    manufacturerData = manufacturer,
                    serviceData = serviceData,
                    nameSource = record?.deviceName?.let { DeviceNameSource.Advertisement },
                    seenInCurrentScan = true,
                    lastSeenMillis = System.currentTimeMillis(),
                )
            }
        }
    }
}
