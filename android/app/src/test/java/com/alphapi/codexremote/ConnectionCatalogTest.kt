package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionCatalogTest {
    private val remote = StoredConnection(
        id = "remote",
        name = "远程电脑",
        serverUrl = "http://100.100.1.2:8766",
        deviceId = "device-remote",
        token = "token-remote",
    )
    private val office = StoredConnection(
        id = "office",
        name = "办公室电脑",
        serverUrl = "http://192.168.1.8:8766",
        deviceId = "device-office",
        token = "token-office",
    )

    @Test
    fun keepsIndependentCredentialsWhenSwitchingTerminals() {
        val catalog = ConnectionCatalog(remote.id, listOf(remote))
            .add(office)

        assertEquals("token-office", catalog.active?.token)
        assertEquals("token-remote", catalog.select(remote.id)?.active?.token)
        assertEquals(listOf("办公室电脑", "远程电脑"), catalog.summaries().map { it.name })
    }

    @Test
    fun editsAddressWithoutReplacingTheExistingCredential() {
        val catalog = ConnectionCatalog(remote.id, listOf(remote, office))
        val edited = catalog.edit(
            connectionId = remote.id,
            name = "家中电脑",
            serverUrl = "http://192.168.50.20:8766",
        )

        assertNotNull(edited?.active)
        val connection = edited!!.active!!
        assertEquals("remote", connection.id)
        assertEquals("device-remote", connection.deviceId)
        assertEquals("token-remote", connection.token)
        assertEquals("家中电脑", connection.name)
        assertEquals("http://192.168.50.20:8766", connection.serverUrl)
    }

    @Test
    fun rejectsEditingAConnectionOntoAnotherSavedAddress() {
        val catalog = ConnectionCatalog(remote.id, listOf(remote, office))

        assertThrows(IllegalArgumentException::class.java) {
            catalog.edit(remote.id, "重复地址", office.serverUrl)
        }
    }

    @Test
    fun removingTheActiveTerminalSelectsTheNextSavedTerminal() {
        val catalog = ConnectionCatalog(office.id, listOf(office, remote))

        val remaining = catalog.remove(office.id)

        assertEquals(remote, remaining?.active)
        assertEquals(null, ConnectionCatalog(remote.id, listOf(remote)).remove(remote.id))
    }

    @Test
    fun migratesEveryLegacyAddressWithoutLosingItsExistingAuthorization() {
        val catalog = buildLegacyCatalog(
            activeUrl = remote.serverUrl,
            urls = setOf(remote.serverUrl, office.serverUrl),
            labels = mapOf(remote.serverUrl to "旧远程地址", office.serverUrl to "旧局域网地址"),
            deviceId = "legacy-device",
            token = "legacy-token",
        )

        assertEquals(remote.serverUrl, catalog.active?.serverUrl)
        assertEquals(listOf("旧远程地址", "旧局域网地址"), catalog.connections.map { it.name })
        assertEquals(setOf("legacy-device"), catalog.connections.map { it.deviceId }.toSet())
        assertEquals(setOf("legacy-token"), catalog.connections.map { it.token }.toSet())
    }
}
