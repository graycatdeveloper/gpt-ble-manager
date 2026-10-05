package gpt.ble.manager.windows.gatt

import gpt.ble.manager.BleUuid
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.GattDescriptor
import gpt.ble.manager.GattService

/**
 * Decodes the JNI catalog: S|service|uuid, C|service|char|uuid|properties, D|char|desc|uuid. ATT
 * handles link records, so duplicate UUIDs are allowed. Record order is preserved. A malformed
 * record still throws an exception instead of producing an incomplete successful result.
 */
internal fun decodeWindowsGattCatalog(id: String, source: Array<String>): List<GattService> {
    val records = source.map { it.split('|') }
    val descriptors =
        records
            .filter { it[0] == "D" }
            .map { GattDescriptor(id, it[2].toInt(), it[1].toInt(), BleUuid.parse(it[3])) }
    val characteristics =
        records
            .filter { it[0] == "C" }
            .map {
                val charId = it[2].toInt()
                GattCharacteristic(
                    id,
                    charId,
                    it[1].toInt(),
                    BleUuid.parse(it[3]),
                    it[4].toInt(),
                    descriptors.filter { d -> d.characteristicId == charId },
                )
            }
    return records
        .filter { it[0] == "S" }
        .map {
            val serviceId = it[1].toInt()
            GattService(
                serviceId,
                BleUuid.parse(it[2]),
                characteristics.filter { c -> c.serviceId == serviceId },
            )
        }
}
