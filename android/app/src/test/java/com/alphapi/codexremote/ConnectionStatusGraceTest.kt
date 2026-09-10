package com.alphapi.codexremote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStatusGraceTest {
    @Test
    fun aShortDisconnectNeverPublishesDisconnectedState() = runTest {
        val states = mutableListOf<Boolean>()
        val grace = ConnectionStatusGrace(this, 4_000) { states += it }

        grace.connected()
        grace.disconnected()
        advanceTimeBy(3_999)
        runCurrent()
        grace.connected()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(listOf(true), states)
    }

    @Test
    fun aPersistentDisconnectBecomesVisibleAfterTheGracePeriod() = runTest {
        val states = mutableListOf<Boolean>()
        val grace = ConnectionStatusGrace(this, 4_000) { states += it }

        grace.connected()
        grace.disconnected()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(listOf(true, false), states)
    }

    @Test
    fun authorizationFailureBypassesTheGracePeriod() = runTest {
        val states = mutableListOf<Boolean>()
        val grace = ConnectionStatusGrace(this, 4_000) { states += it }

        grace.connected()
        grace.disconnected(immediate = true)

        assertEquals(listOf(true, false), states)
    }
}
