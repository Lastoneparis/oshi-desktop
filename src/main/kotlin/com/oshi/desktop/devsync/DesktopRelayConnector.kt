package com.oshi.desktop.devsync

import com.oshi.desktop.DesktopV2Signer
import com.oshi.messenger.network.v2.devsync.DevSyncRelayAuth
import com.oshi.messenger.network.v2.devsync.RelayConnector
import com.oshi.messenger.network.v2.devsync.RelayDeviceCredentials
import com.oshi.messenger.network.v2.devsync.RelayListener
import com.oshi.messenger.network.v2.devsync.RelaySocket
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * __DEVSYNC_DIRECT_2026_09_22__ The live pass-through relay (`GET /v2/devsync?device=<id>`) over the
 * JDK's own `java.net.http.WebSocket` — no new dependency (design §13, Desktop row).
 *
 * The upgrade is account- AND device-signed (__DEVSYNC_DEVICE_AUTH_2026_09_23__,
 * [DevSyncRelayAuth]): the account signature (x-oshi-user = the X25519 account key) covers
 * sha256(B) instead of an empty body, where B binds identity, deviceId, dk, dsk and a fresh nonce;
 * the device signing key `dsk` ([DeviceMailbox.relayCredentials]) signs `OSHI-DEVICE/1…` over the
 * same B. The relay refuses account-only, unbound or replayed upgrades (401) — no grace.
 *
 * `java.net.http.WebSocket` allows ONE outstanding send at a time, so sends are chained; ordering
 * is what the Noise stream needs anyway. Inbound messages may arrive in parts and are reassembled.
 */
class DesktopRelayConnector(
    private val baseUrl: String,
    private val signer: DesktopV2Signer,
    private val credentials: () -> RelayDeviceCredentials,
    private val log: (String) -> Unit = {},
    /** The account signature's timestamp source (tests pin it to prove same-millisecond connects). */
    private val clock: () -> Long = System::currentTimeMillis,
) : RelayConnector {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun connect(deviceIdHex: String, listener: RelayListener): RelaySocket {
        val headers = DevSyncRelayAuth.upgradeHeaders(signer.userKey, deviceIdHex, credentials(), { b ->
            signer.sign("GET", RelayConnector.PATH, b, withUserHeader = true, timestampMs = clock())
        })
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> baseUrl
        }.trimEnd('/')
        val builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
        for ((k, v) in headers) builder.header(k, v)
        val socket = Socket(listener)
        builder.buildAsync(URI.create("$wsBase${RelayConnector.PATH}?device=$deviceIdHex"), socket)
            .whenComplete { ws, err ->
                if (err != null) {
                    log("devsync relay upgrade failed: ${err.cause?.javaClass?.simpleName ?: err.javaClass.simpleName}")
                    socket.fail(-1, "connect-failed")
                } else socket.ws = ws
            }
        return socket
    }

    private class Socket(private val listener: RelayListener) : RelaySocket, WebSocket.Listener {
        @Volatile var ws: WebSocket? = null
        private var chain: CompletableFuture<*> = CompletableFuture.completedFuture(null)
        private val closed = AtomicBoolean(false)
        private val bin = ByteArrayOutputStream()
        private val text = StringBuilder()

        fun fail(code: Int, reason: String) {
            if (closed.compareAndSet(false, true)) listener.onClosed(code, reason)
        }

        override fun sendBinary(bytes: ByteArray): Boolean {
            val w = ws ?: return false
            if (closed.get()) return false
            synchronized(this) {
                chain = chain.thenCompose { w.sendBinary(ByteBuffer.wrap(bytes), true) }
                    .exceptionally { fail(-1, "send-failed"); null }
            }
            return true
        }

        override fun close() {
            val w = ws
            if (closed.compareAndSet(false, true)) {
                runCatching { w?.sendClose(WebSocket.NORMAL_CLOSURE, "") }
                runCatching { w?.abort() }
            }
        }

        override fun onOpen(webSocket: WebSocket) {
            ws = webSocket
            webSocket.request(1)
            listener.onOpen()
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val t = text.toString()
                text.setLength(0)
                listener.onText(t)
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val b = ByteArray(data.remaining())
            data.get(b)
            bin.write(b)
            if (last) {
                val all = bin.toByteArray()
                bin.reset()
                listener.onBinary(all)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            fail(statusCode, reason)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            fail(-1, error.javaClass.simpleName)
        }
    }
}
