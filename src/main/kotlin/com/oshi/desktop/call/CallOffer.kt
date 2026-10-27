package com.oshi.desktop.call

import java.nio.charset.StandardCharsets

/**
 * The body of a `callRequest` (0x01) / `videoCallRequest` (0x08) packet — the OFFER.
 *
 * ```
 * [sessionKey: 32][nonceSalt: 4][callId: UTF-8, variable][cap0: 1][cap1: 1][cap2: 1]
 * ```
 *
 * `OSHI/VoiceCallManager.swift:5936-5961` builds it; Android builds the same bytes at
 * `EnhancedCallManager.kt:1385-1417`, parses at `:2530-2600`.
 *
 * The offer is where the CALL'S MEDIA KEY LIVES: the caller mints a random 32-byte
 * AES-256 session key and a 4-byte nonce salt, and hands both to the callee inside the
 * ECDH-sealed signalling packet. Every media frame for the rest of the call is encrypted
 * under that key. See [com.oshi.desktop.call.media.CallMediaFrame].
 *
 * ============================================================ THE PEEL, AND WHY IT WORKS
 *
 * The callId is a variable-length UTF-8 string with no length prefix, and the capability
 * bytes come AFTER it. Nothing in the format says where one ends and the other begins.
 * Both platforms recover the boundary with the same trick, and both wrote down the
 * invariant it rests on:
 *
 *   > `// Every capability byte MUST stay < 0x2D so the length-agnostic peel in`
 *   > `// peelOfferCapabilities() can separate it from the ASCII callId.`
 *   > — `OSHI/VoiceCallManager.swift:5952-5954`
 *
 * A UUID string is `0-9`, `a-f`/`A-F` and `-`. The smallest byte any of those can be is
 * `'-'` = 0x2D. So: strip trailing bytes below 0x2D, at most three, and whatever is left
 * is the callId. Android states the same rule and the same bound in
 * `CallOfferCapabilities.kt:12-24` — *"INVARIANT for anyone adding a capability: the flag
 * value MUST stay < 0x2D"*.
 *
 * **This is load-bearing far beyond the capability bytes.** The callId is hashed into the
 * P2P connection id (`deriveP2PCallId`, SHA-256's first 8 bytes,
 * `VoiceCallManager.swift:12967-12980`). Peel one byte too few and the callId string
 * changes, the SHA-256 changes, and every hole-punch packet is dropped as `cid_mismatch`
 * — a call that signals perfectly and then has no media. iOS left a comment about exactly
 * this at `EnhancedCallManager.kt:2580-2588`.
 *
 * ============================================================ AN iOS/ANDROID DISAGREEMENT
 *
 * **iOS writes THREE capability bytes; Android writes TWO.**
 *
 *   - iOS: `[oshiCodec][pcm16k][appleCodec]` — `VoiceCallManager.swift:5955-5960`, with
 *     `APPLE_CODEC_CAP_FLAG = 0x04` at `:3163`.
 *   - Android: `[oshiCodec][pcm16k]` — `EnhancedCallManager.kt:1413-1417`. There is no
 *     Apple-codec flag, because there is no AAC-ELD encoder on that side.
 *
 * The peel absorbs the difference for free, which is presumably why it was built this way:
 * a two-byte tail and a three-byte tail both strip cleanly. This client EMITS two
 * capability bytes (it has neither OshiCodec nor AAC-ELD, so both are 0x00 anyway) and
 * PARSES up to three. Emitting a third zero would be harmless and is still not done,
 * because "absent" and "present and false" are different claims and the desktop's claim
 * is absence.
 *
 * The stricter side is taken on the LIMIT: [MAX_CAP_BYTES] is 3, iOS's count, not
 * Android's 8 (`CallOfferCapabilities.kt:38-56` strips up to eight). Eight is not a
 * tolerance, it is eight bytes of a hypothetical future callId that a peel could eat.
 */
