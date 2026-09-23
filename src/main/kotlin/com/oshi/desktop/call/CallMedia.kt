package com.oshi.desktop.call

import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.media.InBandCallEnd
import com.oshi.desktop.call.media.ReplayWindow
import com.oshi.desktop.call.transport.HolePunch
import com.oshi.desktop.call.transport.IceCandidate
import com.oshi.desktop.call.transport.IceCandidateType
import com.oshi.desktop.call.transport.IcePriority
import com.oshi.desktop.call.transport.MediaSocket
import com.oshi.desktop.call.transport.RelayToken
import com.oshi.desktop.call.transport.RelayTokenSource
import com.oshi.desktop.call.transport.StunBinding
import com.oshi.desktop.call.transport.TlsLink
import com.oshi.desktop.call.transport.TurnClient
import com.oshi.desktop.call.transport.TurnLink
import com.oshi.desktop.call.transport.UdpLink
import com.oshi.desktop.call.transport.WsRelayClient
import com.oshi.desktop.call.transport.TurnCredentials
import com.oshi.desktop.call.transport.UdpRelayClient
import com.oshi.desktop.call.video.CallVideoSession
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.LineUnavailableException

/**
 * The joint — the one place where a call's socket and a call's microphone are tied
 * together. PARITY.md row 2.1's missing half of the missing half.
 *
 * ============================================================ WHAT WAS ACTUALLY MISSING
 *
 * Before this file, `com/oshi/desktop/call/` contained a complete UDP media socket
 * ([MediaSocket]), a complete audio device session ([CallAudioSession]), a complete
 * AEAD frame codec, a complete ICE candidate codec and a complete hole-punch — and
 * **nothing that owned any two of them at the same time.** `CallAudioSession.onFrame`
 * had no caller. `MediaSocket.sendSealed` had no caller. `CallLane`'s `StartMedia`
 * branch dropped the session key and the nonce salt on the floor and logged
 * [CallLane.NO_AUDIO_WILL_FLOW].
 *
 * A [CallMediaLeg] is that missing object and nothing more. It owns exactly four wires:
 *
 *  1. the audio session's `send` lambda → [MediaSocket.sendSealed];
 *  2. [MediaSocket.Listener.onSealedMedia] → the audio session's `onFrame`;
 *  3. [MediaSocket.hostCandidates] and the STUN srflx reply → [onLocalCandidates],
 *     which [CallLane] turns into an `ice_candidate` signal;
 *  4. [close] → both halves released, in the order that actually frees the hardware.
 *
 * ============================================================ ONE REPLAY WINDOW, NOT TWO
 *
 * Wire 2 is the one with a trap in it, and [MediaSocket.Listener.onSealedMedia]'s own
 * doc names it: both [MediaSocket] and [CallAudioSession] own a
 * [com.oshi.desktop.call.media.ReplayWindow]. Letting the socket decode and THEN handing
 * the plaintext on would run each frame past two windows that disagree about which
 * counters are fresh; the second one would reject nothing it had not already seen, but
 * the two would drift the moment a frame was dropped between them, and a call whose
 * freshness rule is "whichever window happened to see it" has no freshness rule at all.
 *
 * So this leg takes ownership: `onSealedMedia` returns **true**, the socket does not
 * decode, and the audio session's window is the only one in the call. The socket's
 * `framesPlayed` therefore stays at zero for an audio leg and [framesAccepted] is the
 * counter that means anything — a difference worth knowing before reading a diagnostic.
 *
 * ============================================================ A LEG WITHOUT A DEVICE
 *
 * `audioFor` may be null, and that is not a convenience: it is how every test in this
 * package exercises the transport end to end without touching the shared microphone on
 * the machine that runs it. With no audio session, `onSealedMedia` returns false and the
 * socket decodes and counts as it always did, so a transport-only leg is a genuinely
 * different object rather than a crippled one.
 *
 * **A transport-only leg must never be handed to [CallLane] in production.** It would
 * connect a call, punch a hole, elect a pair and carry nothing — the exact silent
 * connected call the whole of [CallAudioSession]'s TEARDOWN section and requirement (d)
 * of this work exist to prevent. [CallMedia.real] is the only opener that belongs in an
 * app, and it refuses to build a leg at all when [CallAudio.isAvailable] is false.
 *
 * ============================================================ NO TIMER LIVES HERE
 *
 * [tick] takes `nowMs` and is driven from outside, for the reason [MediaSocket.probeTick]
 * gives: a socket that owns a thread that owns a clock cannot be tested without one.
 * [CallLane] owns the 2 Hz timer, and a test drives [CallLane.mediaTick] by hand.
 *
 * ============================================================ WHAT IS NOT VERIFIED HERE
 *
 * Stated because this file is the one a reader will believe:
 *
 *  - **No microphone has ever moved a sample through this class.** `CallMediaLegTest`
 *    and `CallMediaLaneTest` run transport-only legs on `127.0.0.1`; CI has no audio
 *    hardware and this machine's device is shared with other sessions.
 *  - **No NAT has ever been traversed by it.** Loopback has no mapping to open, so the
 *    hole punch is exercised as a protocol and not as a traversal. Behind a symmetric
 *    NAT on both ends the path is the TURN relay or the `:8089` relay (row 2.1-t).
 *  - **No packet from this class has reached a phone**, and no real STUN server has
 *    answered one: the srflx path is exercised against hand-built response bytes in
 *    `MediaSocketTest`.
 *  - **No call has been made between two people.** "Audio crosses loopback in a test" is
 *    the claim this file supports, and it is not the same sentence.
 */
