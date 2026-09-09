package com.alphapi.codexremote

internal data class SelectedTaskRefresh(
    val threadId: String?,
    val detail: TaskDetailDto?,
)

internal fun resolveSelectedTaskRefresh(
    selectedThreadId: String?,
    currentDetail: TaskDetailDto?,
    tasks: List<TaskDto>,
    requestedThreadId: String?,
    fetchedDetail: TaskDetailDto?,
): SelectedTaskRefresh {
    val selected = threadIdForDetail(selectedThreadId, tasks)
    val detail = when {
        selected == null -> null
        selected == requestedThreadId && fetchedDetail?.threadId == selected ->
            mergeLatestHistory(currentDetail?.takeIf { it.threadId == selected }, fetchedDetail)
        else -> currentDetail?.takeIf { it.threadId == selected }
    }
    return SelectedTaskRefresh(selected, detail)
}