data class CallOffer(
    /** AES-256 media key for this call. 32 bytes. Never logged, never persisted. */
    val sessionKey: ByteArray,
    /** 4-byte directional nonce salt. See [com.oshi.desktop.call.media.CallMediaFrame]. */
    val nonceSalt: ByteArray,
    /** The caller's UUID string. The callee adopts it verbatim. */
    val callId: String,
    /** Peer supports the in-house `OshiCodec` (0x01). */
    val supportsOshiCodec: Boolean = false,
    /** Peer supports raw PCM at 16 kHz (0x02). */
    val supports16kPcm: Boolean = false,
    /** Peer supports AAC-ELD (0x04). iOS only — Android never sets it. */
    val supportsAppleCodec: Boolean = false,
    /** Slot 3: peer can do video (0x08). Desktop answers upgrades watch-only (VideoControl). */
    val supportsVideo: Boolean = false,
    /** __WB_ADPCM_CODEC_2026_09_23__ slot 4: wideband IMA-ADPCM 0x18 (flag 0x10). */
    val supportsWbAdpcm: Boolean = false,
    /** __OPUS_CODEC_2026_09_23__ slot 5: Opus 0x19 (flag 0x20). */
    val supportsOpus: Boolean = false,
) {
    init {
        require(sessionKey.size == SESSION_KEY_SIZE) {
            "session key must be $SESSION_KEY_SIZE bytes, got ${sessionKey.size}"
        }
        require(nonceSalt.size == NONCE_SALT_SIZE) {
            "nonce salt must be $NONCE_SALT_SIZE bytes, got ${nonceSalt.size}"
        }
    }

    /**
     * Serialize the offer body.
     *
     * Two capability bytes, not three — see the DISAGREEMENT section. The callId is
     * written as UTF-8 with no terminator and no length, which is what makes [decode]'s
     * peel necessary.
     */
    fun encode(): ByteArray {
        val id = callId.toByteArray(StandardCharsets.UTF_8)
        require(id.isNotEmpty()) { "a call offer with no callId cannot be answered" }
        require(id.all { (it.toInt() and 0xFF) >= PEEL_FLOOR }) {
            "callId '$callId' contains a byte below 0x2D, which the capability peel would " +
                "strip as a flag. Call ids are UUID strings on both platforms."
        }
        // __WB_ADPCM_CODEC_2026_09_23__ five slots now, as iOS/Android write them:
        // [oshi][16k][apple][video][wbAdpcm]. Slot 4 is the only way a phone learns we
        // decode 0x18, so the tail can no longer stop at two bytes.
        // __OPUS_CODEC_2026_09_23__ and a sixth: [opus].
        val out = ByteArray(SESSION_KEY_SIZE + NONCE_SALT_SIZE + id.size + 6)
        sessionKey.copyInto(out, 0)
        nonceSalt.copyInto(out, SESSION_KEY_SIZE)
        id.copyInto(out, HEADER_SIZE)
        out[HEADER_SIZE + id.size] = if (supportsOshiCodec) CAP_OSHI_CODEC else 0x00
        out[HEADER_SIZE + id.size + 1] = if (supports16kPcm) CAP_PCM_16K else 0x00
        out[HEADER_SIZE + id.size + 2] = if (supportsAppleCodec) CAP_APPLE_CODEC else 0x00
        out[HEADER_SIZE + id.size + 3] = if (supportsVideo) CAP_VIDEO else 0x00
        out[HEADER_SIZE + id.size + 4] = if (supportsWbAdpcm) CAP_WB_ADPCM else 0x00
        out[HEADER_SIZE + id.size + 5] = if (supportsOpus) CAP_OPUS else 0x00
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallOffer) return false
        return sessionKey.contentEquals(other.sessionKey) &&
            nonceSalt.contentEquals(other.nonceSalt) &&
            callId == other.callId &&
            supportsOshiCodec == other.supportsOshiCodec &&
            supports16kPcm == other.supports16kPcm &&
            supportsAppleCodec == other.supportsAppleCodec &&
            supportsVideo == other.supportsVideo &&
            supportsWbAdpcm == other.supportsWbAdpcm &&
            supportsOpus == other.supportsOpus
    }

    override fun hashCode(): Int {
        var r = sessionKey.contentHashCode()
        r = 31 * r + nonceSalt.contentHashCode()
        r = 31 * r + callId.hashCode()
        return r
    }

    companion object {
        const val SESSION_KEY_SIZE = 32
        const val NONCE_SALT_SIZE = 4

        /** Where the callId starts: 32 + 4. iOS and Android both hard-code 36 / 45. */
        const val HEADER_SIZE = SESSION_KEY_SIZE + NONCE_SALT_SIZE

        /** `OSHI_CODEC_CAP_FLAG` — `VoiceCallManager.swift:3087`. */
        const val CAP_OSHI_CODEC: Byte = 0x01

        /** `RAW_PCM_16K_CAP_FLAG` — `VoiceCallManager.swift:3098`. */
        const val CAP_PCM_16K: Byte = 0x02

        /** `APPLE_CODEC_CAP_FLAG` — `VoiceCallManager.swift:3163`. iOS only. */
        const val CAP_APPLE_CODEC: Byte = 0x04

        /** `VIDEO_CAP_FLAG` — slot 3. */
        const val CAP_VIDEO: Byte = 0x08

        /** __WB_ADPCM_CODEC_2026_09_23__ slot 4 — [com.oshi.desktop.call.media.WbAdpcmCodec]. */
        const val CAP_WB_ADPCM: Byte = 0x10

        /** __OPUS_CODEC_2026_09_23__ slot 5 — [com.oshi.desktop.call.media.OpusCallEncoder]. */
        const val CAP_OPUS: Byte = 0x20

        /**
         * The peel floor: `'-'`, the lowest byte a UUID string can contain.
         *
         * Every capability flag must stay strictly below this. Both shipped clients say so
         * in a comment; this constant is the place the desktop says it in code, and
         * [encode] asserts it from the other direction.
         */
        const val PEEL_FLOOR = 0x2D

        /**
         * iOS and Android both strip up to EIGHT now (`peelOfferCapabilities`,
         * `CallOfferCapabilities.MAX_CAP_BYTES`). Three was wrong since the phones added
         * the video (4th) byte: the leftover cap byte only survived because the sanitize
         * below strips NULs — and the slots were read shifted. Five flags exist now.
         */
        const val MAX_CAP_BYTES = 8

        /**
         * The shortest legal new-format offer: header + a 1-char callId + 2 caps.
         * iOS's own guard is 45 bytes for a 36-char UUID (`:6540-6620`).
         */
        const val MIN_SIZE = HEADER_SIZE + 1

        /**
         * Parse an offer body, or null when it is not one.
         *
         * The peel is the whole algorithm: take capability bytes off the END while they
         * are below [PEEL_FLOOR], at most [MAX_CAP_BYTES] of them, then read what remains
         * after the key and salt as the callId. Flags are read POSITIONALLY from the front
         * of the stripped tail, matching `CallOfferCapabilities.peel`'s slot rules
         * (`CallOfferCapabilities.kt:38-56`): slot 0 = OshiCodec, 1 = 16 kHz, 2 = Apple.
         *
         * A zero byte is a PRESENT-AND-FALSE flag, not an absent one, and it still gets
         * peeled — which is why the loop tests `< PEEL_FLOOR` rather than `!= 0`.
         */
        fun decode(payload: ByteArray): CallOffer? {
            if (payload.size < MIN_SIZE) return null

            var end = payload.size
            val caps = ArrayList<Byte>(MAX_CAP_BYTES)
            while (end > HEADER_SIZE &&
                caps.size < MAX_CAP_BYTES &&
                (payload[end - 1].toInt() and 0xFF) < PEEL_FLOOR
            ) {
                caps.add(0, payload[end - 1])
                end--
            }
            if (end <= HEADER_SIZE) return null

            val id = String(payload, HEADER_SIZE, end - HEADER_SIZE, StandardCharsets.UTF_8)
            // iOS sanitizes with `sanitizeCallId` (`VoiceCallManager.swift:13285-13289`):
            // control characters and whitespace are stripped, not rejected. A trailing
            // newline from some intermediary must not change the SHA-256 the P2P layer
            // derives from this string.
            val cleaned = id.filter { !it.isWhitespace() && !it.isISOControl() }
            if (cleaned.isEmpty()) return null

            fun cap(slot: Int, flag: Byte): Boolean =
                slot < caps.size && (caps[slot].toInt() and flag.toInt()) != 0

            return CallOffer(
                sessionKey = payload.copyOfRange(0, SESSION_KEY_SIZE),
                nonceSalt = payload.copyOfRange(SESSION_KEY_SIZE, HEADER_SIZE),
                callId = cleaned,
                supportsOshiCodec = cap(0, CAP_OSHI_CODEC),
                supports16kPcm = cap(1, CAP_PCM_16K),
                supportsAppleCodec = cap(2, CAP_APPLE_CODEC),
                supportsVideo = cap(3, CAP_VIDEO),
                supportsWbAdpcm = cap(4, CAP_WB_ADPCM),
                supportsOpus = cap(5, CAP_OPUS),
            )
        }

        /**
         * Read ONLY the callId out of an offer body, without validating anything else.
         *
         * iOS's `peekOfferCallId` (`VoiceCallManager.swift:13210-13223`). It exists because
         * glare detection has to compare the inbound callId against ours BEFORE deciding
         * whether the offer is worth processing at all — see [CallStateMachine]'s glare
         * section.
         */
        fun peekCallId(payload: ByteArray): String? = decode(payload)?.callId
    }
}