class CallMediaLeg internal constructor(
    val spec: CallMediaSpec,
    datagram: DatagramSocket,
    /**
     * Builds the audio session around the `send` lambda this leg supplies. Null means a
     * transport-only leg — see A LEG WITHOUT A DEVICE.
     */
    audioFor: ((send: (ByteArray) -> Unit) -> CallAudioSession)?,
    /** Where to ask for our public mapping, or null to gather host candidates only. */
    private val stunServer: InetSocketAddress?,
    private val log: (String) -> Unit = {},
    /**
     * Where local host candidates come from.
     *
     * Injectable for one reason, and it is not mocking: [MediaSocket.hostCandidates]
     * deliberately SKIPS `127.0.0.1` because both shipped clients do — a loopback address
     * is meaningless to a peer and wastes a probe slot. A loopback test therefore gathers
     * NOTHING from the real gatherer, and a CI box with no network gathers nothing either,
     * so the candidate-exchange path would go untested on exactly the two machines that
     * ever run these tests. The production default is the real one.
     */
    private val hostCandidates: (MediaSocket) -> List<IceCandidate> = { it.hostCandidates() },
    /**
     * PARITY.md row 2.1-v. Builds the call's video half from the socket's send lambda and
     * the audio session's counter. Null = a leg with no video at all (deviceless tests).
     */
    private val videoFor: ((send: (ByteArray) -> Boolean, nextAudioSeq: (() -> Long)?) -> CallVideoSession)? = null,
    /**
     * PARITY.md row 2.1-t. The phones' fallback ladder under the direct pairs: a TURN
     * relay candidate (probed like any other pair) and the `:8089` UDP relay carrier.
     * Null = direct pairs only, which is what every loopback test wants.
     */
    private val relayConfig: CallRelayConfig? = null,
) : AutoCloseable {

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * When [start] ran, or 0 before it did. The clock the media-path watchdog measures
     * from — see [CallLane.MEDIA_PATH_TIMEOUT_MS].
     */
    @Volatile
    var startedAtMs: Long = 0L
        private set

    /**
     * Sealed frames handed to the audio session that it ACCEPTED — authenticated, fresh,
     * and a codec it can play. This is the "audio is flowing" number for an audio leg.
     */
    val framesAccepted = AtomicLong()

    /**
     * Sealed frames the audio session refused: forged, replayed, or an undecodable codec.
     * Counted separately from silence because those are three different diagnoses and
     * only one of them is a network problem.
     */
    val framesRefused = AtomicLong()

    /** How many times [onLocalCandidates] fired. Bounded by [MAX_CANDIDATE_PUBLISHES]. */
    val candidatePublishes = AtomicLong()

    /** ip:port → candidate. Insertion-ordered so a signalled set is stable to read. */
    private val localSet = LinkedHashMap<String, IceCandidate>()

    @Volatile
    private var audioSession: CallAudioSession? = null

    /**
     * The pair audio is currently leaving on, or null while the punch has not landed.
     * Null is a real answer: no direct or TURN pair is live; [sendMedia] falls to `:8089`.
     */
    @Volatile
    var selected: IceCandidate? = null
        private set

    val socket: MediaSocket = MediaSocket(
        datagram,
        spec.sessionKey,
        spec.nonceSalt,
        spec.isCaller,
        HolePunch.deriveP2PCallId(spec.callId),
        object : MediaSocket.Listener {

            override fun onSealedMedia(sealed: ByteArray, from: InetSocketAddress): Boolean {
                lastP2pRxMs = System.currentTimeMillis()
                return deliver(sealed)
            }

            override fun onReflexiveAddress(mapped: StunBinding.Mapped) {
                // The srflx is the one candidate worth more than a host address to a peer
                // on another network, and it is discovered on THIS socket for the reason
                // MediaSocket's ONE SOCKET, FOUR PROTOCOLS section gives: a mapping
                // belongs to a five-tuple.
                val family = if (mapped.ip.contains(':')) 6 else 4
                addLocal(
                    IceCandidate(
                        IceCandidateType.SRFLX, mapped.ip, mapped.port,
                        IcePriority.ios(IceCandidateType.SRFLX, family),
                    ),
                )
                publish()
            }
        },
    )

    @Volatile
    private var videoSession: CallVideoSession? = null

    // ------------------------------------------------------------ relay ladder (row 2.1-t)

    @Volatile private var turn: TurnClient? = null
    @Volatile private var udpRelay: UdpRelayClient? = null
    @Volatile private var wsRelay: WsRelayClient? = null

    /** "udp" or "tls" once a TURN allocation succeeded. */
    @Volatile var turnTransport: String? = null
        private set

    /** Media handed to the WebSocket relay. */
    val wsSent = AtomicLong()

    /** When P2P (direct or TURN) last delivered media, and when a pair was first selected. */
    @Volatile private var lastP2pRxMs = 0L
    @Volatile private var selectedSinceMs = 0L

    /** Media handed to the :8089 relay / received from it. */
    val relaySent = AtomicLong()
    val relayReceived = AtomicLong()

    /** The TURN allocation this leg advertises, or null. */
    val turnAllocation: TurnClient.Allocation? get() = turn?.allocation

    /** Test seam: sees every sealed packet delivered, from any carrier, before the halves do. */
    @Volatile internal var tap: ((ByteArray) -> Unit)? = null

    /** The UDP relay client, for tests and diagnostics. */
    internal val udpRelayClient: UdpRelayClient? get() = udpRelay

    /** The WebSocket relay client, for tests and diagnostics. */
    internal val wsRelayClient: WsRelayClient? get() = wsRelay

    /** This call's relay token (contract §2), once [startRelays] ran with a fetcher. */
    @Volatile internal var relayTokens: RelayTokenSource? = null
        private set

    init {
        socket.relayOnly = relayConfig?.relayOnly == true
        audioSession = audioFor?.invoke { sealed -> sendMedia(sealed) }
        val a = audioSession
        videoSession = videoFor?.invoke({ bytes -> sendMedia(bytes) }, a?.let { { it.nextSequence() } })
    }

    /**
     * Hand one authenticated-or-not sealed packet to the video or audio half, whichever
     * transport carried it. See the listener's comment for the order.
     */
    private fun deliver(sealed: ByteArray): Boolean {
        tap?.invoke(sealed)
        // The phones' in-band hang-up (sealed `0x0D`) before anything else: it is neither
        // video control (whose `0x0D` is the nine-byte cleartext toggle, never this size)
        // nor audio, and the audio session would count it as a refused frame and drop it.
        if (InBandCallEnd.looksLike(sealed)) {
            onInBandEnd(sealed)
            return true
        }
        // Video and video control first: `0xF1`, the 9-byte cleartext `0x0B/0x0C/0x0D`
        // and the sealed `0x0E/0x0F`. None of them is audio, and handing `0x0E` to the
        // audio session would burn its sequence number in the audio replay window.
        if (videoSession?.onMedia(sealed) == true) return true
        val audio = audioSession ?: return false
        if (audio.onFrame(sealed)) framesAccepted.incrementAndGet()
        else framesRefused.incrementAndGet()
        return true
    }

    private fun onInBandEnd(sealed: ByteArray) {
        val opened = InBandCallEnd.decode(spec.sessionKey, sealed)
        // Our OWN hang-up reflected back at us opens under the same key; the direction bit
        // in the salt is what tells it apart (see CallMediaFrame.txSalt).
        val reflected = opened != null &&
            opened.nonceSalt.contentEquals(CallMediaFrame.txSalt(spec.nonceSalt, spec.isCaller))
        if (opened == null || reflected || !inBandReplay.accept(opened.seq)) {
            inBandEndsRefused.incrementAndGet()
            return
        }
        if (closed.get() || !inBandFired.compareAndSet(false, true)) return
        inBandEndsAccepted.incrementAndGet()
        log("call: ${spec.callId} the peer hung up in-band (${opened.reason.wire})")
        val cb = onInBandCallEnd
        Thread({ runCatching { cb(opened.reason) } }, "oshi-call-inband-end").apply { isDaemon = true; start() }
    }

    /**
     * Tell the peer we hung up ON THE MEDIA PATH, as both phones do on every local hang-up
     * (iOS `sendInBandCallEnd`, Android `sendInBandCallEnd`): [InBandCallEnd.BURST] copies,
     * each sealed with a fresh counter from the shared audio sequence, over every carrier
     * that is up — the selected pair, `:8089` when registered, and the WebSocket relay when
     * P2P is not the live carrier. Must run BEFORE the leg is closed.
     *
     * @return how many copies left on at least one carrier.
     */
    fun sendInBandCallEnd(reason: CallEndReason): Int {
        if (closed.get()) return 0
        val audio = audioSession ?: return 0
        val now = System.currentTimeMillis()
        var sent = 0
        repeat(InBandCallEnd.BURST) {
            val packet = runCatching {
                InBandCallEnd.encode(spec.sessionKey, spec.nonceSalt, spec.isCaller, audio.nextSequence(), reason)
            }.getOrNull() ?: return sent
            var any = false
            if (selected != null && runCatching { socket.sendSealed(packet) }.getOrDefault(false)) any = true
            val relay = udpRelay
            if (relay != null && relay.usable(now) && runCatching { relay.send(packet) }.getOrDefault(false)) any = true
            val ws = wsRelay
            if (!p2pHealthy(now) && ws != null && ws.usable(now) && runCatching { ws.send(packet) }.getOrDefault(false)) any = true
            if (any) sent++
        }
        log("call: ${spec.callId} in-band hang-up sent ($sent/${InBandCallEnd.BURST}, ${reason.wire})")
        return sent
    }

    /**
     * THE CARRIER LADDER, the phones' order (`VoiceCallManager.swift` sendAudioPacket,
     * `EnhancedCallManager.kt` `AudioTxPrimary { P2P, UDP_RELAY, WS }`):
     *
     *  1. **P2P** — the selected pair, direct or through our TURN allocation — whenever
     *     one is selected.
     *  2. **The :8089 relay** when P2P is not healthy: no pair selected, or a pair
     *     selected for more than [P2P_GRACE_MS] that has not delivered media for
     *     [P2P_STALE_MS] (Android's `p2pCanCarryAudio`, the "pongs fine, black-holes media"
     *     fix). Both may carry the same packet during a hand-over; the receiver's replay
     *     windows drop the duplicate, which is why the phones mirror too.
     *  3. **The WebSocket relay** when P2P is not healthy and `:8089` is not usable (no
     *     register ack inside [UdpRelayClient.STALE_MS] — every UDP datagram to the server
     *     blocked). The server bridges carriers, so the peer receives on whichever one IT
     *     registered — `:8089` first, its WebSocket otherwise.
     */
    fun sendMedia(sealed: ByteArray): Boolean {
        val now = System.currentTimeMillis()
        val sel = selected
        var sent = false
        if (sel != null) sent = socket.sendSealed(sealed)
        if (p2pHealthy(now)) return sent
        val relay = udpRelay
        if (relay != null && relay.usable(now)) {
            if (relay.send(sealed)) { relaySent.incrementAndGet(); sent = true }
            return sent
        }
        val ws = wsRelay
        if (ws != null && ws.usable(now)) {
            if (ws.send(sealed)) { wsSent.incrementAndGet(); sent = true }
        }
        return sent
    }

    private fun p2pHealthy(now: Long): Boolean {
        if (selected == null) return false
        if (now - selectedSinceMs < P2P_GRACE_MS) return true
        return lastP2pRxMs > 0 && now - lastP2pRxMs < P2P_STALE_MS
    }

    /**
     * True while the `:8089` relay is delivering media — the media-path watchdog must not
     * end a call whose only live carrier is the relay.
     */
    fun relayCarrying(nowMs: Long = System.currentTimeMillis()): Boolean =
        udpRelay?.delivering(nowMs, RELAY_LIVENESS_MS) == true ||
            wsRelay?.delivering(nowMs, RELAY_LIVENESS_MS) == true

    /** The audio half, or null for a transport-only leg. Internal: tests assert on it. */
    internal val audio: CallAudioSession? get() = audioSession

    /** This call's video half, or null when the leg was built without one. */
    val video: CallVideoSession? get() = videoSession

    /**
     * Called with the FULL local candidate set every time it grows — once for the host
     * addresses and once more when STUN answers. [CallLane] turns each call into one
     * `ice_candidate` signal.
     *
     * The full set rather than the delta, because the signal is a single datagram either
     * way and a peer that missed the first one still learns everything from the second.
     */
    var onLocalCandidates: (List<IceCandidate>) -> Unit = {}

    /**
     * The peer hung up and said so ON THE MEDIA PATH — the phones' in-band `0x0D`, see
     * [InBandCallEnd]. Fired at most once per leg, on its own thread (the receive thread
     * must not tear down the socket it is reading from). [CallLane] feeds it to the state
     * machine as the peer's `callEnd`, so the same grace windows and the same history row
     * apply as to the signalled one.
     */
    @Volatile
    var onInBandCallEnd: (CallEndReason) -> Unit = {}

    /** In-band hang-ups that opened and were acted on (0 or 1) / refused (forged, replayed, reflected). */
    val inBandEndsAccepted = AtomicLong()
    val inBandEndsRefused = AtomicLong()

    private val inBandReplay = ReplayWindow()
    private val inBandFired = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    /**
     * Open the socket, open the devices, gather, and ask STUN.
     *
     * @throws LineUnavailableException when the microphone or the speaker cannot be
     *   opened. **This must propagate and the call must be ENDED** — see
     *   [CallAudioSession.start]'s doc and [CallLane]'s `StartMedia` branch. A call that
     *   connects without a device is silent with no error, and both people wait.
     *
     * The socket is closed on that path before the exception leaves, so a failed call
     * does not leak a bound UDP port any more than it leaks a lit microphone.
     */
    @Throws(LineUnavailableException::class)
    fun start(nowMs: Long = System.currentTimeMillis()) {
        if (!started.compareAndSet(false, true)) return
        startedAtMs = nowMs
        socket.start()
        try {
            audioSession?.start()
        } catch (t: Throwable) {
            close()
            throw t
        }
        gather()
        startRelays()
        videoSession?.start(spec.video)
    }

    /** Add every candidate the peer signalled. Returns how many were new. */
    fun addRemoteCandidates(candidates: List<IceCandidate>): Int {
        if (closed.get()) return 0
        val n = socket.addRemoteCandidates(candidates)
        // Both phones bind a channel for each remote RELAY candidate as it arrives
        // (`P2PTransport.kt:387-389`), so the first relayed ping is not queued behind it.
        turn?.let { t -> candidates.filter { it.type == IceCandidateType.RELAY }.forEach { t.bindChannel(it.ip, it.port) } }
        return n
    }

    /**
     * Allocate the TURN relay and open the `:8089` carrier, off the calling thread: a
     * credential fetch and an Allocate are two round trips to a server, and the direct
     * pairs must start probing without waiting for them. Allocation is EAGER, as on both
     * phones since the symmetric-NAT fix: the relay candidate is in the peer's hands before
     * the direct punch has had time to fail.
     */
    private fun startRelays() {
        val cfg = relayConfig ?: return
        val self = spec.selfKey
        val peer = spec.peerKey
        // __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ contract §2: one relay token per call, fetched
        // as soon as the leg exists and EVEN IF :8089 is not used — under `enforce` the server
        // relays media (UDP, WS or HTTP) only between two parties holding mutual live tokens.
        // With :8089 the relay client's heartbeat thread fetches it before its first register;
        // without, a one-shot thread does.
        val tokens = spec.relayToken?.let { RelayTokenSource(it) }
        relayTokens = tokens
        if (tokens != null && (cfg.udpRelay == null || self == null || peer == null)) {
            Thread({ if (!closed.get()) tokens.current() }, "oshi-call-relay-token").apply { isDaemon = true; start() }
        }
        if (cfg.udpRelay != null && self != null && peer != null) {
            runCatching {
                UdpRelayClient(self, peer, spec.callId, cfg.udpRelay, log, tokens = tokens).also { r ->
                    r.onPayload = { payload ->
                        if (!closed.get()) {
                            relayReceived.incrementAndGet()
                            deliver(payload)
                        }
                    }
                    udpRelay = r
                    r.start()
                }
            }.onFailure { log("call: ${spec.callId} udp relay did not open — ${it.javaClass.simpleName}") }
        }
        val wsUrl = cfg.webSocketUrl
        if (wsUrl != null && self != null && peer != null) {
            runCatching {
                WsRelayClient(self, peer, spec.callId, wsUrl, spec.wsUpgradeHeaders ?: { emptyMap() }, log).also { w ->
                    w.onPayload = { payload ->
                        if (!closed.get()) {
                            relayReceived.incrementAndGet()
                            deliver(payload)
                        }
                    }
                    wsRelay = w
                    w.start()
                }
            }.onFailure { log("call: ${spec.callId} ws relay did not open — ${it.javaClass.simpleName}") }
        }
        val creds = cfg.turn ?: return
        Thread({
            // Signed GET (contract §9 Desktop note: it used to go out unsigned).
            val c = creds.get(sign = spec.signedGet)
            if (c == null) {
                log("call: ${spec.callId} no TURN credentials — this call has direct pairs only")
                return@Thread
            }
            if (closed.get()) return@Thread
            val server = if (c.server.isUnresolved) InetSocketAddress(c.server.hostString, c.server.port) else c.server
            var client: TurnClient? = null
            var a: TurnClient.Allocation? = null
            for (kind in cfg.turnOrder()) {
                if (closed.get()) return@Thread
                val link: TurnLink = runCatching {
                    if (kind == "tls") TlsLink(cfg.turnTlsHost, cfg.turnTlsPort) else UdpLink(server)
                }.getOrNull() ?: continue
                val attempt = TurnClient(link, c.username, c.password, log)
                attempt.onData = { data, ip, port ->
                    socket.handleRelayed(data, ip, port) { reply -> attempt.send(reply, ip, port) }
                }
                turn = attempt
                val got = attempt.allocate()
                if (got != null) { client = attempt; a = got; turnTransport = kind; break }
                runCatching { attempt.close() }
                turn = null
                log("call: ${spec.callId} TURN over $kind failed")
            }
            if (client == null || a == null || closed.get()) {
                if (client == null) log("call: ${spec.callId} TURN allocation failed on every transport — no relay path")
                return@Thread
            }
            socket.relay = MediaSocket.RelayRouter { data, remote -> client.send(data, remote.ip, remote.port) }
            socket.remoteCandidates().filter { it.type == IceCandidateType.RELAY }.forEach { client.bindChannel(it.ip, it.port) }
            val family = if (a.relayIp.contains(':')) 6 else 4
            addLocal(IceCandidate(IceCandidateType.RELAY, a.relayIp, a.relayPort, IcePriority.ios(IceCandidateType.RELAY, family)))
            publish()
        }, "oshi-call-relay").apply { isDaemon = true; start() }
    }

    /** The local set as signalled. Host addresses, plus the srflx once STUN answers. */
    fun localCandidates(): List<IceCandidate> = synchronized(localSet) { localSet.values.toList() }

    /**
     * One probe round and one re-selection. Driven by [CallLane] at 2 Hz.
     *
     * A transition to null is logged as a LOSS rather than ignored: it is the moment a
     * live call went silent on P2P; [sendMedia] then falls to the `:8089` relay, if one is
     * registered.
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): IceCandidate? {
        if (closed.get() || !started.get()) return null
        socket.probeTick(nowMs)
        val now = socket.updateSelection(nowMs)
        val before = selected
        selected = now
        if (now != null && before?.key != now.key) selectedSinceMs = nowMs
        if (now != null && before == null) {
            log("call: ${spec.callId} media path selected ${now.key} (${now.type})")
        } else if (now == null && before != null) {
            log("call: ${spec.callId} media path LOST — no live pair; audio has stopped")
        }
        return now
    }

    /**
     * Send one already-sealed frame. This is the EXACT lambda the audio session's capture
     * pump is handed, exposed so a loopback test can drive the send path with no
     * microphone — the same seam `MediaSocketTest` uses one level down.
     */
    fun sendSealed(frame: ByteArray): Boolean = socket.sendSealed(frame)

    /**
     * Release the device and the socket. Idempotent, safe from any thread, and safe from
     * inside [start]'s own failure path.
     *
     * **Audio first.** A held ALSA/PulseAudio device blocks the next call from opening it
     * and lights a recording indicator on Windows and GNOME, while a socket closing under
     * a running capture pump costs at most one dropped frame. Both are wrapped, so a
     * throw from either still releases the other.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { videoSession?.close() }
        runCatching { audioSession?.stop() }
        runCatching { socket.close() }
        runCatching { turn?.close() }
        runCatching { udpRelay?.close() }
        runCatching { wsRelay?.close() }
        selected = null
    }

    private fun addLocal(candidate: IceCandidate) {
        synchronized(localSet) { localSet[candidate.key] = candidate }
    }

    private fun gather() {
        val host = runCatching { hostCandidates(socket) }.getOrDefault(emptyList())
        for (c in host) addLocal(c)
        publish()

        val server = stunServer ?: return
        // Non-blocking on purpose: the Binding Response is demuxed off this same socket
        // and arrives at onReflexiveAddress, which publishes a second, larger set. A
        // blocking gather would hold the signalling thread for StunBinding.TIMEOUT_MS on
        // every call placed on a network with no route to the STUN server.
        runCatching { socket.requestSrflx(resolved(server)) }.onFailure {
            log("call: ${spec.callId} srflx request did not leave — ${it.javaClass.simpleName}")
        }
    }

    private fun publish() {
        if (closed.get()) return
        // Relay-only withholds every non-relay candidate from the peer, as iOS's
        // forceRelay does (`P2PTransport.swift:273-282`) — the point of the mode is that
        // the peer never learns our addresses.
        val set = localCandidates().filter { relayConfig?.relayOnly != true || it.type == IceCandidateType.RELAY }
        if (set.isEmpty()) return
        // Bounded because every publish is an HTTP POST to the call server. Two is the
        // expected number (host, then host+srflx); the cap only matters if a future
        // gatherer learns addresses in a loop.
        if (candidatePublishes.get() >= MAX_CANDIDATE_PUBLISHES) return
        candidatePublishes.incrementAndGet()
        runCatching { onLocalCandidates(set) }.onFailure {
            log("call: ${spec.callId} could not signal candidates — ${it.javaClass.simpleName}")
        }
    }

    companion object {
        /**
         * At most this many `ice_candidate` signals per call.
         *
         * Both phones re-signal as they gather and neither bounds it; this one does,
         * because each publish here is an HTTP POST to a rate-limited server and a
         * gatherer that learned one address per interface on a machine with many would
         * otherwise spend the call's signalling budget on candidates.
         */
        const val MAX_CANDIDATE_PUBLISHES = 4L

        /** Android `P2P_GRACE_PERIOD_MS`: a freshly selected pair is trusted this long. */
        const val P2P_GRACE_MS = 5_000L

        /** Android `P2P_INBOUND_STALE_MS`: no media on P2P for this long = unhealthy. */
        const val P2P_STALE_MS = 2_000L

        /** How recent relay media must be for the watchdog to count the relay as a path. */
        const val RELAY_LIVENESS_MS = 5_000L

        /**
         * Resolve an address that [StunBinding.DEFAULT_SERVER] deliberately leaves
         * unresolved. `DatagramSocket.send` throws on an unresolved destination, and the
         * shipped constant is a literal IP, so this never reaches a resolver in practice.
         */
        private fun resolved(address: InetSocketAddress): InetSocketAddress =
            if (address.isUnresolved) InetSocketAddress(address.hostString, address.port) else address
    }
}

