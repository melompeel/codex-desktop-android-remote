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

    @Test
    fun newerReleaseSupersedesAnOlderActiveDownload() {
        val oldUpdate = update("0.5.2")
        val latestUpdate = update("0.5.3")

        val result = resolveCheckedUpdateState(
            currentVersion = "0.5.1",
            latestUpdate = latestUpdate,
            storedState = UpdateState.Downloading(oldUpdate, 188L),
        )

        assertEquals(UpdateState.Available(latestUpdate), result)
    }

    @Test
    fun matchingReleaseKeepsAnActiveOrDownloadedUpdate() {
        val latestUpdate = update("0.5.3")
        val downloading = UpdateState.Downloading(latestUpdate, 189L)
        val ready = UpdateState.ReadyToInstall(latestUpdate, 189L)

        assertEquals(
            downloading,
            resolveCheckedUpdateState("0.5.1", latestUpdate, downloading),
        )
        assertEquals(
            ready,
            resolveCheckedUpdateState("0.5.1", latestUpdate, ready),
        )
    }

    @Test
    fun currentAppDoesNotOfferAnOlderRelease() {
        assertEquals(
            UpdateState.Current,
            resolveCheckedUpdateState("0.5.3", update("0.5.2"), null),
        )
    }

    @Test
    fun currentUpdateDialogUsesAConfirmationLabel() {
        assertEquals("确认", updateDialogConfirmLabel(UpdateState.Current))
        assertEquals("后台下载", updateDialogConfirmLabel(UpdateState.Downloading(update("0.5.3"), 190L)))
    }

    private fun update(version: String) = AppUpdate(
        version = version,
        apkUrl = "https://example.invalid/$version.apk",
        releasePageUrl = "https://example.invalid/$version",
        notes = "",
    )
}
