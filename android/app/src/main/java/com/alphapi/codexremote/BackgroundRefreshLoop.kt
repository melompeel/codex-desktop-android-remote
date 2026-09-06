package com.alphapi.codexremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ACTIVE_REFRESH_INTERVAL_MS = 15_000L
private const val IDLE_REFRESH_INTERVAL_MS = 120_000L

internal fun backgroundRefreshInterval(statuses: List<String>): Long =
    if (statuses.any(::isActiveTaskStatus)) ACTIVE_REFRESH_INTERVAL_MS else IDLE_REFRESH_INTERVAL_MS

internal class BackgroundRefreshLoop(
    private val scope: CoroutineScope,
    private val intervalMs: () -> Long,
    private val refresh: () -> Unit,
) {
    private var job: Job? = null
    private var scheduledIntervalMs: Long? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        schedule()
    }

    @Synchronized
    fun updateSchedule() {
        val nextInterval = intervalMs()
        if (job?.isActive == true && scheduledIntervalMs == nextInterval) return
        job?.cancel()
        schedule()
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        scheduledIntervalMs = null
    }

    private fun schedule() {
        val interval = intervalMs().coerceAtLeast(1_000L)
        scheduledIntervalMs = interval
        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(interval)
                refresh()
            }
        }
    }
}
