package com.oshi.desktop.call.transport

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The call server's WebSocket media relay — the phones' LAST carrier, for networks that
 * block every UDP datagram to 45.67.216.197. PARITY.md row 2.1-t.
 *
 * `wss://oshi-messenger.com/voip/` (the trailing slash matters: nginx's `location /voip/`
 * carries the proxy timeouts, `VoiceCallManager.swift:2893-2897`), proxied to the call
 * server on :8083. This client speaks the server's BINARY protocol — the one Android uses
 * (`VPSClient.kt:1984-2000, :2135`) and `call_server.js handleBinaryMessage` documents:
 *
 * ```
 * out: [type][recipLen][recipient b64url][senderLen][sender b64url][callIdLen][callId][payload]
 * in:  [type][senderLen][sender][callIdLen][callId][payload]
 * 0x01 audio · 0x02 video · 0x04 register (ack [04 01], refusal [04 00] + close 4403) · 0x05 ping
 * ```
 *
 * — byte-identical to the `:8089` UDP relay, so [UdpRelayClient.encode] builds both. The
 * payload is the same sealed packet P2P carries. iOS uses the older JSON text protocol
 * (`{"type":"audio","audio":<b64>,…}`); the server bridges the two and bridges both to
 * `:8089` — it delivers to a recipient's live UDP endpoint FIRST and to its WebSocket
 * otherwise (`_relayMediaInner`, "UDP FIRST"), JSON to a JSON socket and binary to a binary
 * one. So a desktop on this WebSocket reaches an iPhone on JSON-WS or on `:8089`, and back.
 *
 * AUTH: the upgrade GET is signed the way iOS signs it since the 2026-09-23 contract — the
 * three `x-oshi-*` headers over `GET\n/voip/\n<sha256("")>\n<ts>` — and the server binds that
 * key to the identity in the register frame. The server runs `dual` today (an unsigned
 * register is still served); a signed one also cannot be displaced by an unsigned one.
 *
 * Keepalive: a binary `0x05` ping every [PING_MS] (the server answers `0x05` + its clock),
 * on top of the server's own 15 s WebSocket ping that the JDK answers. Reconnect: iOS's
 * ladder, `0.2 s × 1.3^n` capped at 3 s (`swift:attemptWebSocketReconnect`), for as long as
 * the call lasts.
 */
