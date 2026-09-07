package com.alphapi.codexremote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .createDeviceProtectedStorageContext()
    private val store = CredentialStore(context)

    @Before
    fun resetBefore() = store.clear()

    @After
    fun resetAfter() = store.clear()

    @Test
    fun persistsAndSwitchesIndependentTerminalCredentials() {
        val remote = StoredConnection(
            id = "remote",
            serverUrl = "http://100.100.1.2:8766",
            deviceId = "android-remote",
            token = "token-remote",
            name = "远程电脑",
        )
        val office = StoredConnection(
            id = "office",
            serverUrl = "http://192.168.1.8:8766",
            deviceId = "android-office",
            token = "token-office",
            name = "办公室电脑",
        )
        store.save(remote)

        val active = store.addConnection(office)

        assertEquals("http://192.168.1.8:8766", active?.serverUrl)
        assertEquals("token-office", active?.token)
        assertEquals(
            listOf(
                SavedServerAddress("办公室电脑", "http://192.168.1.8:8766", "office"),
                SavedServerAddress("远程电脑", "http://100.100.1.2:8766", "remote"),
            ),
            store.serverAddresses(),
        )
        assertEquals(
            store.serverAddresses(),
            CredentialStore(context).serverAddresses(),
        )

        val switched = store.selectConnection("remote")
        assertEquals("远程电脑", switched?.name)
        assertEquals("token-remote", switched?.token)

        val edited = store.editConnection(
            connectionId = "remote",
            name = "家中电脑",
            serverUrl = "http://192.168.50.20:8766",
        )
        assertEquals("token-remote", edited?.token)
        assertEquals("http://192.168.50.20:8766", CredentialStore(context).load()?.serverUrl)

        store.removeConnection("office")
        assertEquals(
            listOf(SavedServerAddress("家中电脑", "http://192.168.50.20:8766", "remote")),
            store.serverAddresses(),
        )
    }
}