/**
 * The body of a `callAccept` (0x02) / `videoCallAccept` (0x09) packet — the ANSWER.
 *
 * Capability bytes and NOTHING ELSE: `[cap0][cap1][cap2]` on iOS
 * (`VoiceCallManager.swift:7338-7360`), `[cap0][cap1]` on Android
 * (`EnhancedCallManager.kt:1631-1643`).
 *
 * ============================================================ THE ANSWER CARRIES NO callId
 *
 * This is the single most consequential fact in this file, and it is the root of a real
 * bug in both shipped clients.
 *
 * The accept body has no callId, and neither does the decline body (which is empty) or
 * the end body (which is a reason string). The ONLY place a callId travels on a reply is
 * the OUTER JSON envelope — see [CallSignalEnvelope]. So a client that receives a
 * decrypted `callDecline` cannot tell which call it refers to from the packet alone.
 *
 * iOS's cross-call terminal guard at `VoiceCallManager.swift:5044-5058` is supposed to
 * drop terminal packets for a foreign callId. It reads the callId from the envelope's
 * `type`/`callId` fields — but `declineIncomingCall` calls `sendViaTorRelay` WITHOUT a
 * `signalType` (`:7576`), so for declines this client emits, the guard does not fire.
 * Android has no equivalent guard at all: `handleCallSignal` routes `CALL_REJECT →
 * handleCallRejected()` unconditionally (`EnhancedCallManager.kt:2198`), and
 * `handleCallRejected` is an unguarded `endCall(DECLINED)` (`:2792-2795`).
 *
 * **Consequence, on both platforms: a decline that arrives late — a retransmit, a
 * reorder, or a second device declining — tears down a call that is already connected.**
 * [CallStateMachine] refuses to reproduce that; see its DECLINE CROSSING AN ANSWER
 * section, which is the one place this row is deliberately stricter than both phones.
 */
