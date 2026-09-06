package com.alphapi.codexremote

import android.app.DownloadManager
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

    @Test
    fun mapsSystemDownloadStatesToRestorableUpdateStates() {
        assertEquals(DownloadStatus.ACTIVE, classifyDownloadStatus(DownloadManager.STATUS_PENDING))
        assertEquals(DownloadStatus.ACTIVE, classifyDownloadStatus(DownloadManager.STATUS_RUNNING))
        assertEquals(DownloadStatus.ACTIVE, classifyDownloadStatus(DownloadManager.STATUS_PAUSED))
        assertEquals(DownloadStatus.READY, classifyDownloadStatus(DownloadManager.STATUS_SUCCESSFUL))
        assertEquals(DownloadStatus.FAILED, classifyDownloadStatus(DownloadManager.STATUS_FAILED))
        assertEquals(DownloadStatus.MISSING, classifyDownloadStatus(null))
    }

    @Test
    fun createsStableApkFileNames() {
        assertEquals("codex-desktop-remote-0.4.2.apk", updateApkFileName("v0.4.2"))
        assertEquals("codex-desktop-remote-update.apk", updateApkFileName("preview"))
    }
}
