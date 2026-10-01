package dev.gpt.ble.windows.pairing

import dev.gpt.ble.BleDevice
import dev.gpt.ble.BleError
import dev.gpt.ble.BleException
import dev.gpt.ble.PairResult
import dev.gpt.ble.PairingState
import dev.gpt.ble.UnpairResult
import dev.gpt.ble.internal.canonicalAddress
import dev.gpt.ble.windows.NativeBridge
import dev.gpt.ble.windows.asBleException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pair/unpair резервируют тот же адрес, что connect. finally освобождает резервирование при успехе,
 * ошибке и отмене; результат WinRT классифицируется отдельно в WindowsPairing. Блокирующее native
 * ожидание выполняется на Dispatchers.IO.
 */
internal class WindowsPairingController(
    private val native: NativeBridge,
    private val activeHandle: () -> Long,
    private val addresses: MutableSet<String>,
) {
    suspend fun getPairingState(device: BleDevice): PairingState =
        withContext(Dispatchers.IO) {
            val manager = activeHandle()
            val address = canonicalAddress(device.address)
            try {
                val result = native.pairingState(manager, address, device.addressType.ordinal)
                activeHandle()
                when (result) {
                    0 -> PairingState.NotPaired
                    1 -> PairingState.Paired
                    else -> PairingState.Unknown
                }
            } catch (e: IllegalStateException) {
                throw e.asBleException("Unable to read Windows pairing state")
            }
        }

    suspend fun pair(device: BleDevice, timeoutMillis: Long): PairResult =
        changePairing(device, timeoutMillis) { manager, address ->
            pairResult(native.pair(manager, address, device.addressType.ordinal, timeoutMillis))
        }

    suspend fun unpair(device: BleDevice, timeoutMillis: Long): UnpairResult =
        changePairing(device, timeoutMillis) { manager, address ->
            unpairResult(native.unpair(manager, address, device.addressType.ordinal, timeoutMillis))
        }

    private suspend fun <T> changePairing(
        device: BleDevice,
        timeoutMillis: Long,
        operation: (Long, String) -> T,
    ): T {
        require(timeoutMillis in 1..120_000)
        val manager = activeHandle()
        val address = canonicalAddress(device.address)
        // Also reserves the address against a concurrent connect/unpair on this manager.
        if (!addresses.add(address)) {
            throw BleException(
                BleError.Rejected,
                "Close the connection to $address and wait for pending operations before changing pairing",
            )
        }
        try {
            return withContext(Dispatchers.IO) {
                operation(manager, address)
            }
        } catch (e: IllegalStateException) {
            throw e.asBleException("Windows pairing operation failed")
        } finally {
            addresses.remove(address)
        }
    }
}
