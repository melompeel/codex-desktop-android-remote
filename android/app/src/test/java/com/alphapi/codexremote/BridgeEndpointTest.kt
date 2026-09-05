package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeEndpointTest {
    @Test
    fun acceptsPrivateLanAndLocalAddresses() {
        assertEquals(
            "http://192.168.2.115:8766",
            BridgeEndpoint.normalize(" http://192.168.2.115:8766/ "),
        )
        assertEquals(
            "http://desktop.local:8766",
            BridgeEndpoint.normalize("http://desktop.local:8766"),
        )
        assertEquals(
            "http://localhost:8766",
            BridgeEndpoint.normalize("http://localhost:8766"),
        )
    }

    @Test
    fun acceptsTailscaleAddresses() {
        assertEquals(
            "http://100.64.0.1:8766",
            BridgeEndpoint.normalize("http://100.64.0.1:8766"),
        )
        assertEquals(
            "http://100.127.255.254:8766",
            BridgeEndpoint.normalize("http://100.127.255.254:8766"),
        )
        assertEquals(
            "http://desktop.tail1234.ts.net:8766",
            BridgeEndpoint.normalize("http://desktop.tail1234.ts.net:8766"),
        )
    }

    @Test
    fun rejectsPublicHostsAndUnexpectedPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            BridgeEndpoint.normalize("http://8.8.8.8:8766")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeEndpoint.normalize("https://example.com/bridge")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeEndpoint.normalize("https://fcevil.example.com:8766")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeEndpoint.normalize("http://100.63.255.255:8766")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeEndpoint.normalize("http://100.128.0.0:8766")
        }
    }
}
