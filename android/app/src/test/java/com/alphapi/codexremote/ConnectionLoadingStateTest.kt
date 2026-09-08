package com.alphapi.codexremote

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLoadingStateTest {
    @Test
    fun initialConnectionShowsProgressInsteadOfAFalseDisconnect() {
        val presentation = connectionStatusPresentation(
            RemoteState(connected = true, taskListLoading = true),
        )

        assertEquals("Bridge 已连接，正在确认 Codex Desktop", presentation.text)
        assertFalse(presentation.isError)
    }

    @Test
    fun verifiedDesktopKeepsItsConnectedStatusWhileTasksLoad() {
        val presentation = connectionStatusPresentation(
            RemoteState(
                connected = true,
                ipcConnected = true,
                desktopVersion = "26.901.6511.0",
                taskListLoading = true,
            ),
        )

        assertEquals("Codex Desktop 26.901.6511.0 · 正在加载任务", presentation.text)
        assertFalse(presentation.isError)
    }

    @Test
    fun aRealDisconnectIsStillVisibleAfterTheFirstSuccessfulConnection() {
        val presentation = connectionStatusPresentation(
            RemoteState(
                connected = false,
                connectionEstablished = true,
                taskListLoading = true,
            ),
        )

        assertEquals("手机连接中断，正在重连", presentation.text)
        assertTrue(presentation.isError)
    }

    @Test
    fun startupDoesNotLoadAConversationUntilTheUserSelectsIt() {
        val tasks = listOf(task("thread-1"), task("thread-2"))

        assertNull(threadIdForDetail(null, tasks))
        assertEquals("thread-2", threadIdForDetail("thread-2", tasks))
    }

    @Test
    fun aTaskRefreshTimeoutUsesAProgressMessageAndRetries() {
        val presentation = refreshFailurePresentation(
            SocketTimeoutException("timeout"),
            RemoteState(connected = true, ipcConnected = true, taskListLoading = true),
        )

        assertEquals("任务较多，正在继续加载", presentation.message)
        assertTrue(presentation.shouldRetry)
        assertFalse(presentation.isError)
    }

    @Test
    fun aDisconnectedTimeoutDoesNotExposeTheRawException() {
        val presentation = refreshFailurePresentation(
            SocketTimeoutException("timeout"),
            RemoteState(connected = false),
        )

        assertEquals("连接超时，请检查网络或调整左侧的“连接路由”", presentation.message)
        assertFalse(presentation.shouldRetry)
        assertTrue(presentation.isError)
    }

    private fun task(threadId: String) = TaskDto(
        threadId = threadId,
        title = threadId,
        status = "idle",
        revision = 1,
        pendingApprovals = 0,
    )
}
