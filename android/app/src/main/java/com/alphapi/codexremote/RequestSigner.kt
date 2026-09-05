package com.alphapi.codexremote

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RequestSigner {
    data class Headers(
        val timestamp: String,
        val requestId: String,
        val signature: String,
    )

    fun sign(
        token: String,
        method: String,
        path: String,
        body: ByteArray,
        requestId: String,
        epochSeconds: Long,
    ): Headers {
        val timestamp = epochSeconds.toString()
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).toHex()
        val canonical = listOf(method.uppercase(), path, timestamp, requestId, bodyHash).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Headers(timestamp, requestId, mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
