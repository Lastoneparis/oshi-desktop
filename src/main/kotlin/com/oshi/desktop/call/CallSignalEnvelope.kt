package com.oshi.desktop.call

import com.oshi.desktop.msg.WireClock
import com.oshi.messenger.network.v2.OSHICryptoV2
import java.util.Base64
import org.json.JSONObject

/**
 * The outer JSON envelope of a call signal — `POST /voip/signal` — and the reason this
 * row has a privacy note.
 *
 * ============================================================ IS CALL SIGNALLING E2E ENCRYPTED?
 *
 * **The BODY is. The METADATA is not, and it is worse than the relay's.**
 *
 * The `signal` field is an ECDH-sealed [CallPacket] (see [CallSignalCrypto]) and the
 * server never opens it — `call_server.js:650` copies it verbatim into the recipient's
 * WebSocket frame, `:678` into the pending queue, and the file contains no SDP, offer,
 * answer or ICE parsing of any kind. That much is genuinely end-to-end.
 *
 * Everything wrapped AROUND it is cleartext to the server, and it is enough to build the
 * call graph:
 *
 *   | field | what the server learns |
 *   |---|---|
 *   | `sender`, `recipient` | **who called whom** — used as Map keys, `call_server.js:638` |
 *   | `callId` | correlates every packet of one call |
 *   | `type` | ring / answer / decline / end — the server branches on it, `:666-667` |
 *   | `isVideoCall` | voice or video |
 *   | `callerName` | **the caller's display name, in the clear** (`:790`, `:817`) |
 *   | `senderDeviceId` | which of your devices dialled |
 *   | `privacy_mode` | your transport-privacy preference |
 *
 * PARITY.md row 0.26 already recorded that the bot lane "hands the relay the membership
 * graph V2 spends N pairwise ciphertexts withholding". **Calls do the same thing for the
 * social graph**, and they do it on a path that is polled every second. Three things make
 * it concrete rather than theoretical:
 *
 *  1. **`POST /register` stores the pair explicitly.** `activeCalls.set(callId, {
 *     participants: [participant1, participant2], createdAt })` — `call_server.js:1015`.
 *     That is a literal who-called-whom table, held up to an hour (`:1040`).
 *  2. **journald is a de facto call log.** `console.log('[SIGNAL] POST from ' +
 *     ns.substring(0,12) + ' to ' + nr.substring(0,12) + …)` (`:645`). Twelve base64
 *     characters is ~72 bits — uniquely identifying. Nothing is written to a file or a
 *     database (`call_server.js` has no `fs` reference at all), but the journal has it.
 *     And `[UDP-REG]` logs the client's real `IP:port` (`:1069`) on a path nginx's
 *     `X-Real-IP "0.0.0.0"` masking never touches.
 *  3. **The VoIP/FCM wake push leaks the same set to a SECOND service** before any signal
 *     is sent: `{recipientPublicKey, callerPublicKey, callerName, callId, isVideoCall}`
 *     in cleartext to `push_service.js` (`VPSClient.kt:1195-1200`,
 *     `VoIPPushManager.swift:1819-1831`).
 *
 * And the signalling seal is **NOT the Double Ratchet**: it is a STATIC-static X25519
 * ECDH, so there is no forward secrecy on call signalling at all. See [CallSignalCrypto].
 * `AUDIT_VOIP_TELECOM.md:78` claims "Double Ratchet forward secrecy" for calls; that
 * claim is not supported by the shipped code and this file is not the place to repeat it.
 *
 * The desktop cannot fix any of this unilaterally — the fields are what the server routes
 * on, and omitting them means the call does not connect. What it can do is not make it
 * worse: [encode] sends **no `callerName`**. See CALLERNAME below.
 *
 * ============================================================ AUTHENTICATION: NONE, IN PRACTICE
 *
 * The server has a complete Ed25519 request-signing implementation and it is switched off
 * — `call_server.js:65`, `const SIGNATURE_REQUIRED = false;` with the comment "Grace
 * period: accept unsigned requests from old clients". With the flag false, EVERY failure
 * branch of `verifySignature` returns `{valid:true}`, including the branch that catches a
 * cryptographically INVALID signature (`:131-135`, reason `'invalid-sig-grace'`).
 *
 * So: anyone can POST a signal claiming any `sender`; `GET /signals/<anyPublicKey>` has
 * no auth check whatsoever and is a destructive read; and `GET /turn-creds` (`:598`) mints
 * a working one-hour TURN credential to any anonymous GET. This client still signs
 * (`DesktopV2Signer`), because signing costs nothing and the flag may flip.
 *
 * ============================================================ CALLERNAME
 *
 * Both phones put the user's display name in this envelope. It is the field the server
 * forwards to APNs so a locked iPhone can show who is calling, so it is not gratuitous —
 * but it is also the only field here that is a HUMAN IDENTIFIER rather than a routing key.
 *
 * The desktop omits it, and the reason is that the desktop cannot benefit from it: there
 * is no push path on this platform (PARITY.md row 2.3 — a desktop client POLLS), so the
 * name would be uploaded to serve a wake-screen that does not exist. Sending it anyway
 * would be leaking an identifier for a feature this client does not have. The peer still
 * resolves a name locally from the sender key, exactly as it does for messages.
 *
 * This is a deliberate divergence from both shipped clients and it is one-directional —
 * we omit a field, we never add one — so no peer parse can break on it.
 *
 * ============================================================ THE `timestamp` FIELD IS EPOCH 3
 *
 * `System.currentTimeMillis() / 1000.0` — Unix SECONDS as a fractional Double
 * (`VPSClient.kt:1167`). Not the millis of the packet header nine bytes deeper in the same
 * transmission. That is epochs 2 and 3 one nesting level apart, the exact shape row 0.18
 * documents for receipts.
 *
 * **And Android appears to have got it backwards, in the shipped binary.** The emitter
 * writes seconds; the parser reads `json.optLong("timestamp", System.currentTimeMillis())`
 * (`VPSClient.kt:2482`) into a field the staleness gate treats as millis
 * (`EnhancedCallManager.kt:2131-2134`, drop if `now - timestamp > 60000`). A seconds value
 * of ~1.7e9 gives a computed age of ~1.7e12 ms, so **every signal polled over HTTP is
 * discarded as stale**. Note what saves it: the WebSocket and mesh legs re-stamp locally
 * (`VPSClient.kt:1841`, `EnhancedCallManager.kt:2444`), so the bug is invisible whenever
 * the WebSocket is up and bites only on the polling fallback — which is exactly the path
 * a desktop client lives on.
 *
 * This client therefore **does not trust the envelope timestamp for staleness**. It reads
 * the header instant inside the decrypted packet, which is unambiguously millis and is
 * covered by the AEAD. [CallPacket.isStale] is the only staleness question in this
 * package, and it takes a [CallPacket.Decoded]. The envelope's `timestamp` is emitted for
 * compatibility and never consumed.
 */
