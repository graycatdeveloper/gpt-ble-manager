@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.gpt.ble

import dev.gpt.ble.internal.gatt.OperationQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class OperationQueueTest {
    @Test
    fun requestsRunOneAtATime() = runTest {
        val queue = OperationQueue { error("Unexpected abort") }
        val events = mutableListOf<String>()
        val first = async {
            queue.execute(1000) {
                events += "first start"
                delay(50)
                events += "first end"
                1
            }
        }
        val second = async {
            queue.execute(1000) {
                events += "second"
                2
            }
        }
        assertEquals(1, first.await())
        assertEquals(2, second.await())
        assertEquals(listOf("first start", "first end", "second"), events)
    }

    @Test
    fun timeoutClosesQueueAndRejectsFutureOperations() = runTest {
        var aborted: BleException? = null
        val queue = OperationQueue { aborted = it }
        val error = assertFailsWith<BleException> { queue.execute(100) { delay(101) } }
        assertEquals(BleError.Timeout, error.code)
        assertSame(error, aborted)
        assertFailsWith<BleException> { queue.execute(100) { fail("Must not start") } }
    }

    @Test
    fun cancellingAnActiveRequestClosesQueue() = runTest {
        var aborted = false
        val queue = OperationQueue { aborted = true }
        val operation = launch { queue.execute(1000) { awaitCancellation() } }
        runCurrent()
        operation.cancelAndJoin()
        assertTrue(aborted)
    }

    @Test
    fun cancellingAQueuedRequestDoesNotCloseActiveRequest() = runTest {
        var aborted = false
        val queue = OperationQueue { aborted = true }
        val first = async {
            queue.execute(1000) {
                delay(100)
                42
            }
        }
        val second = launch { queue.execute(1000) { fail("Queued request was cancelled") } }
        runCurrent()
        second.cancelAndJoin()
        assertEquals(42, first.await())
        assertFalse(aborted)
    }

    @Test
    fun disconnectInterruptsPendingOperation() = runTest {
        val queue = OperationQueue {}
        val disconnected = BleException(BleError.Disconnected, "Link lost")
        val job = async { runCatching { queue.execute(1000) { awaitCancellation() } } }
        runCurrent()
        queue.stop(disconnected)
        assertSame(disconnected, job.await().exceptionOrNull())
    }

    @Test
    fun ordinaryGattErrorDoesNotPreventNextOperation() = runTest {
        val queue = OperationQueue { error("Unexpected abort") }
        assertFailsWith<BleException> {
            queue.execute(100) { throw BleException(BleError.Protocol, "Read denied") }
        }
        assertEquals(7, queue.execute(100) { 7 })
    }
}
