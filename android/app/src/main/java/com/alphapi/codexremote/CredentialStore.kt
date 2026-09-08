package com.alphapi.codexremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class StoredConnection(
    val serverUrl: String,
    val deviceId: String,
    val token: String,
    val name: String = defaultConnectionName(serverUrl),
    val id: String = UUID.randomUUID().toString(),
    val routeMode: ConnectionRouteMode = ConnectionRouteMode.SYSTEM,
)

internal data class ConnectionCatalog(
    val activeId: String,
    val connections: List<StoredConnection>,
) {
    val active: StoredConnection?
        get() = connections.firstOrNull { it.id == activeId }

    fun add(connection: StoredConnection): ConnectionCatalog {
        val remaining = connections.filterNot {
            it.id == connection.id || it.serverUrl == connection.serverUrl
        }
        return ConnectionCatalog(connection.id, listOf(connection) + remaining)
    }

    fun select(connectionId: String): ConnectionCatalog? =
        takeIf { connections.any { connection -> connection.id == connectionId } }
            ?.copy(activeId = connectionId)

    fun edit(connectionId: String, name: String, serverUrl: String): ConnectionCatalog? {
        if (connections.none { it.id == connectionId }) return null
        require(connections.none { it.id != connectionId && it.serverUrl == serverUrl }) {
            "connection-address-already-saved"
        }
        val updated = connections.map { connection ->
            if (connection.id != connectionId) connection
            else connection.copy(
                serverUrl = serverUrl,
                name = name.trim().ifBlank { defaultConnectionName(serverUrl) },
            )
        }
        return copy(connections = updated)
    }

    fun remove(connectionId: String): ConnectionCatalog? {
        if (connections.size <= 1 || connections.none { it.id == connectionId }) return null
        val remaining = connections.filterNot { it.id == connectionId }
        val nextActiveId = if (activeId == connectionId) remaining.first().id else activeId
        return ConnectionCatalog(nextActiveId, remaining)
    }

    fun updateRouteMode(connectionId: String, routeMode: ConnectionRouteMode): ConnectionCatalog? {
        if (connections.none { it.id == connectionId }) return null
        return copy(
            connections = connections.map { connection ->
                if (connection.id == connectionId) connection.copy(routeMode = routeMode) else connection
            },
        )
    }

    fun summaries(): List<SavedServerAddress> {
        val ordered = listOfNotNull(active) + connections.filterNot { it.id == activeId }
        return ordered.map { connection ->
            SavedServerAddress(connection.name, connection.serverUrl, connection.id)
        }
    }
}

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val keyAlias = "alphapi_codex_remote_token"

    @Synchronized
    fun save(connection: StoredConnection) {
        val normalized = connection.normalizedName()
        writeCatalog(ConnectionCatalog(normalized.id, listOf(normalized)))
    }

    @Synchronized
    fun addConnection(connection: StoredConnection): StoredConnection {
        val catalog = requireNotNull(readCatalog()) { "当前没有已保存的连接" }
            .add(connection.normalizedName())
        writeCatalog(catalog)
        return requireNotNull(catalog.active)
    }

    @Synchronized
    fun editConnection(connectionId: String, name: String, serverUrl: String): StoredConnection? {
        val catalog = readCatalog()?.edit(connectionId, name, serverUrl) ?: return null
        writeCatalog(catalog)
        return catalog.connections.firstOrNull { it.id == connectionId }
    }

    @Synchronized
    fun selectConnection(connectionId: String): StoredConnection? {
        val catalog = readCatalog()?.select(connectionId) ?: return null
        writeCatalog(catalog)
        return catalog.active
    }

    @Synchronized
    fun removeConnection(connectionId: String): StoredConnection? {
        val catalog = readCatalog()?.remove(connectionId) ?: return null
        writeCatalog(catalog)
        return catalog.active
    }

    @Synchronized
    fun updateRouteMode(connectionId: String, routeMode: ConnectionRouteMode): StoredConnection? {
        val catalog = readCatalog()?.updateRouteMode(connectionId, routeMode) ?: return null
        writeCatalog(catalog)
        return catalog.connections.firstOrNull { it.id == connectionId }
    }

    @Synchronized
    fun load(): StoredConnection? = readCatalog()?.active

    @Synchronized
    fun serverAddresses(): List<SavedServerAddress> = readCatalog()?.summaries().orEmpty()

    fun clear() {
        preferences.edit().clear().apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    private fun readCatalog(): ConnectionCatalog? {
        readCurrentCatalog()?.let { return it }
        val legacy = readLegacyCatalog() ?: return null
        writeCatalog(legacy)
        return legacy
    }

    private fun readCurrentCatalog(): ConnectionCatalog? {
        val iv = preferences.getString(CONNECTIONS_IV, null) ?: return null
        val encrypted = preferences.getString(CONNECTIONS_DATA, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            decodeCatalog(
                String(
                    cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8,
                ),
            )
        }.getOrNull()
    }

    private fun readLegacyCatalog(): ConnectionCatalog? {
        val activeUrl = preferences.getString(LEGACY_SERVER_URL, null) ?: return null
        val deviceId = preferences.getString(LEGACY_DEVICE_ID, null) ?: return null
        val iv = preferences.getString(LEGACY_TOKEN_IV, null) ?: return null
        val encrypted = preferences.getString(LEGACY_TOKEN_DATA, null) ?: return null
        val token = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(
                cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
        }.getOrNull() ?: return null
        val labels = readLegacyLabels()
        val urls = preferences.getStringSet(LEGACY_SERVER_URLS, emptySet()).orEmpty()
            .filter(String::isNotBlank)
            .toSet() + activeUrl
        return buildLegacyCatalog(activeUrl, urls, labels, deviceId, token)
    }

    private fun writeCatalog(catalog: ConnectionCatalog) {
        require(catalog.connections.isNotEmpty() && catalog.active != null) { "connection-catalog-invalid" }
        require(catalog.connections.map { it.id }.distinct().size == catalog.connections.size) {
            "connection-id-duplicate"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(encodeCatalog(catalog).toByteArray(StandardCharsets.UTF_8))
        check(
            preferences.edit()
                .putString(CONNECTIONS_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(CONNECTIONS_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .remove(LEGACY_SERVER_URL)
                .remove(LEGACY_SERVER_URLS)
                .remove(LEGACY_SERVER_LABELS)
                .remove(LEGACY_DEVICE_ID)
                .remove(LEGACY_TOKEN_IV)
                .remove(LEGACY_TOKEN_DATA)
                .commit(),
        ) { "connection-storage-write-failed" }
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

    private fun readLegacyLabels(): Map<String, String> {
        val raw = preferences.getString(LEGACY_SERVER_LABELS, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key -> json.optString(key) }
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val PREFERENCES = "bridge_connection"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CONNECTIONS_IV = "connections_iv_v2"
        private const val CONNECTIONS_DATA = "connections_data_v2"
        private const val LEGACY_SERVER_URL = "server_url"
        private const val LEGACY_SERVER_URLS = "server_urls"
        private const val LEGACY_SERVER_LABELS = "server_labels"
        private const val LEGACY_DEVICE_ID = "device_id"
        private const val LEGACY_TOKEN_IV = "token_iv"
        private const val LEGACY_TOKEN_DATA = "token_data"
    }
}

private fun StoredConnection.normalizedName(): StoredConnection = copy(
    name = name.trim().ifBlank { defaultConnectionName(serverUrl) },
)

private fun encodeCatalog(catalog: ConnectionCatalog): String = JSONObject().apply {
    put("activeId", catalog.activeId)
    put(
        "connections",
        JSONArray().apply {
            catalog.connections.forEach { connection ->
                put(
                    JSONObject().apply {
                        put("id", connection.id)
                        put("name", connection.name)
                        put("serverUrl", connection.serverUrl)
                        put("deviceId", connection.deviceId)
                        put("token", connection.token)
                        put("routeMode", connection.routeMode.name.lowercase())
                    },
                )
            }
        },
    )
}.toString()

private fun decodeCatalog(raw: String): ConnectionCatalog {
    val json = JSONObject(raw)
    val array = json.getJSONArray("connections")
    val connections = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                StoredConnection(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    serverUrl = item.getString("serverUrl"),
                    deviceId = item.getString("deviceId"),
                    token = item.getString("token"),
                    routeMode = runCatching {
                        ConnectionRouteMode.valueOf(item.optString("routeMode", "system").uppercase())
                    }.getOrDefault(ConnectionRouteMode.SYSTEM),
                ),
            )
        }
    }
    return ConnectionCatalog(json.getString("activeId"), connections).also { catalog ->
        require(catalog.connections.isNotEmpty() && catalog.active != null)
        require(catalog.connections.map { it.id }.distinct().size == catalog.connections.size)
    }
}

private fun legacyConnectionId(serverUrl: String): String = UUID.nameUUIDFromBytes(
    "legacy:$serverUrl".toByteArray(StandardCharsets.UTF_8),
).toString()

internal fun buildLegacyCatalog(
    activeUrl: String,
    urls: Set<String>,
    labels: Map<String, String>,
    deviceId: String,
    token: String,
): ConnectionCatalog {
    val orderedUrls = listOf(activeUrl) + (urls - activeUrl).sorted()
    val connections = orderedUrls.map { url ->
        StoredConnection(
            id = legacyConnectionId(url),
            serverUrl = url,
            deviceId = deviceId,
            token = token,
            name = labels[url].orEmpty().ifBlank { defaultConnectionName(url) },
        )
    }
    return ConnectionCatalog(connections.first().id, connections)
}

private fun defaultConnectionName(serverUrl: String): String =
    runCatching { URI(serverUrl).host }.getOrNull().orEmpty().ifBlank { serverUrl }
