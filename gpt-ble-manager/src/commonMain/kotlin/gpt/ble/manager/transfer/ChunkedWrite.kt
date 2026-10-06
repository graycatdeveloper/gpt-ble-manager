package gpt.ble.manager.transfer

import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleConnection
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.WriteMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class ChunkedWriteOptions(
    val mode: WriteMode = WriteMode.WithResponse,
    /** Null uses the current MTU - 3. Re-evaluated for each packet. */
    val maxChunkBytes: Int? = null,
    val pacingMillis: Long = 0,
    /** Bytes reserved for an application header/checksum added by the frame callback. */
    val framingOverheadBytes: Int = 0,
) {
    init {
        require(maxChunkBytes == null || maxChunkBytes > 0)
        require(pacingMillis >= 0)
        require(framingOverheadBytes >= 0)
    }
}

data class TransferProgress(val bytesSent: Int, val totalBytes: Int, val packetsSent: Int) {
    val complete: Boolean
        get() = bytesSent == totalBytes
}

data class TransferChunk(
    val index: Int,
    val offset: Int,
    val totalBytes: Int,
    val payload: BleBytes,
)

/**
 * Opt-in raw chunking, only for peripherals whose protocol accepts consecutive payload fragments. A
 * cold flow: each collection starts a NEW transfer. Values are copied when this method is called.
 * Progress means completed platform writes, not application-level acknowledgement. Cancelling a
 * running write closes its connection; cancelling between packets leaves it open. Never retries
 * packets. Other callers can enqueue operations between chunks; use one application transfer owner
 * when your device protocol requires exclusive ordering.
 */
fun BleConnection.writeChunks(
    characteristic: GattCharacteristic,
    value: ByteArray,
    options: ChunkedWriteOptions = ChunkedWriteOptions(),
    frame: (TransferChunk) -> ByteArray = { it.payload.toByteArray() },
): Flow<TransferProgress> {
    val bytes = value.copyOf()
    return flow {
        var offset = 0
        var packets = 0
        emit(TransferProgress(0, bytes.size, 0))
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val available = mtu.value - 3 - options.framingOverheadBytes
            check(available > 0) { "Invalid negotiated MTU" }
            val count = minOf(available, options.maxChunkBytes ?: available, bytes.size - offset)
            val packet =
                frame(
                    TransferChunk(
                        packets,
                        offset,
                        bytes.size,
                        BleBytes(bytes.copyOfRange(offset, offset + count)),
                    )
                )
            require(packet.size <= mtu.value - 3) { "Framed packet exceeds MTU - 3" }
            write(characteristic, packet, options.mode)
            offset += count
            packets++
            emit(TransferProgress(offset, bytes.size, packets))
            if (offset < bytes.size && options.pacingMillis > 0) {
                delay(options.pacingMillis)
            }
        }
    }
}
