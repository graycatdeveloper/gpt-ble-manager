package gpt.ble.manager.internal.scan

import gpt.ble.manager.AddressType
import gpt.ble.manager.BleDevice
import gpt.ble.manager.ScanEvent
import gpt.ble.manager.ScanOptions
import gpt.ble.manager.internal.canonicalAddress
import gpt.ble.manager.internal.names.usableSystemName
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Merges packets and system records before filtering because a name may arrive after the first
 * packet. The platform scanner calls these methods under its own monitor; there is no separate
 * mutex here. LinkedHashMap preserves discovery order, and StateFlow publishes a new immutable
 * list.
 */
internal class ScanStore(
    private val options: ScanOptions,
    private val emit: (ScanEvent) -> Unit = {},
    private val epochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val elapsedMillis: () -> Long = run {
        val origin = TimeSource.Monotonic.markNow()
        val clock: () -> Long = { origin.elapsedNow().inWholeMilliseconds }
        clock
    },
) {
    private val lastSeen = mutableMapOf<String, Long>()
    private val entries = linkedMapOf<String, ScanEntry>()
    private val results = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices = results.asStateFlow()

    // Platform owners serialize all calls. Only actual advertisements mark a device as seen.
    fun accept(packet: Advertisement) {
        val incoming =
            packet.device.copy(
                address = canonicalAddress(packet.device.address),
                lastSeenMillis = epochMillis(),
                seenInCurrentScan = true,
            )
        lastSeen[incoming.address] = elapsedMillis()
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
                        serviceData = old.device.serviceData + incoming.serviceData,
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
        if (options.matches(entries.getValue(incoming.address).resolved())) {
            emit(ScanEvent.Packet(incoming, incoming.lastSeenMillis!!))
        }
    }

    /** Called by the platform timer under the same monitor as advertising callbacks. */
    fun expire() {
        val ttl = options.lostTimeoutMillis ?: return
        val now = elapsedMillis()
        val expired = lastSeen.filterValues { now - it >= ttl }.keys
        expired.forEach {
            entries.remove(it)
            lastSeen.remove(it)
        }
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
        val old = results.value.associateBy { it.address }
        val next = entries.values.map { it.resolved() }.filter(options::matches)
        val present = next.map { it.address }.toSet()
        old.values.filter { it.address !in present }.forEach { emit(ScanEvent.Disappeared(it)) }
        next.forEach { device ->
            when (old[device.address]) {
                null -> emit(ScanEvent.Appeared(device))
                device -> Unit
                else -> emit(ScanEvent.Updated(device))
            }
        }
        results.value = next
    }
}
