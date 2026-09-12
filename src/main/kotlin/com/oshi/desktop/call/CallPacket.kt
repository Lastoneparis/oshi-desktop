package com.oshi.desktop.call

/**
 * The OSHI call signalling packet — PARITY.md row 2.1, the byte layer.
 *
 * ============================================================ THE FORMAT
 *
 * ```
 * [type: UInt8][timestampMs: UInt64 big-endian][payload…]
 * ```
 *
 * Nine bytes of header and then a type-specific body. It is BINARY, not JSON, and it is
 * not SDP. `OSHI/VoiceCallManager.swift:13591-13602` builds it:
 *
 * ```swift
 * packet.append(type.rawValue)
 * let timestampMs = UInt64(Date().timeIntervalSince1970 * 1000)
 * var timestampBytes = timestampMs.bigEndian
 * packet.append(Data(bytes: &timestampBytes, count: 8))
 * packet.append(contentsOf: payload)
 * ```
 *
 * Android emits the identical nine bytes by hand, one shift per byte
 * (`EnhancedCallManager.kt:1391-1400`), and decodes it the same way at `:2542-2545`.
 *
 * ============================================================ THIS IS NOT WEBRTC
 *
 * Worth stating at the top of the first file of this row, because the ledger's own
 * description of row 2.1 was "WebRTC + a codec pipeline" and that premise is wrong.
 * NEITHER shipped client links libwebrtc: iOS's project file has no package reference of
 * any kind and `OSHI/StunClient.swift:12` says "No libwebrtc. Pure Network.framework.";
 * Android REMOVED `io.getstream:stream-webrtc-android` and left the reason in
 * `app/build.gradle.kts:221-235`. There is no SDP in either tree. So the desktop's
 * signalling is this packet, and matching it is what makes a desktop able to ring a
 * phone. See [com.oshi.desktop.call.media.CallMediaFrame] for the same argument about
 * media, and the WEBRTC block in `build.gradle.kts` for the dependency decision.
 *
 * ============================================================ THE TIMESTAMP IS EPOCH 2
 *
 * Unix MILLISECONDS. Not Apple-reference seconds, not Unix seconds — and this file is the
 * reason PARITY.md's "four live epochs" warning gets a fifth reading: the call path
 * carries THREE encodings of the same instant, one nesting level apart, exactly like row
 * 0.18's receipts:
 *
 *  1. **this header** — Unix millis, `UInt64` big-endian, binary. Epoch 2.
 *  2. **the outer JSON envelope's `timestamp`** — Unix SECONDS as a fractional Double
 *     (`VPSClient.kt:1167`, `System.currentTimeMillis() / 1000.0`). Epoch 3.
 *  3. **the IPFS last-resort fallback** — an ISO-8601 STRING
 *     (`VoiceCallManager.swift:13820`). Epoch 4.
 *
 * All three fields are called `timestamp`. PLAN.md §4.2's rule — never guess an epoch
 * from a field's name — is load-bearing here, and every conversion in this package goes
 * through [com.oshi.desktop.msg.WireClock] and nowhere else. See [CallSignalEnvelope] for
 * a live bug that this exact confusion appears to have caused on Android.
 *
 * ============================================================ THE LEGACY 2-BYTE FORM
 *
 * Builds older than the timestamp header emitted `[type][payload]` with no header at all.
 * iOS still accepts it, and ONLY for the two offer types
 * (`VoiceCallManager.swift:13605-13617`). [decode] reproduces that asymmetry rather than
 * being uniformly tolerant: accepting a headerless `callEnd` would mean reading eight
 * bytes of an end-reason string as a timestamp, which is not a tolerance, it is a
 * misparse. Tolerant where the shipped client is tolerant, strict where it is strict.
 */
object CallPacket {

    /** Fixed header length: one type byte plus eight timestamp bytes. */
    const val HEADER_SIZE = 9

