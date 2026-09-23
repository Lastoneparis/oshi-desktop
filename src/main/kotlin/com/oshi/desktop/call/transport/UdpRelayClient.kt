package com.oshi.desktop.call.transport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `:8089` UDP media relay — the phones' SECOND carrier, between P2P (direct or TURN)
 * and the WebSocket fallback. PARITY.md row 2.1-t.
 *
 * `OSHI/UDPRelayClient.swift`, `OSHI-Android/.../service/UDPRelayClient.kt`:
 *
 * ```
 * out: [type 1][recipLen 1][recipient b64url][senderLen 1][sender b64url][callIdLen 1][callId][payload]
 * in:  [type 1][senderLen 1][sender][callIdLen 1][callId][payload]      register ack = [0x04][0x01]
 * types: 0x01 audio, 0x02 video, 0x04 register, 0x05 ping
 * ```
 *
 * Keys are the X25519 identity keys as base64URL without padding; the server matches the
 * (recipient, sender, callId) triple both ends registered and forwards. The payload is
 * the SAME sealed packet P2P carries — an audio frame, or the whole `0xF1` video envelope
 * — so the relay sees ciphertext only. It does see who calls whom, like the call server.
 *
 * Endpoints expire after 30 s of silence; registration is re-sent every 10 s
 * ([HEARTBEAT_MS], `swift:heartbeatInterval`).
 *
 * Verified by hand against production on 2026-09-23 (two fresh keys, register acks
 * `0401`, an `0x01` and an `0x02` frame forwarded byte-exact).
 *
 * __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ `docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md` (STABLE 1.0):
 * with a [tokens] source every register carries the 44-byte `ORA1` trailer ([UdpRelayAuth]),
 * the counter strictly increasing per token; [registered] is set by the exact `04 01` ack
 * only; a 3-byte `04 00 <code>` is a nack (§5) handled in [handle]. Media datagrams are
 * unchanged. Without a token (fetch failed) the legacy token-less register is sent — but
 * never again once a trailer has been sent or a nack received (§5 last paragraph).
 */
