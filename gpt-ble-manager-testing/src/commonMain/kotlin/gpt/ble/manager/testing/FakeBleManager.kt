package gpt.ble.manager.testing

import gpt.ble.manager.AdapterState
import gpt.ble.manager.BleConnection
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.BleManager
import gpt.ble.manager.PairResult
import gpt.ble.manager.PairingState
import gpt.ble.manager.ScanEvent
import gpt.ble.manager.ScanOptions
import gpt.ble.manager.ScanState
import gpt.ble.manager.UnpairResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList

/**
 * Deterministic in-memory radio for one test coroutine dispatcher. Explicit advertise/disconnect/
 * setAdapterState calls replace hardware. It never loads JNI or Android APIs. This simulator tests
 * application policy, not the platform stack; use the core's contract tests for transport behavior.
 */
class FakeBleManager(peripherals: List<FakePeripheral> = emptyList()) : BleManager {
    private val radio = peripherals.associateBy { it.device.address.uppercase() }.toMutableMap()
    private val adapter = MutableStateFlow(AdapterState.Ready)
    private val scanning = MutableStateFlow(ScanState())
    private val found = MutableStateFlow<List<BleDevice>>(emptyList())
    private val active = MutableStateFlow<List<BleConnection>>(emptyList())
    private val events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 256)
    private val dropped = MutableStateFlow(0L)
    private val paired = mutableSetOf<String>()
    private var scanOptions = ScanOptions()
    private var nextId = 0L
    private val reserved = mutableSetOf<String>()
    val connectOutcomes = ArrayDeque<FakeOutcome>()
    var connectAttempts: Int = 0
        private set

    override val adapterState = adapter.asStateFlow()
    override val scanState = scanning.asStateFlow()
    override val devices = found.asStateFlow()
    override val connections = active.asStateFlow()
    override val scanEvents = events.asSharedFlow()
    override val droppedScanEvents = dropped.asStateFlow()

    fun add(peripheral: FakePeripheral) {
        radio[peripheral.device.address.uppercase()] = peripheral
    }

    fun advertise(device: BleDevice, timestampMillis: Long = 0) {
        if (!scanning.value.scanning) {
            return
        }
        val snapshot =
            device.copy(
                address = device.address.uppercase(),
                seenInCurrentScan = true,
                lastSeenMillis = timestampMillis,
            )
        if (!scanOptions.matches(snapshot)) {
            return
        }
        val previous = found.value.firstOrNull { it.address == snapshot.address }
        found.value = found.value.filterNot { it.address == snapshot.address } + snapshot
        emit(
            if (previous == null) {
                ScanEvent.Appeared(snapshot)
            } else {
                ScanEvent.Updated(snapshot)
            }
        )
        emit(ScanEvent.Packet(snapshot, timestampMillis))
    }

    fun disappear(address: String) {
        val old = found.value.firstOrNull { it.address.equals(address, true) } ?: return
        found.value = found.value - old
        emit(ScanEvent.Disappeared(old))
    }

    private fun emit(event: ScanEvent) {
        if (!events.tryEmit(event)) {
            dropped.value++
        }
    }

    fun setAdapterState(state: AdapterState) {
        check(adapter.value != AdapterState.Closed) { "Manager is closed" }
        adapter.value = state
        if (state != AdapterState.Ready) {
            stopScan()
            active.value.toList().forEach {
                (it as FakeBleConnection).disconnect(
                    BleException(BleError.NotReady, "Adapter unavailable")
                )
            }
        }
    }

    override suspend fun refreshAdapterState(): AdapterState = adapter.value

    private fun checkReady() {
        if (adapter.value != AdapterState.Ready)
            throw BleException(
                if (adapter.value == AdapterState.Closed) {
                    BleError.Closed
                } else {
                    BleError.NotReady
                },
                "Adapter is ${adapter.value}",
            )
    }

    override suspend fun startScan(options: ScanOptions) {
        checkReady()
        scanOptions = options
        found.value = emptyList()
        scanning.value = ScanState(scanning = true)
    }

    override fun stopScan() {
        scanning.value = ScanState()
    }

    override suspend fun connect(device: BleDevice, timeoutMillis: Long): BleConnection {
        checkReady()
        require(timeoutMillis > 0)
        val address = device.address.uppercase()
        if (connectionFor(address) != null || !reserved.add(address))
            throw BleException(BleError.Rejected, "Address is already connected")
        connectAttempts++
        try {
            val outcome = connectOutcomes.removeFirstOrNull() ?: FakeOutcome()
            if (outcome.delayMillis >= timeoutMillis) {
                delay(timeoutMillis)
                throw BleException(BleError.Timeout, "Simulated connect timeout")
            }
            delay(outcome.delayMillis)
            checkReady()
            outcome.error?.let { throw it }
            val peripheral =
                radio[address]
                    ?: throw BleException(BleError.Disconnected, "Peripheral unavailable")
            val connection =
                FakeBleConnection("fake-${++nextId}", peripheral) { closed ->
                    active.value = active.value - closed
                }
            active.value = active.value + connection
            return connection
        } finally {
            reserved.remove(address)
        }
    }

    override suspend fun getPairingState(device: BleDevice): PairingState {
        checkReady()
        return if (device.address.uppercase() in paired) {
            PairingState.Paired
        } else PairingState.NotPaired
    }

    override suspend fun pair(device: BleDevice, timeoutMillis: Long): PairResult {
        checkReady()
        require(timeoutMillis > 0)
        if (connectionFor(device.address) != null)
            throw BleException(BleError.Rejected, "Close the connection first")
        return if (paired.add(device.address.uppercase())) {
            PairResult.Paired
        } else PairResult.AlreadyPaired
    }

    override suspend fun unpair(device: BleDevice, timeoutMillis: Long): UnpairResult {
        checkReady()
        require(timeoutMillis > 0)
        if (connectionFor(device.address) != null)
            throw BleException(BleError.Rejected, "Close the connection first")
        return if (paired.remove(device.address.uppercase())) {
            UnpairResult.Unpaired
        } else UnpairResult.AlreadyUnpaired
    }

    override fun close() {
        if (adapter.value == AdapterState.Closed) {
            return
        }
        stopScan()
        disconnectAll()
        adapter.value = AdapterState.Closed
    }
}