    /**
     * The packet type byte — `CallPacketType` (`OSHI/VoiceCallManager.swift:1951-1975`),
     * mirrored value-for-value by Android's constants (`EnhancedCallManager.kt:180-243`).
     *
     * The four this row's state machine acts on are [CALL_REQUEST], [CALL_ACCEPT],
     * [CALL_DECLINE] and [CALL_END]. The rest are carried for the same reason
     * [com.oshi.desktop.msg.ControlPrefix] carries its whole catalog: a partial list is
     * how you ship a client that treats an unknown-but-legitimate packet as garbage.
     */
    enum class Type(val code: Int) {
        /** 0x01 — the voice offer. Body: session key, salt, callId, capability bytes. */
        CALL_REQUEST(0x01),

        /** 0x02 — the voice answer. Body: capability bytes only, NO callId. */
        CALL_ACCEPT(0x02),

        /** 0x03 — decline. Body is EMPTY on iOS; Android sends the same empty body. */
        CALL_DECLINE(0x03),

        /** 0x04 — hang up. Body: a UTF-8 end-reason string. See [CallEndReason]. */
        CALL_END(0x04),

        /** 0x05 — AAC-ELD audio. Media, not signalling. */
        AUDIO_DATA(0x05),

        /** 0x06 — in-call keepalive. */
        KEEP_ALIVE(0x06),

        /** 0x07 — declared on both platforms and never sent by either. */
        KEY_EXCHANGE(0x07),

        /** 0x08 — the video offer. Same body shape as [CALL_REQUEST]. */
        VIDEO_CALL_REQUEST(0x08),

        /** 0x09 — the video answer. Same body shape as [CALL_ACCEPT]. */
        VIDEO_CALL_ACCEPT(0x09),

        /** 0x0A — multi-device: "I picked this up elsewhere, stop ringing." */
        CALL_ANSWERED_ELSEWHERE(0x0A),

        /** 0x0B — video: send me a keyframe (PLI). Empty body. */
        REQUEST_KEYFRAME(0x0B),

        /** 0x0C / 0x0D — video pause / resume. Empty bodies. */
        VIDEO_PAUSED(0x0C),
        VIDEO_RESUMED(0x0D),

        /** 0x0E — voice→video upgrade negotiation. Body: one sub-opcode byte. */
        VIDEO_UPGRADE(0x0E),

        /** 0x0F — resync request. Overloaded on Android: `stopVideo` on the media channel. */
        RESYNC_REQUEST(0x0F),

        /** 0x15 / 0x16 / 0x17 — raw PCM 48 kHz, OshiCodec, raw PCM 16 kHz. Media. */
        RAW_PCM_48K(0x15),
        OSHI_CODEC(0x16),
        RAW_PCM_16K(0x17),

        /** 0x30 — the ICE candidate exchange. Body: the bespoke binary TLV, NOT SDP. */
        ICE_CANDIDATE_EXCHANGE(0x30),

        /** 0x31 / 0x32 — hole-punch ping/pong. Raw UDP socket only, never signalling. */
        HOLE_PUNCH_PING(0x31),
        HOLE_PUNCH_PONG(0x32);

        /** True for the two packet types that carry an offer body. */
        val isOffer: Boolean get() = this == CALL_REQUEST || this == VIDEO_CALL_REQUEST

        /** True for the two packet types that carry an accept body. */
        val isAccept: Boolean get() = this == CALL_ACCEPT || this == VIDEO_CALL_ACCEPT

        /** True when this offer/accept pair is a VIDEO call rather than voice. */
        val isVideo: Boolean get() = this == VIDEO_CALL_REQUEST || this == VIDEO_CALL_ACCEPT

