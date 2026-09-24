package com.oshi.desktop.call

import com.oshi.desktop.block.BlockPolicy
import com.oshi.desktop.store.ContactStore

/**
 * Where a call is. `CallState` — `OSHI/VoiceCallManager.swift:1841-1858`, and the same
 * five names on Android (`CallManager.kt:960-966`), which is rarer than it sounds.
 *
 * **[RINGING] is overloaded and that is the shipped design.** It means both "my phone is
 * ringing" and "I am hearing ringback". iOS acknowledges it in a comment at `:6968-6971`
 * and disambiguates with an `isOutgoingCall` flag; Android does the same with
 * `isOutgoing`. This port keeps one state and one flag rather than splitting into
 * `RINGING_IN`/`RINGING_OUT`, because the two clients' timeouts, guards and grace periods
 * are all written against the combined state — splitting it here would mean re-deriving
 * every one of them and losing the ability to check this file against theirs.
 */
enum class CallState {
    IDLE,

    /** Dialling, or answering. Nothing is ringing yet. */
    CONNECTING,

    /** Ringing — inbound or outbound. See the class note. */
    RINGING,

    /** Media is expected to be flowing. */
    IN_CALL,

    /** Terminal. Settles back to [IDLE] after [CallTimeouts.ENDED_SETTLE_MS]. */
    ENDED,
}

/**
 * Every timeout in the call state machine, with the shipped value and the side taken.
 *
 * PLAN.md's rule is that where the two clients disagree the STRICTER option wins and the
 * disagreement is recorded. Most of these agree exactly, which is itself evidence they
 * were ported from one another. The three that do not are marked.
 */
object CallTimeouts {

    /**
     * Outgoing ring give-up → [CallEndReason.NO_ANSWER].
     *
     * iOS `Task.sleep(nanoseconds: 45_000_000_000)` (`VoiceCallManager.swift:5927`),
     * Android `CALLER_NO_ANSWER_TIMEOUT_MS = 45_000L` (`EnhancedCallManager.kt:1029`).
     * Agree.
     */
    const val CALLER_NO_ANSWER_MS = 45_000L

    /**
     * Incoming ring backstop → the call is logged MISSED.
     *
     * iOS 55 s (`VoiceCallManager.swift:4084`), Android `CALLEE_RING_TIMEOUT_MS = 55_000L`
     * (`:1039`). Agree — and the ten-second gap above [CALLER_NO_ANSWER_MS] is
     * deliberate on both: the callee must outlive the caller's give-up, or a call that
     * the caller abandoned at 45 s would still be ringing here with nothing to answer.
     *
     * Android's `IncomingCallService.RING_TIMEOUT_MS` is 45 s (`IncomingCallService.kt:60`)
     * and fires a `CALL_MISSED` broadcast that **has no receiver anywhere in the app** —
     * the missed-call record is written by this 55 s watchdog instead. The desktop has no
     * ringer service, so there is one watchdog here and no dead broadcast.
     */
    const val CALLEE_RING_MS = 55_000L

    /**
     * Stuck in [CallState.CONNECTING] → [CallEndReason.NETWORK_ERROR].
     *
     * iOS `connectingTimeoutSeconds = 20.0` (`:3343`), Android
     * `CONNECTING_TIMEOUT_MS = 20_000L` (`:1047`). Agree.
     */
    const val CONNECTING_MS = 20_000L

    /**
     * [CallState.ENDED] → [CallState.IDLE].
     *
     * **DISAGREEMENT.** iOS settles after 1 s (`VoiceCallManager.swift:8703`), Android
     * after 2 s (`EnhancedCallManager.kt:2100`). Longer is stricter here — the settle
     * window is dead time during which a late terminal packet is absorbed instead of
     * being applied to a fresh call — so Android's 2 s wins.
     */
    const val ENDED_SETTLE_MS = 2_000L

    /**
     * How long a finished callId keeps refusing packets.
     *
     * **DISAGREEMENT, and a large one.** iOS `endedCallIdTTL = 15.0`
     * (`VoiceCallManager.swift:2399`); Android `ENDED_CALL_ID_TTL_MS = 90_000L`
     * (`EnhancedCallManager.kt:939`). Android's is six times longer and is the stricter
     * side: this window is the only thing stopping a re-delivered offer from re-ringing a
     * call the user already dealt with, and the server's own pending queue holds signals
     * for 60 s (`call_server.js:1031`). A 15 s TTL is shorter than the queue that feeds
     * it, so iOS can re-ring from its own backlog. 90 s wins.
     */
    const val ENDED_CALL_ID_TTL_MS = 90_000L

    /**
     * Ignore a `callEnd` for this long after connecting.
     *
     * iOS `callEndGracePeriodSeconds = 3.0` (`:2428`, enforced `:7062-7069`); Android's
     * equivalent literal is 5 000 ms (`:1850`) but applies only to `CONNECTION_LOST`.
     * The purpose is the same on both: a `callEnd` retransmit from the ladder that the
     * peer fired *before* it saw our accept must not kill the call it just established.
     * Taking iOS's 3 s, the narrower window, because this grace SUPPRESSES a real
     * user-initiated hang-up and a longer one means a peer's deliberate hang-up is
     * ignored for longer.
     */
    const val END_GRACE_AFTER_CONNECT_MS = 3_000L

