package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun normalizesReleaseTags() {
        assertEquals("0.4.0", normalizeVersion("v0.4.0"))
        assertEquals("2.1", normalizeVersion("2.1"))
        assertNull(normalizeVersion("release-next"))
    }

    @Test
    fun comparesNumericVersionSegments() {
        assertEquals(1, compareVersions("0.10.0", "0.9.9"))
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertEquals(-1, compareVersions("1.2.9", "1.3.0"))
    }
}
