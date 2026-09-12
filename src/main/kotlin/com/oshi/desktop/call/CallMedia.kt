package com.oshi.desktop.call

import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.transport.HolePunch
import com.oshi.desktop.call.transport.IceCandidate
import com.oshi.desktop.call.transport.IceCandidateType
import com.oshi.desktop.call.transport.IcePriority
import com.oshi.desktop.call.transport.MediaSocket
import com.oshi.desktop.call.transport.StunBinding
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
 *    NAT on both ends there is still no path at all — [MediaSocket]'s TURN IS NOT HERE.
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
     * Null is a real answer: with no TURN under this package there is nowhere else to go.
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
                // Read through the field rather than a captured value: this listener is
                // built while `audioSession` is still null (the session needs this
                // socket's send lambda, so one of the two has to exist first) and is only
                // ever INVOKED after the receive loop starts, which is after `start()`.
                val audio = audioSession ?: return false
                if (audio.onFrame(sealed)) framesAccepted.incrementAndGet()
                else framesRefused.incrementAndGet()
                return true
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

    init {
        audioSession = audioFor?.invoke { sealed -> socket.sendSealed(sealed) }
    }

    /** The audio half, or null for a transport-only leg. Internal: tests assert on it. */
    internal val audio: CallAudioSession? get() = audioSession

    /**
     * Called with the FULL local candidate set every time it grows — once for the host
     * addresses and once more when STUN answers. [CallLane] turns each call into one
     * `ice_candidate` signal.
     *
     * The full set rather than the delta, because the signal is a single datagram either
     * way and a peer that missed the first one still learns everything from the second.
     */
    var onLocalCandidates: (List<IceCandidate>) -> Unit = {}

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
    }

    /** Add every candidate the peer signalled. Returns how many were new. */
    fun addRemoteCandidates(candidates: List<IceCandidate>): Int =
        if (closed.get()) 0 else socket.addRemoteCandidates(candidates)

    /** The local set as signalled. Host addresses, plus the srflx once STUN answers. */
    fun localCandidates(): List<IceCandidate> = synchronized(localSet) { localSet.values.toList() }

    /**
     * One probe round and one re-selection. Driven by [CallLane] at 2 Hz.
     *
     * A transition to null is logged as a LOSS rather than ignored: it is the moment a
     * live call went silent, and with no relay under this package it is also the moment
     * there is nothing left to try.
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): IceCandidate? {
        if (closed.get() || !started.get()) return null
        socket.probeTick(nowMs)
        val now = socket.updateSelection(nowMs)
        val before = selected
        selected = now
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
        runCatching { audioSession?.stop() }
        runCatching { socket.close() }
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
        val set = localCandidates()
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallMediaSpec) return false
        return callId == other.callId && isCaller == other.isCaller &&
            sessionKey.contentEquals(other.sessionKey) && nonceSalt.contentEquals(other.nonceSalt)
    }

    override fun hashCode(): Int = callId.hashCode() * 31 + isCaller.hashCode()

    /** Never print a session key. */
    override fun toString(): String = "CallMediaSpec($callId, isCaller=$isCaller)"
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
    fun real(stunServer: InetSocketAddress? = StunBinding.DEFAULT_SERVER): CallMediaOpener =
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
                    stunServer = stunServer,
                    log = log,
                )
            } catch (t: Throwable) {
                runCatching { datagram.close() }
                throw t
            }
        }
}
