package com.alphapi.codexremote

internal data class ConnectionStatusPresentation(
    val text: String,
    val isError: Boolean,
)

internal data class RefreshFailurePresentation(
    val message: String,
    val shouldRetry: Boolean,
    val isError: Boolean,
)

internal fun connectionStatusPresentation(state: RemoteState): ConnectionStatusPresentation = when {
    !state.error.isNullOrBlank() -> ConnectionStatusPresentation(
        text = state.error,
        isError = true,
    )
    !state.connected && !state.connectionEstablished && state.taskListLoading -> ConnectionStatusPresentation(
        text = "正在连接电脑上的 Codex",
        isError = false,
    )
    !state.connected -> ConnectionStatusPresentation(
        text = "手机连接中断，正在重连",
        isError = true,
    )
    !state.ipcConnected && state.taskListLoading -> ConnectionStatusPresentation(
        text = "Bridge 已连接，正在确认 Codex Desktop",
        isError = false,
    )
    !state.ipcConnected -> ConnectionStatusPresentation(
        text = "Codex Desktop 未连接",
        isError = true,
    )
    state.taskListLoading -> ConnectionStatusPresentation(
        text = "Codex Desktop ${state.desktopVersion ?: "已连接"} · 正在加载任务",
        isError = false,
    )
    state.desktopVersion != null -> ConnectionStatusPresentation(
        text = "Codex Desktop ${state.desktopVersion}",
        isError = false,
    )
    else -> ConnectionStatusPresentation(
        text = "Codex Desktop 已连接",
        isError = false,
    )
}

internal data class EmptyTaskListPresentation(
    val showLoading: Boolean,
    val message: String,
    val isError: Boolean,
)

internal fun emptyTaskListPresentation(
    connected: Boolean,
    loading: Boolean,
    error: String?,
): EmptyTaskListPresentation = when {
    !connected -> EmptyTaskListPresentation(
        showLoading = false,
        message = error?.takeIf(String::isNotBlank) ?: "Bridge 未连接",
        isError = true,
    )
    loading -> EmptyTaskListPresentation(
        showLoading = true,
        message = "任务较多，正在继续加载",
        isError = false,
    )
    else -> EmptyTaskListPresentation(
        showLoading = false,
        message = "这里还没有任务",
        isError = false,
    )
}

internal fun isAuthorizationFailure(error: Throwable): Boolean =
    error is BridgeHttpException && error.statusCode == 401

internal fun authenticatedBridgeErrorMessage(error: Throwable): String = when {
    isAuthorizationFailure(error) -> "当前终端授权已失效，请在 Codex 终端中重新配对"
    else -> error.message?.takeIf(String::isNotBlank) ?: "连接失败"
}

internal fun threadIdForDetail(requestedThreadId: String?, tasks: List<TaskDto>): String? =
    requestedThreadId?.takeIf { selected -> tasks.any { it.threadId == selected } }

internal fun refreshFailurePresentation(
    error: Throwable,
    state: RemoteState,
): RefreshFailurePresentation {
    if (isAuthorizationFailure(error)) {
        return RefreshFailurePresentation(
            message = authenticatedBridgeErrorMessage(error),
            shouldRetry = false,
            isError = true,
        )
    }
    val detail = buildString {
        append(error::class.simpleName.orEmpty())
        append(' ')
        append(error.message.orEmpty())
    }.lowercase()
    val timedOut = "timeout" in detail || "timed out" in detail
    if (timedOut && state.connected) {
        return RefreshFailurePresentation(
            message = "任务较多，正在继续加载",
            shouldRetry = true,
            isError = false,
        )
    }
    if (timedOut) {
        return RefreshFailurePresentation(
            message = "连接超时，请检查网络或调整左侧的“连接路由”",
            shouldRetry = false,
            isError = true,
        )
    }
    return RefreshFailurePresentation(
        message = authenticatedBridgeErrorMessage(error),
        shouldRetry = false,
        isError = true,
    )
}
