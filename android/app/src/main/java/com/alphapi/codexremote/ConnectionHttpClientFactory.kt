package com.alphapi.codexremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

internal object ConnectionHttpClientFactory {
    @Suppress("DEPRECATION")
    fun create(
        context: Context,
        requestedRouteMode: ConnectionRouteMode,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
        if (requestedRouteMode != ConnectionRouteMode.DIRECT_LAN) {
            return builder.build()
        }

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val localNetwork = connectivity.allNetworks.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
        } ?: throw IllegalStateException("未找到可用于局域网直连的 Wi-Fi 或有线网络")

        return builder
            .proxy(Proxy.NO_PROXY)
            .socketFactory(localNetwork.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = localNetwork.getAllByName(hostname).toList()
            })
            .build()
    }
}
