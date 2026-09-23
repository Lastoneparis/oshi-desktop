package com.oshi.desktop.call

import com.oshi.desktop.block.BlockPolicy
import com.oshi.desktop.call.transport.IceCandidate
import com.oshi.desktop.call.transport.IceCandidateCodec
import com.oshi.desktop.call.transport.IcePairTable
import com.oshi.desktop.pairing.ContactQr
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The call SIGNALLING lane, wired: a state machine, a seal, and a socket — PARITY.md 2.1.
 *
 * ============================================================ WHAT THIS IS, AND IS NOT
 *
 * Before it is anything else, this class is the thing that makes a codec package reachable.
 * Until it existed, `com/oshi/desktop/call/` was ~3 900 lines imported by nothing under
 * `app/`: a packet codec, an offer codec, a seal, a state machine and a video reassembler,
 * with no `/call` command, no signalling client and no socket. Every byte of it was correct
 * and none of it could ring anybody.
 *
 * What it adds is exactly one lane: **ring, answer, decline, hang up** — plus, when and
 * only when it is handed a [CallMediaOpener], the media leg under it.
 *
 * ============================================================ MEDIA IS A SEAM, AND IT IS OFF
 *
 * [mediaOpener] is null by default, and with it null this class behaves exactly as it did
 * before media existed: `StartMedia` logs [NO_AUDIO_WILL_FLOW], emits
 * `Connected(noAudio = true)`, and drops the session key on the floor. That is the shape
 * `app/OshiClient` still constructs, so **an unmodified build of this application still
 * carries no audio** and [NO_AUDIO_WILL_FLOW] is still a true sentence on every surface
 * that prints it.
 *
 * Hand it [CallMedia.real] and the other half switches on: a UDP socket, a microphone, a
 * speaker, host and srflx candidates signalled over the `ice_candidate` packet the
 * protocol already had, a 2 Hz hole punch, and `Connected(noAudio = false)`. A caller that
 * does that **must also stop printing [NO_AUDIO_WILL_FLOW] unconditionally** — branch on
 * [CallEvent.Connected.noAudio] instead.
 *
 * The default is off rather than on because the cost of being wrong is asymmetric: an
 * opener that cannot find an audio device ENDS the call (see [CallAudioSession.start] and
 * the `StartMedia` branch below), which is the right answer for a person trying to talk
 * and the wrong one for a client that only ever wanted to decline politely.
 *
 * Still true in both configurations:
 *
 *  - **No TURN.** `GET /turn-creds` is a live route on this very server and is still not
 *    called. Behind a symmetric NAT on both ends there is no media path at all — see
 *    (TURN and the `:8089` relay now exist — PARITY.md row 2.1-t.)
 *  - **No push, so no wake.** PARITY.md row 2.3: a desktop client POLLS. If this process is
 *    not running, an incoming call is not missed politely — it is not seen at all, and the
 *    caller gets their own 45 s no-answer.
 *  - **No call has been made between two people.** Audio has been proven to cross
 *    `127.0.0.1` in `CallMediaLaneTest`; a loopback socket is not a network and a test is
 *    not a phone call.
 *
 * [NO_AUDIO_WILL_FLOW] is the single owner of the no-media sentence, in the shape row
 * 0.26's `BOT_CHANNEL_IS_PLAINTEXT` established: one string, one owner, printed by every
 * surface that starts a call on the media-less path.
 *
 * ============================================================ THE DIVISION OF LABOUR
 *
 * [CallStateMachine] decides; this class performs. The machine is pure and clock-injected
 * and stays that way — every method here that changes call state takes `nowMs`, hands it
 * straight down, and then executes the [CallAction] list the machine returned. Nothing in
 * this file re-decides anything: there is no second staleness check, no second block gate,
 * no second glare rule. That is what keeps the races in one testable place.
 *
 * ============================================================ WHO SENT IT
 *
 * The envelope's `sender` is UNAUTHENTICATED — `SIGNATURE_REQUIRED = false`
 * (`call_server.js:65`) means anyone can POST a signal claiming to be anyone. So the sender
 * this lane hands the machine is never the field: it is the identity whose public half
 * OPENED the seal. [CallSignalCrypto.open] is a static-static ECDH under
 * `DH(myIdentityPriv, theirIdentityPub)`, so a packet that opens under a claimed sender's
 * key could only have been sealed by someone holding one of those two private keys.
 *
 * The claimed `sender` is therefore used for exactly one thing — deciding which public key
 * to TRY — and a packet that fails to open is dropped and counted ([unopenable]), never
 * rung. That counter matters: a client that silently swallows undecryptable signals is a
 * client whose "calls sometimes don't ring" is unfixable.
 *
 * ============================================================ RETRANSMISSION
 *
 * There is none, and that is a consequence of the transport rather than a shortcut. iOS
 * retransmits its accept seven times and fans every offer out to three transports at once
 * (`VoiceCallManager.swift:5874-5876`, `:7405`) because two of those three are lossy
 * best-effort paths. This client has ONE path, and on that path the server **always queues**
 * the signal (`call_server.js:664-694`), holds it 60 s, and hands it to every polling device
 * that has not seen it. A second POST would buy a duplicate the peer has to dedup, not a
 * second chance — and the server's own `NEWCALL_PURGE` and `STALE_TERMINAL_DROP` rules
 * (`:706-724`, `:668-683`) are written against clients that retransmit.
 *
 * What replaces the ladder is a refusal to lie about it: an offer whose POST did not land is
 * not left ringing. See [call].
 */
