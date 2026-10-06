package gpt.ble.manager.internal.scan

import gpt.ble.manager.ScanEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Keeps radio callbacks non-blocking; exposes loss instead of silently dropping a full buffer. */
internal class ScanEventPublisher {
    private val mutableEvents = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 256)
    private val lost = MutableStateFlow(0L)
    val events = mutableEvents.asSharedFlow()
    val dropped = lost.asStateFlow()

    fun emit(event: ScanEvent) {
        if (!mutableEvents.tryEmit(event)) {
            lost.update { it + 1 }
        }
    }
}
