package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestSignerTest {
    @Test
    fun matchesKnownBridgeSignature() {
        val result = RequestSigner.sign(
            token = "0123456789abcdef0123456789abcdef",
            method = "POST",
            path = "/v1/tasks/thread-1/messages",
            body = "{\"text\":\"hello\"}".toByteArray(),
            requestId = "request-1",
            epochSeconds = 1_700_000_000,
        )
        assertEquals("1700000000", result.timestamp)
        assertEquals("request-1", result.requestId)
        assertEquals(
            "ac0dd2416c8bae4c5ceb309e4eff61ed49673c8adc2b8b00f9543e932e42d1c8",
            result.signature,
        )
    }
}
