package com.alphapi.codexremote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundRefreshLoopTest {
    @Test
    fun keepsRefreshingWhenNoActivityIsCollectingTheRepository() = runTest {
        var refreshes = 0
        val loop = BackgroundRefreshLoop(this, intervalMs = { 10_000L }) { refreshes++ }

        loop.start()
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(3, refreshes)
        loop.stop()
    }

    @Test
    fun usesFastPollingOnlyWhileCodexHasAnActiveTask() {
        assertEquals(15_000L, backgroundRefreshInterval(listOf("idle", "inProgress")))
        assertEquals(15_000L, backgroundRefreshInterval(listOf("active")))
        assertEquals(120_000L, backgroundRefreshInterval(listOf("idle", "completed")))
    }
}