data class CallSignalEnvelope(
    /** Our public key, base64url. */
    val sender: String,
    /** The peer's public key, base64url. */
    val recipient: String,
    /** The sealed [CallPacket], base64 (standard alphabet, as both clients emit). */
    val signalBase64: String,
    val callId: String,
    /** Lowercased [CallSignalType] name, or null when the sender did not hint one. */
    val type: String?,
    val isVideoCall: Boolean = false,
    /** Which of our devices sent this, so the server can drop our own echo. */
    val senderDeviceId: String? = null,
    /** `"direct"` (lower latency) or `"relay"` (IP hidden). Default matches both clients. */
    val privacyMode: String = "direct",
) {

    /**
     * Emit the envelope JSON.
     *
     * Key order is Android's `sendCallSignal` order (`VPSClient.kt:1158-1173`) — the same
     * diffability argument [com.oshi.desktop.msg.ControlJson] makes, for the same reason:
     * a byte comparison against a real Android payload has to stay meaningful.
     *
     * Both `sender`/`senderPublicKey` and `recipient`/`recipientPublicKey` are written,
     * and `payload`/`signal` are written twice with the same value. That duplication is
     * not tidiness that can be removed: iOS reads `sender` and `signal`, Android reads
     * `sender` OR `senderPublicKey` and `payload` OR `signal`, and the shipped Android
     * emitter carries all four with the comment `// 🔧 iOS compatibility`. Dropping either
     * spelling makes this client unreadable to one of the two platforms.
     *
     * [nowMs] is Unix MILLIS and is converted here to the envelope's Unix SECONDS — the
     * one place in this package that touches epoch 3, and it goes through [WireClock].
     */
    fun encode(nowMs: Long): String {
        val json = StringBuilder("{")
        fun put(key: String, raw: String) {
            if (json.length > 1) json.append(',')
            json.append('"').append(OSHICryptoV2.jsonEscape(key)).append("\":").append(raw)
        }
        fun str(key: String, value: String) =
            put(key, "\"" + OSHICryptoV2.jsonEscape(value) + "\"")

        str("sender", sender)
        str("senderPublicKey", sender)
        str("recipient", recipient)
        str("recipientPublicKey", recipient)
        str("callId", callId)
        if (type != null) str("type", type)
        str("payload", signalBase64)
        str("signal", signalBase64)
        put("timestamp", WireClock.jsonNumber(WireClock.toUnixSecondsDouble(nowMs)))
        if (senderDeviceId != null) str("senderDeviceId", senderDeviceId)
        put("isVideoCall", isVideoCall.toString())
        str("privacy_mode", privacyMode)
        // NO callerName. See CALLERNAME in the class doc.
        json.append('}')
        return json.toString()
    }

    /** The sealed packet bytes, or null when the base64 is unusable. */
    fun signalBytes(): ByteArray? =
        runCatching { Base64.getDecoder().decode(signalBase64) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(signalBase64) }.getOrNull()

    companion object {

        /**
         * Parse an envelope off the wire, or null.
         *
         * Accepts every field-name variant the two clients emit, exactly as Android's
         * `CallSignal.fromJson` does (`VPSClient.kt:2464-2485`) — `sender` ∥
         * `senderPublicKey` ∥ `senderAddress`, `payload` ∥ `signal`.
         *
         * **One shipped behaviour deliberately NOT copied.** Android defaults a missing
         * `type` to `CALL_REQUEST` (`VPSClient.kt:2470-2476`). Here a missing `type` stays
         * null. The Android default is a workaround for iOS omitting the field, and it is
         * safe there only because the real type is re-resolved from byte 0 of the
         * DECRYPTED body a moment later. Keeping the default without keeping that
         * re-resolution would mean every envelope with no type hint is treated as an
         * incoming call — an unauthenticated ring on a server with no authentication at
         * all. The type that this package acts on always comes from the sealed packet's
         * own byte 0; the envelope hint is routing metadata and never a decision.
         */
        fun decode(text: String): CallSignalEnvelope? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            fun s(vararg keys: String): String? {
                for (k in keys) {
                    if (o.has(k) && !o.isNull(k)) {
                        val v = o.opt(k) as? String
                        if (!v.isNullOrEmpty()) return v
                    }
                }
                return null
            }
            val sender = s("sender", "senderPublicKey", "senderAddress") ?: return null
            val signal = s("payload", "signal", "encryptedContent") ?: return null
            return CallSignalEnvelope(
                sender = sender,
                recipient = s("recipient", "recipientPublicKey", "recipientAddress") ?: "",
                signalBase64 = signal,
                callId = s("callId") ?: "",
                type = s("type")?.lowercase(),
                isVideoCall = o.optBoolean("isVideoCall", false),
                senderDeviceId = s("senderDeviceId"),
                privacyMode = s("privacy_mode") ?: "direct",
            )
        }
    }
}

