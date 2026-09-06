package com.alphapi.codexremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReviewStoreTest {
    @Test
    fun marksObservedActiveTaskWhenItFinishes() {
        assertTrue(shouldMarkCompleted("active", 10, "idle", 20))
        assertTrue(shouldMarkCompleted("inProgress", 10, "failed", 20))
    }

    @Test
    fun doesNotMarkExistingHistoryOnFirstSync() {
        assertFalse(shouldMarkCompleted(null, null, "idle", 20))
    }

    @Test
    fun marksAnOfflineCompletionWhenItsTimestampAdvances() {
        assertTrue(shouldMarkCompleted("idle", 10, "completed", 20))
        assertFalse(shouldMarkCompleted("idle", 20, "idle", 20))
        assertFalse(shouldMarkCompleted("active", 10, "active", 20))
    }
}
