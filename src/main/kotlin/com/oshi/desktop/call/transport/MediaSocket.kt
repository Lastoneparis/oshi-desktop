package com.oshi.desktop.call.transport

import com.oshi.desktop.call.CallOffer
import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.media.MediaSequence
import com.oshi.desktop.call.media.ReplayWindow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The UDP socket a call's audio actually leaves from — PARITY.md row 2.1's missing half.
 *
 * Row 2.1 records the state this file changes: *"Nothing sends any of these: the media
 * ingress this lane belongs to does not exist yet — `CallAudioSession.onFrame` has no
 * caller either — so the whole lane is decisions with no socket under them."* This is the
 * socket. It does four things and nothing else:
 *
 *  1. seals PCM with the EXISTING [CallMediaFrame] codec and sends it;
 *  2. demuxes everything that arrives ([MediaDemux]) and opens the media;
 *  3. sends and answers [HolePunch] pings, stamped with this call's derived id;
 *  4. asks STUN for its own public mapping **on this very socket**.
 *
 * It does NOT know what a call is. No state machine, no signalling, no offer parsing, no
 * audio device — that is the seam: [com.oshi.desktop.call.CallLane] hands it a key, a
 * salt, a role and a callId, and takes candidates and audio back out. The split is the
 * same one [com.oshi.desktop.call.media.CallAudioSession] makes and for the same reason:
 * every decision in here can be tested over loopback with no microphone and no peer.
 *
 * ============================================================ ONE SOCKET, FOUR PROTOCOLS
 *
 * Audio, hole-punch pings, STUN responses and TURN ChannelData all arrive here. That is
 * not a design choice, it is a NAT requirement: a mapping belongs to a five-tuple, so the
 * srflx address worth advertising is the one discovered on the socket the audio leaves
 * from, and a pong that arrives on a different socket proves nothing about the path the
 * audio takes. iOS shipped the other version first and left the post-mortem in the source
 * — see [StunBinding.discoverOn]. [requestSrflx] is the fixed version, and Android still
 * has the bug (`StunClient.kt:55,71` opens and closes its own socket).
 *
 * ============================================================ THE DIRECTION CONVENTION
 *
 * The convention comes from the shipped source, not from inference
 * (`VoiceCallManager.swift:3148-3156`):
 *
 *   > `/// Assign the per-direction salts once the shared salt is known.`
 *   > `/// isInitiator = we placed the call (we generated the salt).`
 *   > `txNonceSalt = isInitiator ? base : responder`
 *   > `rxNonceSalt = isInitiator ? responder : base`
 *
 * with `responderNonceSalt` being the base salt with byte 0 XOR `0xA5`
 * (`:3141-3146`). So: **the CALLER — the side that minted the salt inside the offer —
 * transmits under the BASE salt; the CALLEE transmits under the flipped one.**
 * [CallMediaFrame.txSalt] is that rule; this class passes [isCaller] straight through.
 *
 * **What happens when it is backwards is worse than what this package's own codec file
 * says, and the difference matters.** [CallMediaFrame]'s header claims *"Get it
 * backwards and the call is silent in both directions with no error — the peer's AEAD
 * simply never verifies."* That is not what either shipped client does. Receiving never
 * uses a salt at all: iOS's `decryptAudioData` takes `let nonceData = data.prefix(12)`
 * straight off the wire and opens the box with it (`:13202-13226`), and `rxNonceSalt` is
 * marked *"Diagnostics only — decrypt reads the wire"* (`:3136`). The same file's own
 * [CallMediaFrame.decode] doc says so too, in the opposite direction from its header.
 *
 * So a desktop with [isCaller] inverted produces a call that **works perfectly**: audio
 * flows both ways, nothing logs, nobody notices — and both ends transmit under the SAME
 * salt with counters that both start at 1, so the two directions emit **bit-identical
 * nonces under one AES-256-GCM key**. That is nonce reuse in GCM: an XOR of the two
 * plaintexts falls out of the ciphertexts, and the authentication subkey is recoverable
 * from a forbidden-attack pair. It is the exact failure `responderNonceSalt` was added
 * to fix, reintroduced with no symptom at all. Silence would have been the lucky
 * outcome; `MediaSocketTest` pins the real one.
 *
 * ============================================================ ONE COUNTER, OR NONE
 *
 * A GCM nonce here is `salt(4) ‖ counter(8)` under a key that never changes for the life
 * of the call. **Two independent counters under one key and one salt is a total break of
 * the cipher**, not a glitch — it is the same failure the directional salt exists to
 * prevent, arrived at from the other side. So this class refuses to be half-used:
 * [sendPcm] owns a [MediaSequence], [sendSealed] takes frames somebody else sealed (a
 * [com.oshi.desktop.call.media.CallAudioSession], which owns its own), and calling BOTH
 * on one socket throws on the second one rather than emitting the duplicate nonce.
 *
 * ============================================================ TURN LIVES BESIDE THIS SOCKET
 *
 * TURN runs on its OWN socket ([TurnClient]), as on both phones, and reaches this class
 * through [relay] and [handleRelayed] — PARITY.md row 2.1-t. Both phones use Allocate +
 * Refresh + **ChannelBind + ChannelData** with long-term auth and no CreatePermission
 * (`OSHI/TurnClient.swift:16`, `TurnClient.kt:41`). ChannelData can therefore never
 * legitimately arrive HERE, so the `0x40..0x7F` range is still recognised and dropped with
 * its own reason ([DropReason.TURN_CHANNEL_DATA_UNSUPPORTED]) rather than being handed to
 * the media codec, where it would fail to authenticate and be counted as broken audio.
 */
