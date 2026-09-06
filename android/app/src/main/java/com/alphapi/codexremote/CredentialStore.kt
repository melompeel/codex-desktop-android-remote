package com.alphapi.codexremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class StoredConnection(
    val serverUrl: String,
    val deviceId: String,
    val token: String,
    val name: String = defaultConnectionName(serverUrl),
)

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("bridge_connection", Context.MODE_PRIVATE)
    private val keyAlias = "alphapi_codex_remote_token"

    fun save(connection: StoredConnection) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(connection.token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("server_url", connection.serverUrl)
            .putStringSet("server_urls", setOf(connection.serverUrl))
            .putString(
                "server_labels",
                JSONObject().put(connection.serverUrl, connection.name).toString(),
            )
            .putString("device_id", connection.deviceId)
            .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): StoredConnection? {
        val serverUrl = preferences.getString("server_url", null) ?: return null
        val deviceId = preferences.getString("device_id", null) ?: return null
        val iv = preferences.getString("token_iv", null) ?: return null
        val encrypted = preferences.getString("token_data", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val token = String(
                cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                Charsets.UTF_8,
            )
            StoredConnection(serverUrl, deviceId, token)
        }.getOrNull()
    }

    fun serverAddresses(): List<SavedServerAddress> {
        val active = preferences.getString("server_url", null) ?: return emptyList()
        val saved = preferences.getStringSet("server_urls", emptySet()).orEmpty()
            .filter(String::isNotBlank)
            .toSet() + active
        val labels = readLabels()
        return (listOf(active) + (saved - active).sorted()).map { url ->
            SavedServerAddress(labels[url].orEmpty().ifBlank { defaultConnectionName(url) }, url)
        }
    }

    fun addServerUrl(name: String, serverUrl: String): StoredConnection? {
        val current = load() ?: return null
        val urls = (serverAddresses().map { it.serverUrl } + serverUrl).toSet()
        val labels = readLabels().toMutableMap().apply {
            put(serverUrl, name.trim().ifBlank { defaultConnectionName(serverUrl) })
        }
        preferences.edit()
            .putStringSet("server_urls", urls)
            .putString("server_labels", labels.toJson())
            .putString("server_url", serverUrl)
            .apply()
        return current.copy(serverUrl = serverUrl, name = labels.getValue(serverUrl))
    }

    fun selectServerUrl(serverUrl: String): StoredConnection? {
        val address = serverAddresses().firstOrNull { it.serverUrl == serverUrl } ?: return null
        val current = load() ?: return null
        preferences.edit().putString("server_url", serverUrl).apply()
        return current.copy(serverUrl = serverUrl, name = address.name)
    }

    fun removeServerUrl(serverUrl: String): StoredConnection? {
        val current = load() ?: return null
        val urls = serverAddresses().map { it.serverUrl }
        if (serverUrl !in urls || urls.size <= 1) return current
        val remaining = urls.filterNot { it == serverUrl }
        val active = if (current.serverUrl == serverUrl) remaining.first() else current.serverUrl
        val labels = readLabels().toMutableMap().apply { remove(serverUrl) }
        preferences.edit()
            .putStringSet("server_urls", remaining.toSet())
            .putString("server_labels", labels.toJson())
            .putString("server_url", active)
            .apply()
        return current.copy(
            serverUrl = active,
            name = labels[active].orEmpty().ifBlank { defaultConnectionName(active) },
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun readLabels(): Map<String, String> {
        val raw = preferences.getString("server_labels", null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key -> json.optString(key) }
        }.getOrDefault(emptyMap())
    }
}

private fun Map<String, String>.toJson(): String = JSONObject().also { json ->
    forEach { (url, name) -> json.put(url, name) }
}.toString()

private fun defaultConnectionName(serverUrl: String): String =
    runCatching { URI(serverUrl).host }.getOrNull().orEmpty().ifBlank { serverUrl }
