package com.alphapi.codexremote

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class RemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var approvalJob: Job? = null
    private var backgroundRefresh: BackgroundRefreshLoop? = null
    private var activeTaskWakeLock: PowerManager.WakeLock? = null
    private val shownApprovals = mutableSetOf<String>()
    private val shownCompletedThreads = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Codex connection", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, "Codex approvals", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETIONS, "Codex completions", NotificationManager.IMPORTANCE_DEFAULT),
        )
        startForeground(
            CONNECTION_NOTIFICATION,
            connectionNotification("正在连接电脑上的 Codex"),
        )
        val repository = RemoteRepository.get(this)
        repository.restoreAndStart(startService = false)
        backgroundRefresh = BackgroundRefreshLoop(
            scope = scope,
            intervalMs = {
                backgroundRefreshInterval(repository.state.value.tasks.map { it.status })
            },
            refresh = repository::refresh,
        ).also(BackgroundRefreshLoop::start)
        approvalJob = scope.launch {
            repository.state.collectLatest { state ->
                val hasActiveTask = state.tasks.any { isActiveTaskStatus(it.status) }
                updateActiveTaskWakeLock(hasActiveTask)
                backgroundRefresh?.updateSchedule()
                manager.notify(
                    CONNECTION_NOTIFICATION,
                    connectionNotification(connectionStatusPresentation(state).text),
                )
                val activeIds = state.approvals.mapTo(mutableSetOf()) { it.requestId }
                val resolvedIds = shownApprovals.filterNot(activeIds::contains)
                resolvedIds.forEach { requestId -> manager.cancel(requestId.hashCode()) }
                shownApprovals.removeAll(resolvedIds.toSet())
                for (approval in state.approvals) {
                    if (shownApprovals.add(approval.requestId)) {
                        manager.notify(
                            approval.requestId.hashCode(),
                            NotificationCompat.Builder(this@RemoteService, CHANNEL_APPROVALS)
                                .setSmallIcon(android.R.drawable.stat_sys_warning)
                                .setContentTitle("Codex 需要确认")
                                .setContentText(approval.method.substringAfterLast('/'))
                                .setContentIntent(openAppIntent(
                                    requestCode = approval.requestId.hashCode(),
                                    route = RemoteNotificationRoute(
                                        kind = notificationKindForApproval(approval.method),
                                        threadId = approval.threadId,
                                        requestId = approval.requestId,
                                        nonce = UUID.randomUUID().toString(),
                                    ),
                                ))
                                .setAutoCancel(true)
                                .build(),
                        )
                    }
                }
                val completedIds = state.completedReviewThreadIds
                val viewedIds = shownCompletedThreads.filterNot(completedIds::contains)
                viewedIds.forEach { threadId -> manager.cancel(completionNotificationId(threadId)) }
                shownCompletedThreads.removeAll(viewedIds.toSet())
                for (threadId in completedIds) {
                    if (shownCompletedThreads.add(threadId)) {
                        val task = state.tasks.firstOrNull { it.threadId == threadId }
                        manager.notify(
                            completionNotificationId(threadId),
                            NotificationCompat.Builder(this@RemoteService, CHANNEL_COMPLETIONS)
                                .setSmallIcon(android.R.drawable.stat_notify_chat)
                                .setContentTitle(if (task?.status == "failed") "Codex 任务运行失败" else "Codex 任务已完成")
                                .setContentText(task?.title ?: "点击查看结果")
                                .setContentIntent(openAppIntent(
                                    requestCode = completionNotificationId(threadId),
                                    route = RemoteNotificationRoute(
                                        kind = RemoteNotificationKind.COMPLETION,
                                        threadId = threadId,
                                        nonce = UUID.randomUUID().toString(),
                                    ),
                                ))
                                .setAutoCancel(true)
                                .build(),
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        approvalJob?.cancel()
        backgroundRefresh?.stop()
        releaseActiveTaskWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun updateActiveTaskWakeLock(active: Boolean) {
        if (!active) {
            releaseActiveTaskWakeLock()
            return
        }
        val wakeLock = activeTaskWakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:activeCodexTask")
            .apply { setReferenceCounted(false) }
            .also { activeTaskWakeLock = it }
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    private fun releaseActiveTaskWakeLock() {
        activeTaskWakeLock?.takeIf { it.isHeld }?.release()
        activeTaskWakeLock = null
    }

    private fun connectionNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Codex Remote")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private fun openAppIntent(
        requestCode: Int = 0,
        route: RemoteNotificationRoute? = null,
    ): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (route != null) {
                action = "$packageName.OPEN_NOTIFICATION.${route.nonce}"
                putRemoteNotificationRoute(route)
            }
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun completionNotificationId(threadId: String): Int =
        COMPLETION_NOTIFICATION_BASE xor threadId.hashCode()

    companion object {
        private const val CHANNEL_CONNECTION = "codex_connection"
        private const val CHANNEL_APPROVALS = "codex_approvals"
        private const val CHANNEL_COMPLETIONS = "codex_completions"
        private const val CONNECTION_NOTIFICATION = 1001
        private const val COMPLETION_NOTIFICATION_BASE = 0x434F4445
    }
}
