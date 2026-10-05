@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package gpt.ble.manager

import gpt.ble.manager.internal.pairing.BondOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class BondOperationTest {
    private class FakeBond(var state: PairingState = PairingState.NotPaired) {
        var listener: (() -> Unit)? = null
        var starts = 0
        var releases = 0
        var onStart: () -> Boolean = { true }

        suspend fun run(operation: BondOperation) =
            operation.execute(
                1_000,
                state = { state },
                listen = { callback ->
                    listener = callback
                    val release: () -> Unit = {
                        releases++
                        listener = null
                    }
                    release
                },
                start = {
                    assertNotNull(listener)
                    starts++
                    onStart()
                },
            )

        fun emit(next: PairingState) {
            state = next
            listener?.invoke()
        }
    }

    @Test
    fun acceptedRequestWaitsForCompletedPairing() = runTest {
        val fake = FakeBond()
        val result = async { fake.run(BondOperation(PairingState.Paired)) }
        runCurrent()
        assertFalse(result.isCompleted)
        fake.emit(PairingState.Pairing)
        runCurrent()
        assertFalse(result.isCompleted)
        fake.emit(PairingState.Paired)
        assertTrue(result.await())
        assertEquals(1, fake.releases)
    }

    @Test
    fun unpairWaitsForBondRemoval() = runTest {
        val fake = FakeBond(PairingState.Paired)
        val result = async { fake.run(BondOperation(PairingState.NotPaired)) }
        runCurrent()
        assertFalse(result.isCompleted)
        fake.emit(PairingState.NotPaired)
        assertTrue(result.await())
        assertEquals(1, fake.releases)
    }

    @Test
    fun alreadyAtTargetDoesNotStartAnOperation() = runTest {
        for (target in listOf(PairingState.Paired, PairingState.NotPaired)) {
            val fake = FakeBond(target)
            assertFalse(fake.run(BondOperation(target)))
            assertEquals(0, fake.starts)
            assertEquals(1, fake.releases)
        }
    }

    @Test
    fun synchronousCallbackIsNotLost() = runTest {
        val fake = FakeBond()
        fake.onStart = {
            fake.emit(PairingState.Paired)
            true
        }
        assertTrue(fake.run(BondOperation(PairingState.Paired)))
        assertEquals(1, fake.releases)
    }

    @Test
    fun joinsPairingAlreadyStartedByTheOs() = runTest {
        val fake = FakeBond(PairingState.Pairing)
        val result = async { fake.run(BondOperation(PairingState.Paired)) }
        runCurrent()
        assertEquals(0, fake.starts)
        fake.emit(PairingState.Paired)
        assertTrue(result.await())
    }

    @Test
    fun rejectedStartAndThrownStartBothReleaseListener() = runTest {
        val fake = FakeBond()
        fake.onStart = { false }
        assertEquals(
            BleError.Rejected,
            assertFailsWith<BleException> { fake.run(BondOperation(PairingState.Paired)) }.code,
        )
        fake.onStart = { throw BleException(BleError.PermissionDenied, "Denied") }
        assertEquals(
            BleError.PermissionDenied,
            assertFailsWith<BleException> { fake.run(BondOperation(PairingState.Paired)) }.code,
        )
        assertEquals(2, fake.releases)
    }

    @Test
    fun bondingFailureIsNotReportedAsSuccess() = runTest {
        val fake = FakeBond()
        val result = async { runCatching { fake.run(BondOperation(PairingState.Paired)) } }
        runCurrent()
        fake.emit(PairingState.Pairing)
        fake.emit(PairingState.NotPaired)
        assertEquals(BleError.Rejected, (result.await().exceptionOrNull() as BleException).code)
        assertEquals(1, fake.releases)
    }

    @Test
    fun timeoutReleasesListener() = runTest {
        val fake = FakeBond()
        assertEquals(
            BleError.Timeout,
            assertFailsWith<BleException> { fake.run(BondOperation(PairingState.Paired)) }.code,
        )
        assertEquals(1_000, currentTime)
        assertEquals(1, fake.releases)
    }

    @Test
    fun callerCancellationReleasesListener() = runTest {
        val fake = FakeBond()
        val job = launch { fake.run(BondOperation(PairingState.Paired)) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, fake.releases)
    }

    @Test
    fun managerCloseInterruptsWaiting() = runTest {
        val fake = FakeBond()
        val operation = BondOperation(PairingState.Paired)
        val result = async { runCatching { fake.run(operation) } }
        runCurrent()
        val error = BleException(BleError.Closed, "Closed")
        operation.stop(error)
        assertSame(error, result.await().exceptionOrNull())
        assertEquals(1, fake.releases)
    }

    @Test
    fun unavailableAdapterAndConflictingPairingDoNotStart() = runTest {
        val fake = FakeBond(PairingState.Unknown)
        assertEquals(
            BleError.NotReady,
            assertFailsWith<BleException> { fake.run(BondOperation(PairingState.Paired)) }.code,
        )
        fake.state = PairingState.Pairing
        assertEquals(
            BleError.Rejected,
            assertFailsWith<BleException> { fake.run(BondOperation(PairingState.NotPaired)) }.code,
        )
        assertEquals(0, fake.starts)
        assertEquals(2, fake.releases)
    }
}
