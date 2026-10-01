package dev.gpt.ble.windows

import dev.gpt.ble.AdapterState
import dev.gpt.ble.BleConnection
import dev.gpt.ble.BleDevice
import dev.gpt.ble.BleError
import dev.gpt.ble.BleException
import dev.gpt.ble.BleManager
import dev.gpt.ble.PairResult
import dev.gpt.ble.PairingState
import dev.gpt.ble.ScanOptions
import dev.gpt.ble.UnpairResult
import dev.gpt.ble.internal.canonicalAddress
import dev.gpt.ble.windows.gatt.WindowsConnection
import dev.gpt.ble.windows.pairing.WindowsPairingController
import dev.gpt.ble.windows.scan.WindowsScanner
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class WindowsBleManager : BleManager {
    private val lock = Any()
    private val native = NativeBridge(this)
    @Volatile private var handle = native.create()
    private val adapters = MutableStateFlow(AdapterState.PoweredOff)
    override val adapterState = adapters.asStateFlow()
    override val scanState
        get() = scanner.scanState

    override val devices
        get() = scanner.devices

    private val connections = ConcurrentHashMap<Long, WindowsConnection>()
    private val addresses = ConcurrentHashMap.newKeySet<String>()

    private val scanner = WindowsScanner(lock, native, { handle }, ::activeHandle)
    private val pairing = WindowsPairingController(native, ::activeHandle, addresses)

    private fun activeHandle(): Long =
        handle.takeIf { it != 0L } ?: throw BleException(BleError.Closed, "Manager is closed")

    override suspend fun refreshAdapterState(): AdapterState =
        withContext(Dispatchers.IO) {
                if (handle == 0L) {
                    AdapterState.Closed
                } else {
                    when (native.adapterState(activeHandle())) {
                        0 -> AdapterState.Ready
                        1 -> AdapterState.PoweredOff
                        2 -> AdapterState.PermissionRequired
                        else -> AdapterState.Unsupported
                    }
                }
            }
            .let { result ->
                synchronized(lock) {
                    (if (handle == 0L) {
                            AdapterState.Closed
                        } else {
                            result
                        })
                        .also { adapters.value = it }
                }
            }

    override suspend fun getPairingState(device: BleDevice): PairingState =
        pairing.getPairingState(device)

    override suspend fun pair(device: BleDevice, timeoutMillis: Long): PairResult =
        pairing.pair(device, timeoutMillis)

    override suspend fun unpair(device: BleDevice, timeoutMillis: Long): UnpairResult =
        pairing.unpair(device, timeoutMillis)

    override suspend fun startScan(options: ScanOptions) {
        val state = refreshAdapterState()
        if (state != AdapterState.Ready) {
            throw BleException(BleError.NotReady, "Bluetooth adapter is $state")
        }
        scanner.startScan(options)
    }

    override fun stopScan() = scanner.stopScan()

    override suspend fun connect(device: BleDevice, timeoutMillis: Long): BleConnection {
        require(timeoutMillis in 1..120_000)
        val address = canonicalAddress(device.address)
        val manager = activeHandle()
        if (!addresses.add(address)) {
            throw BleException(BleError.Rejected, "A connection to $address already exists")
        }
        var session = 0L
        var connection: WindowsConnection? = null
        try {
            withContext(Dispatchers.IO) {
                session =
                    native.connect(manager, address, device.addressType.ordinal, timeoutMillis)
            }
            currentCoroutineContext().ensureActive()
            connection =
                WindowsConnection(
                    native,
                    manager,
                    session,
                    device.copy(address = address),
                    onDeviceName = { name -> updateGattName(address, name) },
                ) {
                    connections.remove(session)
                    addresses.remove(address)
                }
            connections[session] = connection
            if (handle == 0L || !native.monitor(manager, session)) {
                throw BleException(
                    BleError.Disconnected,
                    "Device disconnected during connection setup",
                )
            }
            connection.connected()
            return connection
        } catch (e: Throwable) {
            if (connection != null) {
                connection.close()
            } else if (session != 0L) {
                runCatching { native.disconnect(manager, session) }
            }
            addresses.remove(address)
            if (e is IllegalStateException) {
                throw e.asBleException("Windows connection failed")
            }
            throw e
        }
    }

    override fun close() {
        val previous =
            synchronized(lock) {
                if (handle == 0L) {
                    return
                }
                stopScan()
                handle.also { handle = 0L }
            }
        connections.values.toList().forEach { it.close() }
        native.destroy(previous)
        adapters.value = AdapterState.Closed
    }

    internal fun advertisement(
        token: Long,
        address: String,
        name: String?,
        rssi: Int,
        addressType: Int,
        connectable: Boolean,
        completeName: Boolean,
        services: Array<String>,
        manufacturer: Array<ByteArray>,
    ) =
        scanner.advertisement(
            token,
            address,
            name,
            rssi,
            addressType,
            connectable,
            completeName,
            services,
            manufacturer,
        )

    internal fun scanStopped(token: Long, error: String?) = scanner.scanStopped(token, error)

    internal fun knownDevice(token: Long, address: String, name: String?) =
        scanner.knownDevice(token, address, name)

    internal fun nameLookupFailed(token: Long, message: String) =
        scanner.nameLookupFailed(token, message)

    private fun updateGattName(address: String, name: String) =
        scanner.updateGattName(address, name)

    internal fun disconnected(session: Long, error: String) {
        connections[session]?.terminate(BleException(BleError.Disconnected, error))
    }

    internal fun notification(session: Long, attribute: Int, value: ByteArray) {
        connections[session]?.notification(attribute, value)
    }

    internal fun mtuChanged(session: Long, mtu: Int) {
        connections[session]?.mtuChanged(mtu)
    }

    internal fun adapterChanged(state: Int) {
        if (handle == 0L) {
            return
        }
        adapters.value =
            if (state == 0) {
                AdapterState.Ready
            } else {
                AdapterState.PoweredOff
            }
        if (state != 0) {
            stopScan()
            connections.values.toList().forEach {
                it.terminate(BleException(BleError.NotReady, "Bluetooth adapter was turned off"))
            }
        }
    }
}