        companion object {
            private val byCode = entries.associateBy { it.code }

            /**
             * The type for [code], or null.
             *
             * Null rather than a throw or a default: an unknown type byte is a packet from
             * a newer build, and the shipped clients ignore it. Android's `fromJson`
             * defaults a MISSING envelope `type` to `CALL_REQUEST` (`VPSClient.kt:2470-2476`)
             * — that is a different question (a missing OUTER hint, resolved from byte 0 of
             * the decrypted body) and must not be copied into this function, which is
             * reading byte 0 itself. Defaulting here would turn every unrecognised packet
             * into an incoming call.
             */
            fun fromCode(code: Int): Type? = byCode[code and 0xFF]
        }
    }

    /** A decoded packet: the type byte, the header instant, and the untouched body. */
    data class Decoded(
        val type: Type,
        /** Unix MILLIS off the header. Null when the packet used the legacy 2-byte form. */
        val timestampMs: Long?,
        val payload: ByteArray,
    ) {
        /** True when this arrived in the pre-timestamp `[type][payload]` shape. */
        val isLegacyForm: Boolean get() = timestampMs == null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return type == other.type && timestampMs == other.timestampMs &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int =
            (type.hashCode() * 31 + (timestampMs?.hashCode() ?: 0)) * 31 + payload.contentHashCode()
    }

    /**
     * Build `[type][timestampMs BE8][payload]`.
     *
     * [unixMillis] is passed rather than read from the clock, for the reason every date in
     * this project is: a packet builder that reads `System.currentTimeMillis()` cannot be
     * tested against a byte shape. The bound is the same plausibility window
     * [com.oshi.desktop.msg.WireClock.toAppleSeconds] enforces, and it is enforced for the
     * same reason — we control our own clock, so an implausible instant here is a bug in
     * this process and should stop at the emitter.
     */
    fun encode(type: Type, unixMillis: Long, payload: ByteArray = ByteArray(0)): ByteArray {
        require(unixMillis in MIN_PLAUSIBLE_MS..MAX_PLAUSIBLE_MS) {
            "unixMillis=$unixMillis is outside [2000-01-01, 2100-01-01]. The call packet " +
                "header is Unix MILLISECONDS (epoch 2), not Apple-epoch seconds and not " +
                "Unix seconds — see the class doc."
        }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = type.code.toByte()
        for (i in 0 until 8) {
            out[1 + i] = ((unixMillis ushr (56 - i * 8)) and 0xFF).toByte()
        }
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /**
     * Parse a packet, or null when [bytes] is not one.
     *
     * Null rather than throwing: this is fed by a network read of attacker-chosen bytes,
     * and "that was not a call packet" is an ordinary outcome, not an exceptional one.
     *
     * **The legacy tolerance is offer-only**, matching `VoiceCallManager.swift:13605-13617`
     * — see THE LEGACY 2-BYTE FORM in the class doc for why widening it would be a
     * misparse rather than a kindness.
     */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.isEmpty()) return null
        val type = Type.fromCode(bytes[0].toInt()) ?: return null

        if (bytes.size >= HEADER_SIZE) {
            var ts = 0L
            for (i in 1..8) ts = (ts shl 8) or (bytes[i].toLong() and 0xFF)
            // A plausible header instant is what separates the two encodings. An offer
            // from an ancient build whose body happens to start with eight bytes that
            // read as a sane millisecond epoch is indistinguishable from a modern one —
            // that ambiguity is in the shipped format, not introduced here, and the
            // modern reading is the one both phones take first.
            if (ts in MIN_PLAUSIBLE_MS..MAX_PLAUSIBLE_MS) {
                return Decoded(type, ts, bytes.copyOfRange(HEADER_SIZE, bytes.size))
            }
        }