/**
 * Everything a media leg needs, and nothing about a call.
 *
 * Lifted straight out of [CallAction.StartMedia] — the state machine mints the session
 * key inside the offer and decides the direction, and neither is re-derived here. See
 * [MediaSocket]'s THE DIRECTION CONVENTION for why [isCaller] is load-bearing: inverted,
 * the call sounds perfect and both directions emit byte-identical GCM nonces under one
 * key.
 */
data class CallMediaSpec(
    val callId: String,
    val sessionKey: ByteArray,
    val nonceSalt: ByteArray,
    /** True when WE sent the offer and minted the salt. */
    val isCaller: Boolean,
    /** A video call (`0x08`/`0x09`): both ends start their camera on connect, as the phones do. */
    val video: Boolean = false,
    /** Our X25519 identity key, standard base64. Needed by the `:8089` relay only. */
    val selfKey: String? = null,
    /** The peer's X25519 identity key, standard base64. Needed by the `:8089` relay only. */
    val peerKey: String? = null,
    /** Signs the WebSocket relay's upgrade GET (`x-oshi-*` headers for a path), or null. */
    val wsUpgradeHeaders: ((path: String) -> Map<String, String>)? = null,
    /**
     * __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ The signed `POST /relay-token` for exactly
     * (selfKey, peerKey, callId), blocking; null result = refused/unreachable. Null = no token
     * (legacy token-less `:8089` register).
     */
    val relayToken: (() -> RelayToken?)? = null,
    /** `x-oshi-*` headers for an empty-body GET of a path (`/voip/turn-creds`), or null = unsigned. */
    val signedGet: ((path: String) -> Map<String, String>)? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallMediaSpec) return false
        return callId == other.callId && isCaller == other.isCaller && video == other.video &&
            sessionKey.contentEquals(other.sessionKey) && nonceSalt.contentEquals(other.nonceSalt)
    }

    override fun hashCode(): Int = callId.hashCode() * 31 + isCaller.hashCode()

    /** Never print a session key. */
    override fun toString(): String = "CallMediaSpec($callId, isCaller=$isCaller, video=$video)"
}

