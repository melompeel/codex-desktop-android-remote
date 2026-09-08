package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionRoutingTest {
    @Test
    fun storesRoutingChoicePerTerminalWithoutChangingItsAuthorization() {
        val remote = connection("remote", "http://100.104.36.62:8766")
        val office = connection("office", "http://192.168.1.20:8766")
        val catalog = ConnectionCatalog(remote.id, listOf(remote, office))

        val updated = catalog.updateRouteMode(office.id, ConnectionRouteMode.DIRECT_LAN)
            ?: error("connection missing")

        assertEquals(ConnectionRouteMode.SYSTEM, updated.connections.first().routeMode)
        assertEquals(ConnectionRouteMode.DIRECT_LAN, updated.connections.last().routeMode)
        assertEquals("token-office", updated.connections.last().token)
    }

    @Test
    fun routeChoiceIsNotOverriddenFromTheEndpointAddress() {
        val tailscale = connection("tailscale", "http://100.104.36.62:8766")
        val catalog = ConnectionCatalog(tailscale.id, listOf(tailscale))

        val updated = catalog.updateRouteMode(tailscale.id, ConnectionRouteMode.DIRECT_LAN)
            ?: error("connection missing")

        assertEquals(ConnectionRouteMode.DIRECT_LAN, updated.active?.routeMode)
    }

    private fun connection(id: String, url: String) = StoredConnection(
        id = id,
        name = id,
        serverUrl = url,
        deviceId = "device-$id",
        token = "token-$id",
    )
}
