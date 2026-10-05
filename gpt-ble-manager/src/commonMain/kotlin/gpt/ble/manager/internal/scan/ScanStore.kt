package gpt.ble.manager.internal.scan

import gpt.ble.manager.AddressType
import gpt.ble.manager.BleDevice
import gpt.ble.manager.ScanOptions
import gpt.ble.manager.internal.canonicalAddress
import gpt.ble.manager.internal.names.usableSystemName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Объединяет пакеты и системные записи до фильтрации: имя может появиться позже первого пакета.
 * Методы вызывает платформенный scanner под своим monitor; отдельного mutex здесь нет.
 * LinkedHashMap сохраняет порядок обнаружения, StateFlow публикует новый immutable-список.
 */
internal class ScanStore(private val options: ScanOptions) {
    private val entries = linkedMapOf<String, ScanEntry>()
    private val results = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices = results.asStateFlow()

    // Platform owners serialize all calls. Only actual advertisements mark a device as seen.
    fun accept(packet: Advertisement) {
        val incoming = packet.device.copy(address = canonicalAddress(packet.device.address))
        val old = entries[incoming.address] ?: ScanEntry(BleDevice(incoming.address))
        val useName = !incoming.name.isNullOrBlank() && (packet.completeName || !old.completeName)
        entries[incoming.address] =
            old.copy(
                device =
                    incoming.copy(
                        rssi = incoming.rssi ?: old.device.rssi,
                        addressType =
                            incoming.addressType.takeUnless { it == AddressType.Unknown }
                                ?: old.device.addressType,
                        connectable =
                            if (incoming.connectable == true || old.device.connectable == true) {
                                true
                            } else {
                                incoming.connectable ?: old.device.connectable
                            },
                        serviceUuids = old.device.serviceUuids + incoming.serviceUuids,
                        manufacturerData = old.device.manufacturerData + incoming.manufacturerData,
                        seenInCurrentScan = true,
                    ),
                advertisedName =
                    if (useName) {
                        incoming.name
                    } else {
                        old.advertisedName
                    },
                completeName =
                    if (useName) {
                        packet.completeName
                    } else {
                        old.completeName
                    },
            )
        publish()
    }

    fun rememberSystemDevice(device: BleDevice) {
        val address = canonicalAddress(device.address)
        val old = entries[address] ?: ScanEntry(BleDevice(address))
        entries[address] =
            old.copy(
                device =
                    old.device.copy(
                        addressType =
                            old.device.addressType.takeUnless { it == AddressType.Unknown }
                                ?: device.addressType
                    ),
                systemName = usableSystemName(address, device.name) ?: old.systemName,
            )
        publish()
    }

    fun updateGattName(address: String, name: String) {
        val key = canonicalAddress(address)
        val old = entries[key] ?: return
        if (name.isNotBlank()) {
            entries[key] = old.copy(gattName = name)
            publish()
        }
    }

    private fun publish() {
        results.value =
            entries.values
                .map { it.resolved() }
                .filter { device ->
                    (device.seenInCurrentScan || options.includeKnownDevices) &&
                        (options.serviceUuids.isEmpty() ||
                            device.serviceUuids.any { it in options.serviceUuids }) &&
                        (options.namePrefix == null ||
                            device.name?.startsWith(options.namePrefix) == true)
                }
    }
}