class MediaSocket(
    private val socket: DatagramSocket,
    private val sessionKey: ByteArray,
    private val baseSalt: ByteArray,
    /** True when WE sent the offer and minted the salt. See THE DIRECTION CONVENTION. */
    private val isCaller: Boolean,
    /** `SHA-256(callId)[0..8]` — [HolePunch.deriveP2PCallId]. Stamped into every ping. */
    private val p2pCallId: Long,
    private val listener: Listener = Listener.NOOP,
) : AutoCloseable {

    init {
        require(sessionKey.size == CallOffer.SESSION_KEY_SIZE) {
            "session key must be ${CallOffer.SESSION_KEY_SIZE} bytes"
        }
        require(baseSalt.size == CallOffer.NONCE_SALT_SIZE) {
            "nonce salt must be ${CallOffer.NONCE_SALT_SIZE} bytes"
        }
    }

    /** Everything this socket can tell a caller. Every method has a no-op default. */
    interface Listener {
        /** A frame that authenticated, was fresh, and is raw PCM this client can play. */
        fun onAudio(frame: CallMediaFrame.Decoded, from: InetSocketAddress) {}

        /**
         * A media datagram, BEFORE it is opened. Return true to take ownership — the
         * socket then does not decode it and does not run it past the replay window.
         *
         * This is the hook for wiring [com.oshi.desktop.call.media.CallAudioSession],
         * whose `onFrame` already owns a codec and a [ReplayWindow]: forwarding the raw
         * bytes there and returning true keeps exactly one replay window in the call
         * instead of two that disagree.
         */
        fun onSealedMedia(sealed: ByteArray, from: InetSocketAddress): Boolean = false

        /** STUN told us our public mapping. This is the srflx candidate to advertise. */
        fun onReflexiveAddress(mapped: StunBinding.Mapped) {}

        /** A ping arrived and was answered. [learned] is non-null for a NEW peer-reflexive source. */
        fun onPing(from: InetSocketAddress, learned: IceCandidate?) {}

        /** A pong correlated to a probe we sent. */
        fun onPong(pair: IceCandidate, rttMs: Long) {}

        /** Anything discarded, with the reason. Count these; silence has causes. */
        fun onDropped(reason: DropReason, from: InetSocketAddress?) {}

        /** A frame that did not authenticate, replayed, or used a codec we cannot decode. */
        fun onMediaRejected(reason: MediaRejection, from: InetSocketAddress) {}

        companion object {
            val NOOP = object : Listener {}
        }
    }

    /** Why an authenticated-looking media datagram was not played. */
    enum class MediaRejection {
        /** AEAD verification failed, or the frame was shorter than a sealed frame can be. */
        NOT_AUTHENTIC,

        /** The counter is outside [ReplayWindow], or has already been seen. */
        REPLAYED,

        /**
         * Authenticated, fresh, and a codec the JVM has no decoder for — `0x05` AAC-ELD
         * or `0x16` OshiCodec. Dropping is correct; playing compressed bytes as PCM is
         * white noise. See [CallMediaFrame]'s THE CODEC THIS CLIENT PICKS.
         */
        UNDECODABLE_CODEC,
    }

    private val running = AtomicBoolean(false)
    private val sequence = MediaSequence()
    private val replay = ReplayWindow()
    private val pairs = IcePairTable()

    /** Transaction ids of STUN requests we sent from THIS socket. Bounded — see [requestSrflx]. */
    private val stunTxIds = LinkedHashSet<String>()

    private var receiveThread: Thread? = null

    /** Guards ONE COUNTER, OR NONE. Null until the first send picks a mode. */
    @Volatile
    private var sendMode: SendMode? = null

    private enum class SendMode { SEALS_HERE, PRE_SEALED }

    @Volatile
    private var selectedKey: String? = null

    @Volatile
    private var peerAddress: InetSocketAddress? = null

    /** The selected pair's candidate — a RELAY one is reached through [relay], not [socket]. */
    @Volatile
    private var peerCandidate: IceCandidate? = null

    /**
     * How a RELAY-type remote candidate is reached: through OUR TURN allocation. PARITY.md
     * row 2.1-t. Both phones route exactly this way — `sendToPair` sends via the TURN
     * channel iff the REMOTE candidate is `.relay` (`P2PTransport.swift:1370-1392`,
     * `P2PTransport.kt:1146-1158`) — so a relay pair is always relay↔relay through the
     * one coturn, which works whatever NAT sits on either side. Null = no allocation;
     * relay candidates are then skipped, as the phones skip them with no TURN client.
     */
    @Volatile
    var relay: RelayRouter? = null

    /**
     * Relay-only: probe and send on RELAY pairs only. The phones' `forceRelay`
     * ("Always use relay", IP privacy) — and how a test proves the relay leg carries a
     * call on its own.
     */
    @Volatile
    var relayOnly: Boolean = false

    /** Reaches a remote RELAY candidate through our own TURN allocation. */
    fun interface RelayRouter {
        fun send(data: ByteArray, remote: IceCandidate): Boolean
    }

    val relayPingsSent = AtomicLong()

    // Counters. Cheap, and the difference between "the call is silent" and a diagnosis.
    val framesSent = AtomicLong()
    val framesPlayed = AtomicLong()
    val framesRejected = AtomicLong()
    val pingsSent = AtomicLong()
    val pingsAnswered = AtomicLong()
    val pongsCorrelated = AtomicLong()
    val packetsDropped = AtomicLong()

    /** The port audio leaves from. What host candidates must advertise. */
    val localPort: Int get() = socket.localPort

    /** The remote this socket is currently sending audio to, or null. */
    val selectedRemote: IceCandidate? get() = selectedKey?.let { key -> pairs.pair(key)?.candidate }

    /** Remote candidates known so far — signalled plus peer-reflexive. */
    fun remoteCandidates(): List<IceCandidate> = pairs.remoteCandidates()

    // ================================================================= local candidates

    /**
     * Host candidates for this socket: every non-loopback, non-link-local interface
     * address paired with [localPort].
     *
     * Both clients gather exactly this set and skip the same addresses — iOS at
     * `P2PTransport.swift:1703` drops loopback and `:1717` drops `fe80:`, Android at `kt:640`
     * drops `isLoopbackAddress || isLinkLocalAddress`. A link-local address is
     * meaningless to a peer that is not on the same segment, and advertising it wastes a
     * probe slot on an address that can never answer.
     */
    fun hostCandidates(): List<IceCandidate> {
        val out = ArrayList<IceCandidate>()
        val ifaces = runCatching { java.net.NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return out
        for (nif in ifaces) {
            if (!runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)) continue
            for (addr in nif.inetAddresses) {
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val ip = addr.hostAddress?.substringBefore('%') ?: continue
                val family = if (addr is java.net.Inet6Address) 6 else 4
                out.add(
                    IceCandidate(
                        IceCandidateType.HOST, ip, localPort,
                        IcePriority.ios(IceCandidateType.HOST, family),
                    ),
                )
            }
        }
        return out
    }

    /**
     * Send a STUN Binding Request from THIS socket. Non-blocking: the reply comes back
     * through the ordinary demux and surfaces as [Listener.onReflexiveAddress].
     *
     * The outstanding transaction id set is bounded at eight, which is iOS's bound and
     * its reason (`P2PTransport.swift:639-641`): a set that grows per retransmit is a
     * memory leak on a lossy path, and eight is more retries than the 2 s gathering
     * window can fit anyway.
     */
    fun requestSrflx(server: InetSocketAddress) {
        val txId = StunBinding.newTransactionId()
        val hex = HolePunch.nonceHex(txId)
        synchronized(stunTxIds) {
            stunTxIds.add(hex)
            while (stunTxIds.size > MAX_OUTSTANDING_STUN) {
                stunTxIds.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            }
        }
        sendRaw(StunBinding.buildBindingRequest(txId), server)
    }

    // ================================================================= remote candidates

    /** Add a candidate the peer signalled in a `0x30` exchange. */
    fun addRemoteCandidate(candidate: IceCandidate): Boolean = pairs.addRemote(candidate)

    /** Add every candidate from a decoded `0x30` payload. */
    fun addRemoteCandidates(candidates: List<IceCandidate>): Int =
        candidates.count { pairs.addRemote(it) }

    // ================================================================= probing

    /**
     * One probe round: a fresh ping to every remote candidate, and a loss recorded for
     * every probe that has been outstanding longer than
     * [IcePairTable.PROBE_INFLIGHT_TIMEOUT_MS].
     *
     * The caller drives the clock. Both phones run this at 2 Hz
     * ([IcePairTable.PROBE_INTERVAL_MS]) on every candidate rather than only the active
     * one, so a path that rots mid-call is noticed in a probe interval instead of after
     * ten seconds of silence — but the TIMER is the integration's, not this class's,
     * because a socket that owns a thread that owns a clock cannot be tested without one.
     */
    fun probeTick(nowMs: Long = System.currentTimeMillis()) {
        pairs.expireProbes(nowMs)
        for (cand in pairs.remoteCandidates()) {
            val isRelay = cand.type == IceCandidateType.RELAY
            if (relayOnly && !isRelay) continue
            val router = relay
            if (isRelay && router == null) continue
            val nonce = HolePunch.newNonce()
            val ping = HolePunch.buildPing(p2pCallId, nonce, nowMs)
            if (isRelay) {
                pairs.noteProbeSent(HolePunch.nonceHex(nonce), cand.key, nowMs)
                if (router!!.send(ping, cand)) { pingsSent.incrementAndGet(); relayPingsSent.incrementAndGet() }
                continue
            }
            val target = addressOf(cand) ?: continue
            pairs.noteProbeSent(HolePunch.nonceHex(nonce), cand.key, nowMs)
            if (sendRaw(ping, target)) pingsSent.incrementAndGet()
        }
    }

    /**
     * Recompute which pair carries audio. Returns the selection, or null when no pair
     * has answered inside [IcePairTable.LIVENESS_MAX_AGE_MS].
     *
     * Null is a real answer and the caller must treat it as one: no pair — direct or
     * TURN — has answered; the `:8089` relay in [com.oshi.desktop.call.CallMediaLeg] is the
     * only carrier left.
     */
    fun updateSelection(nowMs: Long = System.currentTimeMillis()): IceCandidate? {
        val best = pairs.best(nowMs, selectedKey)
            ?.takeUnless { relayOnly && it.type != IceCandidateType.RELAY }
        selectedKey = best?.key
        peerCandidate = best
        peerAddress = best?.let { addressOf(it) }
        return best
    }

    // ================================================================= sending

    /**
     * Seal one 20 ms PCM frame and send it to the selected pair.
     *
     * Returns false when there is no selected pair — which is not an error, it is the
     * honest state of a call whose hole punch has not landed yet, and it must not be
     * reported as sent.
     *
     * @throws IllegalStateException if [sendSealed] has already been used on this socket.
     *   See ONE COUNTER, OR NONE.
     */
    fun sendPcm(pcm: ByteArray, audioType: Int = CallMediaFrame.TYPE_PCM_48K): Boolean {
        claimSendMode(SendMode.SEALS_HERE)
        if (peerCandidate == null) return false
        val frame = CallMediaFrame.encode(
            sessionKey, baseSalt, isCaller, sequence.next(), audioType, pcm,
        )
        return sendToSelected(frame).also { if (it) framesSent.incrementAndGet() }
    }

    /**
     * Send a frame somebody else already sealed — the output of
     * [com.oshi.desktop.call.media.CallAudioSession]'s `send` lambda.
     *
     * @throws IllegalStateException if [sendPcm] has already been used on this socket.
     */
    fun sendSealed(frame: ByteArray): Boolean {
        claimSendMode(SendMode.PRE_SEALED)
        return sendToSelected(frame).also { if (it) framesSent.incrementAndGet() }
    }

    /** Direct to the selected pair, or through our TURN allocation when it is a RELAY pair. */
    private fun sendToSelected(frame: ByteArray): Boolean {
        val cand = peerCandidate ?: return false
        if (cand.type == IceCandidateType.RELAY) return relay?.send(frame, cand) ?: false
        val target = peerAddress ?: return false
        return sendRaw(frame, target)
    }

    private fun claimSendMode(mode: SendMode) {
        val current = sendMode
        if (current == null) {
            sendMode = mode
            return
        }
        check(current == mode) {
            "this socket already sends ${current.name} frames; mixing sendPcm() and " +
                "sendSealed() means two GCM counters under one key and one salt, which " +
                "repeats a nonce and breaks the cipher outright"
        }
    }

    /** Raw datagram out. Used for pings, pongs and STUN. False on any IO failure. */
    fun sendRaw(bytes: ByteArray, to: InetSocketAddress): Boolean =
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, to)); true }.getOrDefault(false)

    // ================================================================= receiving

    /** Start the receive loop. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        receiveThread = Thread({ receiveLoop() }, "oshi-media-socket").apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(RECEIVE_BUFFER_SIZE)
        while (running.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (_: Exception) {
                if (!running.get()) return
                continue
            }
            val from = InetSocketAddress(pkt.address, pkt.port)
            runCatching { handle(buf, pkt.length, from) }
        }
    }

    /**
     * Handle one datagram. Public and synchronous so a test can drive it without a thread.
     */
    fun handle(data: ByteArray, length: Int, from: InetSocketAddress) {
        val classified = MediaDemux.classify(data, length, p2pCallId) { hex ->
            synchronized(stunTxIds) { stunTxIds.contains(hex) }
        }
        when (classified) {
            is InboundPacket.StunResponse -> {
                synchronized(stunTxIds) { stunTxIds.remove(classified.txIdHex) }
                classified.mapped?.let { listener.onReflexiveAddress(it) }
            }

            is InboundPacket.Ping -> {
                // Always answered. A ping we do not answer is a pair the peer cannot
                // promote, and the peer is the one that decides where ITS audio goes.
                sendRaw(classified.pong, from)
                pingsAnswered.incrementAndGet()
                val learned = from.address?.hostAddress?.substringBefore('%')?.let {
                    pairs.learnPeerReflexive(it, from.port)
                }
                listener.onPing(from, learned)
            }

            is InboundPacket.Pong -> {
                val rtt = pairs.recordPong(classified.nonceHex, System.currentTimeMillis())
                if (rtt != null) {
                    pongsCorrelated.incrementAndGet()
                    val key = "${from.address?.hostAddress?.substringBefore('%')}:${from.port}"
                    val cand = pairs.pair(key)?.candidate
                    if (cand != null) listener.onPong(cand, rtt)
                }
            }

            is InboundPacket.Media -> handleMedia(classified.bytes, from)

            is InboundPacket.Dropped -> {
                packetsDropped.incrementAndGet()
                listener.onDropped(classified.reason, from)
            }
        }
    }

    /**
     * One datagram that arrived through OUR TURN allocation, from [fromIp]:[fromPort] —
     * the peer's relayed address. Same demux as [handle], with two differences the
     * phones share (`P2PTransport.swift:1087-1116`, `P2PTransport.kt:1117-1140`):
     *
     *  - a ping is answered BACK THROUGH THE RELAY ([reply]), never from the media
     *    socket, because the peer is pinging our RELAY candidate and must see the pong
     *    come from it;
     *  - the source is NOT learned as a peer-reflexive candidate: it is the peer's relay
     *    address, reachable only through TURN, and learning it as a direct pair would
     *    send media from the media socket to a coturn port that has no permission for it.
     */
    fun handleRelayed(data: ByteArray, fromIp: String, fromPort: Int, reply: (ByteArray) -> Unit) {
        if (!running.get() || data.isEmpty()) return
        val from = runCatching {
            InetSocketAddress(InetAddress.getByAddress(IceCandidateCodec.parseIpLiteral(fromIp) ?: return), fromPort)
        }.getOrNull() ?: return
        when (val classified = MediaDemux.classify(data, data.size, p2pCallId) { false }) {
            is InboundPacket.Ping -> {
                reply(classified.pong)
                pingsAnswered.incrementAndGet()
                listener.onPing(from, null)
            }
            is InboundPacket.Pong -> {
                val rtt = pairs.recordPong(classified.nonceHex, System.currentTimeMillis())
                if (rtt != null) {
                    pongsCorrelated.incrementAndGet()
                    pairs.pair("$fromIp:$fromPort")?.candidate?.let { listener.onPong(it, rtt) }
                }
            }
            is InboundPacket.Media -> handleMedia(classified.bytes, from)
            is InboundPacket.StunResponse -> Unit
            is InboundPacket.Dropped -> {
                packetsDropped.incrementAndGet()
                listener.onDropped(classified.reason, from)
            }
        }
    }

    private fun handleMedia(sealed: ByteArray, from: InetSocketAddress) {
        if (listener.onSealedMedia(sealed, from)) return
        val decoded = CallMediaFrame.decode(sessionKey, sealed)
        if (decoded == null) {
            framesRejected.incrementAndGet()
            listener.onMediaRejected(MediaRejection.NOT_AUTHENTIC, from)
            return
        }
        if (!replay.accept(decoded.seq)) {
            framesRejected.incrementAndGet()
            listener.onMediaRejected(MediaRejection.REPLAYED, from)
            return
        }
        if (decoded.audioType != CallMediaFrame.TYPE_PCM_48K) {
            framesRejected.incrementAndGet()
            listener.onMediaRejected(MediaRejection.UNDECODABLE_CODEC, from)
            return
        }
        framesPlayed.incrementAndGet()
        listener.onAudio(decoded, from)
    }

    // ================================================================= teardown

    /**
     * Stop and release. Idempotent, and closes the socket BEFORE joining so the blocked
     * `receive()` returns instead of holding the thread for its full timeout.
     */
    override fun close() {
        running.set(false)
        runCatching { socket.close() }
        receiveThread?.let { t -> runCatching { t.join(CLOSE_JOIN_MS) } }
        receiveThread = null
        pairs.clear()
        selectedKey = null
        peerAddress = null
        peerCandidate = null
        relay = null
        synchronized(stunTxIds) { stunTxIds.clear() }
    }

    /** Literal only — [IceCandidateCodec.parseIpLiteral] never touches the resolver. */
    private fun addressOf(c: IceCandidate): InetSocketAddress? {
        val bytes = IceCandidateCodec.parseIpLiteral(c.ip) ?: return null
        return runCatching { InetSocketAddress(InetAddress.getByAddress(bytes), c.port) }.getOrNull()
    }

    companion object {
        /** `kt:92 RECV_BUFFER_SIZE`. A 48 kHz PCM frame seals to 1 957 bytes. */
        const val RECEIVE_BUFFER_SIZE = 2048

        /** iOS bounds its outstanding STUN transaction set at 8 (`swift:640`). */
        const val MAX_OUTSTANDING_STUN = 8

        private const val CLOSE_JOIN_MS = 500L
    }
}
