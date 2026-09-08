package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskOpeningStateTest {
    @Test
    fun activatesOnlyAUserSelectedHistoricalTaskWhenTheBridgeSupportsIt() {
        val historical = task(ownerAvailable = false)
        val live = task(ownerAvailable = true)
        val capabilities = RemoteCapabilitiesDto(taskActivation = true)

        assertEquals(TaskOpenAction.ACTIVATE, taskOpenAction(historical, capabilities, false))
        assertEquals(TaskOpenAction.FOLLOW, taskOpenAction(live, capabilities, false))
        assertEquals(
            TaskOpenAction.FOLLOW,
            taskOpenAction(historical, RemoteCapabilitiesDto(taskActivation = false), false),
        )
    }

    @Test
    fun doesNotStartAnotherActivationWhileTheFirstRequestIsRunning() {
        assertEquals(
            TaskOpenAction.WAIT,
            taskOpenAction(
                task(ownerAvailable = false),
                RemoteCapabilitiesDto(taskActivation = true),
                activationInProgress = true,
            ),
        )
    }

    private fun task(ownerAvailable: Boolean) = TaskDto(
        threadId = "thread-1",
        title = "History",
        status = "idle",
        revision = 1,
        pendingApprovals = 0,
        ownerAvailable = ownerAvailable,
    )
}
