package com.oshi.desktop.call.transport

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A TURN allocation on the coturn both phones use — PARITY.md row 2.1-t.
 *
 * `OSHI/TurnClient.swift` and `OSHI-Android/.../service/p2p/TurnClient.kt` do the same
 * five things, and so does this:
 *
 *  1. **Allocate** with long-term credentials: the first request goes out bare, coturn
 *     answers 401 with REALM + NONCE, the retry carries MESSAGE-INTEGRITY. A 438 (stale
 *     nonce) is retried once with the fresh nonce. The credentials are the EPHEMERAL ones
 *     from `/voip/turn-creds` ([TurnCredentials]): coturn runs `use-auth-secret`, and iOS
 *     measured that the static `oshi:…` pair both apps once shipped is answered 401
 *     (`VoiceCallManager.swift:14116-14125`).
 *  2. **Refresh** at `lifetime − 60 s`, retransmitted, so a long call never loses its
 *     allocation (the iOS 2026-07-27 fix: one lost Refresh used to kill the relay
 *     silently at T+lifetime).
 *  3. **ChannelBind** per peer relay candidate, re-bound every [CHANNEL_REBIND_MS]
 *     because a binding's PERMISSION expires after 300 s (RFC 8656 §9) even though the
 *     channel lives 600 s. Packets for a channel not yet acknowledged are QUEUED (cap
 *     [PENDING_CAP]) and flushed on the ack — coturn drops ChannelData on an unbound
 *     channel, and the first second of a relay call would be lost (iOS F1).
 *  4. **ChannelData** out, and ChannelData or Data indications in, delivered to
 *     [onData] with the PEER's address, never the server's.
 *  5. **Deallocate** on close (Refresh, lifetime 0), best-effort.
 *
 * It owns its OWN UDP socket, separate from the media socket, exactly as both phones do
 * (an `NWConnection` on iOS, a second `DatagramSocket` on Android). Sharing the media
 * socket would work too, but it would make every STUN/TURN answer and every relayed
 * frame contend with the media demux for one reader, and it would diverge from the
 * phones for no gain.
 *
 * Secrets: the password and the derived key are never logged, and nothing here prints a
 * username either — only the relayed address, which is what the peer is told anyway.
 */