    /**
     * Ignore a `callEnd` for this long after we START ringing outbound.
     *
     * iOS only (`VoiceCallManager.swift:7076-7080`), 5 s. Android has no equivalent.
     * Same argument as above from the other end of the call: our own offer fan-out goes
     * out on three transports and the peer may answer one and end another.
     */
    const val END_GRACE_AFTER_DIAL_MS = 5_000L

    /**
     * Duplicate-accept suppression. iOS `acceptDeduplicationWindow = 5.0` (`:2312`).
     *
     * The accept is retransmitted SEVEN times on iOS
     * (`delays = [0, 400, 600, 1000, 1500, 2000, 2500]`, `:7405`) and three times on
     * Android (`:1685-1693`), so duplicates are the norm, not an anomaly.
     */
    const val ACCEPT_DEDUP_MS = 5_000L

    /**
     * Duplicate-offer suppression. iOS's `seenOfferSignatures` window, 60 s
     * (`VoiceCallManager.swift:6172-6182`), capped at 32 entries.
     *
     * Needed because the same offer arrives on up to three transports at once — the
     * caller fans out to the relay, the cross-platform mesh and the iOS↔iOS mesh in
     * parallel (`:5874-5876`) — plus once more from the HTTP poll.
     */
    const val OFFER_DEDUP_MS = 60_000L
}

/**
 * What the caller of the state machine must DO. A pure description; nothing here performs
 * I/O.
 *
 * Returning actions rather than invoking callbacks is what makes every transition, every
 * timeout and every race in this file testable without a network, a clock or a thread.
 * It is also what let the three races below be written down as tests before they were
 * written as code.
 */
sealed class CallAction {

    /** Put a packet on the wire to [peer]. [envelopeType] is the outer JSON hint. */
    data class Send(
        val peer: String,
        val type: CallPacket.Type,
        val payload: ByteArray,
        val envelopeType: String?,
        val callId: String,
    ) : CallAction() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Send) return false
            return peer == other.peer && type == other.type && callId == other.callId &&
                envelopeType == other.envelopeType && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int =
            ((peer.hashCode() * 31 + type.hashCode()) * 31 + callId.hashCode()) * 31 +
                payload.contentHashCode()
    }

    /** Start local ringing / ringback. */
    data class StartRinging(val incoming: Boolean) : CallAction()

    /** Stop local ringing / ringback. */
    object StopRinging : CallAction()

    /** Open the audio device and start the media session under [sessionKey]. */
    data class StartMedia(
        val peer: String,
        val callId: String,
        val sessionKey: ByteArray,
        val nonceSalt: ByteArray,
        /** True when WE sent the offer. Decides which nonce salt direction is ours. */
        val isCaller: Boolean,
        /** __WB_ADPCM_CODEC_2026_09_23__ the peer advertised 0x18: send it instead of 0x15. */
        val wbAdpcm: Boolean = false,
    ) : CallAction() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StartMedia) return false
            return peer == other.peer && callId == other.callId && isCaller == other.isCaller &&
                sessionKey.contentEquals(other.sessionKey) && nonceSalt.contentEquals(other.nonceSalt)
        }

        override fun hashCode(): Int = callId.hashCode() * 31 + peer.hashCode()
    }

    /** Close the audio device and release it. Always paired with a terminal transition. */
    object StopMedia : CallAction()

    /** Write a call-log row. [connected] false means missed/declined/failed. */
    data class Log(val peer: String, val callId: String, val reason: CallEndReason, val connected: Boolean) :
        CallAction()
}

/**
 * Why the machine refused something. Countable on purpose — every one of these is a
 * silent drop in at least one shipped client, and a silent drop nobody counts is how
 * "calls sometimes don't ring" becomes an unfixable bug report.
 */
enum class CallRefusal {
    /** Not refused. */
    NONE,

    /** The peer is blocked. Nothing was sent back. See [BlockPolicy.incomingCall]. */
    BLOCKED,

    /** We are already in a call. See GLARE for why this is not always the answer. */
    BUSY,

    /** The packet's header instant is older than [CallPacket.OFFER_MAX_AGE_MS]. */
    STALE,

    /** This callId ended recently. See [CallTimeouts.ENDED_CALL_ID_TTL_MS]. */
    RECENTLY_ENDED,

    /** A duplicate offer or accept inside the dedup window. */
    DUPLICATE,

    /** An accept, decline or end for a call this machine is not in. */
    WRONG_STATE,

    /** A terminal packet naming a DIFFERENT call than the one we are in. */
    FOREIGN_CALL,

    /** A terminal packet inside a grace window. See [CallTimeouts]. */
    GRACE_PERIOD,

    /** Glare: we dialled simultaneously and lost the tie-break, so we yielded. */
    GLARE_YIELDED,

    /** Glare: we dialled simultaneously and won, so the inbound offer was dropped. */
    GLARE_WON,

    /** The packet body did not parse. */
    UNPARSEABLE,
}

/** One step of the machine: the state after, what to do, and why anything was refused. */
data class CallDecision(
    val state: CallState,
    val actions: List<CallAction> = emptyList(),
    val refusal: CallRefusal = CallRefusal.NONE,
)

