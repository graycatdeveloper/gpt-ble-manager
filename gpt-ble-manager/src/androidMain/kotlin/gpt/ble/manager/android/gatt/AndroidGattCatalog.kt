@file:Suppress("MissingPermission", "DEPRECATION", "OVERRIDE_DEPRECATION")

package gpt.ble.manager.android.gatt

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import gpt.ble.manager.BleUuid
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.GattDescriptor
import gpt.ble.manager.GattService

/**
 * Stable numeric IDs for Android GATT objects, including duplicate UUIDs. There is no internal
 * mutex: all methods run under the AndroidConnection monitor. clear preserves the counter, as in
 * the original implementation; rebuilding the catalog does not reuse IDs.
 */
internal class AndroidGattCatalog(private val id: String) {
    private val characteristics = mutableMapOf<Int, BluetoothGattCharacteristic>()
    private val descriptors = mutableMapOf<Int, BluetoothGattDescriptor>()
    private var nextId = 1

    fun characteristic(id: Int): BluetoothGattCharacteristic = characteristics.getValue(id)

    fun descriptor(id: Int): BluetoothGattDescriptor = descriptors.getValue(id)

    fun characteristicId(value: BluetoothGattCharacteristic): Int? =
        characteristics.entries.find { it.value === value }?.key

    fun clear() {
        characteristics.clear()
        descriptors.clear()
    }

    fun build(services: List<BluetoothGattService>): List<GattService> {
        characteristics.clear()
        descriptors.clear()
        return services.map { service ->
            val serviceId = nextId++
            GattService(
                serviceId,
                BleUuid.parse(service.uuid.toString()),
                service.characteristics.map { char ->
                    val charId = nextId++
                    characteristics[charId] = char
                    GattCharacteristic(
                        id,
                        charId,
                        serviceId,
                        BleUuid.parse(char.uuid.toString()),
                        char.properties,
                        char.descriptors.map { desc ->
                            val descId = nextId++
                            descriptors[descId] = desc
                            GattDescriptor(id, descId, charId, BleUuid.parse(desc.uuid.toString()))
                        },
                    )
                },
            )
        }
    }
}
