package com.oshi.desktop.call.media

import com.oshi.desktop.call.CallOffer
import com.oshi.messenger.network.v2.OSHICryptoV2

/**
 * One encrypted media frame — PARITY.md row 2.1's media wire format.
 *
 * ```
 * [audioType: UInt8][seq: UInt64 BE][nonce(12) ‖ ciphertext ‖ tag(16)]
 *                                    └ nonce = txSalt(4) ‖ seq(8 BE) ┘
 * ```
 *
 * `OSHI/VoiceCallManager.swift:10160-10172` assembles it; `:12752-12780` builds the
 * nonce and seals; `:12786-12800` opens it. Android produces the same bytes.
 *
 * ============================================================ WHY THERE IS NO WEBRTC HERE
 *
 * This is the whole media decision for row 2.1, and it was settled by reading the two
 * shipped clients rather than by reading the ledger's description of the row.
 *
 * OSHI media is **not SRTP** and its signalling is **not SDP**. Frames are AES-256-GCM
 * under a 32-byte session key that the CALLER mints and hands to the callee inside the
 * offer ([CallOffer]), over a transport the client picks itself (P2P UDP hole punch, a
 * TURN channel, the VPS UDP relay on :8089, or the signalling WebSocket). Neither client
 * links libwebrtc — iOS's project file has no package reference at all and
 * `OSHI/StunClient.swift:12` says "No libwebrtc. Pure Network.framework."; Android
 * removed `io.getstream:stream-webrtc-android` and documented why in
 * `app/build.gradle.kts:221-235`.
 *
 * So a WebRTC media stack on the desktop could not carry one second of audio to any OSHI
 * phone. It would be PARITY.md row 0.16's mistake — "the obvious reading would produce
 * something no phone can read" — with a 15 MB native library attached. And the project's
 * own roadmap points the other way: `CALL_V2_PLAN.md:26-31` specifies a proprietary codec
 * and records the constraint as a user requirement, *"Cannot rely on Opus/AAC."*
 *
 * What this package implements is therefore the OSHI path, and it needs **no new
 * dependency at all**: AES-256-GCM is already here (`OSHICryptoV2`, row 0.1), and the
 * codec this client speaks is raw PCM, which needs no codec. See [CallAudioSession].
 *
 * The `webrtc-java` binding is still evaluated, verified and declared — opt-in, default
 * off — in `build.gradle.kts`. Read the WEBRTC block there for what it costs and what it
 * cannot talk to.
 *
 * ============================================================ THE CODEC THIS CLIENT PICKS
 *
 * Four audio types are live: `0x05` AAC-ELD, `0x15` raw PCM 48 kHz, `0x16` OshiCodec,
 * `0x17` raw PCM 16 kHz. The desktop emits and accepts **`0x15`, raw PCM 48 kHz**, and
 * that is not a fallback — it is the only one of the four that is universally decodable,
 * and both clients say so in as many words:
 *
 *   > `// Apple AudioToolbox and Android MediaCodec produce incompatible AAC-ELD`
 *   > `// bitstreams. When remotePlatform != "ios", send/receive raw PCM Int16 (no`
 *   > `// codec) — universally decodable.` — `VoiceCallManager.swift:3068-3071`
 *
 * `0x16` OshiCodec is an LPC codec whose reference implementation is C++ in
 * `llama.cpp/jni/oshi_codec/` (`CALL_V2_PLAN.md`), reachable from Swift and JNI and from
 * nothing on the JVM. `0x05` AAC-ELD is Apple-only. Both are advertised by a capability
 * byte and both fall back to `0x15` when either side omits it, so a desktop that
 * advertises neither negotiates raw PCM with every peer — which is the shipped default
 * path between an iPhone and an Android phone today.
 *
 * **The cost is stated rather than hidden.** iOS measured it and left the number in a
 * diagnostic at `:10193-10199`: 48 kHz raw PCM is 1 957 bytes per frame at 50 fps, about
 * **780 kbit/s in one direction**, and every datagram splits into two IP fragments so
 * losing either loses the frame. That is the honest bandwidth of a desktop call today,
 * it is the same bandwidth an iPhone↔Android call uses, and improving it means a codec
 * this row does not have.
 *
 * ============================================================ THE DIRECTIONAL NONCE SALT
 *
 * The offer carries ONE 4-byte salt, and both ends would otherwise transmit under it —
 * same key, same salt, same counter sequence, so the two directions emit **identical
 * nonces under one key**. That is a catastrophic GCM failure and it was a shipped bug;
 * the fix is in the code as a comment (`VoiceCallManager.swift:12761-12763`):
 *
 *   > `// 🔧 CRYPTO FIX: per-DIRECTION salt (was 'sessionNonceSalt', identical on`
 *   > `// both ends ⇒ both directions emitted the same nonces under the same key).`
 *
 * The repair is [responderSalt]: the responder transmits under the base salt with its
 * FIRST BYTE XORed by `0xA5` (`:3047-3052`), which always differs from the base, so
 * initiator-TX and responder-TX nonces cannot collide even at equal counters. The
 * initiator transmits under the base.
 *
 * [forSending] takes `isCaller` for exactly this and nothing else, and it is the reason
 * that flag is threaded all the way from [com.oshi.desktop.call.CallAction.StartMedia].
 * Get it backwards and the call is silent in both directions with no error — the peer's
 * AEAD simply never verifies.
 *
 * ============================================================ REPLAY
 *
 * The counter is authenticated (it is in the nonce) but not ordered by the transport, and
 * media rides UDP. iOS rejects a counter it has already seen but allows out-of-order
 * arrival (`:12786-12800`). [ReplayWindow] is that rule, and it is a rule rather than a
 * `lastSeen` comparison because a strict high-water mark drops every legitimately
 * reordered datagram, which on a lossy cellular leg is most of them.
 */