class WsRelayClient(
    selfKeyB64: String,
    peerKeyB64: String,
    private val callId: String,
    private val url: String = DEFAULT_URL,
    /** Signed upgrade headers for the path, or empty to connect unsigned (Android's way). */
    private val upgradeHeaders: (path: String) -> Map<String, String> = { emptyMap() },
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    private val selfKey = UdpRelayClient.toB64Url(selfKeyB64)
    private val peerKey = UdpRelayClient.toB64Url(peerKeyB64)
    private val closed = AtomicBoolean(false)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "oshi-ws-relay").apply { isDaemon = true }
    }
    @Volatile private var ws: WebSocket? = null
    private val attempts = AtomicInteger(0)
    private val connecting = AtomicBoolean(false)

    @Volatile var onPayload: (ByteArray) -> Unit = {}
    @Volatile var registered = false; private set
    @Volatile var refused: String? = null; private set
    @Volatile var lastServerReplyMs = 0L; private set
    @Volatile var lastMediaRxMs = 0L; private set
    @Volatile var sent = 0L; private set
    @Volatile var received = 0L; private set

    fun start() {
        if (closed.get()) return
        sender.start()
        connect()
        timers.scheduleWithFixedDelay({ ping() }, PING_MS, PING_MS, TimeUnit.MILLISECONDS)
    }

    /** Registered and heard from inside [STALE_MS]. */
    fun usable(nowMs: Long = System.currentTimeMillis()): Boolean =
        registered && ws != null && nowMs - lastServerReplyMs < STALE_MS

    fun delivering(nowMs: Long = System.currentTimeMillis(), window: Long = UdpRelayClient.LIVENESS_MS): Boolean =
        lastMediaRxMs > 0 && nowMs - lastMediaRxMs < window

    fun send(sealed: ByteArray): Boolean {
        if (sealed.isEmpty()) return false
        val type = if ((sealed[0].toInt() and 0xFF) == 0xF1) UdpRelayClient.TYPE_VIDEO else UdpRelayClient.TYPE_AUDIO
        return sendFrame(UdpRelayClient.encode(type, peerKey, selfKey, callId, sealed)).also { if (it) sent++ }
    }

    /**
     * Frames go through ONE sender thread: `java.net.http.WebSocket` refuses a send while
     * the previous one is still in flight, and the audio pump must never block on TCP. The
     * queue is bounded ([QUEUE_CAP], ~2 s of audio + video); when a stalled socket fills it
     * the OLDEST frame is dropped — stale real-time media is worth less than fresh.
     */
    private val queue = java.util.concurrent.LinkedBlockingDeque<ByteArray>()
    private val sender = Thread({
        while (!closed.get()) {
            val f = try { queue.take() } catch (_: InterruptedException) { return@Thread }
            val socket = ws ?: continue
            runCatching { socket.sendBinary(ByteBuffer.wrap(f), true).get(5, TimeUnit.SECONDS) }
                .onFailure { markDead("send failed: ${it.javaClass.simpleName}") }
        }
    }, "oshi-ws-relay-tx").apply { isDaemon = true }

    private fun sendFrame(frame: ByteArray): Boolean {
        if (ws == null || closed.get()) return false
        while (queue.size >= QUEUE_CAP) queue.pollFirst()
        queue.offerLast(frame)
        return true
    }

    private fun ping() {
        if (closed.get() || !registered) return
        sendFrame(UdpRelayClient.encode(UdpRelayClient.TYPE_PING, "", selfKey, callId, ByteArray(0)))
    }

    private fun connect() {
        if (closed.get() || !connecting.compareAndSet(false, true)) return
        val uri = URI(url)
        val path = uri.rawPath.ifEmpty { "/" }
        val builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8))
        runCatching { upgradeHeaders(path) }.getOrDefault(emptyMap()).forEach { (k, v) -> builder.header(k, v) }
        builder.header("X-OSHI-Platform", "desktop")
        builder.buildAsync(uri, Listener()).whenComplete { socket, err ->
            connecting.set(false)
            if (err != null || socket == null) {
                log("call: ws relay connect failed — ${err?.cause?.javaClass?.simpleName ?: err?.javaClass?.simpleName}")
                scheduleReconnect()
                return@whenComplete
            }
            if (closed.get()) { runCatching { socket.abort() }; return@whenComplete }
            ws = socket
            // Binary register: empty recipient, our key as sender (`VPSClient.kt:1990-1996`).
            sendFrame(UdpRelayClient.encode(UdpRelayClient.TYPE_REGISTER, "", selfKey, callId, ByteArray(0)))
        }
    }

    private fun markDead(why: String) {
        val had = ws
        ws = null
        registered = false
        if (had != null) {
            runCatching { had.abort() }
            if (!closed.get()) log("call: ws relay lost ($why) — reconnecting")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (closed.get() || refused != null) return
        val n = attempts.incrementAndGet()
        val delay = minOf((200.0 * Math.pow(1.3, (n - 1).toDouble())).toLong(), 3_000L)
        runCatching { timers.schedule({ connect() }, delay, TimeUnit.MILLISECONDS) }
    }

    internal fun handle(frame: ByteArray) {
        if (frame.isEmpty()) return
        lastServerReplyMs = System.currentTimeMillis()
        val type = frame[0].toInt() and 0xFF
        when {
            type == UdpRelayClient.TYPE_REGISTER && frame.size == 2 -> {
                if (frame[1].toInt() == 1) {
                    if (!registered) log("call: ws relay registered at $url")
                    registered = true
                    attempts.set(0)
                } else {
                    refused = "register refused"
                    log("call: ws relay REFUSED our registration (auth) — not retrying")
                }
            }
            type == UdpRelayClient.TYPE_PING -> Unit
            type == UdpRelayClient.TYPE_AUDIO || type == UdpRelayClient.TYPE_VIDEO -> {
                val payload = UdpRelayClient.decodeInbound(frame) ?: return
                if (payload.isEmpty()) return
                received++
                lastMediaRxMs = lastServerReplyMs
                onPayload(payload)
            }
        }
    }

    private inner class Listener : WebSocket.Listener {
        private val acc = ByteArrayOutputStream()

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val chunk = ByteArray(data.remaining()).also { data.get(it) }
            acc.write(chunk)
            if (last) {
                val whole = acc.toByteArray()
                acc.reset()
                runCatching { handle(whole) }
            }
            webSocket.request(1)
            return null
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            // Binary mode: the server only sends text for errors / JSON acks. Seen = alive.
            lastServerReplyMs = System.currentTimeMillis()
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            if (statusCode == 4403) {
                refused = reason ?: "4403"
                log("call: ws relay closed 4403 ($reason) — auth refused, not retrying")
            }
            if (ws === webSocket) markDead("closed $statusCode")
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (ws === webSocket) markDead(error.javaClass.simpleName)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        timers.shutdownNow()
        sender.interrupt()
        queue.clear()
        val s = ws
        ws = null
        runCatching { s?.sendClose(WebSocket.NORMAL_CLOSURE, "call ended")?.get(1, TimeUnit.SECONDS) }
        runCatching { s?.abort() }
    }

    companion object {
        const val DEFAULT_URL = "wss://oshi-messenger.com/voip/"
        const val PING_MS = 10_000L
        const val QUEUE_CAP = 200
        /** No server frame (ack, ping reply, media) for this long = not usable. */
        const val STALE_MS = 25_000L

        /** `wss://<host>/voip/` for an `https://<host>` base. */
        fun urlFor(baseUrl: String): String {
            val b = baseUrl.trimEnd('/')
            val scheme = if (b.startsWith("https://")) "wss://" else "ws://"
            return scheme + b.substringAfter("://") + "/voip/"
        }
    }
}
