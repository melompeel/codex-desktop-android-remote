package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TaskConnectionPresentationTest {
    @Test
    fun liveOwnerDoesNotNeedAConnectionBanner() {
        assertNull(
            taskConnectionPresentation(
                ownerAvailable = true,
                activating = false,
                syncing = false,
            ),
        )
    }

    @Test
    fun activationExplainsThatLoadedHistoryRemainsVisible() {
        val presentation = taskConnectionPresentation(
            ownerAvailable = false,
            activating = true,
            syncing = true,
        )

        assertEquals("正在连接桌面任务，当前显示已加载的历史记录", presentation?.text)
        assertFalse(presentation!!.isError)
    }

    @Test
    fun missingSnapshotDoesNotClaimThatDesktopIsClosed() {
        val presentation = taskConnectionPresentation(
            ownerAvailable = false,
            activating = false,
            syncing = false,
        )

        assertEquals("正在确认桌面任务连接，当前显示已加载的历史记录", presentation?.text)
        assertFalse(presentation!!.text.contains("未打开"))
        assertFalse(presentation.isError)
    }
}
