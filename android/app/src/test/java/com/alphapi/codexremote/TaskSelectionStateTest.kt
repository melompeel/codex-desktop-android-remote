package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSelectionStateTest {
    @Test
    fun aLateRefreshForThePreviousTaskCannotReplaceTheNewSelection() {
        val tasks = listOf(task("thread-a"), task("thread-b"))
        val current = detail("thread-a", "current A")
        val late = detail("thread-b", "late B")

        val result = resolveSelectedTaskRefresh(
            selectedThreadId = "thread-a",
            currentDetail = current,
            tasks = tasks,
            requestedThreadId = "thread-b",
            fetchedDetail = late,
        )

        assertEquals("thread-a", result.threadId)
        assertEquals("thread-a", result.detail?.threadId)
        assertEquals("current A", result.detail?.items?.single()?.text)
    }

    @Test
    fun theCurrentTasksRefreshStillUpdatesTheSelectedDetail() {
        val tasks = listOf(task("thread-a"))
        val result = resolveSelectedTaskRefresh(
            selectedThreadId = "thread-a",
            currentDetail = detail("thread-a", "old"),
            tasks = tasks,
            requestedThreadId = "thread-a",
            fetchedDetail = detail("thread-a", "new"),
        )

        assertEquals("thread-a", result.threadId)
        assertEquals("new", result.detail?.items?.single()?.text)
    }

    @Test
    fun aNetworkTransportChangeRequestsAReconnectButDuplicateCallbacksDoNot() {
        val wifi = NetworkTransportSignature("10", wifi = true, cellular = false, ethernet = false, vpn = true, validated = true)
        val cellular = NetworkTransportSignature("11", wifi = false, cellular = true, ethernet = false, vpn = true, validated = true)

        assertEquals(true, shouldReconnectForNetworkChange(null, wifi))
        assertEquals(false, shouldReconnectForNetworkChange(wifi, wifi))
        assertEquals(true, shouldReconnectForNetworkChange(wifi, cellular))
        assertEquals(true, shouldReconnectForNetworkChange(cellular, null))
    }

    private fun task(id: String) = TaskDto(id, id, "active", 1, 0, true)

    private fun detail(id: String, text: String) = TaskDetailDto(
        threadId = id,
        title = id,
        status = "active",
        revision = 1,
        items = listOf(TimelineItemDto("reply", "turn", "assistant", text)),
    )
}
