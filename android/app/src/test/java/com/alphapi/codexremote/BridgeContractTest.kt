package com.alphapi.codexremote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesCapabilitiesWithoutInventingUnavailableTaskCreation() {
        val response = json.decodeFromString<CapabilitiesResponse>(
            """{"capabilities":{"apiVersion":"v1","writable":true,"taskCreation":false,"deliveries":["auto","start","steer","queue"],"queue":true,"modelSettings":true,"diff":true,"attachments":{"enabled":true,"kinds":["image","file"],"queued":false,"maxBytes":10485760}}}""",
        )

        assertTrue(response.capabilities.explicitDelivery)
        assertTrue(response.capabilities.attachments.enabled)
        assertEquals(10_485_760L, response.capabilities.maxAttachmentBytes)
        assertFalse(response.capabilities.newTask)
    }

    @Test
    fun decodesQueueAndStructuredDiffWrappers() {
        val queue = json.decodeFromString<QueueResponse>(
            """{"queue":{"threadId":"t1","hash":"h1","messages":[{"id":"q1","text":"later"}]}}""",
        )
        val diff = json.decodeFromString<DiffResponse>(
            """{"diff":{"threadId":"t1","revision":2,"turns":[],"files":[{"turnId":"turn","itemId":"item","path":"a.kt","kind":"update","unifiedDiff":"diff --git a/a.kt b/a.kt"}]}}""",
        )

        assertEquals("h1", queue.hash)
        assertEquals("later", queue.values().single().text)
        assertEquals("diff --git a/a.kt b/a.kt", diff.diff.unifiedDiff())
    }

    @Test
    fun keepsTaskDialogOpenWhenCreationOnlyPartiallyCompletes() {
        val response = json.decodeFromString<CreateTaskResponse>(
            """{"threadId":"thread-1","promptAccepted":false,"stage":"prompt","error":"start-turn-timeout"}""",
        )

        val disposition = response.disposition()

        assertFalse(disposition.closeDialog)
        assertTrue(disposition.message.orEmpty().contains("首条指令"))
        assertTrue(disposition.message.orEmpty().contains("仍保留"))
        assertTrue(disposition.message.orEmpty().contains("start-turn-timeout"))
    }

    @Test
    fun closesTaskDialogOnlyAfterPromptWasAccepted() {
        val response = json.decodeFromString<CreateTaskResponse>(
            """{"threadId":"thread-1","promptAccepted":true,"stage":"complete"}""",
        )

        assertTrue(response.disposition().closeDialog)
    }
}