        if (type.isOffer) {
            return Decoded(type, null, bytes.copyOfRange(1, bytes.size))
        }
        return null
    }

    /**
     * Is this packet too old to act on?
     *
     * **The two windows are different on purpose, and both are the shipped ones.** iOS
     * uses `maxCallSignalAgeSeconds = 30.0` for an OFFER
     * (`VoiceCallManager.swift:3589`) and `max(that, 60.0)` for the terminal types
     * (`:13640`). The asymmetry is right: a 45-second-old offer would ring for a call the
     * caller already gave up on, whereas a 45-second-old `callEnd` still needs to be
     * honoured or the callee rings forever.
     *
     * A packet with no header instant ([Decoded.isLegacyForm]) is never stale — there is
     * nothing to measure, and refusing it would drop every legacy offer.
     */
    fun isStale(packet: Decoded, nowMs: Long): Boolean {
        val ts = packet.timestampMs ?: return false
        val ageMs = nowMs - ts
        if (ageMs <= 0) return false
        val limit = if (isTerminal(packet.type)) TERMINAL_MAX_AGE_MS else OFFER_MAX_AGE_MS
        return ageMs > limit
    }

    /** The types that END a call rather than starting or advancing one. */
    fun isTerminal(type: Type): Boolean =
        type == Type.CALL_DECLINE || type == Type.CALL_END || type == Type.CALL_ANSWERED_ELSEWHERE

    /** iOS `maxCallSignalAgeSeconds = 30.0` (`VoiceCallManager.swift:3589`). */
    const val OFFER_MAX_AGE_MS = 30_000L

    /** iOS `max(maxCallSignalAgeSeconds, 60.0)` (`VoiceCallManager.swift:13640`). */
    const val TERMINAL_MAX_AGE_MS = 60_000L

    /** 2000-01-01T00:00:00Z, matching [com.oshi.desktop.msg.WireClock]'s window. */
    private const val MIN_PLAUSIBLE_MS = 946_684_800_000L

    /** 2100-01-01T00:00:00Z. */
    private const val MAX_PLAUSIBLE_MS = 4_102_444_800_000L
}

/**
 * Why a call ended — the UTF-8 body of a `callEnd` (0x04) packet.
 *
 * ============================================================ AN iOS/ANDROID DISAGREEMENT
 *
 * iOS's `CallEndReason` (`OSHI/VoiceCallManager.swift:1872-1893`) has SEVEN raw values;
 * Android's `CallEndReason` enum has EIGHT names but only six wire spellings, because
 * `CallEndReasonWire.toWire()` (`CallEndReasonWire.kt:49-58`) folds two of them:
 * `PEER_ENDED → "hung_up"` and `MISSED → "no_answer"`.
 *
 * The spelling that exists on iOS and has no Android emitter is **`peer_disconnected`**.
 * So this client ACCEPTS all seven and EMITS the six Android emits — accept-both-emit-one,
 * the same rule [com.oshi.desktop.msg.ControlPrefix] applies to duplicate sentinels.
 *
 * ============================================================ AND ONE PLACE BOTH IGNORE IT
 *
 * Android's `handleCallEnded()` discards the reason entirely
 * (`EnhancedCallManager.kt:2797-2800`). iOS parses it (`:7089-7093`) and uses it to pick
 * the call-log line. The reason therefore affects what a user is TOLD, never what the
 * state machine does — which is why [CallStateMachine] carries it as data and never
 * branches on it.
 */
enum class CallEndReason(val wire: String) {
    HUNG_UP("hung_up"),
    DECLINED("declined"),
    NO_ANSWER("no_answer"),
    NETWORK_ERROR("network_error"),

    /** iOS-only spelling. Accepted, never emitted — see the class doc. */
    PEER_DISCONNECTED("peer_disconnected"),

    CONNECTION_LOST("connection_lost"),
    ANSWERED_ELSEWHERE("answered_elsewhere");

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /**
         * Parse an end-reason body, or null.
         *
         * A `callEnd` with an EMPTY body is legal and common — iOS's own decline sends an
         * empty payload and several end paths send no reason at all — so the caller must
         * treat null as "unspecified", never as "malformed". [CallStateMachine] does.
         */
        fun fromWire(value: String): CallEndReason? = byWire[value.trim()]
    }
}