class TurnClient(
    /** UDP to :3478, or TLS to oshi-messenger.com:5349 — see [TurnLink]. */
    private val link: TurnLink,
    private val username: String,
    private val password: String,
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    /** Plain UDP to [server], the phones' default. */
    constructor(server: InetSocketAddress, username: String, password: String, log: (String) -> Unit = {}) :
        this(UdpLink(server), username, password, log)

    data class Allocation(val relayIp: String, val relayPort: Int, val mappedIp: String?, val mappedPort: Int?, val lifetimeSec: Int)

    /** Every relayed datagram, with the PEER's address. Runs on the receive thread. */
    @Volatile
    var onData: (data: ByteArray, peerIp: String, peerPort: Int) -> Unit = { _, _, _ -> }

    @Volatile
    var allocation: Allocation? = null
        private set

    private val closed = AtomicBoolean(false)
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "oshi-turn-timer").apply { isDaemon = true }
    }
    private val linkStarted = AtomicBoolean(false)

    @Volatile private var realm: String? = null
    @Volatile private var nonce: ByteArray? = null
    @Volatile private var key: ByteArray? = null

    /** txId hex → waiter. */
    private val pending = ConcurrentHashMap<String, Waiter>()

    private class Waiter {
        val latch = CountDownLatch(1)
        @Volatile var response: TurnMessage.Parsed? = null
    }

    /** "ip:port" → channel. */
    private val channels = ConcurrentHashMap<String, Int>()
    /** channel → (ip, port). */
    private val peers = ConcurrentHashMap<Int, Pair<String, Int>>()
    private val confirmed = ConcurrentHashMap.newKeySet<Int>()
    private val queued = ConcurrentHashMap<Int, MutableList<ByteArray>>()
    @Volatile private var nextChannel = TurnMessage.CHANNEL_MIN

    // Counters for diagnostics and tests.
    @Volatile var relayedIn = 0L; private set
    @Volatile var relayedOut = 0L; private set

    /**
     * Allocate. Blocking, bounded by [ALLOCATE_DEADLINE_MS] per attempt; call off the
     * signalling thread. Returns null on failure — a call with no relay is a worse call,
     * not a failed one, and the direct pairs keep probing.
     */
    fun allocate(): Allocation? {
        if (closed.get()) return null
        try {
            startReceiver()
        } catch (t: Throwable) {
            log("turn: could not open ${link.describe} — ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
        val transport = TurnMessage.Attr(TurnMessage.ATTR_REQUESTED_TRANSPORT, byteArrayOf(TurnMessage.TRANSPORT_UDP, 0, 0, 0))
        // Unauthenticated first shot → 401 + realm/nonce.
        var resp = request(TurnMessage.ALLOCATE_REQUEST, listOf(transport), authenticated = false) ?: return null.also {
            log("turn: allocate got no answer over ${link.describe}")
        }
        var authRetries = 0
        while (resp.type == TurnMessage.ALLOCATE_ERROR && authRetries < 2) {
            val code = resp.errorCode
            if (code != 401 && code != 438) {
                log("turn: allocate refused with $code")
                return null
            }
            learnAuth(resp)
            authRetries++
            resp = request(TurnMessage.ALLOCATE_REQUEST, listOf(transport), authenticated = true) ?: return null
        }
        if (resp.type != TurnMessage.ALLOCATE_SUCCESS) {
            log("turn: allocate failed (${resp.errorCode ?: "type ${Integer.toHexString(resp.type)}"})")
            return null
        }
        val relayed = resp.relayed ?: return null
        val mapped = resp.mapped
        val a = Allocation(relayed.first, relayed.second, mapped?.first, mapped?.second, resp.lifetime ?: 600)
        allocation = a
        scheduleRefresh(a.lifetimeSec)
        log("turn: relay allocated ${a.relayIp}:${a.relayPort} over ${link.describe} (lifetime ${a.lifetimeSec}s)")
        return a
    }

    /**
     * Bind a channel to a peer, or return the one already bound. Asynchronous: the ack
     * lands on the receive thread and flushes anything [send] queued meanwhile.
     */
    fun bindChannel(peerIp: String, peerPort: Int): Int? {
        if (closed.get() || allocation == null) return null
        val k = "$peerIp:$peerPort"
        channels[k]?.let { return it }
        val ch = synchronized(this) {
            channels[k] ?: run {
                if (nextChannel > TurnMessage.CHANNEL_MAX) return null
                val c = nextChannel++
                channels[k] = c
                peers[c] = peerIp to peerPort
                c
            }
        }
        issueBind(ch, peerIp, peerPort)
        return ch
    }

    private fun issueBind(ch: Int, peerIp: String, peerPort: Int) {
        timers.execute {
            if (closed.get()) return@execute
            val txId = TurnMessage.newTransactionId()
            val attrs = listOf(
                TurnMessage.Attr(TurnMessage.ATTR_CHANNEL_NUMBER, byteArrayOf((ch ushr 8).toByte(), ch.toByte(), 0, 0)),
                TurnMessage.Attr(TurnMessage.ATTR_XOR_PEER_ADDRESS, TurnMessage.encodeXorAddress(peerIp, peerPort, txId)),
            )
            var resp = request(TurnMessage.CHANNEL_BIND_REQUEST, attrs, authenticated = true, txId = txId)
            if (resp?.type == TurnMessage.CHANNEL_BIND_ERROR && resp.errorCode == 438) {
                learnAuth(resp)
                val tx2 = TurnMessage.newTransactionId()
                val attrs2 = listOf(attrs[0], TurnMessage.Attr(TurnMessage.ATTR_XOR_PEER_ADDRESS, TurnMessage.encodeXorAddress(peerIp, peerPort, tx2)))
                resp = request(TurnMessage.CHANNEL_BIND_REQUEST, attrs2, authenticated = true, txId = tx2)
            }
            if (resp?.type == TurnMessage.CHANNEL_BIND_SUCCESS) {
                val first = confirmed.add(ch)
                val backlog = queued.remove(ch)
                backlog?.forEach { sendNow(ch, it) }
                if (first) log("turn: channel 0x${Integer.toHexString(ch)} bound to $peerIp:$peerPort" + (backlog?.let { " — flushed ${it.size}" } ?: ""))
                timers.schedule({ issueBind(ch, peerIp, peerPort) }, CHANNEL_REBIND_MS, TimeUnit.MILLISECONDS)
            } else {
                log("turn: channel bind to $peerIp:$peerPort failed (${resp?.errorCode ?: "no answer"}) — retrying")
                timers.schedule({ issueBind(ch, peerIp, peerPort) }, BIND_RETRY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    /**
     * Relay [data] to a peer. Binds on first use. False only when there is no allocation
     * or no channel left; a packet queued behind an unacknowledged bind counts as sent.
     */
    fun send(data: ByteArray, peerIp: String, peerPort: Int): Boolean {
        val ch = bindChannel(peerIp, peerPort) ?: return false
        if (confirmed.contains(ch)) return sendNow(ch, data)
        val q = queued.computeIfAbsent(ch) { ArrayList() }
        synchronized(q) {
            if (confirmed.contains(ch)) return sendNow(ch, data)
            if (q.size < PENDING_CAP) q.add(data)
        }
        return true
    }

    fun isBound(peerIp: String, peerPort: Int): Boolean =
        channels["$peerIp:$peerPort"]?.let { confirmed.contains(it) } ?: false

    private fun sendNow(ch: Int, data: ByteArray): Boolean {
        val frame = TurnMessage.channelData(ch, data, pad = link.padsChannelData)
        return link.send(frame).also { if (it) relayedOut++ }
    }

    // ================================================================ requests

    private fun learnAuth(resp: TurnMessage.Parsed) {
        resp.realm?.let { realm = it }
        resp.nonce?.let { nonce = it }
        val r = realm ?: return
        key = TurnMessage.longTermKey(username, r, password)
    }

    private fun authAttrs(): List<TurnMessage.Attr> {
        val r = realm ?: return emptyList()
        val n = nonce ?: return emptyList()
        return listOf(
            TurnMessage.Attr(TurnMessage.ATTR_USERNAME, username.toByteArray(Charsets.UTF_8)),
            TurnMessage.Attr(TurnMessage.ATTR_REALM, r.toByteArray(Charsets.UTF_8)),
            TurnMessage.Attr(TurnMessage.ATTR_NONCE, n),
        )
    }

    /**
     * Send one request and wait for its answer, retransmitting on the phones' ladder
     * (500, 1000, 1500 ms; `TurnClient.swift:124-125`). Authenticated answers must pass
     * MESSAGE-INTEGRITY or they are ignored — a forged Allocate success would otherwise
     * plant a relay address we would advertise to the peer.
     */
    private fun request(
        type: Int,
        attrs: List<TurnMessage.Attr>,
        authenticated: Boolean,
        txId: ByteArray = TurnMessage.newTransactionId(),
    ): TurnMessage.Parsed? {
        if (closed.get()) return null
        val k = if (authenticated) key else null
        val msg = TurnMessage.build(type, txId, attrs + if (authenticated) authAttrs() else emptyList(), k)
        val hex = HolePunch.nonceHex(txId)
        val w = Waiter()
        pending[hex] = w
        try {
            var elapsed = 0L
            for (wait in RETRANSMIT_MS) {
                link.send(msg)
                if (w.latch.await(wait, TimeUnit.MILLISECONDS)) break
                elapsed += wait
                if (closed.get()) return null
            }
            val r = w.response ?: return null
            val isSuccess = (r.type and 0x0110) == 0x0100
            if (k != null && isSuccess && !r.integrityValid(k)) {
                log("turn: dropped a success response that failed MESSAGE-INTEGRITY")
                return null
            }
            return r
        } finally {
            pending.remove(hex)
        }
    }

    private fun scheduleRefresh(lifetimeSec: Int) {
        val delaySec = maxOf(lifetimeSec - 60, 30).toLong()
        timers.schedule({ refresh() }, delaySec, TimeUnit.SECONDS)
    }

    private fun refresh() {
        if (closed.get()) return
        val life = TurnMessage.Attr(TurnMessage.ATTR_LIFETIME, byteArrayOf(0, 0, 0x02, 0x58.toByte())) // 600 s
        var resp = request(TurnMessage.REFRESH_REQUEST, listOf(life), authenticated = true)
        if (resp?.type == TurnMessage.REFRESH_ERROR && resp.errorCode == 438) {
            learnAuth(resp)
            resp = request(TurnMessage.REFRESH_REQUEST, listOf(life), authenticated = true)
        }
        if (resp?.type == TurnMessage.REFRESH_SUCCESS) {
            scheduleRefresh(resp.lifetime ?: 600)
        } else {
            log("turn: refresh failed (${resp?.errorCode ?: "no answer"}) — retrying in 5 s")
            timers.schedule({ refresh() }, 5, TimeUnit.SECONDS)
        }
    }

    // ================================================================ receive

    private fun startReceiver() {
        if (!linkStarted.compareAndSet(false, true)) return
        link.start { data, length -> runCatching { handle(data, length) } }
    }

    /** One datagram from the server. Internal so a test can feed bytes in by hand. */
    internal fun handle(data: ByteArray, length: Int) {
        if (TurnMessage.isChannelData(data, length)) {
            val (ch, payload) = TurnMessage.parseChannelData(data, length) ?: return
            val peer = peers[ch] ?: return
            relayedIn++
            onData(payload, peer.first, peer.second)
            return
        }
        val msg = TurnMessage.parse(data, length) ?: return
        if (!msg.fingerprintValid()) return
        if (msg.type == TurnMessage.DATA_INDICATION) {
            val peer = msg.peer ?: return
            val payload = msg.attr(TurnMessage.ATTR_DATA) ?: return
            relayedIn++
            onData(payload, peer.first, peer.second)
            return
        }
        val w = pending[HolePunch.nonceHex(msg.txId)] ?: return
        w.response = msg
        w.latch.countDown()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Deallocate: Refresh with LIFETIME 0, fire-and-forget. Frees the server's port now
        // instead of at the end of the lifetime.
        if (allocation != null && key != null) {
            runCatching {
                val msg = TurnMessage.build(
                    TurnMessage.REFRESH_REQUEST, TurnMessage.newTransactionId(),
                    listOf(TurnMessage.Attr(TurnMessage.ATTR_LIFETIME, byteArrayOf(0, 0, 0, 0))) + authAttrs(), key,
                )
                link.send(msg)
            }
        }
        timers.shutdownNow()
        runCatching { link.close() }
        pending.values.forEach { it.latch.countDown() }
        allocation = null
    }

    companion object {
        /** Retransmit waits for one request. Sum = the 3 s deadline iOS uses. */
        val RETRANSMIT_MS = longArrayOf(500, 1000, 1500)
        const val ALLOCATE_DEADLINE_MS = 3_000L

        /** Re-bind before the 300 s permission lapses. */
        const val CHANNEL_REBIND_MS = 240_000L
        const val BIND_RETRY_MS = 2_000L

        /** iOS `pendingRelayPacketsCap`. */
        const val PENDING_CAP = 50

        const val RECEIVE_BUFFER = 4096
    }
}
