package com.alphapi.codexremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

internal data class NetworkTransportSignature(
    val networkId: String,
    val wifi: Boolean,
    val cellular: Boolean,
    val ethernet: Boolean,
    val vpn: Boolean,
    val validated: Boolean,
)

internal fun shouldReconnectForNetworkChange(
    previous: NetworkTransportSignature?,
    current: NetworkTransportSignature?,
): Boolean = previous != current

internal class NetworkChangeMonitor(
    context: Context,
    private val onChanged: () -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var lastSignature: NetworkTransportSignature? = currentSignature()
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = inspect()
        override fun onLost(network: Network) = inspect()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = inspect()
    }

    fun start() {
        if (started) return
        started = runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)
    }

    fun close() {
        if (!started) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        started = false
    }

    @Synchronized
    private fun inspect() {
        val current = currentSignature()
        val changed = shouldReconnectForNetworkChange(lastSignature, current)
        lastSignature = current
        if (changed) onChanged()
    }

    private fun currentSignature(): NetworkTransportSignature? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return NetworkTransportSignature(
            networkId = network.toString(),
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }
}
