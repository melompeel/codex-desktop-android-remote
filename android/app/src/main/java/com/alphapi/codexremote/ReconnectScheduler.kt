package com.alphapi.codexremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ReconnectScheduler(
    private val scope: CoroutineScope,
    private val reconnect: suspend () -> Unit,
) {
    private var job: Job? = null
    private var attempt = 0

    @Synchronized
    fun schedule() {
        if (job?.isActive == true) return
        val delayMs = minOf(30_000L, 1_000L shl minOf(attempt++, 5))
        val scheduled = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            synchronized(this@ReconnectScheduler) {
                if (job != coroutineContext[Job]) return@launch
                job = null
            }
            reconnect()
        }
        job = scheduled
        scheduled.start()
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }

    @Synchronized
    fun reset() {
        cancel()
        attempt = 0
    }
}
