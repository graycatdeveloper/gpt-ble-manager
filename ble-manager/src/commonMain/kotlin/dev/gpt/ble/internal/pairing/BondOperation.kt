package dev.gpt.ble.internal.pairing

import dev.gpt.ble.BleError
import dev.gpt.ble.BleException
import dev.gpt.ble.PairingState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** Waits for a completed OS bond change; a successful start is not a successful pairing. */
internal class BondOperation(private val target: PairingState) {
    private val events = Channel<PairingState>(Channel.UNLIMITED)

    fun changed(state: PairingState) {
        events.trySend(state)
    }

    fun stop(error: BleException) {
        events.close(error)
    }

    /**
     * Returns false if already at target. Listener registration precedes both snapshot and start.
     */
    suspend fun execute(
        timeoutMillis: Long,
        state: () -> PairingState,
        listen: (() -> Unit) -> (() -> Unit),
        start: () -> Boolean,
    ): Boolean {
        val release = listen { changed(state()) }
        try {
            val initial = state()
            if (initial == target) {
                return false
            }
            if (initial == PairingState.Unknown) {
                throw BleException(BleError.NotReady, "Bluetooth bond state is unavailable")
            }
            if (target == PairingState.NotPaired && initial == PairingState.Pairing) {
                throw BleException(
                    BleError.Rejected,
                    "Wait for the current pairing operation before unpairing",
                )
            }
            // Join an already running OS pairing instead of submitting another createBond().
            if (initial != PairingState.Pairing && !start()) {
                if (state() == target) {
                    return true
                }
                throw BleException(BleError.Rejected, "Android rejected the bond change request")
            }
            if (state() == target) {
                return true
            }
            return withTimeoutOrNull(timeoutMillis.milliseconds) {
                while (true) {
                    val next = events.receive()
                    if (next == target) {
                        return@withTimeoutOrNull true
                    }
                    if (target == PairingState.Paired && next == PairingState.NotPaired) {
                        throw BleException(
                            BleError.Rejected,
                            "Android pairing failed or was cancelled",
                        )
                    }
                    if (next == PairingState.Unknown) {
                        throw BleException(
                            BleError.NotReady,
                            "Bluetooth became unavailable during pairing",
                        )
                    }
                }
                @Suppress("UNREACHABLE_CODE") false
            }
                ?: throw BleException(
                    BleError.Timeout,
                    "Android bond change timed out; query pairing state before retrying",
                )
        } finally {
            release()
            events.close()
        }
    }
}
