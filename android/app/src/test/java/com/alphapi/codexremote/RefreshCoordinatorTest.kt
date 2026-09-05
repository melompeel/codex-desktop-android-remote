package com.alphapi.codexremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {
    @Test
    fun conflatesBurstsWithoutRunningConcurrentRefreshes() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        var runs = 0
        var active = 0
        var maxActive = 0
        val coordinator = ConflatedRefreshCoordinator(this) {
            runs += 1
            active += 1
            maxActive = maxOf(maxActive, active)
            if (runs == 1) releaseFirst.await()
            active -= 1
        }

        repeat(30) { coordinator.request(0) }
        runCurrent()
        repeat(30) { coordinator.request(0) }
        runCurrent()

        assertEquals(1, runs)
        assertEquals(1, maxActive)

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, runs)
        assertEquals(1, maxActive)
        coordinator.dispose()
    }
}
