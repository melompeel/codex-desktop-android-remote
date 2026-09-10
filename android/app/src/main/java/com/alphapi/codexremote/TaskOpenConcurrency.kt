package com.alphapi.codexremote

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

internal data class TaskOpenResults<T>(
    val open: Result<Unit>,
    val history: Result<T>,
)

internal suspend fun <T> loadTaskWhileOpening(
    openTask: suspend () -> Unit,
    loadHistory: suspend () -> T,
    onHistoryLoaded: (T) -> Unit,
): TaskOpenResults<T> = supervisorScope {
    val opening = async { runCatching { openTask() } }
    val history = runCatching { loadHistory() }
    history.onSuccess(onHistoryLoaded)
    TaskOpenResults(open = opening.await(), history = history)
}
