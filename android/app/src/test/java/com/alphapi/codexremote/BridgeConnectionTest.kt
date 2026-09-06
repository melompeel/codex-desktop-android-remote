package com.alphapi.codexremote

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeConnectionTest {
    @Test
    fun serverCloseImmediatelyReportsDisconnectedSoRepositoryCanReconnect() {
        SocketFixture { socket, headers ->
            upgrade(socket, headers)
            socket.getOutputStream().apply {
                write(byteArrayOf(0x88.toByte(), 2, 3, 0xe9.toByte()))
                flush()
            }
            // A server waits for the client's closing handshake before releasing TCP.
            socket.getInputStream().read()
        }.use { server ->
            val connected = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val api = BridgeApi(server.url, "test-only-token")
            val stream = api.stream({}, { if (it) connected.countDown() else disconnected.countDown() })
            try {
                assertTrue("stream must first connect", connected.await(2, TimeUnit.SECONDS))
                assertTrue(
                    "server shutdown must trigger reconnection without waiting for TCP timeout",
                    disconnected.await(800, TimeUnit.MILLISECONDS),
                )
            } finally {
                stream.cancel()
            }
        }
    }

    @Test
    fun cancellingRefreshCancelsTheHttpCallInsteadOfBlockingTheNewConnection() = runBlocking {
        val requestReceived = CountDownLatch(1)
        SocketFixture { socket, _ ->
            requestReceived.countDown()
            socket.getInputStream().read()
        }.use { server ->
            val client = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
            val api = BridgeApi(server.url, null, client)
            val request = launch(Dispatchers.IO) { runCatching { api.health() } }
            try {
                assertTrue(requestReceived.await(2, TimeUnit.SECONDS))
                val cancelledPromptly = withTimeoutOrNull(600) {
                    request.cancelAndJoin()
                    true
                }
                assertEquals("changing networks must release the old HTTP operation", true, cancelledPromptly)
            } finally {
                client.dispatcher.cancelAll()
                request.cancelAndJoin()
            }
        }
    }

    @Test
    fun unresponsiveWebSocketReportsDisconnectedAfterMissedPong() {
        SocketFixture { socket, headers ->
            upgrade(socket, headers)
            while (socket.getInputStream().read() != -1) { }
        }.use { server ->
            val disconnected = CountDownLatch(1)
            val client = OkHttpClient.Builder().pingInterval(100, TimeUnit.MILLISECONDS).build()
            val stream = BridgeApi(server.url, "test-only-token", client)
                .stream({}, { if (!it) disconnected.countDown() })
            try {
                assertTrue("lost network must cause a reconnect signal", disconnected.await(2, TimeUnit.SECONDS))
            } finally {
                stream.cancel()
            }
        }
    }

    private class SocketFixture(handler: (Socket, Map<String, String>) -> Unit) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        @Volatile private var accepted: Socket? = null
        val url: String = "http://127.0.0.1:${server.localPort}"
        private val worker = thread(isDaemon = true, name = "bridge-connection-test") {
            runCatching {
                server.accept().use { socket ->
                    accepted = socket
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    val headers = buildMap {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
                        }
                    }
                    handler(socket, headers)
                }
            }
        }

        override fun close() {
            accepted?.close()
            server.close()
            worker.join(1_000)
        }
    }

    companion object {
        private fun upgrade(socket: Socket, headers: Map<String, String>) {
            val key = requireNotNull(headers["sec-websocket-key"])
            val digest = MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
            val accept = Base64.getEncoder().encodeToString(digest)
            socket.getOutputStream().apply {
                write(("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                flush()
            }
        }
    }
}
