package com.alphapi.codexremote

import java.net.URI

object BridgeEndpoint {
    fun normalize(value: String): String {
        val trimmed = value.trim()
        val candidate = if ("://" in trimmed) trimmed else "http://$trimmed"
        val uri = runCatching { URI(candidate) }
            .getOrElse { throw IllegalArgumentException("bridge-address-invalid") }
        require(uri.scheme == "http" || uri.scheme == "https") {
            "bridge-address-must-use-http"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "bridge-address-invalid"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "bridge-address-must-not-have-path"
        }
        val host = uri.host ?: throw IllegalArgumentException("bridge-host-required")
        require(isTrustedBridgeHost(host)) { "bridge-host-must-be-private-lan-or-tailscale" }
        require(uri.port == -1 || uri.port in 1..65_535) { "bridge-port-invalid" }
        return candidate.trimEnd('/')
    }

    internal fun isTrustedBridgeHost(host: String): Boolean {
        val normalized = host.lowercase().trim('[', ']')
        if (
            normalized == "localhost" ||
            normalized.endsWith(".local") ||
            normalized.endsWith(".ts.net")
        ) return true
        if (!normalized.contains('.') && !normalized.contains(':')) return true
        if (normalized.contains(':') && (
                normalized == "::1" || normalized.startsWith("fe80:") ||
                    normalized.startsWith("fc") || normalized.startsWith("fd")
            )
        ) return true
        val parts = normalized.ipv4Parts() ?: return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 100 && parts[1] in 64..127) ||
            (parts[0] == 169 && parts[1] == 254) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    private fun String.ipv4Parts(): List<Int>? {
        val parts = split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return parts
    }
}

enum class ConnectionRouteMode {
    SYSTEM,
    DIRECT_LAN,
}