/**
 * The `type` hint on the outer envelope.
 *
 * TWO VOCABULARIES ARE LIVE, and they are not the same list. This is the kind of
 * disagreement PLAN.md's rules exist for, so both are here and the union is accepted.
 *
 *  - **Android** emits `CallSignalType.name.lowercase()` (`VPSClient.kt:1164`, enum at
 *    `:2503-2512`): `offer`, `answer`, `ice_candidate`, `call_request`, `call_accept`,
 *    `call_reject`, `call_end`, `audio_data`. Of these, `offer` and `answer` are declared
 *    and NEVER sent — `grep CallSignalType.OFFER` finds no construction site.
 *  - **iOS** emits camelCase strings, and only sometimes: `callEnd`, `callDeclined`,
 *    `callMissed`, `callTimeout`, `callCancelled` — the set the SERVER branches on
 *    (`call_server.js:666-667`, `:858-866`), plus `callRequest`/`callOffer`/
 *    `videoCallRequest` on the offer side. `declineIncomingCall` sends NO type at all
 *    (`VoiceCallManager.swift:7576`), which is the hole that disables iOS's own cross-call
 *    terminal guard — see [CallAccept]'s doc.
 *
 * The server's own terminal set is the authority on which hints stop a ring, and it is
 * iOS's spelling: `["callEnd","callDeclined","callMissed","callTimeout","callCancelled"]`.
 * This client EMITS the iOS spelling (it is the one the server acts on) and ACCEPTS both.
 */
object CallSignalType {
    const val CALL_REQUEST = "callRequest"
    const val CALL_ACCEPT = "callAccept"
    const val CALL_DECLINED = "callDeclined"
    const val CALL_END = "callEnd"
    const val CALL_MISSED = "callMissed"
    const val CALL_TIMEOUT = "callTimeout"
    const val CALL_CANCELLED = "callCancelled"
    const val ICE_CANDIDATE = "ice_candidate"

    /**
     * The hints the SERVER treats as ending a ring (`call_server.js:666-667`).
     *
     * Lowercased for comparison because Android lowercases everything it emits and iOS
     * does not — matching on the raw spelling would silently accept only half the traffic.
     */
    private val terminal = setOf(
        "callend", "calldeclined", "callmissed", "calltimeout", "callcancelled",
        "call_end", "call_reject",
    )

    fun isTerminal(hint: String?): Boolean = hint != null && hint.lowercase() in terminal

    /** The envelope hint this client puts on a packet of the given type. */
    fun forPacket(type: CallPacket.Type, endReason: CallEndReason? = null): String? = when {
        type.isOffer -> CALL_REQUEST
        type.isAccept -> CALL_ACCEPT
        type == CallPacket.Type.CALL_DECLINE -> CALL_DECLINED
        type == CallPacket.Type.CALL_END ->
            if (endReason == CallEndReason.NO_ANSWER) CALL_MISSED else CALL_END
        type == CallPacket.Type.ICE_CANDIDATE_EXCHANGE -> ICE_CANDIDATE
        else -> null
    }
}
