package com.alphapi.codexremote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSchedulerTest {
    @Test
    fun openingReplacementStreamDoesNotCancelItsOwnRefresh() = runTest {
        var refreshes = 0
        lateinit var scheduler: ReconnectScheduler
        scheduler = ReconnectScheduler(this) {
            // RemoteRepository.openStream cancels any pending retry before opening a socket.
            scheduler.cancel()
            currentCoroutineContext().ensureActive()
            refreshes++
        }

        scheduler.schedule()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("reopening the stream must also refresh the selected task", 1, refreshes)
    }

    @Test
    fun immediateConnectionFailureKeepsRetryingUntilServerReturns() = runTest {
        var attempts = 0
        lateinit var scheduler: ReconnectScheduler
        scheduler = ReconnectScheduler(this) {
            attempts++
            if (attempts < 3) scheduler.schedule()
        }

        scheduler.schedule()
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()

        assertEquals("a fast connection refusal must not permanently stop reconnection", 3, attempts)
        scheduler.cancel()
    }

    @Test
    fun repeatedDisconnectSignalsDoNotOpenParallelSockets() = runTest {
        var attempts = 0
        val scheduler = ReconnectScheduler(this) { attempts++ }
        repeat(10) { scheduler.schedule() }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, attempts)
        scheduler.cancel()
    }

    @Test
    fun resetCancelsOldAddressRetryAndRestartsBackoffForNewAddress() = runTest {
        var attempts = 0
        val scheduler = ReconnectScheduler(this) { attempts++ }
        scheduler.schedule()
        runCurrent()
        scheduler.reset()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, attempts)

        scheduler.schedule()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, attempts)
        scheduler.cancel()
    }
}