/**
 * How [CallLane] gets a media leg — the seam that keeps a state machine, an HTTP client
 * and an audio device out of one constructor.
 *
 * It is allowed to THROW, and the throw is the contract: a machine with no usable audio
 * device must fail the call loudly rather than connect it silently, so
 * [CallMedia.real] refuses up front and [CallMediaLeg.start] refuses on the way up.
 */
fun interface CallMediaOpener {
    /**
     * @throws javax.sound.sampled.LineUnavailableException when no audio device can serve
     *   the call, and any other exception when the socket cannot be opened. [CallLane]
     *   ends the call with a reason the user sees either way.
     */
    fun open(spec: CallMediaSpec, log: (String) -> Unit): CallMediaLeg
}

/** The openers an application may use. There is exactly one, and it is honest. */
object CallMedia {

    /**
     * A real UDP socket, a real microphone, a real speaker, and STUN.
     *
     * Refuses BEFORE opening anything when [CallAudio.isAvailable] says this machine
     * cannot do 48 kHz mono signed-16 big-endian in both directions — a headless server,
     * a container with no `/dev/snd`, a device already held in exclusive mode. Refusing
     * here rather than at the first `read()` is what turns "the call was silent" into
     * "the call ended and said why".
     *
     * @param stunServer defaults to the coturn both phones use. Pass null on a network
     *   where it is unreachable: the call then advertises host candidates only, which is
     *   enough on one LAN and nothing at all across two NATs.
     */
    fun real(
        stunServer: InetSocketAddress? = StunBinding.DEFAULT_SERVER,
        relay: CallRelayConfig? = CallRelayConfig.production(),
    ): CallMediaOpener =
        CallMediaOpener { spec, log ->
            if (!CallAudio.isAvailable()) {
                throw LineUnavailableException(
                    "this machine cannot capture and play ${CallAudio.SAMPLE_RATE.toInt()} Hz " +
                        "mono signed-16 big-endian audio, which is the only format an OSHI " +
                        "call carries",
                )
            }
            // Port 0 on the WILDCARD address, not on loopback: host candidates are
            // gathered from every real interface and they all have to name the port audio
            // actually leaves from.
            //
            // Bound BEFORE the leg and released by hand if the leg's constructor refuses
            // it (a key or salt of the wrong length — [MediaSocket]'s `require`s). Passing
            // `DatagramSocket(0)` straight as an argument would leak a bound UDP port on
            // that path, because nothing would ever hold a reference to it again.
            val datagram = DatagramSocket(0)
            try {
                CallMediaLeg(
                    spec = spec,
                    datagram = datagram,
                    audioFor = { send ->
                        CallAudioSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send)
                    },
                    videoFor = { send, nextSeq ->
                        CallVideoSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send, nextSeq, log = log)
                    },
                    stunServer = stunServer,
                    log = log,
                    relayConfig = relay,
                )
            } catch (t: Throwable) {
                runCatching { datagram.close() }
                throw t
            }
        }
}

