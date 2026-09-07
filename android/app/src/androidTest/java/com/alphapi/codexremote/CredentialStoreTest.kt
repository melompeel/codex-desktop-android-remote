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
    fun keepsNamedLanAndTailscaleAddressesUnderOneCredential() {
        store.save(
            StoredConnection(
                serverUrl = "http://100.100.1.2:8766",
                deviceId = "android-test",
                token = "test-token",
                name = "远程连接",
            ),
        )

        val active = store.addServerUrl("工作室", "http://192.168.1.8:8766")

        assertEquals("http://192.168.1.8:8766", active?.serverUrl)
        assertEquals(
            listOf(
                SavedServerAddress("工作室", "http://192.168.1.8:8766"),
                SavedServerAddress("远程连接", "http://100.100.1.2:8766"),
            ),
            store.serverAddresses(),
        )
        assertEquals(
            store.serverAddresses(),
            CredentialStore(context).serverAddresses(),
        )

        val switched = store.selectServerUrl("http://100.100.1.2:8766")
        assertEquals("远程连接", switched?.name)
        assertEquals("test-token", switched?.token)

        store.removeServerUrl("http://192.168.1.8:8766")
        assertEquals(
            listOf(SavedServerAddress("远程连接", "http://100.100.1.2:8766")),
            store.serverAddresses(),
        )
    }
}