/**
 * The OSHI call state machine — PARITY.md row 2.1, and the half of a calling feature
 * where the bugs actually live.
 *
 * ============================================================ WHAT THIS IS FOR
 *
 * Media is a pipeline: it either carries audio or it does not, and you find out in a
 * second. Signalling is a distributed agreement between two devices over three unreliable
 * transports with no server-side authentication, where every message can be duplicated,
 * reordered, delayed past its own timeout, or delivered to a second device. That is where
 * "the call didn't ring", "it rang after I hung up" and "my call died when my other phone
 * declined" come from, and all three of those are real, reproducible defects in the
 * shipped clients — see the race sections below.
 *
 * So this class is pure and clock-injected. Every method takes `nowMs`, no method reads a
 * clock or a socket, and every outcome is a value. That is what makes a timeout testable
 * at its boundary instead of with a `Thread.sleep`, and it is the same decision
 * [com.oshi.desktop.place.PlaceRouter] made for expiry.
 *
 * ============================================================ RACE 1 — GLARE
 *
 * Both sides dial at the same instant. **iOS handles this; Android does not.**
 *
 * iOS detects it (`VoiceCallManager.swift:6228-6244`) and breaks the tie
 * deterministically at `:6257-6259`:
 *
 * ```swift
 * if isPhoneTagCollision, let myKey = getIdentityManager()?.publicKey {
 *     let myWins = myKey < peerPublicKey
 * ```
 *
 * The winner keeps its outgoing call and drops the inbound offer; the loser ends its own
 * call and becomes the callee. Because the comparison is on the same two strings on both
 * devices and the relation is total, exactly one side wins — no round trip needed.
 *
 * Android has none of this. An inbound offer while not IDLE is dropped with a log line
 * and **nothing is sent back** (`EnhancedCallManager.kt:2465-2469`); an outbound attempt
 * while not IDLE returns a failure. So true glare between two Androids leaves both sides
 * ringing into a void until both 45 s watchdogs fire. This client takes iOS's side.
 *
 * **One defect in iOS's version is NOT copied.** iOS compares `myKey` — the raw stored
 * identity — against `peerPublicKey`, which on the signalling path may be base64URL,
 * while every other comparison in the same function first normalises with
 * `base64urlDecode`. Two spellings of the same key compare differently, so both sides can
 * compute `myWins = true` (or both false) and the tie-break inverts. [glareWinner]
 * compares through [BlockPolicy.normalizeKey], the fold both platforms already use for
 * block lists, which maps `-`→`+` and `_`→`/` and drops padding — so the two devices
 * compare the same bytes whichever spelling each received.
 *
 * ============================================================ RACE 2 — ANSWER AFTER HANGUP
 *
 * We give up and hang up; the peer's accept was already in flight. **Both platforms
 * handle this** and this client follows: an accept is honoured only from [CallState.RINGING]
 * or [CallState.CONNECTING] (iOS `:7018-7029`, Android `:2673-2679`), and the ended-callId
 * TTL refuses anything else for that call afterwards.
 *
 * ============================================================ RACE 3 — DECLINE CROSSING AN ANSWER
 *
 * **Neither platform handles this, and this client is deliberately stricter than both.**
 *
 * On Android `handleCallSignal` routes `CALL_REJECT → handleCallRejected()`
 * unconditionally (`EnhancedCallManager.kt:2198`), and that function is an unguarded
 * `endCall(CallEndReason.DECLINED)` (`:2792-2795`). `endCall`'s protective guards are all
 * reason-specific — the CONNECTING guard fires only for `PEER_ENDED` (`:1841-1844`), the
 * post-connect grace only for `CONNECTION_LOST` and `PEER_ENDED` (`:1850`, `:1857`), the
 * ringing grace only for `PEER_ENDED` (`:1866`). `DECLINED` passes every one of them.
 *
 * iOS is no better (`VoiceCallManager.swift:7031-7034`): no state guard, no callId check,
 * no grace period on `callDecline`, unlike `callEnd` which has all three. Its cross-call
 * terminal guard at `:5044-5058` would catch a foreign callId — but the decline packet
 * body carries no callId ([CallAccept]) and `declineIncomingCall` sends no envelope
 * `type` either (`:7576`), so for a decline this client emits, the guard cannot fire.
 *
 * **The consequence on both phones: a decline that lands after the call is connected
 * tears down a live call.** That is not hypothetical — a second device declining, or a
 * retransmit reordered behind an accept, produces exactly it.
 *
 * Here, a decline is subject to the SAME three guards a `callEnd` gets: it must name the
 * current call (or name nothing, which the format forces), it must arrive in a state
 * where a decline is meaningful ([CallState.RINGING] or [CallState.CONNECTING] while
 * outgoing), and it is refused inside the post-connect grace. A decline received while
 * [CallState.IN_CALL] is refused as [CallRefusal.WRONG_STATE]. Refusing it costs a user
 * nothing — a peer who genuinely wants to end a connected call sends `callEnd`, which is
 * what every hang-up path on both platforms actually sends.
 *
 * ============================================================ RACE 4 — BUSY
 *
 * There is **no busy signal in this protocol**, on either platform. iOS expresses busy by
 * sending an ordinary `callDecline` (`VoiceCallManager.swift:6432-6437`), so the caller
 * cannot tell "busy" from "declined". Android sends nothing at all: `handleIncomingCall`
 * returns early (`:2465-2469`) and the comment above the dead `CallManager` equivalent
 * says `// Already in a call, send busy signal` above a bare `return` — a comment
 * describing code that was never written.
 *
 * This client takes iOS's side and declines, because silence is indistinguishable from
 * being blocked and a caller deserves to know the difference. [CallRefusal.BUSY] carries
 * a decline action; [CallRefusal.BLOCKED] carries none. That asymmetry is the entire
 * privacy property of blocking and it is the reason these are two refusal values and not
 * one.
 *
 * ============================================================ WHAT THIS DOES NOT DO
 *
 * It does not send, poll, ring, or open an audio device — it says what should happen. It
 * does not implement ICE (packet type 0x30 is carried by [CallPacket] and routed to the
 * caller as an opaque body; the desktop's transport selection is not this row's). And it
 * holds exactly one call, like both shipped clients.
 */
