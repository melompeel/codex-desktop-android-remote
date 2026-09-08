package com.alphapi.codexremote

internal data class HistoryViewport(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val totalItemsCount: Int,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
)

internal fun shouldRequestOlderHistory(
    previous: HistoryViewport,
    current: HistoryViewport,
): Boolean {
    if (isHistoryViewportUnderfilled(current)) return true
    val movedTowardStart = current.firstVisibleItemIndex < previous.firstVisibleItemIndex ||
        (
            current.firstVisibleItemIndex == previous.firstVisibleItemIndex &&
                current.firstVisibleItemScrollOffset < previous.firstVisibleItemScrollOffset
            )
    return movedTowardStart && current.firstVisibleItemIndex <= 1
}

internal fun isHistoryViewportUnderfilled(viewport: HistoryViewport): Boolean =
    viewport.totalItemsCount > 0 &&
        !viewport.canScrollBackward &&
        !viewport.canScrollForward

internal fun mergeOlderHistory(
    current: TaskDetailDto,
    older: TaskDetailDto,
): TaskDetailDto {
    if (current.threadId != older.threadId) return current
    return current.copy(
        revision = maxOf(current.revision, older.revision),
        items = mergeTimelineItems(older.items, current.items),
        hasMoreHistory = older.hasMoreHistory,
        historyCursor = older.historyCursor,
    )
}

internal fun mergeLatestHistory(
    current: TaskDetailDto?,
    latest: TaskDetailDto,
): TaskDetailDto {
    if (current?.threadId != latest.threadId) return latest
    return latest.copy(
        items = mergeTimelineItems(current.items, latest.items),
        hasMoreHistory = current.hasMoreHistory,
        historyCursor = current.historyCursor,
    )
}

private fun mergeTimelineItems(
    older: List<TimelineItemDto>,
    newer: List<TimelineItemDto>,
): List<TimelineItemDto> {
    val merged = linkedMapOf<String, TimelineItemDto>()
    older.forEach { merged[timelineItemKey(it)] = it }
    newer.forEach { merged[timelineItemKey(it)] = it }
    return merged.values.toList()
}

private fun timelineItemKey(item: TimelineItemDto): String = "${item.turnId}\u0000${item.id}"