/**
 * The relay half of a call — PARITY.md row 2.1-t. [turn] supplies ephemeral credentials for
 * the coturn relay candidate; [udpRelay] is the `:8089` carrier; [relayOnly] withholds and
 * ignores every direct pair (the phones' "Always use relay").
 */
data class CallRelayConfig(
    val turn: TurnCredentials? = null,
    val udpRelay: InetSocketAddress? = null,
    val relayOnly: Boolean = false,
    /** The call server's WebSocket relay (`wss://…/voip/`), the last carrier; null = off. */
    val webSocketUrl: String? = null,
    /**
     * Which leg to coturn: `"udp"`, `"tls"`, or `"auto"`. AUTO is iOS's placement — TLS
     * (`oshi-messenger.com:5349`) when relay-only, UDP (`:3478`) otherwise
     * (`VoiceCallManager.swift:14490-14505`) — plus one desktop addition, stated as ours: if
     * the first transport cannot allocate, the other is tried, so a network that drops
     * every UDP datagram still gets a TURN relay over TLS.
     */
    val turnTransport: String = "auto",
    val turnTlsHost: String = TurnCredentials.TLS_HOST,
    val turnTlsPort: Int = TurnCredentials.TLS_PORT,
) {
    fun turnOrder(): List<String> = when (turnTransport) {
        "udp" -> listOf("udp")
        "tls" -> listOf("tls")
        else -> if (relayOnly) listOf("tls", "udp") else listOf("udp", "tls")
    }

    companion object {
        /** What the phones use: `/voip/turn-creds` + coturn, `:8089`, and the WebSocket relay. */
        fun production(relayOnly: Boolean = false): CallRelayConfig =
            CallRelayConfig(
                PRODUCTION_CREDS, UdpRelayClient.DEFAULT_SERVER, relayOnly,
                webSocketUrl = WsRelayClient.urlFor(com.oshi.desktop.net.V2Http.defaultBaseUrl()),
            )

        /** One cache for the process: credentials are good for an hour. */
        private val PRODUCTION_CREDS = TurnCredentials()
    }
}
