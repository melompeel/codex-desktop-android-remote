package com.alphapi.codexremote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var approvalJob: Job? = null
    private val shownApprovals = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Codex connection", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, "Codex approvals", NotificationManager.IMPORTANCE_HIGH),
        )
        startForeground(
            CONNECTION_NOTIFICATION,
            connectionNotification("正在连接电脑上的 Codex"),
        )
        val repository = RemoteRepository.get(this)
        repository.restoreAndStart(startService = false)
        approvalJob = scope.launch {
            repository.state.collectLatest { state ->
                val connectionText = when {
                    !state.connected -> "手机与 Bridge 连接中断"
                    !state.ipcConnected -> "Bridge 已连接，Codex Desktop 未连接"
                    !state.writeSupported -> "桌面版本不兼容，仅查看"
                    !state.compatibilityVerified -> "Codex 版本已更新，正在兼容模式运行"
                    else -> "正在监听当前 Codex 任务"
                }
                manager.notify(CONNECTION_NOTIFICATION, connectionNotification(connectionText))
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
                                .setContentIntent(openAppIntent())
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
    override fun onDestroy() { approvalJob?.cancel(); scope.cancel(); super.onDestroy() }

    private fun connectionNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Codex Remote")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL_CONNECTION = "codex_connection"
        private const val CHANNEL_APPROVALS = "codex_approvals"
        private const val CONNECTION_NOTIFICATION = 1001
    }
}
