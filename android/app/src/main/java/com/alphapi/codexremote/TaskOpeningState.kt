package com.alphapi.codexremote

internal enum class TaskOpenAction {
    FOLLOW,
    ACTIVATE,
    WAIT,
}

internal fun taskOpenAction(
    task: TaskDto?,
    capabilities: RemoteCapabilitiesDto,
    activationInProgress: Boolean,
): TaskOpenAction = when {
    activationInProgress -> TaskOpenAction.WAIT
    task?.ownerAvailable == false && capabilities.taskActivation -> TaskOpenAction.ACTIVATE
    else -> TaskOpenAction.FOLLOW
}