class UdpRelayClient(
    selfKeyB64: String,
    peerKeyB64: String,
    private val callId: String,
    private val server: InetSocketAddress = DEFAULT_SERVER,
    private val log: (String) -> Unit = {},
    private val socket: DatagramSocket = DatagramSocket(0),
    /** Contract §2: this call's relay token, or null for the legacy token-less register. */
    private val tokens: RelayTokenSource? = null,
) : AutoCloseable {

    private val selfKey = toB64Url(selfKeyB64)
    private val peerKey = toB64Url(peerKeyB64)
    private val closed = AtomicBoolean(false)
    private var rx: Thread? = null
    private var heartbeat: Thread? = null

    /** The sealed payload the peer sent through the relay. Runs on the receive thread. */
    @Volatile
    var onPayload: (ByteArray) -> Unit = {}

    /** Wall-clock ms of the last datagram of any kind from the server, or 0. */
    @Volatile
    var lastServerReplyMs: Long = 0L
        private set

    /** Wall-clock ms of the last MEDIA payload received, or 0. */
    @Volatile
    var lastMediaRxMs: Long = 0L
        private set

    @Volatile
    var registered: Boolean = false
        private set

    @Volatile var sent = 0L; private set
    @Volatile var received = 0L; private set

    // ------------------------------------------------ relay auth (contract §3-§5)

    /** Registers sent WITH the trailer / without it (legacy). Diagnostics and tests. */
    @Volatile var authRegistersSent = 0L; private set
    @Volatile var legacyRegistersSent = 0L; private set

    /** The last nack code received (§5), or 0. */
    @Volatile var lastNack = 0; private set

    /** §5 code 2 twice: the key or the bytes are wrong — this carrier is given up, WS takes over. */
    @Volatile var authFailed = false; private set

    /** Heartbeat-thread state: the token the counter belongs to, and the counter. */
    private var counterToken: RelayToken? = null
    private var counter = 0L
    @Volatile private var needRefresh = false
    @Volatile private var badMacRefetched = false
    /** Once true, a token-less register is never sent again (§5). */
    @Volatile private var noLegacy = false
    private val wake = LinkedBlockingQueue<Unit>(1)

    fun start() {
        if (rx != null || closed.get()) return
        socket.soTimeout = 500
        rx = Thread({ receiveLoop() }, "oshi-udp-relay-rx").apply { isDaemon = true; start() }
        heartbeat = Thread({
            var lastRegisterMs = 0L
            while (!closed.get()) {
                // A nack wakes this loop early; never re-register faster than once a second.
                val since = System.currentTimeMillis() - lastRegisterMs
                if (since < MIN_REREGISTER_MS) {
                    try { Thread.sleep(MIN_REREGISTER_MS - since) } catch (_: InterruptedException) { return@Thread }
                }
                lastRegisterMs = System.currentTimeMillis()
                runCatching { sendRegister() }
                try { wake.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return@Thread }
            }
        }, "oshi-udp-relay-hb").apply { isDaemon = true; start() }
    }

    /** One register: authenticated when a token is available (§3), legacy otherwise. */
    private fun sendRegister() {
        if (closed.get() || authFailed) return
        val src = tokens
        if (src == null) {
            if (sendFrame(TYPE_REGISTER, ByteArray(0))) legacyRegistersSent++
            return
        }
        val t = if (needRefresh) { needRefresh = false; src.refresh() } else src.current()
        if (t == null) {
            if (!noLegacy && sendFrame(TYPE_REGISTER, ByteArray(0))) legacyRegistersSent++
            return
        }
        if (counterToken !== t) { counterToken = t; counter = 0L } // §2: counter restarts at 1
        counter++
        val f = UdpRelayAuth.datagram(TYPE_REGISTER, peerKey, selfKey, callId, t.tokenId, counter, t.macKey)
        if (runCatching { socket.send(DatagramPacket(f, f.size, resolved())); true }.getOrDefault(false)) {
            noLegacy = true
            authRegistersSent++
        }
    }

    /** True when the server answered inside [STALE_MS] — the phones' `udpRelayUsable`. */
    fun usable(nowMs: Long = System.currentTimeMillis()): Boolean =
        registered && !authFailed && lastServerReplyMs > 0 && nowMs - lastServerReplyMs < STALE_MS

    /** True when media arrived through the relay inside [window] ms. */
    fun delivering(nowMs: Long = System.currentTimeMillis(), window: Long = LIVENESS_MS): Boolean =
        lastMediaRxMs > 0 && nowMs - lastMediaRxMs < window

    /** Send one sealed packet. Video envelopes (`0xF1`) ride type 0x02, everything else 0x01. */
    fun send(sealed: ByteArray): Boolean {
        if (sealed.isEmpty()) return false
        val type = if ((sealed[0].toInt() and 0xFF) == VIDEO_ENVELOPE) TYPE_VIDEO else TYPE_AUDIO
        return sendFrame(type, sealed).also { if (it) sent++ }
    }

    private fun sendFrame(type: Int, payload: ByteArray): Boolean {
        if (closed.get()) return false
        val f = encode(type, peerKey, selfKey, callId, payload)
        return runCatching { socket.send(DatagramPacket(f, f.size, resolved())); true }.getOrDefault(false)
    }

    private fun resolved(): InetSocketAddress =
        if (server.isUnresolved) InetSocketAddress(server.hostString, server.port) else server

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (!closed.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (closed.get()) return
                continue
            }
            handle(buf.copyOf(pkt.length))
        }
    }

    internal fun handle(data: ByteArray) {
        if (data.isEmpty()) return
        lastServerReplyMs = System.currentTimeMillis()
        val type = data[0].toInt() and 0xFF
        // Ack test stays "exactly 04 01" (contract §3/§9).
        if (type == TYPE_REGISTER && data.size == 2 && data[1].toInt() == 0x01) {
            if (!registered) {
                val how = if (authRegistersSent > 0) "token-authenticated" else "legacy, no token"
                log("call: udp relay registered at ${server.hostString}:${server.port} ($how)")
            }
            registered = true
            return
        }
        if (type == TYPE_REGISTER && data.size == 3 && data[1].toInt() == 0x00) {
            onNack(data[2].toInt() and 0xFF)
            return
        }
        if (type != TYPE_AUDIO && type != TYPE_VIDEO) return
        val payload = decodeInbound(data) ?: return
        if (payload.isEmpty()) return
        received++
        lastMediaRxMs = lastServerReplyMs
        onPayload(payload)
    }

    /** Contract §5. Runs on the receive thread; the heartbeat thread does the fetching. */
    private fun onNack(code: Int) {
        lastNack = code
        noLegacy = true
        if (code != UdpRelayAuth.NACK_REPLAY) registered = false
        when (code) {
            UdpRelayAuth.NACK_UNKNOWN_TOKEN, UdpRelayAuth.NACK_BINDING -> {
                log("call: udp relay nack $code — fetching a new relay token")
                needRefresh = true
            }
            UdpRelayAuth.NACK_BAD_MAC -> {
                if (!badMacRefetched) {
                    badMacRefetched = true
                    log("call: udp relay nack 2 (bad MAC) — fetching a new relay token once")
                    needRefresh = true
                } else {
                    log("call: udp relay nack 2 (bad MAC) again — giving up on :8089, WS relay takes over")
                    authFailed = true
                    return
                }
            }
            UdpRelayAuth.NACK_REPLAY -> log("call: udp relay nack 3 (replay) — continuing with a higher counter")
            UdpRelayAuth.NACK_KEY_HELD, UdpRelayAuth.NACK_UNAUTH_REFUSED -> {
                log("call: udp relay nack $code — a trailer is required")
                if (counterToken == null) needRefresh = true
            }
            else -> log("call: udp relay nack $code (unknown)")
        }
        wake.offer(Unit)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeat?.interrupt()
        runCatching { socket.close() }
        rx?.let { runCatching { it.join(600) } }
    }

    companion object {
        val DEFAULT_SERVER: InetSocketAddress = InetSocketAddress.createUnresolved("45.67.216.197", 8089)

        const val TYPE_AUDIO = 0x01
        const val TYPE_VIDEO = 0x02
        const val TYPE_REGISTER = 0x04
        const val TYPE_PING = 0x05
        private const val VIDEO_ENVELOPE = 0xF1

        const val HEARTBEAT_MS = 10_000L
        /** Floor between two registers when a nack wakes the heartbeat early. */
        const val MIN_REREGISTER_MS = 1_000L
        /** Android `UDP_RELAY_STALE_MS` / iOS relay usability: a reply inside 15 s. */
        const val STALE_MS = 15_000L
        /** iOS `transportLivenessWindow`. */
        const val LIVENESS_MS = 2_000L

        /** Standard base64 → base64url, padding stripped (`UDPRelayClient.swift:base64urlEncode`). */
        fun toB64Url(s: String): String = s.replace('+', '-').replace('/', '_').trimEnd('=')

        fun encode(type: Int, recipient: String, sender: String, callId: String, payload: ByteArray): ByteArray {
            val r = recipient.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, 255)) }
            val s = sender.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, 255)) }
            val c = callId.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, 255)) }
            return byteArrayOf(type.toByte(), r.size.toByte()) + r + byteArrayOf(s.size.toByte()) + s +
                byteArrayOf(c.size.toByte()) + c + payload
        }

        /** Strip `[type][senderLen][sender][callIdLen][callId]`, or null if truncated. */
        fun decodeInbound(data: ByteArray): ByteArray? {
            var o = 1
            if (o >= data.size) return null
            val sl = data[o].toInt() and 0xFF; o += 1 + sl
            if (o >= data.size) return null
            val cl = data[o].toInt() and 0xFF; o += 1 + cl
            if (o > data.size) return null
            return data.copyOfRange(o, data.size)
        }
    }
}