class CallLane(
    /** Our address = our X25519 identity key, standard base64. */
    private val myAddress: String,
    /** Our X25519 identity PRIVATE key. Used only by [CallSignalCrypto]. Never logged. */
    private val myPrivateKey: ByteArray,
    private val transport: CallSignalClient,
    /** Ours, so the server never hands our own signals back. See [CallSignalClient.poll]. */
    private val deviceId: String,
    private val machine: CallStateMachine,
    private val log: (String) -> Unit = {},
    /**
     * Opens the media leg for a connected call, or null for signalling only.
     *
     * **Null is the default and the shipped configuration** — see MEDIA IS A SEAM, AND IT
     * IS OFF. [CallMedia.real] is the only opener an application should pass.
     */
    private val mediaOpener: CallMediaOpener? = null,
    /**
     * How often to probe candidate pairs and re-select, in ms. Both phones run 2 Hz
     * ([IcePairTable.PROBE_INTERVAL_MS]).
     *
     * **0 disables the internal timer entirely** and leaves [mediaTick] to be driven by
     * the caller, which is how every test in this package gets a deterministic hole
     * punch — a background thread probing on its own clock turns an assertion about
     * "pings sent" into a race.
     */
    private val mediaProbeIntervalMs: Long = IcePairTable.PROBE_INTERVAL_MS,
    /** How long a connected call may go without a media path. See [MEDIA_PATH_TIMEOUT_MS]. */
    private val mediaPathTimeoutMs: Long = MEDIA_PATH_TIMEOUT_MS,
) {

    /** Something happened to a call. A description, never a rendering. */
    sealed class CallEvent {
        /** Local ring or ringback started. */
        data class Ringing(
            val peer: String,
            val callId: String,
            val incoming: Boolean,
            val video: Boolean,
        ) : CallEvent()

        /** Local ring or ringback stopped, for any reason. */
        object RingingStopped : CallEvent()

        /**
         * Both sides believe the call is up.
         *
         * [noAudio] is the field a UI must branch on and it is not decoration. True means
         * there is no media leg under this call at all — no socket, no device, no ICE —
         * and the peer will hear silence; that is the state of an application that did not
         * pass a [CallMediaOpener], which is every build of this one today.
         *
         * False means a leg opened, the devices are live and candidates have been
         * signalled. It does **not** mean the peer can hear anything yet: the hole punch
         * may never land, and [CallMediaLeg.selected] is null until it does. The honest
         * rendering of `noAudio = false` is "connecting audio", not "connected".
         *
         * A call whose media leg could not open never reaches this event at all — it is
         * ended instead. See the `StartMedia` branch.
         */
        data class Connected(
            val peer: String,
            val callId: String,
            val noAudio: Boolean = true,
        ) : CallEvent()

        data class Ended(
            val peer: String,
            val callId: String,
            val reason: CallEndReason,
            val wasConnected: Boolean,
        ) : CallEvent()

        /** The machine refused something. Countable — see [CallRefusal]. */
        data class Refused(val refusal: CallRefusal, val from: String?) : CallEvent()

        /** The signalling transport misbehaved. Never silent. */
        data class TransportProblem(val detail: String) : CallEvent()
    }

    /** One finished call. In memory only — see [calls]. */
    data class CallLogEntry(
        val peer: String,
        val callId: String,
        val incoming: Boolean,
        val reason: CallEndReason,
        val connected: Boolean,
        val atMs: Long,
    )

    /**
     * Privacy-preserving state of the desktop media lane.
     *
     * This deliberately contains counts and booleans only: candidate addresses and ports
     * are sensitive network metadata and belong neither in a UI nor in a support log.
     * `pathSelected` means that the local ICE probe received a valid response; it does
     * not prove that a remote person heard audio.
     */
    data class MediaDiagnostics(
        val audioConfigured: Boolean,
        /**
         * A truthful lifecycle status for a surface such as the Windows diagnostics pane.
         * This is intentionally not a call-quality claim: `WAITING_FOR_PATH` means that
         * local devices and UDP opened, but no bidirectional media path has been proven.
         */
        val status: MediaStatus,
        val mediaFailures: Int,
        val candidatesSent: Int,
        val candidatesReceived: Int,
        val candidatesIgnored: Int,
        val pathSelected: Boolean,
        val framesAccepted: Long,
        val framesRefused: Long,
        /** Sealed frames our microphone put on the socket (silence while muted). */
        val framesSent: Long = 0,
        /**
         * The microphone has delivered only digital zero for ~3 s while unmuted — what
         * Windows (and macOS) give a process whose microphone access is off. See
         * [com.oshi.desktop.call.media.CallAudioSession.consecutiveSilentFrames].
         */
        val micLooksBlocked: Boolean = false,
    )

    /**
     * The only media states a UI may present. In particular, no state means "audio is
     * audible": even [ACTIVE] records only that ICE selected a path locally.
     */
    enum class MediaStatus {
        /** This build was deliberately started without a media opener. */
        DISABLED,
        /** A media opener is configured, but no connected call currently owns it. */
        READY,
        /** Devices and UDP opened; ICE has not selected a usable peer path yet. */
        WAITING_FOR_PATH,
        /** A local ICE probe selected a path; remote audibility still needs a real call test. */
        ACTIVE,
    }

    /** What [call] did. */
    sealed class Dialled {
        data class Ringing(val callId: String, val delivery: String) : Dialled()
        data class Refused(val why: String, val refusal: CallRefusal = CallRefusal.NONE) : Dialled()
    }

    var onEvent: (CallEvent) -> Unit = {}

    /**
     * Whether this lane was built with a media opener — i.e. whether a connected call will
     * open a microphone and a speaker, or be two devices agreeing and silence.
     *
     * Exposed for ONE reason: the REPL prints a warning on every call, and with an opener
     * configured that warning becomes a lie in the other direction. A surface that says
     * "no audio will flow" over a call that is carrying audio is not cautious, it is wrong,
     * and a user who learns to ignore that line will ignore it when it is true again.
     */
    val carriesAudio: Boolean get() = mediaOpener != null

    val state: CallState get() = machine.state
    val peer: String? get() = machine.peer
    val callId: String? get() = machine.callId
    val isOutgoing: Boolean get() = machine.isOutgoing

    /**
     * Finished calls, newest last. **In memory only, and gone on restart.**
     *
     * Stated rather than quietly true: this is a signalling row, not a call-history row.
     * Persisting it would mean writing a peer's address and a call time to disk on a path
     * that has no store of its own yet, and inventing one here would be a second message
     * store nobody asked for. `/calls` says so.
     */
    private val callLog = ArrayList<CallLogEntry>()

    fun calls(): List<CallLogEntry> = synchronized(callLog) { callLog.toList() }

    /** A support-safe snapshot for `/calls`; see [MediaDiagnostics]. */
    fun mediaDiagnostics(): MediaDiagnostics {
        val live = leg
        val status = when {
            !carriesAudio -> MediaStatus.DISABLED
            live == null -> MediaStatus.READY
            live.selected == null -> MediaStatus.WAITING_FOR_PATH
            else -> MediaStatus.ACTIVE
        }
        return MediaDiagnostics(
            audioConfigured = carriesAudio,
            status = status,
            mediaFailures = mediaFailures,
            candidatesSent = candidatesSent,
            candidatesReceived = candidatesReceived,
            candidatesIgnored = candidatesIgnored,
            pathSelected = live?.selected != null,
            framesAccepted = live?.framesAccepted?.get() ?: 0,
            framesRefused = live?.framesRefused?.get() ?: 0,
            framesSent = live?.audio?.framesSent?.get() ?: 0,
            micLooksBlocked = live?.audio?.micLooksBlocked ?: false,
        )
    }

    /** Sealed signals that did not open. See WHO SENT IT — never zero silently. */
    @Volatile var unopenable: Int = 0
        private set

    /** Signals whose `sender` was not a usable identity key at all. */
    @Volatile var unaddressable: Int = 0
        private set

    /** Signals we could not put on the wire. */
    @Volatile var sendFailures: Int = 0
        private set

    /**
     * Calls that reached CONNECTED and whose media leg would not open, so the call was
     * ENDED rather than connected silently. Never zero silently — this is the counter that
     * distinguishes "nobody called" from "every call dies on this machine's audio stack".
     */
    @Volatile var mediaFailures: Int = 0
        private set

    /** `ice_candidate` signals put on the wire. Two per call is the expected number. */
    @Volatile var candidatesSent: Int = 0
        private set

    /** Individual remote candidates decoded off `ice_candidate` signals and accepted. */
    @Volatile var candidatesReceived: Int = 0
        private set

    /**
     * `ice_candidate` signals dropped: no media leg, the wrong peer, the wrong call, or a
     * payload that decoded to nothing.
     *
     * Counted because a call that signals, connects and stays silent is EXACTLY what a
     * silently-ignored candidate exchange looks like, and `HolePunch`'s own doc records
     * that failure shipping on both phones ("rings back, no audio").
     */
    @Volatile var candidatesIgnored: Int = 0
        private set

    /**
     * The phones' in-band hang-up (`0x0D` on the media path, [com.oshi.desktop.call.media.InBandCallEnd]):
     * how many ended a call here, and how many copies we sent when WE hung up.
     */
    @Volatile var inBandEndsApplied: Int = 0
        private set
    @Volatile var inBandEndsSent: Int = 0
        private set

    /**
     * The media leg of the call that is up, or null.
     *
     * Guarded by [mediaLock] on every write, because it is written from the signalling
     * thread and read from the probe timer.
     */
    @Volatile private var leg: CallMediaLeg? = null

    private val mediaLock = Any()

    /**
     * __DESKTOP_CALL_UI_2026_09_23__ ONE thread drives the machine at a time. The window
     * answers on a click while the poll thread applies a remote cancel and the media timer
     * may end a silent call — three threads, one unsynchronized [CallStateMachine]. The REPL
     * always had the same race (stdin + poll) and got away with it because a human typing
     * `/answer` rarely lands inside a poll. Reentrant: `call` → `perform` → `apply` nests.
     */
    private val drive = Any()

    private var mediaTimer: ScheduledExecutorService? = null

    /** The live media leg. Internal: tests assert on its counters and its socket. */
    internal val media: CallMediaLeg? get() = leg

    /**
     * Sealed blobs already processed, by their base64.
     *
     * A second belt over the server's per-device `_seen` list: the same signal legitimately
     * arrives twice when a poll crosses the `POLL_GRACE_MS` window, and the seal's nonce is
     * random per packet, so its base64 is a free unique id for one transmission. Bounded,
     * because this is fed by anything anyone can POST.
     */
    private val seenSignals = LinkedHashMap<String, Long>()

    // ------------------------------------------------------------------ outbound

    /**
     * Ring [peerAddress]. The media leg is enabled only when this lane has an opener;
     * see [carriesAudio] and [NO_AUDIO_WILL_FLOW].
     *
     * The offer carries the call's media session key, so it is minted by [CallStateMachine]
     * and never by a caller (a caller that could supply it could reuse one across calls).
     *
     * **An offer whose POST did not land ends the call here and now.** Both phones can leave
     * a dial ringing on a failed transport because they have two more; this client has one,
     * and a `RINGING` state with nothing on the wire is a 45-second lie followed by "no
     * answer". The machine is told with [CallEndReason.NETWORK_ERROR] so the reason a person
     * is given matches what actually happened.
     */
    fun call(
        peerAddress: String,
        nowMs: Long = System.currentTimeMillis(),
        video: Boolean = false,
        newCallId: String = UUID.randomUUID().toString().uppercase(java.util.Locale.US),
    ): Dialled {
        return synchronized(drive) { callLocked(peerAddress, nowMs, video, newCallId) }
    }

    private fun callLocked(peerAddress: String, nowMs: Long, video: Boolean, newCallId: String): Dialled {
        val canonical = ContactQr.canonicalAddress(peerAddress)
            ?: return Dialled.Refused("'$peerAddress' is not an OSHI address")

        val decision = machine.startCall(canonical, nowMs, video, newCallId)
        if (decision.refusal != CallRefusal.NONE) {
            return Dialled.Refused(refusalText(decision.refusal), decision.refusal)
        }

        // The offer is the first action; its outcome decides whether this is a call at all,
        // so it is sent before the ring is announced rather than fired and forgotten.
        var delivery = "unknown"
        var failure: String? = null
        for (action in decision.actions) {
            if (action is CallAction.Send) {
                when (val post = deliver(action, nowMs)) {
                    is CallSignalClient.Post.Queued -> delivery = post.delivery
                    is CallSignalClient.Post.Refused -> failure = "${post.code}: ${post.reason}"
                }
            } else {
                perform(action, nowMs)
            }
        }
        if (failure != null) {
            log("call: the offer for $newCallId never left — $failure")
            onEvent(CallEvent.TransportProblem("the call offer was not accepted by the server ($failure)"))
            // hangUp sends a callEnd of its own. It will almost certainly fail the same way,
            // and that is fine: it costs one request and it is the only thing that can stop
            // a peer who DID get the offer through some other route from ringing.
            apply(machine.hangUp(CallEndReason.NETWORK_ERROR, nowMs), nowMs)
            return Dialled.Refused("the offer did not reach the call server ($failure)")
        }
        return Dialled.Ringing(newCallId, delivery)
    }

    /** Answer the ringing call; a configured media leg opens only after acceptance. */
    fun answer(nowMs: Long = System.currentTimeMillis()): CallRefusal =
        siblingsDrop(nowMs) { apply(machine.accept(nowMs), nowMs) }

    /** Decline the ringing call. */
    fun decline(nowMs: Long = System.currentTimeMillis()): CallRefusal =
        siblingsDrop(nowMs) { apply(machine.decline(nowMs), nowMs) }

    /**
     * __DESKTOP_BACKGROUND_2026_09_23__ MULTI-DEVICE: when this computer holds the same account
     * as a phone (restored with its recovery key), one incoming call rings BOTH. Answering,
     * declining or hanging up here must stop the phone ringing, exactly as a sibling iPhone does:
     * `VoiceCallManager.notifyOtherDevicesCallAnswered` / `notifyOtherDevicesCallEnded`
     * (`swift:7922-8080`) seal a `callAnsweredElsewhere` (0x0A) packet to OUR OWN key, payload
     * `[len(1)][deviceId utf8][callId utf8]`, POST it to `/signal` with `senderDeviceId` so the
     * server never echoes it to us (`call_server.js:752, :943`), and hit push_service
     * `/signal-answered` so a SUSPENDED iPhone/Android gets a push that dismisses CallKit /
     * the ringing notification. The receive half already existed (`onAnsweredElsewhere`);
     * this client just never sent it, so a desktop answer left the phone ringing to timeout.
     * Only an INCOMING call is announced — an outgoing call never rang a sibling. Best effort
     * on a daemon thread: a failure here must never delay or change the call itself.
     */
    private fun siblingsDrop(nowMs: Long, body: () -> CallRefusal): CallRefusal = synchronized(drive) {
        val incomingCallId = machine.callId.takeIf { !machine.isOutgoing }
        val refusal = body()
        if (refusal == CallRefusal.NONE && incomingCallId != null) {
            Thread({ runCatching { announceToSiblings(incomingCallId, nowMs) } }, "oshi-call-siblings")
                .apply { isDaemon = true }.start()
        }
        refusal
    }

    private fun announceToSiblings(callId: String, nowMs: Long) {
        val canonical = ContactQr.canonicalAddress(myAddress) ?: return
        val self = Base64.getDecoder().decode(canonical)
        val dev = deviceId.toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(dev.size.coerceAtMost(255).toByte()) + dev.copyOf(dev.size.coerceAtMost(255)) +
            callId.toByteArray(Charsets.UTF_8)
        val packet = CallPacket.encode(CallPacket.Type.CALL_ANSWERED_ELSEWHERE, nowMs, payload)
        val me = CallSignalClient.base64Url(myAddress)
        transport.sendSignal(
            CallSignalEnvelope(
                sender = me,
                recipient = me,
                signalBase64 = Base64.getEncoder().encodeToString(CallSignalCrypto.seal(myPrivateKey, self, packet)),
                callId = callId,
                type = "callAnsweredElsewhere",
                isVideoCall = false,
                senderDeviceId = deviceId,
            ),
            nowMs,
        )
        siblingPush(canonical, callId)
    }

    /**
     * Where [siblingPush] posts; null turns it off.
     *
     * __CALL_PUSH_AUTH_DESKTOP_2026_09_23__ DERIVED from the transport's base URL (same
     * origin, nginx `/push/`) instead of a hard-coded production URL: a lane built on a
     * local test server used to POST every test answer/decline to production push.
     */
    @Volatile internal var siblingPushUrl: String? = transport.signalAnsweredUrl

    /** push_service `/signal-answered`, SIGNED by our bound key (contract §3 row 6). */
    private fun siblingPush(identity: String, callId: String) {
        val url = siblingPushUrl ?: return
        val status = transport.signalAnswered(identity, callId, deviceId, url)
        log("call: sibling devices told to stop ringing $callId (push $status)")
    }

    /**
     * __DESKTOP_CALL_UI_2026_09_23__ Mute the microphone of the call that is up — or of
     * the one about to be: the choice is remembered and applied when the leg opens, so a
     * user who mutes while "connecting" is not live the moment audio starts.
     */
    @Volatile var muted: Boolean = false
        private set

    fun setMuted(value: Boolean) {
        muted = value
        leg?.audio?.muted = value
    }

    /** The live call's video half — PARITY.md row 2.1-v. Null outside a connected call. */
    fun video(): com.oshi.desktop.call.video.CallVideoSession? = leg?.video

    /** Hang up from any live state. */
    fun hangUp(
        reason: CallEndReason = CallEndReason.HUNG_UP,
        nowMs: Long = System.currentTimeMillis(),
    ): CallRefusal = siblingsDrop(nowMs) { apply(machine.hangUp(reason, nowMs), nowMs) }

    // ------------------------------------------------------------------ inbound

    /**
     * One poll of the call server.
     *
     * @return how many signals were APPLIED to the state machine, or -1 when the server
     *   could not be reached. -1 rather than 0 for the reason [CallSignalClient.poll]
     *   returns null: an outage and a quiet minute are different facts.
     */
    fun pollOnce(nowMs: Long = System.currentTimeMillis()): Int {
        val signals = transport.poll(myAddress, deviceId)
        if (signals == null) {
            onEvent(CallEvent.TransportProblem("the call server did not answer a poll"))
            return -1
        }
        var applied = 0
        for (envelope in signals) if (accept(envelope, nowMs)) applied++
        return applied
    }

    /**
     * Feed ONE envelope in, exactly as a poll would. Internal so a test can drive the lane
     * without a socket, and so a future WebSocket leg has one door rather than two.
     *
     * @return true when the packet reached the state machine.
     */
    internal fun accept(envelope: CallSignalEnvelope, nowMs: Long): Boolean = synchronized(drive) { acceptLocked(envelope, nowMs) }

    private fun acceptLocked(envelope: CallSignalEnvelope, nowMs: Long): Boolean {
        pruneSeen(nowMs)
        if (seenSignals.containsKey(envelope.signalBase64)) return false

        val sealed = envelope.signalBytes()
        if (sealed == null) {
            unopenable++
            log("call: a signal from ${envelope.sender.take(12)}… carried unusable base64")
            return false
        }

        val senderAddress = ContactQr.canonicalAddress(envelope.sender)
        if (senderAddress == null) {
            // The server routes on this field, so a value that is not a key cannot have come
            // from a client that is speaking this protocol.
            unaddressable++
            log("call: a signal claimed a sender that is not an OSHI address")
            return false
        }
        val senderKey = Base64.getDecoder().decode(senderAddress)

        val plain = CallSignalCrypto.open(myPrivateKey, senderKey, sealed)
        if (plain == null) {
            // Expected, not exceptional: the server has no authentication, so anything can
            // be POSTed under anyone's name. Counted so "it never rang" has a diagnosis.
            unopenable++
            log("call: a signal claiming to be from ${senderAddress.take(12)}… did not open")
            return false
        }
        val packet = CallPacket.decode(plain)
        if (packet == null) {
            unopenable++
            log("call: an OPENED signal from ${senderAddress.take(12)}… was not a call packet")
            return false
        }

        seenSignals[envelope.signalBase64] = nowMs
        val decision = machine.onPacket(
            from = senderAddress,
            packet = packet,
            nowMs = nowMs,
            // The outer hint, used ONLY for terminal correlation — the accept, decline and
            // end bodies carry no callId at all (see CallAccept). Never an instruction.
            envelopeCallId = envelope.callId.takeIf { it.isNotEmpty() },
        )
        val refusal = apply(decision, nowMs)
        if (refusal != CallRefusal.NONE) {
            log("call: refused a ${packet.type} from ${senderAddress.take(12)}… — $refusal")
            onEvent(CallEvent.Refused(refusal, senderAddress))
            return true
        }

        // `0x30` reaches the machine like everything else and the machine returns no
        // actions for it (`else -> CallDecision(state)`), which is deliberate: the
        // candidate exchange changes no call STATE, so it belongs to the lane and not to
        // the state machine. What the machine does do first is worth having — the block
        // gate, the staleness check and the recently-ended check all run before this line,
        // so a blocked peer cannot feed candidates and a replayed exchange from a finished
        // call cannot repoint a live one.
        if (packet.type == CallPacket.Type.ICE_CANDIDATE_EXCHANGE) {
            onIceCandidates(senderAddress, packet, envelope.callId.takeIf { it.isNotEmpty() })
        }
        // __VIDEO_PLI_SIGNAL_2026_09_23__ iOS sends keyframe requests and camera pause/resume
        // ONLY on this channel (`VoiceCallManager.sendKeyframeRequest`, `createCallPacket` →
        // `/signal`), and Android sends a copy here as well. The machine returns no action for
        // them, so until now every iPhone PLI reached a desktop and was dropped: the desktop
        // encoder waited for its own 1 s GOP. Same gates as the candidates (authenticated
        // peer of THIS call); handed to the video half exactly as its media-channel twin.
        if (packet.type == CallPacket.Type.REQUEST_KEYFRAME ||
            packet.type == CallPacket.Type.VIDEO_PAUSED ||
            packet.type == CallPacket.Type.VIDEO_RESUMED
        ) {
            val current = machine.peer
            val video = leg?.takeIf { !it.isClosed }?.video
            if (video != null && current != null &&
                BlockPolicy.normalizeKey(current) == BlockPolicy.normalizeKey(senderAddress)
            ) {
                video.onMedia(com.oshi.desktop.call.media.VideoControl.encodeToggle(packet.type.code, nowMs))
            }
        }
        return true
    }

    /**
     * A peer signalled its candidates. Decode, and hand them to the live leg.
     *
     * Three gates, all of which drop and COUNT rather than throw:
     *
     *  - **there must be a leg**, because a candidate with no socket to probe from is a
     *    remote address this client has nowhere to put;
     *  - **it must be the peer we are in a call with**, compared on the identity that
     *    OPENED the seal and never on the envelope's `sender` — the server has no
     *    authentication, so the field is a claim (see WHO SENT IT);
     *  - **it must be THIS call**, when the envelope offered a callId. A candidate set
     *    accepted for a different call would repoint live audio at an address the person
     *    on this call never agreed to talk to.
     */
    private fun onIceCandidates(from: String, packet: CallPacket.Decoded, envelopeCallId: String?) {
        val live = leg
        if (live == null || live.isClosed) {
            candidatesIgnored++
            return
        }
        val current = machine.peer
        if (current == null || BlockPolicy.normalizeKey(current) != BlockPolicy.normalizeKey(from)) {
            candidatesIgnored++
            log("call: candidates from ${from.take(12)}… are not from the peer on this call")
            return
        }
        if (envelopeCallId != null && envelopeCallId != machine.callId) {
            candidatesIgnored++
            log("call: candidates for $envelopeCallId arrived during ${machine.callId}")
            return
        }
        val candidates = IceCandidateCodec.decode(packet.payload)
        if (candidates.isEmpty()) {
            // `01 00` — "nothing gathered" — is a legal payload both clients emit, so this
            // is a real state and not a parse failure. Counted anyway: a call that only
            // ever receives empty sets is a call that will never punch.
            candidatesIgnored++
            return
        }
        val added = live.addRemoteCandidates(candidates)
        candidatesReceived += added
        log("call: ${machine.callId} learned $added of ${candidates.size} remote candidates")
    }

    /**
     * Advance every watchdog. Driven by the same loop that polls.
     *
     * This is what turns 45 s of nobody answering into a `callEnd` on the wire — see
     * [CallStateMachine.tick], which is the only place a timeout is evaluated.
     */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        synchronized(drive) { apply(machine.tick(nowMs), nowMs) }
    }

    /**
     * One hole-punch round for the live media leg, if there is one. Returns the selected
     * pair, or null while the punch has not landed.
     *
     * Deliberately NOT folded into [tick]. [tick] runs on the signalling poll (1 Hz) and
     * the phones probe at 2 Hz ([IcePairTable.PROBE_INTERVAL_MS]); more importantly, this
     * is the method a test drives by hand, and a method that a background timer ALSO calls
     * on its own clock cannot be asserted on. When [mediaProbeIntervalMs] is non-zero this
     * lane's own timer is the only production caller.
     */
    fun mediaTick(nowMs: Long = System.currentTimeMillis()): IceCandidate? {
        val live = leg ?: return null
        val selected = live.tick(nowMs)
        // A call carried by the :8089 relay alone has a media path too (row 2.1-t).
        if (selected != null || live.isClosed || live.relayCarrying(nowMs)) return selected

        // THE MEDIA-PATH WATCHDOG. See MEDIA_PATH_TIMEOUT_MS — a connected call whose
        // hole punch never lands is the silent call this whole lane exists to avoid, and
        // nothing else in this client would ever end it: CallStateMachine has watchdogs
        // for RINGING and CONNECTING and none for IN_CALL, because before media existed
        // an IN_CALL state could not be wrong.
        val since = live.startedAtMs
        if (since > 0 && nowMs - since >= mediaPathTimeoutMs) {
            mediaFailures++
            log(
                "call: ${machine.callId} never opened a media path in " +
                    "${mediaPathTimeoutMs / 1000} s — ending rather than staying silently connected",
            )
            onEvent(
                CallEvent.TransportProblem(
                    "no audio path could be opened to this peer, so the call was ended " +
                        "rather than left connected in silence",
                ),
            )
            synchronized(drive) { apply(machine.hangUp(CallEndReason.CONNECTION_LOST, nowMs), nowMs) }
        }
        return null
    }

    /**
     * The peer hung up ON THE MEDIA PATH — an authenticated in-band `0x0D` opened under this
     * call's session key (only the peer holds it), so it is fed to the state machine exactly
     * as the peer's signalled `callEnd` would be: same current-peer and callId gates, same
     * iOS grace windows, same `Ended` event and the same call-history row. Idempotent: once
     * the call has ended the machine refuses it as WRONG_STATE.
     */
    internal fun onInBandCallEnd(callId: String, reason: CallEndReason, nowMs: Long = System.currentTimeMillis()) {
        synchronized(drive) {
            val peer = machine.peer ?: return
            if (machine.callId != callId || machine.state != CallState.IN_CALL) return
            val packet = CallPacket.Decoded(CallPacket.Type.CALL_END, nowMs, reason.wire.toByteArray(Charsets.UTF_8))
            val decision = machine.onPacket(from = peer, packet = packet, nowMs = nowMs, envelopeCallId = callId)
            val refusal = apply(decision, nowMs)
            if (refusal == CallRefusal.NONE) {
                inBandEndsApplied++
                log("call: $callId ended by the peer's in-band hang-up (${reason.wire})")
            } else {
                log("call: $callId in-band hang-up not applied — $refusal")
            }
        }
    }

    /**
     * Release the media leg and its timer, from anywhere, any number of times.
     *
     * Public because teardown must be reachable on every path a caller can reach — a
     * process shutting down while a call is up has to be able to put the microphone down
     * without going through the state machine.
     */
    fun closeMedia() {
        muted = false
        val (old, timer) = synchronized(mediaLock) {
            val pair = leg to mediaTimer
            leg = null
            mediaTimer = null
            pair
        }
        // Device before timer before nothing else: `CallMediaLeg.close` is the thing that
        // actually frees the hardware, and a still-running probe timer would only find a
        // closed leg and return null from `tick`.
        old?.let { runCatching { it.close() } }
        timer?.let { runCatching { it.shutdownNow() } }
    }

    // ------------------------------------------------------------------ internals

    /**
     * THE JOINT. The state machine says "open the audio device"; this is where one gets
     * opened, tied to a socket, and told about the peer.
     *
     * With no [mediaOpener] this is the old behaviour verbatim — the key and the salt are
     * dropped, [NO_AUDIO_WILL_FLOW] is logged, and the event says `noAudio = true`.
     *
     * With one, the order matters and it is: open → wire → start → gather → signal →
     * announce. **Nothing announces a connected call until the devices are actually
     * open**, because [CallEvent.Connected] is what a UI draws a live call from, and a
     * call drawn live over a microphone that never opened is the single most expensive
     * failure in a messenger — both people wait, and neither is told anything.
     *
     * So a leg that will not open is not connected, it is ENDED, with
     * [CallEndReason.NETWORK_ERROR] on the wire and a [CallEvent.TransportProblem]
     * carrying the reason locally. That is [CallAudioSession.start]'s contract honoured
     * rather than restated: it throws [javax.sound.sampled.LineUnavailableException] on
     * purpose, precisely so this branch cannot swallow it.
     */
    private fun startMedia(action: CallAction.StartMedia, nowMs: Long) {
        val opener = mediaOpener
        if (opener == null) {
            log("call: connected to ${action.peer.take(12)}… — $NO_AUDIO_WILL_FLOW")
            onEvent(CallEvent.Connected(action.peer, action.callId, noAudio = true))
            return
        }

        // No `closeMedia()` here. It stood here as a belt against a leg surviving into a
        // second StartMedia, and it is unreachable: `connect()` is reached only from
        // `accept()` and `onAccept()`, both of which refuse unless the machine is NOT
        // IN_CALL, and `leg` is assigned nowhere else. Left in, it would be a line no
        // fixture can exercise — the same thing `IceCandidateCodec.encode`'s removed
        // length check was, found the same way.
        val spec = CallMediaSpec(
            action.callId, action.sessionKey, action.nonceSalt, action.isCaller, video = machine.isVideo,
            wbAdpcm = action.wbAdpcm,
            selfKey = myAddress, peerKey = action.peer,
            wsUpgradeHeaders = transport::webSocketUpgradeHeaders,
            // __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ contract §2: this call's relay token.
            relayToken = {
                val r = transport.relayToken(myAddress, action.peer, action.callId)
                if (r.token == null) log("call: ${action.callId} relay token not issued (${r.code} ${r.reason})")
                else log("call: ${action.callId} relay token issued (mode ${r.token.mode})")
                r.token
            },
            signedGet = transport::signedGetHeaders,
        )
        val opened = try {
            opener.open(spec, log).also { fresh ->
                fresh.onLocalCandidates = { candidates ->
                    sendCandidates(action.peer, action.callId, candidates)
                }
                fresh.onInBandCallEnd = { reason -> onInBandCallEnd(action.callId, reason) }
                // May throw; `CallMediaLeg.start` releases both halves before it does.
                // The machine's clock, not the wall clock: in production they are the same
                // value and in a test they must not be two different ones, or the watchdog
                // below measures from an instant the test never chose.
                fresh.start(nowMs)
            }
        } catch (t: Throwable) {
            mediaFailures++
            val detail = "${t.javaClass.simpleName}: ${t.message ?: "no detail"}"
            log("call: the media leg for ${action.callId} did not open — $detail")
            onEvent(
                CallEvent.TransportProblem(
                    "this call could not open its audio devices ($detail), so it was ended " +
                        "rather than connected in silence",
                ),
            )
            // Re-entrant into apply(): hangUp() emits StopRinging, a callEnd Send and a
            // Log. That is intended — the peer has to be told, and StartMedia is the last
            // action in every decision that contains one, so nothing after it is skipped.
            apply(machine.hangUp(CallEndReason.NETWORK_ERROR, nowMs), nowMs)
            return
        }

        opened.audio?.muted = muted
        synchronized(mediaLock) {
            leg = opened
            if (mediaProbeIntervalMs > 0) mediaTimer = startProbeTimer()
        }
        log(
            "call: connected to ${action.peer.take(12)}… with a media leg on UDP port " +
                "${opened.socket.localPort} — audio is attempted, not guaranteed: it only " +
                "flows once a candidate pair answers.",
        )
        onEvent(CallEvent.Connected(action.peer, action.callId, noAudio = false))
    }

    private fun startProbeTimer(): ScheduledExecutorService {
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "oshi-call-media").apply { isDaemon = true }
        }
        exec.scheduleWithFixedDelay(
            { runCatching { mediaTick() } },
            0, mediaProbeIntervalMs, TimeUnit.MILLISECONDS,
        )
        return exec
    }

    /**
     * Put our candidates on the wire as a `0x30` exchange.
     *
     * Not a [CallAction] and not routed through the state machine, for the same reason
     * [onIceCandidates] is not: candidates change no call state. They ride the ordinary
     * sealed signalling envelope, so the addresses are end-to-end encrypted to the peer
     * even though the call server can see who is exchanging them with whom — the same
     * metadata split PARITY.md row 2.1 already records for the offer.
     */
    private fun sendCandidates(peer: String, callId: String, candidates: List<IceCandidate>) {
        if (candidates.isEmpty()) return
        val send = CallAction.Send(
            peer = peer,
            type = CallPacket.Type.ICE_CANDIDATE_EXCHANGE,
            payload = IceCandidateCodec.encode(candidates),
            envelopeType = CallSignalType.ICE_CANDIDATE,
            callId = callId,
        )
        val post = deliver(send, System.currentTimeMillis())
        if (post is CallSignalClient.Post.Queued) {
            candidatesSent++
            log("call: $callId signalled ${candidates.size} candidates")
        }
        // A refusal is already counted and announced by deliver(). It is NOT fatal the way
        // a refused offer is: the peer may still reach us from its own candidates, and
        // ending a live call because one of two candidate signals bounced would be worse
        // than the degraded path it is trying to avoid.
    }

    private fun apply(decision: CallDecision, nowMs: Long): CallRefusal {
        for (action in decision.actions) perform(action, nowMs)
        return decision.refusal
    }

    private fun perform(action: CallAction, nowMs: Long) {
        when (action) {
            is CallAction.Send -> {
                // WE are ending a live call: tell the peer on the media path too, as both
                // phones do, BEFORE StopMedia closes the leg. A remote-driven end emits no
                // Send, so this never echoes a hang-up back to the peer that sent it.
                if (action.type == CallPacket.Type.CALL_END) {
                    val live = leg
                    if (live != null && !live.isClosed) {
                        val reason = CallEndReason.fromWire(String(action.payload, Charsets.UTF_8))
                            ?: CallEndReason.HUNG_UP
                        inBandEndsSent += runCatching { live.sendInBandCallEnd(reason) }.getOrDefault(0)
                    }
                }
                deliver(action, nowMs)
            }

            is CallAction.StartRinging -> onEvent(
                CallEvent.Ringing(
                    peer = machine.peer.orEmpty(),
                    callId = machine.callId.orEmpty(),
                    incoming = action.incoming,
                    video = machine.isVideo,
                )
            )

            CallAction.StopRinging -> onEvent(CallEvent.RingingStopped)

            is CallAction.StartMedia -> startMedia(action, nowMs)

            CallAction.StopMedia -> closeMedia()

            is CallAction.Log -> {
                // A SECOND `closeMedia()` stood here as a belt, on the theory that
                // `CallStateMachine.finish` emits StopMedia only for IN_CALL while every
                // terminal path emits a Log. A mutation run showed it was DEAD: deleting
                // it left the suite green, and deleting the StopMedia handler ALSO left
                // the suite green, because each was covering for the other. The two
                // together tested nothing that one alone did not.
                //
                // A leg can only exist while the machine is IN_CALL — `startMedia` assigns
                // it after `connect()` has already entered that state, and its failure
                // path returns before assigning anything — so StopMedia is emitted on
                // every path that can have one to release. It is now the single owner, and
                // `M5_stopmedia_does_nothing` goes red.
                val entry = CallLogEntry(
                    peer = action.peer,
                    callId = action.callId,
                    incoming = !machine.isOutgoing,
                    reason = action.reason,
                    connected = action.connected,
                    atMs = nowMs,
                )
                synchronized(callLog) {
                    callLog += entry
                    while (callLog.size > MAX_CALL_LOG) callLog.removeAt(0)
                }
                // Best effort, and never load-bearing — see CallSignalClient.endCall.
                transport.endCall(action.callId, myAddress)
                onEvent(CallEvent.Ended(action.peer, action.callId, action.reason, action.connected))
            }
        }
    }

    private fun deliver(send: CallAction.Send, nowMs: Long): CallSignalClient.Post {
        val peerKey = ContactQr.canonicalAddress(send.peer)?.let { Base64.getDecoder().decode(it) }
        if (peerKey == null) {
            sendFailures++
            return CallSignalClient.Post.Refused(-1, "'${send.peer}' is not an OSHI address")
        }
        val packet = CallPacket.encode(send.type, nowMs, send.payload)
        val sealed = CallSignalCrypto.seal(myPrivateKey, peerKey, packet)
        val envelope = CallSignalEnvelope(
            // base64url on BOTH sides of the envelope, because that is what the server keys
            // its maps on (`normalizeKey`, `call_server.js:56-59`) and what both phones
            // emit. A standard-base64 recipient would be normalised by the server anyway;
            // emitting the fold ourselves keeps a byte comparison against a real Android
            // payload meaningful.
            sender = CallSignalClient.base64Url(myAddress),
            recipient = CallSignalClient.base64Url(send.peer),
            signalBase64 = Base64.getEncoder().encodeToString(sealed),
            callId = send.callId,
            type = send.envelopeType,
            isVideoCall = machine.isVideo,
            senderDeviceId = deviceId,
        )
        val post = transport.sendSignal(envelope, nowMs)
        if (post is CallSignalClient.Post.Refused) {
            sendFailures++
            log("call: could not send ${send.type} for ${send.callId} — ${post.code}: ${post.reason}")
            onEvent(
                CallEvent.TransportProblem(
                    "a ${send.type} signal was refused by the call server (${post.code}: ${post.reason})"
                )
            )
        }
        return post
    }

    private fun pruneSeen(nowMs: Long) {
        val it = seenSignals.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value >= SEEN_SIGNAL_TTL_MS) it.remove()
        }
        while (seenSignals.size > MAX_SEEN_SIGNALS) seenSignals.remove(seenSignals.keys.first())
    }

    private fun refusalText(refusal: CallRefusal): String = when (refusal) {
        CallRefusal.BLOCKED -> "that contact is blocked"
        CallRefusal.BUSY -> "this client is already on a call"
        else -> refusal.name.lowercase().replace('_', ' ')
    }

    companion object {

        /**
         * The sentence every surface that starts a call **on the media-less path** is
         * obliged to show, every time.
         *
         * One string with one owner, exactly as `OshiClient.BOT_CHANNEL_IS_PLAINTEXT` is for
         * row 0.26's plaintext lane. The failure it exists to prevent is specific: a CLI or a
         * window that prints "calling…" and then "connected" has told a person they are on a
         * call, and a lane with no [CallMediaOpener] cannot carry one second of audio in
         * either direction.
         *
         * **It is still literally true of every build of this application**, because
         * `app/OshiClient` constructs this lane with `mediaOpener = null`. The moment a
         * caller passes [CallMedia.real] it stops being true, and that caller's obligation
         * is to branch on [CallEvent.Connected.noAudio] rather than print this
         * unconditionally. The name is kept so that obligation is impossible to miss at a
         * call site: a surface printing a constant called NO_AUDIO_WILL_FLOW next to a
         * `noAudio = false` event is a bug anyone reading it can see.
         */
        const val NO_AUDIO_WILL_FLOW: String =
            "SIGNALLING ONLY — NO AUDIO WILL FLOW. This client can ring, answer, decline " +
                "and end a call; it has no microphone, no speaker, no media socket and no " +
                "codec running. The other end will show a connected call and hear silence."

        /** iOS polls call signals every second while a call is possible. */
        const val POLL_INTERVAL_MS = 1_000L

        /**
         * How long a connected call may sit with no selected candidate pair before it is
         * ended with [CallEndReason.CONNECTION_LOST].
         *
         * **This number is OURS. Neither shipped client exposes an equivalent**, so it is
         * not copied and must not be presented as parity: 20 s is
         * [CallTimeouts.CONNECTING_MS], this codebase's own watchdog on the nearest
         * analogous state, and picking the same figure keeps one number in a reader's head
         * instead of two.
         *
         * It exists because [CallStateMachine] has watchdogs on RINGING and CONNECTING and
         * none on IN_CALL — correctly, because before this lane had media an IN_CALL state
         * could not be wrong. Now it can: the hole punch, the TURN allocation and the
         * `:8089` relay can all fail (row 2.1-t), and then there is nowhere for the audio
         * to go. Without this, that call stays "connected" and
         * silent until a person gives up — the exact failure this whole row is about.
         *
         * It also covers a case the state machine cannot: a peer whose own media failed
         * hangs up within 3 s of us connecting, and
         * [CallTimeouts.END_GRACE_AFTER_CONNECT_MS] deliberately swallows that `callEnd`
         * (it is the retransmit guard both phones ship). The grace is right and is not
         * touched; this watchdog is what makes the outcome survivable anyway.
         */
        const val MEDIA_PATH_TIMEOUT_MS = 20_000L

        /**
         * How long a processed signal stays remembered.
         *
         * Longer than the server's `POLL_GRACE_MS` (8 s, `call_server.js:846`) and its
         * terminal TTL (25 s, `:875`), so a re-delivered signal is recognised for as long as
         * the server can re-deliver one.
         */
        const val SEEN_SIGNAL_TTL_MS = 90_000L

        private const val MAX_SEEN_SIGNALS = 64
        private const val MAX_CALL_LOG = 100
    }
}