class CallStateMachine(
    /** Our own address = our X25519 identity key, base64. */
    private val myAddress: String,
    /** Blocking is enforced through [BlockPolicy] and nowhere else. Null disables it. */
    private val contacts: ContactStore? = null,
    /** Our device id, for the multi-device echo drop. Optional. */
    private val myDeviceId: String? = null,
) {

    var state: CallState = CallState.IDLE
        private set

    /** The peer of the current call, or null. */
    var peer: String? = null
        private set

    /** The current callId, or null. */
    var callId: String? = null
        private set

    /** True when WE dialled. Disambiguates the overloaded [CallState.RINGING]. */
    var isOutgoing: Boolean = false
        private set

    /** True when the current call is video. */
    var isVideo: Boolean = false
        private set

    /** __WB_ADPCM_CODEC_2026_09_23__ the peer advertised WB-ADPCM (offer slot 4 / accept index 4). */
    var peerWbAdpcm: Boolean = false
        private set

    /** The media key from the offer, once known. */
    var sessionKey: ByteArray? = null
        private set

    private var nonceSalt: ByteArray? = null

    /** When the current state was entered. Drives every timeout. */
    private var stateSinceMs: Long = 0

    /** When we connected, for [CallTimeouts.END_GRACE_AFTER_CONNECT_MS]. */
    private var connectedAtMs: Long? = null

    /** When we started dialling, for [CallTimeouts.END_GRACE_AFTER_DIAL_MS]. */
    private var dialedAtMs: Long? = null

    /** callId → when it ended. Refuses late packets. See [CallTimeouts.ENDED_CALL_ID_TTL_MS]. */
    private val endedCalls = LinkedHashMap<String, Long>()

    /** callId → when first seen, for offer dedup across transports. */
    private val seenOffers = LinkedHashMap<String, Long>()

    /** When we last applied an accept, for [CallTimeouts.ACCEPT_DEDUP_MS]. */
    private var lastAcceptAtMs: Long? = null

    // ------------------------------------------------------------------ outbound

    /**
     * Dial [peerAddress].
     *
     * Refuses a blocked peer through [BlockPolicy.outgoingCall] — the gate BOTH phones
     * have (`VoiceCallManager.swift:5641-5643` throws `CallError.peerBlocked`,
     * `EnhancedCallManager.kt:1296-1302` returns a failure). Refuses while a call is up,
     * as both do.
     *
     * The offer body is built here rather than by the caller because the session key is
     * the call's media key and must be minted exactly once, by the side that dials — a
     * caller that could supply it could reuse one across calls.
     */
    fun startCall(
        peerAddress: String,
        nowMs: Long,
        video: Boolean = false,
        newCallId: String,
        newSessionKey: ByteArray = CallSignalCrypto.randomSessionKey(),
        newNonceSalt: ByteArray = CallSignalCrypto.randomNonceSalt(),
    ): CallDecision {
        if (state != CallState.IDLE) return CallDecision(state, refusal = CallRefusal.BUSY)

        val store = contacts
        if (store != null && BlockPolicy.outgoingCall(store, peerAddress) == BlockPolicy.Outbound.REFUSE_BLOCKED) {
            return CallDecision(state, refusal = CallRefusal.BLOCKED)
        }

        peer = peerAddress
        callId = newCallId
        isOutgoing = true
        isVideo = video
        sessionKey = newSessionKey
        nonceSalt = newNonceSalt
        dialedAtMs = nowMs
        connectedAtMs = null
        lastAcceptAtMs = null

        val type = if (video) CallPacket.Type.VIDEO_CALL_REQUEST else CallPacket.Type.CALL_REQUEST
        val offer = CallOffer(newSessionKey, newNonceSalt, newCallId, supportsVideo = true, supportsWbAdpcm = true).encode()

        // CONNECTING first, then RINGING — both clients publish the intermediate state and
        // arm a 20 s watchdog on it (`:5744`/`:5766`, `:1375`/`:1377`). Collapsing the two
        // would lose that watchdog: a dial that never reaches the transport would sit in
        // RINGING for 45 s and report "no answer" for a call that was never sent.
        enter(CallState.CONNECTING, nowMs)
        val actions = mutableListOf<CallAction>(
            CallAction.Send(peerAddress, type, offer, CallSignalType.CALL_REQUEST, newCallId),
        )
        enter(CallState.RINGING, nowMs)
        actions.add(CallAction.StartRinging(incoming = false))
        return CallDecision(state, actions)
    }

    /**
     * Answer the call that is ringing.
     *
     * The accept body is capability bytes only, and this client claims neither OshiCodec
     * nor AAC-ELD nor 16 kHz PCM — see [com.oshi.desktop.call.media.CallMediaFrame] for
     * what it does claim, which is the raw-PCM path both platforms already speak.
     */
    fun accept(nowMs: Long): CallDecision {
        if (state != CallState.RINGING || isOutgoing) {
            return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        }
        val p = peer ?: return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        val id = callId ?: return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        val key = sessionKey ?: return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        val salt = nonceSalt ?: return CallDecision(state, refusal = CallRefusal.WRONG_STATE)

        val type = if (isVideo) CallPacket.Type.VIDEO_CALL_ACCEPT else CallPacket.Type.CALL_ACCEPT
        enter(CallState.CONNECTING, nowMs)
        val actions = mutableListOf<CallAction>(
            CallAction.StopRinging,
            CallAction.Send(p, type, CallAccept(supportsVideo = true, supportsWbAdpcm = true).encode(), CallSignalType.CALL_ACCEPT, id),
        )
        // The callee connects on sending the accept; the caller connects on receiving it.
        // Both clients do it this way, and the asymmetry is why the accept is
        // retransmitted seven times on iOS: the callee is already live and the caller is
        // not, so a lost accept is a call that one side thinks is up.
        connect(nowMs, actions)
        return CallDecision(state, actions)
    }

    /** Decline a ringing call: send 0x03, then end locally. */
    fun decline(nowMs: Long): CallDecision {
        if (state != CallState.RINGING || isOutgoing) {
            return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        }
        val p = peer!!
        val id = callId!!
        val actions = mutableListOf<CallAction>(
            CallAction.StopRinging,
            CallAction.Send(p, CallPacket.Type.CALL_DECLINE, ByteArray(0), CallSignalType.CALL_DECLINED, id),
        )
        finish(CallEndReason.DECLINED, nowMs, actions, connected = false)
        return CallDecision(state, actions)
    }

    /** Hang up, from any live state. Sends 0x04 with a reason body. */
    fun hangUp(reason: CallEndReason, nowMs: Long): CallDecision {
        if (state == CallState.IDLE || state == CallState.ENDED) {
            return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        }
        val p = peer!!
        val id = callId!!
        val wasConnected = state == CallState.IN_CALL
        val actions = mutableListOf<CallAction>(
            CallAction.StopRinging,
            CallAction.Send(
                p, CallPacket.Type.CALL_END,
                reason.wire.toByteArray(Charsets.UTF_8),
                CallSignalType.forPacket(CallPacket.Type.CALL_END, reason),
                id,
            ),
        )
        finish(reason, nowMs, actions, connected = wasConnected)
        return CallDecision(state, actions)
    }

    // ------------------------------------------------------------------ inbound

    /**
     * Feed one DECRYPTED, AUTHENTICATED signalling packet in.
     *
     * [from] is the identity the SEAL authenticated — the key whose private half opened
     * the packet — never the envelope's `sender` field. The envelope is unauthenticated
     * (`call_server.js:65`) and using its `sender` here would let anyone suppress or
     * hijack a call by naming someone else. [envelopeCallId] is the outer hint and is
     * used ONLY for terminal correlation, because the accept/decline/end bodies carry no
     * callId (see [CallAccept]); it is treated as a hint that can be absent or wrong, and
     * never as an instruction.
     */
    fun onPacket(
        from: String,
        packet: CallPacket.Decoded,
        nowMs: Long,
        envelopeCallId: String? = null,
    ): CallDecision {
        // 1. BLOCK, first, and silently. After the AEAD (we are handed plaintext) and
        //    before any state change, ring or log. See BlockPolicy.incomingCall.
        val store = contacts
        if (store != null && BlockPolicy.incomingCall(store, from) == BlockPolicy.Inbound.DROP_BLOCKED) {
            return CallDecision(state, refusal = CallRefusal.BLOCKED)
        }

        // 2. STALE. Off the header inside the AEAD, never off the envelope — see
        //    CallSignalEnvelope's epoch note and the shipped Android bug it describes.
        if (CallPacket.isStale(packet, nowMs)) {
            return CallDecision(state, refusal = CallRefusal.STALE)
        }

        // 3. A call that recently ended stays ended.
        pruneEnded(nowMs)
        val correlated = envelopeCallId?.takeIf { it.isNotEmpty() }
            ?: if (packet.type.isOffer) CallOffer.peekCallId(packet.payload) else null
        if (correlated != null && endedCalls.containsKey(correlated)) {
            return CallDecision(state, refusal = CallRefusal.RECENTLY_ENDED)
        }

        return when {
            packet.type.isOffer -> onOffer(from, packet, nowMs)
            packet.type.isAccept -> onAccept(from, packet, nowMs, correlated)
            packet.type == CallPacket.Type.CALL_DECLINE -> onDecline(from, nowMs, correlated)
            packet.type == CallPacket.Type.CALL_END -> onEnd(from, packet, nowMs, correlated)
            packet.type == CallPacket.Type.CALL_ANSWERED_ELSEWHERE -> onAnsweredElsewhere(nowMs, correlated)
            else -> CallDecision(state)
        }
    }

    private fun onOffer(from: String, packet: CallPacket.Decoded, nowMs: Long): CallDecision {
        val offer = CallOffer.decode(packet.payload)
            ?: return CallDecision(state, refusal = CallRefusal.UNPARSEABLE)

        // Duplicate across transports: the caller fans an offer out to the relay AND both
        // meshes at once, and the HTTP poll may hand it to us a fourth time.
        pruneSeenOffers(nowMs)
        if (seenOffers.containsKey(offer.callId) && offer.callId != callId) {
            return CallDecision(state, refusal = CallRefusal.DUPLICATE)
        }

        if (state != CallState.IDLE) {
            // Our own offer echoed back by the relay. Not glare, not busy — nothing.
            if (offer.callId == callId) {
                return CallDecision(state, refusal = CallRefusal.DUPLICATE)
            }

            // GLARE: we are dialling THIS peer and they are dialling us. See RACE 1.
            val samePeer = peer?.let { BlockPolicy.normalizeKey(it) == BlockPolicy.normalizeKey(from) } == true
            val dialing = isOutgoing && (state == CallState.CONNECTING || state == CallState.RINGING)
            if (samePeer && dialing) {
                seenOffers[offer.callId] = nowMs
                return if (glareWinner(myAddress, from)) {
                    // We keep our outgoing call; their offer is dropped. They will yield.
                    CallDecision(state, refusal = CallRefusal.GLARE_WON)
                } else {
                    // We yield: abandon our own call and become the callee of theirs.
                    // No callEnd is sent — the winner is already dropping our offer, and
                    // an end for a call they never accepted would race their own ring.
                    val actions = mutableListOf<CallAction>(CallAction.StopRinging)
                    resetCall()
                    ring(from, offer, packet.type.isVideo, nowMs, actions)
                    CallDecision(state, actions, refusal = CallRefusal.GLARE_YIELDED)
                }
            }

            // SAME PEER, NEW CALL: the peer cannot be in two calls with us, so a fresh
            // offer from the person we are (still) connected to or ringing with means
            // they abandoned the old one — their app restarted, crashed, or lost the
            // call. Found with a real iPhone (2026-09-24): the iOS app died mid-call,
            // the user redialled, and the desktop — still IN_CALL on the dead call —
            // answered the redial with a BUSY decline, so the user saw "rejected".
            // End the stale call locally (no callEnd: the peer has already dropped it)
            // and ring the new one. Android's IncomingCallAdmission replaces a redial
            // from the same caller the same way.
            if (samePeer && !dialing) {
                seenOffers[offer.callId] = nowMs
                val actions = mutableListOf<CallAction>(CallAction.StopRinging)
                if (state != CallState.ENDED) {
                    finish(CallEndReason.CONNECTION_LOST, nowMs, actions, connected = state == CallState.IN_CALL)
                }
                resetCall()
                ring(from, offer, packet.type.isVideo, nowMs, actions)
                return CallDecision(state, actions, refusal = CallRefusal.NONE)
            }

            // BUSY. iOS declines, Android sends nothing. We decline — see RACE 4.
            return CallDecision(
                state,
                actions = listOf(
                    CallAction.Send(
                        from, CallPacket.Type.CALL_DECLINE, ByteArray(0),
                        CallSignalType.CALL_DECLINED, offer.callId,
                    ),
                ),
                refusal = CallRefusal.BUSY,
            )
        }

        seenOffers[offer.callId] = nowMs
        val actions = mutableListOf<CallAction>()
        ring(from, offer, packet.type.isVideo, nowMs, actions)
        return CallDecision(state, actions)
    }

    private fun ring(
        from: String,
        offer: CallOffer,
        video: Boolean,
        nowMs: Long,
        actions: MutableList<CallAction>,
    ) {
        peer = from
        callId = offer.callId
        isOutgoing = false
        isVideo = video
        sessionKey = offer.sessionKey
        nonceSalt = offer.nonceSalt
        peerWbAdpcm = offer.supportsWbAdpcm
        connectedAtMs = null
        dialedAtMs = null
        enter(CallState.RINGING, nowMs)
        actions.add(CallAction.StartRinging(incoming = true))
    }

    private fun onAccept(
        from: String,
        packet: CallPacket.Decoded,
        nowMs: Long,
        correlated: String?,
    ): CallDecision {
        // RACE 2. Only a call we are placing can be accepted.
        if (!isOutgoing || (state != CallState.RINGING && state != CallState.CONNECTING)) {
            return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        }
        if (!isCurrentPeer(from)) return CallDecision(state, refusal = CallRefusal.FOREIGN_CALL)
        if (correlated != null && callId != null && correlated != callId) {
            return CallDecision(state, refusal = CallRefusal.FOREIGN_CALL)
        }
        val last = lastAcceptAtMs
        if (last != null && nowMs - last < CallTimeouts.ACCEPT_DEDUP_MS) {
            return CallDecision(state, refusal = CallRefusal.DUPLICATE)
        }
        lastAcceptAtMs = nowMs
        // The accept's capability bytes are parsed and deliberately unused: this client
        // offers neither OshiCodec nor AAC-ELD, so no negotiation outcome changes. Parsing
        // it anyway keeps the codec exercised and makes an empty legacy accept a tested
        // case rather than a discovered one.
        // __WB_ADPCM_CODEC_2026_09_23__ except slot 4: WB-ADPCM is the one codec we share.
        peerWbAdpcm = CallAccept.decode(packet.payload).supportsWbAdpcm

        val actions = mutableListOf<CallAction>(CallAction.StopRinging)
        connect(nowMs, actions)
        return CallDecision(state, actions)
    }

    /**
     * RACE 3 — the one place this client is stricter than both phones. See the class doc.
     */
    private fun onDecline(from: String, nowMs: Long, correlated: String?): CallDecision {
        if (!isCurrentPeer(from)) return CallDecision(state, refusal = CallRefusal.FOREIGN_CALL)
        if (correlated != null && callId != null && correlated != callId) {
            return CallDecision(state, refusal = CallRefusal.FOREIGN_CALL)
        }
        // A decline is only meaningful for a call we are PLACING and that is not yet up.
        // Both phones apply it in any state, which is how a live call dies.
        if (state == CallState.IN_CALL) {
            return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        }
        if (!isOutgoing || (state != CallState.RINGING && state != CallState.CONNECTING)) {
            return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        }
        // __DECLINE_NO_DIAL_GRACE_2026_09_23__ NO post-dial grace here. iOS applies its 5 s
        // window to `callEnd` only; a `callDecline` ends the call at once
        // (`VoiceCallManager.swift:5603-5605`: `.callDecline` → `endCall(reason: .declined)`).
        // With the grace, a callee who declined within 5 s — the usual case for a decline —
        // was ignored, and the caller rang on for the full 45 s no-answer timeout. Found by
        // `CallScreenModelTest.incomingDecline`, the first test that declined at human speed.
        val actions = mutableListOf<CallAction>(CallAction.StopRinging)
        finish(CallEndReason.DECLINED, nowMs, actions, connected = false)
        return CallDecision(state, actions)
    }

    private fun onEnd(
        from: String,
        packet: CallPacket.Decoded,
        nowMs: Long,
        correlated: String?,
    ): CallDecision {
        if (state == CallState.IDLE || state == CallState.ENDED) {
            return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        }
        if (!isCurrentPeer(from)) return CallDecision(state, refusal = CallRefusal.FOREIGN_CALL)
        if (correlated != null && callId != null && correlated != callId) {
            return CallDecision(state, refusal = CallRefusal.FOREIGN_CALL)
        }

        // The two grace windows, both iOS's. See CallTimeouts.
        val connected = connectedAtMs
        if (connected != null && nowMs - connected < CallTimeouts.END_GRACE_AFTER_CONNECT_MS) {
            return CallDecision(state, refusal = CallRefusal.GRACE_PERIOD)
        }
        val dialed = dialedAtMs
        if (state != CallState.IN_CALL && dialed != null &&
            nowMs - dialed < CallTimeouts.END_GRACE_AFTER_DIAL_MS
        ) {
            return CallDecision(state, refusal = CallRefusal.GRACE_PERIOD)
        }

        // The body is a reason string, and it may be empty or unknown — both are legal.
        // It is recorded and never branched on, because Android discards it entirely
        // (`EnhancedCallManager.kt:2797-2800`) and a state machine that behaved
        // differently per reason would diverge from a peer that never sends one.
        val wire = CallEndReason.fromWire(String(packet.payload, Charsets.UTF_8))
            ?: CallEndReason.HUNG_UP
        // A `callEnd` from the CALLEE while our outgoing call is still RINGING is a refusal.
        // iOS sends `callDecline` only from its in-app overlay; Decline on the CallKit
        // screen (lock screen / banner) runs `endCall(reason: .hungUp)` and sends
        // `callEnd` (`VoiceCallManager.swift:4982-5002`). Measured with a real iPhone on
        // 2026-09-23: the history read "hung_up, not connected" for a decline.
        val reason = if (isOutgoing && state == CallState.RINGING && wire == CallEndReason.HUNG_UP) {
            CallEndReason.DECLINED
        } else wire
        val wasConnected = state == CallState.IN_CALL
        val actions = mutableListOf<CallAction>(CallAction.StopRinging)
        finish(reason, nowMs, actions, connected = wasConnected)
        return CallDecision(state, actions)
    }

    /**
     * 0x0A — another of OUR devices answered. Stop ringing, log nothing as missed.
     *
     * The echo guard matters: this packet is addressed to our own key, so we receive our
     * own (`EnhancedCallManager.kt:2934-2947`). Both clients compare the deviceId inside
     * the body and drop their own (`VoiceCallManager.swift:6497-6512`).
     */
    private fun onAnsweredElsewhere(nowMs: Long, correlated: String?): CallDecision {
        if (state != CallState.RINGING || isOutgoing) {
            return CallDecision(state, refusal = CallRefusal.WRONG_STATE)
        }
        if (correlated != null && callId != null && correlated != callId) {
            return CallDecision(state, refusal = CallRefusal.FOREIGN_CALL)
        }
        val actions = mutableListOf<CallAction>(CallAction.StopRinging)
        finish(CallEndReason.ANSWERED_ELSEWHERE, nowMs, actions, connected = false)
        return CallDecision(state, actions)
    }

    // ------------------------------------------------------------------ time

    /**
     * Advance the clock. Fires whichever watchdog is due, or does nothing.
     *
     * The caller drives this from its poll loop. Every timeout in [CallTimeouts] is
     * evaluated here and nowhere else, so a test can step to one millisecond either side
     * of a boundary and assert both answers — which is the only way to know a timeout is
     * the value it claims to be.
     */
    fun tick(nowMs: Long): CallDecision {
        val elapsed = nowMs - stateSinceMs
        return when (state) {
            CallState.CONNECTING ->
                if (elapsed >= CallTimeouts.CONNECTING_MS) {
                    endLocally(CallEndReason.NETWORK_ERROR, nowMs, connected = false)
                } else {
                    CallDecision(state)
                }

            CallState.RINGING -> {
                val limit = if (isOutgoing) CallTimeouts.CALLER_NO_ANSWER_MS else CallTimeouts.CALLEE_RING_MS
                if (elapsed >= limit) {
                    endLocally(CallEndReason.NO_ANSWER, nowMs, connected = false)
                } else {
                    CallDecision(state)
                }
            }

            CallState.ENDED ->
                if (elapsed >= CallTimeouts.ENDED_SETTLE_MS) {
                    resetCall()
                    enter(CallState.IDLE, nowMs)
                    CallDecision(state)
                } else {
                    CallDecision(state)
                }

            else -> CallDecision(state)
        }
    }

    /**
     * A watchdog firing ends the call locally AND tells the peer.
     *
     * Telling the peer is not optional: a caller that gives up at 45 s while the callee's
     * backstop is 55 s leaves a phone ringing for ten more seconds with nobody on the
     * other end. Both clients send here — iOS tags it `callMissed`/`callTimeout` on the
     * envelope so the SERVER can push a ring-dismissal, which is what actually silences a
     * locked iPhone.
     */
    private fun endLocally(reason: CallEndReason, nowMs: Long, connected: Boolean): CallDecision {
        val p = peer
        val id = callId
        val actions = mutableListOf<CallAction>(CallAction.StopRinging)
        if (p != null && id != null) {
            actions.add(
                CallAction.Send(
                    p, CallPacket.Type.CALL_END,
                    reason.wire.toByteArray(Charsets.UTF_8),
                    CallSignalType.forPacket(CallPacket.Type.CALL_END, reason),
                    id,
                ),
            )
        }
        finish(reason, nowMs, actions, connected)
        return CallDecision(state, actions)
    }

    // ------------------------------------------------------------------ internals

    private fun connect(nowMs: Long, actions: MutableList<CallAction>) {
        enter(CallState.IN_CALL, nowMs)
        connectedAtMs = nowMs
        val key = sessionKey
        val salt = nonceSalt
        if (key != null && salt != null) {
            actions.add(CallAction.StartMedia(peer!!, callId!!, key, salt, isOutgoing, peerWbAdpcm))
        }
    }

    private fun finish(
        reason: CallEndReason,
        nowMs: Long,
        actions: MutableList<CallAction>,
        connected: Boolean,
    ) {
        val p = peer
        val id = callId
        if (state == CallState.IN_CALL) actions.add(CallAction.StopMedia)
        if (p != null && id != null) {
            actions.add(CallAction.Log(p, id, reason, connected))
            endedCalls[id] = nowMs
        }
        enter(CallState.ENDED, nowMs)
    }

    private fun enter(next: CallState, nowMs: Long) {
        state = next
        stateSinceMs = nowMs
    }

    private fun resetCall() {
        peer = null
        callId = null
        isOutgoing = false
        isVideo = false
        sessionKey = null
        nonceSalt = null
        peerWbAdpcm = false
        connectedAtMs = null
        dialedAtMs = null
        lastAcceptAtMs = null
    }

    /**
     * Is [from] the peer of the current call?
     *
     * Through [BlockPolicy.normalizeKey], because the SAME peer arrives spelled base64 on
     * one transport and base64url on another — the relay envelope carries base64url
     * (`VPSClient.kt:1156-1157`, `publicKeyToBase64url`) while a stored contact address is
     * standard base64. A raw `==` here drops a legitimate accept as a foreign call, which
     * presents as "the call rang and never connected".
     *
     * This is NOT the C-MSG-4 situation. There, normalising an identity would weaken an
     * ownership check; here the sender has ALREADY been authenticated by the AEAD before
     * this function is reached, and the only question left is which of the two spellings
     * of that one authenticated identity this transport used.
     */
    private fun isCurrentPeer(from: String): Boolean {
        val p = peer ?: return false
        return BlockPolicy.normalizeKey(p) == BlockPolicy.normalizeKey(from)
    }

    private fun pruneEnded(nowMs: Long) {
        val it = endedCalls.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value >= CallTimeouts.ENDED_CALL_ID_TTL_MS) it.remove()
        }
    }

    private fun pruneSeenOffers(nowMs: Long) {
        val it = seenOffers.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value >= CallTimeouts.OFFER_DEDUP_MS) it.remove()
        }
        // iOS caps the same set at 32 (`seenOfferSignatures`, `:6172-6182`). An unbounded
        // map on a path anyone can POST to is a memory leak with a remote trigger.
        while (seenOffers.size > MAX_SEEN_OFFERS) {
            seenOffers.remove(seenOffers.keys.first())
        }
    }

    companion object {
        /** iOS's `seenOfferSignatures` cap (`VoiceCallManager.swift:6172-6182`). */
        const val MAX_SEEN_OFFERS = 32

        /**
         * The glare tie-break: true when [mine] keeps its outgoing call.
         *
         * iOS's `myKey < peerPublicKey` (`VoiceCallManager.swift:6257-6259`), fixed to
         * compare NORMALISED spellings — see RACE 1 for the encoding skew this repairs.
         * Total and antisymmetric, so exactly one of the two devices gets true, and
         * neither needs a round trip to find out.
         *
         * Equal keys mean a peer is calling itself, which no shipped client can produce.
         * It returns false so that the degenerate case yields rather than deadlocking two
         * winners.
         */
        fun glareWinner(mine: String, theirs: String): Boolean =
            BlockPolicy.normalizeKey(mine) < BlockPolicy.normalizeKey(theirs)
    }
}
