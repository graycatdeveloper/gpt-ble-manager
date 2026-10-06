@file:Suppress("MissingPermission", "DEPRECATION")

package gpt.ble.manager.android.scan

import android.bluetooth.le.ScanResult
import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleUuid
import gpt.ble.manager.internal.scan.Advertisement
import gpt.ble.manager.internal.scan.ScanStore
import kotlinx.coroutines.flow.map

/**
 * Transfers advertising data and the system name as separate sources. UUIDs from Service Data
 * supplement Service UUIDs. Called only under the scanner monitor because ScanStore is not
 * thread-safe.
 */
internal fun acceptAndroidAdvertisement(store: ScanStore, result: ScanResult) {
    val record = result.scanRecord
    val uuids =
        record?.serviceUuids.orEmpty().map { BleUuid.parse(it.uuid.toString()) }.toSet() +
            record?.serviceData.orEmpty().keys.map { BleUuid.parse(it.uuid.toString()) }
    val data = mutableMapOf<Int, BleBytes>()
    record?.manufacturerSpecificData?.let { values ->
        for (i in 0 until values.size()) {
            data[values.keyAt(i)] = BleBytes(values.valueAt(i))
        }
    }
    val rawName = record?.deviceName
    val systemName = runCatching { result.device.name }.getOrNull()
    if (!systemName.isNullOrBlank()) {
        store.rememberSystemDevice(BleDevice(result.device.address, systemName))
    }
    val complete = hasCompleteName(record?.bytes)
    store.accept(
        Advertisement(
            BleDevice(
                address = result.device.address,
                name = rawName,
                rssi = result.rssi,
                connectable = result.isConnectable,
                serviceUuids = uuids,
                manufacturerData = data,
                serviceData =
                    record?.serviceData.orEmpty().entries.associate { (uuid, bytes) ->
                        BleUuid.parse(uuid.uuid.toString()) to BleBytes(bytes)
                    },
            ),
            completeName = complete,
        )
    )
}

/**
 * AD structure: length includes the type but not the length byte itself; 0x09 denotes the complete
 * name. A truncated or terminating section stops parsing without reading beyond the array.
 *
 * @see <a href="https://www.bluetooth.com/specifications/assigned-numbers/">Bluetooth Assigned
 *   Numbers</a>
 */
internal fun hasCompleteName(bytes: ByteArray?): Boolean {
    if (bytes == null) {
        return false
    }
    var offset = 0
    while (offset < bytes.size) {
        val length = bytes[offset].toInt() and 255
        if (length == 0 || offset + length >= bytes.size) {
            break
        }
        if ((bytes[offset + 1].toInt() and 255) == 0x09) {
            return true
        }
        offset += length + 1
    }
    return false
}
