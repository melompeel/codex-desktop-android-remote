package com.alphapi.codexremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ConnectionStatusGrace(
    private val scope: CoroutineScope,
    private val gracePeriodMs: Long = 4_000L,
    private val publish: (Boolean) -> Unit,
) {
    private var disconnectJob: Job? = null
    private var visibleConnected = false

    @Synchronized
    fun connected() {
        disconnectJob?.cancel()
        disconnectJob = null
        setVisible(true)
    }

    @Synchronized
    fun disconnected(immediate: Boolean = false) {
        disconnectJob?.cancel()
        disconnectJob = null
        if (immediate || !visibleConnected) {
            setVisible(false)
            return
        }
        disconnectJob = scope.launch {
            delay(gracePeriodMs)
            synchronized(this@ConnectionStatusGrace) {
                disconnectJob = null
                setVisible(false)
            }
        }
    }

    @Synchronized
    fun reset(connected: Boolean = false) {
        disconnectJob?.cancel()
        disconnectJob = null
        visibleConnected = connected
    }

    private fun setVisible(connected: Boolean) {
        if (visibleConnected == connected) return
        visibleConnected = connected
        publish(connected)
    }
}
