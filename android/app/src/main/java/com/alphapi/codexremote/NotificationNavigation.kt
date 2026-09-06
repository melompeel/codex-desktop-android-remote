package com.alphapi.codexremote

import android.content.Intent

internal enum class RemoteNotificationKind(val wireValue: String) {
    APPROVAL("approval"),
    USER_INPUT("userInput"),
    COMPLETION("completion"),
}

internal data class RemoteNotificationRoute(
    val kind: RemoteNotificationKind,
    val threadId: String,
    val requestId: String? = null,
    val nonce: String = "${kind.wireValue}:$threadId:${requestId.orEmpty()}",
)

internal fun notificationKindForApproval(method: String): RemoteNotificationKind =
    if (method == "item/tool/requestUserInput") RemoteNotificationKind.USER_INPUT
    else RemoteNotificationKind.APPROVAL

internal fun Intent.putRemoteNotificationRoute(route: RemoteNotificationRoute): Intent = apply {
    putExtra(EXTRA_NOTIFICATION_KIND, route.kind.wireValue)
    putExtra(EXTRA_NOTIFICATION_THREAD_ID, route.threadId)
    route.requestId?.let { putExtra(EXTRA_NOTIFICATION_REQUEST_ID, it) }
    putExtra(EXTRA_NOTIFICATION_NONCE, route.nonce)
}

internal fun Intent.remoteNotificationRoute(): RemoteNotificationRoute? {
    val kind = when (getStringExtra(EXTRA_NOTIFICATION_KIND)) {
        RemoteNotificationKind.APPROVAL.wireValue -> RemoteNotificationKind.APPROVAL
        RemoteNotificationKind.USER_INPUT.wireValue -> RemoteNotificationKind.USER_INPUT
        RemoteNotificationKind.COMPLETION.wireValue -> RemoteNotificationKind.COMPLETION
        else -> return null
    }
    val threadId = getStringExtra(EXTRA_NOTIFICATION_THREAD_ID)?.takeIf(String::isNotBlank)
        ?: return null
    return RemoteNotificationRoute(
        kind = kind,
        threadId = threadId,
        requestId = getStringExtra(EXTRA_NOTIFICATION_REQUEST_ID)?.takeIf(String::isNotBlank),
        nonce = getStringExtra(EXTRA_NOTIFICATION_NONCE)
            ?: "${kind.wireValue}:$threadId:${getStringExtra(EXTRA_NOTIFICATION_REQUEST_ID).orEmpty()}",
    )
}

private const val EXTRA_NOTIFICATION_KIND = "codexRemote.notification.kind"
private const val EXTRA_NOTIFICATION_THREAD_ID = "codexRemote.notification.threadId"
private const val EXTRA_NOTIFICATION_REQUEST_ID = "codexRemote.notification.requestId"
private const val EXTRA_NOTIFICATION_NONCE = "codexRemote.notification.nonce"
