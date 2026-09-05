package com.alphapi.codexremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredConnection(val serverUrl: String, val deviceId: String, val token: String)

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("bridge_connection", Context.MODE_PRIVATE)
    private val keyAlias = "alphapi_codex_remote_token"

    fun save(connection: StoredConnection) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(connection.token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("server_url", connection.serverUrl)
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
}
