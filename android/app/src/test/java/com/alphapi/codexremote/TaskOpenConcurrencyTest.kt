package com.alphapi.codexremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskOpenConcurrencyTest {
    @Test
    fun historyIsDeliveredBeforeBlockedDesktopActivationCompletes() = runTest {
        val activationGate = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()

        val job = launch {
            loadTaskWhileOpening(
                openTask = { activationGate.await() },
                loadHistory = { "cached catalog history" },
                onHistoryLoaded = { delivered += it },
            )
        }
        runCurrent()

        assertEquals(listOf("cached catalog history"), delivered)
        assertFalse(job.isCompleted)

        activationGate.complete(Unit)
        runCurrent()
        assertTrue(job.isCompleted)
    }
}