object CallMediaFrame {

    /** `0x05` — AAC-ELD. Accepted for framing; this client cannot decode the payload. */
    const val TYPE_AAC_ELD = 0x05

    /** `0x15` — raw PCM 48 kHz, signed 16-bit. What this client emits. */
    const val TYPE_PCM_48K = 0x15

    /** `0x16` — OshiCodec. Native-only; never emitted here. */
    const val TYPE_OSHI_CODEC = 0x16

    /** `0x17` — raw PCM 16 kHz. */
    const val TYPE_PCM_16K = 0x17

    /** `0x18` — wideband IMA-ADPCM 16 kHz ([WbAdpcmCodec]); emitted when the peer advertised 0x10. */
    const val TYPE_WB_ADPCM = 0x18

    /** `0x19` — Opus 48 kHz ([OpusCallEncoder]); emitted when the peer advertised 0x20. */
    const val TYPE_OPUS = 0x19

    /** type(1) + seq(8). */
    const val HEADER_SIZE = 9

    /** nonce(12) + tag(16): the smallest possible sealed body. */
    const val MIN_SEALED = 28

    /**
     * The responder's transmit salt: base with byte 0 XOR `0xA5`.
     * `VoiceCallManager.responderNonceSalt` (`:3047-3052`).
     */
    fun responderSalt(base: ByteArray): ByteArray {
        if (base.size != CallOffer.NONCE_SALT_SIZE) return base
        val s = base.copyOf()
        s[0] = (s[0].toInt() xor 0xA5).toByte()
        return s
    }

    /**
     * The salt WE transmit under.
     *
     * @param isCaller true when we sent the offer, i.e. we minted the salt.
     */
    fun txSalt(base: ByteArray, isCaller: Boolean): ByteArray =
        if (isCaller) base else responderSalt(base)

    /** `txSalt(4) ‖ seq(8 big-endian)`. */
    fun nonce(salt: ByteArray, seq: Long): ByteArray {
        require(salt.size == CallOffer.NONCE_SALT_SIZE) { "salt must be 4 bytes" }
        val n = ByteArray(12)
        salt.copyInto(n, 0)
        for (i in 0 until 8) n[4 + i] = ((seq ushr (56 - i * 8)) and 0xFF).toByte()
        return n
    }