data class CallAccept(
    val supportsOshiCodec: Boolean = false,
    val supports16kPcm: Boolean = false,
    val supportsAppleCodec: Boolean = false,
    val supportsVideo: Boolean = false,
    /** __WB_ADPCM_CODEC_2026_09_23__ index 4 of the accept body (13 of the packet). */
    val supportsWbAdpcm: Boolean = false,
    /** __OPUS_CODEC_2026_09_23__ index 5 of the accept body (14 of the packet). */
    val supportsOpus: Boolean = false,
) {
    /** Five bytes `[oshi][16k][apple][video][wbAdpcm]`, as iOS/Android now write them. */
    fun encode(): ByteArray = byteArrayOf(
        if (supportsOshiCodec) CallOffer.CAP_OSHI_CODEC else 0x00,
        if (supports16kPcm) CallOffer.CAP_PCM_16K else 0x00,
        if (supportsAppleCodec) CallOffer.CAP_APPLE_CODEC else 0x00,
        if (supportsVideo) CallOffer.CAP_VIDEO else 0x00,
        if (supportsWbAdpcm) CallOffer.CAP_WB_ADPCM else 0x00,
        if (supportsOpus) CallOffer.CAP_OPUS else 0x00,
    )

    companion object {
        /**
         * Parse an accept body. Never null — an EMPTY body is legal.
         *
         * A build older than the capability negotiation sent an accept with no payload at
         * all, and both platforms read absent flags as false rather than rejecting the
         * accept (`parseAcceptCapabilityFlags`, `VoiceCallManager.swift:13272-13282`).
         * Refusing an empty accept would make this client unable to complete a call with
         * an older peer — the answer arrives, is discarded, and the caller times out at 45
         * seconds with no diagnosis.
         */
        /** Slot 4 compared by EQUALITY (positional), like the phones. */
        private fun i4(p: ByteArray): Boolean = p.size > 4 && p[4] == CallOffer.CAP_WB_ADPCM

        fun decode(payload: ByteArray): CallAccept {
            fun at(i: Int, flag: Byte): Boolean =
                i < payload.size && (payload[i].toInt() and flag.toInt()) != 0
            return CallAccept(
                supportsOshiCodec = at(0, CallOffer.CAP_OSHI_CODEC),
                supports16kPcm = at(1, CallOffer.CAP_PCM_16K),
                supportsAppleCodec = at(2, CallOffer.CAP_APPLE_CODEC),
                supportsVideo = at(3, CallOffer.CAP_VIDEO),
                supportsWbAdpcm = i4(payload),
                supportsOpus = payload.size > 5 && payload[5] == CallOffer.CAP_OPUS,
            )
        }
    }
}
