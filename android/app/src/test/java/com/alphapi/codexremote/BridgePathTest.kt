package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgePathTest {
    @Test
    fun attachmentMetadataLivesInTheExactSignedPath() {
        assertEquals(
            "/v1/attachments?name=%E8%AE%BE%E8%AE%A1%20%E5%9B%BE.png&mimeType=image%2Fpng&idempotencyKey=request-1",
            attachmentUploadPath("设计 图.png", "IMAGE/PNG", "request-1"),
        )
    }

    @Test
    fun queueDeleteIncludesConcurrencyAndIdempotencyTokens() {
        assertEquals(
            "/v1/tasks/thread-1/queue/message-1?expectedQueueHash=abc%2B123&idempotencyKey=request-2",
            queueDeletePath("thread-1", "message-1", "abc+123", "request-2"),
        )
    }
}
