package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationHistoryTest {
    @Test
    fun requestsAnotherPageWhenCollapsedBlocksFitWithoutScrolling() {
        val viewport = viewport(
            firstIndex = 0,
            offset = 0,
            totalItems = 3,
            canScrollBackward = false,
            canScrollForward = false,
        )

        assertEquals(true, shouldRequestOlderHistory(viewport, viewport))
    }

    @Test
    fun requestsAnotherPageWhenScrollingUpInsideTheFirstTallBlock() {
        val previous = viewport(firstIndex = 0, offset = 480, canScrollBackward = true)
        val current = viewport(firstIndex = 0, offset = 0, canScrollForward = true)

        assertEquals(true, shouldRequestOlderHistory(previous, current))
    }

    @Test
    fun doesNotRequestHistoryWhileRemainingAtTheScrollableLatestPage() {
        val viewport = viewport(
            firstIndex = 8,
            offset = 0,
            canScrollBackward = true,
            canScrollForward = false,
        )

        assertEquals(false, shouldRequestOlderHistory(viewport, viewport))
    }

    @Test
    fun prependsOlderPageWithoutDuplicatingTheAnchorMessage() {
        val current = detail(
            items = listOf(item("anchor", "latest request"), item("final", "latest result")),
            cursor = "older-cursor",
            hasMore = true,
        )
        val older = detail(
            items = listOf(item("old", "older request"), item("anchor", "latest request")),
            cursor = null,
            hasMore = false,
        )

        val merged = mergeOlderHistory(current, older)

        assertEquals(listOf("old", "anchor", "final"), merged.items.map { it.id })
        assertFalse(merged.hasMoreHistory)
        assertEquals(null, merged.historyCursor)
    }

    @Test
    fun liveRefreshKeepsPreviouslyLoadedHistoryAndUpdatesExistingItems() {
        val current = detail(
            items = listOf(item("old", "older"), item("active", "working")),
            cursor = "older-cursor",
            hasMore = true,
        )
        val latest = detail(
            items = listOf(item("active", "finished"), item("new", "result")),
            cursor = "latest-cursor",
            hasMore = true,
        )

        val merged = mergeLatestHistory(current, latest)

        assertEquals(listOf("old", "active", "new"), merged.items.map { it.id })
        assertEquals("finished", merged.items[1].text)
        assertEquals("older-cursor", merged.historyCursor)
    }

    private fun detail(
        items: List<TimelineItemDto>,
        cursor: String?,
        hasMore: Boolean,
    ) = TaskDetailDto(
        threadId = "thread",
        title = "Thread",
        status = "idle",
        revision = 1,
        items = items,
        hasMoreHistory = hasMore,
        historyCursor = cursor,
    )

    private fun item(id: String, text: String) = TimelineItemDto(
        id = id,
        turnId = "turn",
        kind = if (id == "anchor" || id == "old") "user" else "assistant",
        text = text,
    )

    private fun viewport(
        firstIndex: Int,
        offset: Int,
        totalItems: Int = 12,
        canScrollBackward: Boolean = false,
        canScrollForward: Boolean = false,
    ) = HistoryViewport(
        firstVisibleItemIndex = firstIndex,
        firstVisibleItemScrollOffset = offset,
        totalItemsCount = totalItems,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward,
    )
}