    /**
     * Seal one media frame.
     *
     * [seq] must strictly increase for the life of the call. It is a parameter rather
     * than internal state so the emitted bytes are a pure function of the inputs and can
     * be pinned in a test — the counter itself is owned by [MediaSequence], which is the
     * thing that must never repeat.
     */
    fun encode(
        sessionKey: ByteArray,
        baseSalt: ByteArray,
        isCaller: Boolean,
        seq: Long,
        audioType: Int,
        pcm: ByteArray,
    ): ByteArray {
        require(seq > 0) { "media sequence starts at 1; 0 would reuse the all-zero counter" }
        val n = nonce(txSalt(baseSalt, isCaller), seq)
        val sealed = OSHICryptoV2.aesGcmSeal(sessionKey, n, pcm, ByteArray(0))
        val out = ByteArray(HEADER_SIZE + n.size + sealed.size)
        out[0] = audioType.toByte()
        for (i in 0 until 8) out[1 + i] = ((seq ushr (56 - i * 8)) and 0xFF).toByte()
        n.copyInto(out, HEADER_SIZE)
        sealed.copyInto(out, HEADER_SIZE + n.size)
        return out
    }

    /** A decoded media frame. [pcm] is plaintext. */
    data class Decoded(val audioType: Int, val seq: Long, val pcm: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return audioType == other.audioType && seq == other.seq && pcm.contentEquals(other.pcm)
        }

        override fun hashCode(): Int = (audioType * 31 + seq.hashCode()) * 31 + pcm.contentHashCode()
    }

    /**
     * Open one media frame, or null.
     *
     * **The nonce is read off the wire, not reconstructed**, which is what iOS does
     * (`:12789`, "decrypt reads the wire" — its `rxNonceSalt` is explicitly diagnostics
     * only, `:3041`). That matters for interop: a peer whose salt derivation differs from
     * ours still decrypts, because the AEAD authenticates the nonce it was given. The
     * header `seq` is therefore only a hint, and the AUTHENTICATED counter is the one
     * inside the nonce — [Decoded.seq] returns that one.
     */
    fun decode(sessionKey: ByteArray, frame: ByteArray): Decoded? {
        if (frame.size < HEADER_SIZE + MIN_SEALED) return null
        val type = frame[0].toInt() and 0xFF
        val nonce = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + 12)
        val body = frame.copyOfRange(HEADER_SIZE + 12, frame.size)
        val pcm = runCatching {
            OSHICryptoV2.aesGcmOpen(sessionKey, nonce, body, ByteArray(0))
        }.getOrNull() ?: return null
        var seq = 0L
        for (i in 4 until 12) seq = (seq shl 8) or (nonce[i].toLong() and 0xFF)
        return Decoded(type, seq, pcm)
    }
}

/**
 * The transmit counter. One per call, per direction.
 *
 * Separate from [CallMediaFrame] because a codec that remembered a counter could not be
 * tested against a byte shape — the same split [com.oshi.desktop.msg.ControlPayloadRouter]
 * makes against its payload codecs.
 *
 * Starts at 1, never repeats, and refuses to wrap. Wrapping a GCM counter under a fixed
 * key is the failure this whole nonce scheme exists to prevent, and at 50 frames a second
 * a 64-bit counter lasts about 11 billion years — so a wrap means something else is very
 * wrong and stopping is the correct response.
 */
class MediaSequence {
    private var value = 0L

    @Synchronized
    fun next(): Long {
        check(value != Long.MAX_VALUE) { "media sequence exhausted — refusing to reuse a GCM nonce" }
        return ++value
    }

    @Synchronized
    fun current(): Long = value
}

/**
 * Replay protection for inbound media.
 *
 * A sliding window rather than a high-water mark, for the reason in [CallMediaFrame]'s
 * REPLAY note: media is UDP and reordering is normal, so `seq <= lastSeen → drop` throws
 * away good audio. iOS allows out-of-order arrival for the same reason (`:12786-12800`).
 *
 * [WINDOW] frames is 1.28 s at 50 fps — long enough to absorb the jitter a call actually
 * sees, short enough that an attacker cannot replay a frame from earlier in the call.
 */
class ReplayWindow(private val window: Int = WINDOW) {
    private var highest = 0L
    private val seen = HashSet<Long>()

    /** True when [seq] is fresh and should be played. Records it. */
    @Synchronized
    fun accept(seq: Long): Boolean {
        if (seq <= 0) return false
        if (seq <= highest - window) return false
        if (!seen.add(seq)) return false
        if (seq > highest) highest = seq
        seen.removeIf { it <= highest - window }
        return true
    }

    companion object {
        /** 64 frames ≈ 1.28 s at 50 fps. */
        const val WINDOW = 64
    }
}
