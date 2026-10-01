@file:Suppress("MissingPermission", "DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.gpt.ble.android.gatt

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import dev.gpt.ble.BleUuid
import dev.gpt.ble.GattCharacteristic
import dev.gpt.ble.GattDescriptor
import dev.gpt.ble.GattService

/**
 * Стабильные числовые IDs для объектов Android GATT, включая повторяющиеся UUID. Внутри нет mutex:
 * все методы вызываются под monitor AndroidConnection. clear сохраняет счётчик, как исходная
 * реализация; IDs не переиспользуются при rebuild.
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
