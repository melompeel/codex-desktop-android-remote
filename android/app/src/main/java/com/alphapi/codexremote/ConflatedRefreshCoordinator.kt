package com.alphapi.codexremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ConflatedRefreshCoordinator(
    scope: CoroutineScope,
    private val refresh: suspend () -> Unit,
) {
    private val requests = Channel<Long>(Channel.CONFLATED)
    private val worker: Job = scope.launch {
        for (delayMs in requests) {
            if (delayMs > 0) delay(delayMs)
            refresh()
        }
    }

    fun request(delayMs: Long) {
        requests.trySend(delayMs.coerceAtLeast(0))
    }

    fun dispose() {
        requests.close()
        worker.cancel()
    }
}
