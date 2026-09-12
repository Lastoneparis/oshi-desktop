package com.oshi.desktop.call.media

import com.oshi.desktop.call.CallOffer
import com.oshi.messenger.network.v2.OSHICryptoV2
import java.security.SecureRandom

/**
 * The `0xF1` envelope that carries one sealed video fragment — PARITY.md row 2.1.
 *
 * ```
 * [0xF1][seq(8)][nonce(12) ‖ ciphertext ‖ tag(16)]
 *                └ nonce = videoSalt(4) ‖ (counter | 1<<63)(8 BE) ┘
 * ```
 *
 * `OSHI/VoiceCallManager.swift:11076` names the type and `:11125-11141` builds the
 * envelope; `OSHI-Android/…/EnhancedCallManager.kt:94-95, :4925-4945` builds the same one.
 * The plaintext inside is ONE [VideoFragment], not a frame — every fragment is sealed on
 * its own, so one lost datagram costs one fragment.
 *
 * ============================================================ THE TWO CLIENTS DISAGREE HERE
 *
 * **The header sequence number is big-endian on iOS and little-endian on Android.** iOS
 * writes `currentVideoNonce.bigEndian` with the comment *"Write big-endian for
 * cross-platform compatibility"* (`swift:11139`). Android writes byte `i` as
 * `seq shr (i*8)` — little-endian — under a comment claiming it *"Matches iOS
 * VoiceCallManager.sendVideoPacketInternal() — Data(bytes: &seq, count: 8) on ARM64 = LE"*
 * (`kt:4923-4925`), which describes the code iOS had BEFORE that fix and not the code it
 * ships. Both are one field of one envelope, and they are eight bytes apart in meaning.
 *
 * It has stayed invisible because almost nothing reads the field: iOS's P2P ingress
 * branches to the video handler on the type byte before the deduplicator sees it, and the
 * WebSocket path peels nine bytes and discards them (`swift:11147-11165`). The one carrier
 * that DOES read it is mesh-relayed video, which is deduplicated on
 * `(type << 56) | seq` — where an Android sender's `seq = 1` reads as
 * `0x0100000000000000`. Dedup still works there (the mapping is injective), but every gap
 * and ordering statistic computed from it is nonsense.
 *
 * This client emits **big-endian**, because parity here is parity with macOS. On receive
 * it treats the header sequence as **opaque**: reassembly is keyed by `frame_id` inside
 * the fragment and replay is caught by the nonce, so nothing needs a number whose byte
 * order depends on which phone sent it. [Decoded.headerSeq] is exposed for diagnostics
 * and is deliberately not used for anything.
 *
 * ============================================================ WHY VIDEO HAS ITS OWN NONCE
 *
 * Video is sealed under the SAME session key as audio ([CallMediaFrame]), so a video nonce
 * that collided with an audio nonce would break both. Two mechanisms keep them apart, and
 * iOS documents both because it shipped without them (`swift:276-315`):
 *
 * - **The salt is fresh per VIDEO SESSION** — 31 random bits, redrawn on every
 *   start/stop cycle, because toggling video off and on inside one call restarts the
 *   counter at 0 and would otherwise replay every nonce the previous session used.
 * - **Bit 63 of the counter is forced set** ([DOMAIN_BIT]). The audio path has the same
 *   `salt(4) ‖ counter(8 BE)` layout, a counter starting at 1 that realistically never
 *   passes 2³², and it shares the key. Forcing the top bit makes the two nonce spaces
 *   disjoint by construction instead of by a 2⁻³¹ argument about salt collisions.
 * - **Bit 7 of salt byte 0 is the direction bit** — 1 for the caller, 0 for the callee.
 *   Before it existed, both ends started at counter 0 under one key and the caller's
 *   first video fragment carried a BIT-IDENTICAL nonce to the callee's: the XOR of the
 *   two plaintexts leaks, and the GHASH authentication subkey is recoverable from the
 *   pair. The E2EE claim was false for video, whatever the key exchange did.
 *
 * The receiver derives none of this. [decode] takes the twelve nonce bytes off the wire
 * and hands them to GCM, so a peer whose salt derivation differs — including a peer old
 * enough to emit the pre-fix `[counter LE(8)][0×4]` layout — still decrypts.
 */
object VideoMediaFrame {

    /** `0xF1` — the video packet type, shared with both phones. */
    const val TYPE_VIDEO = 0xF1

    /** type(1) + seq(8). */
    const val HEADER_SIZE = 9

    /** nonce(12) + tag(16). */
    const val MIN_SEALED = 28

    /** Bit 63 of the counter field: this nonce belongs to video, never to audio. */
    const val DOMAIN_BIT: Long = Long.MIN_VALUE

    private val rng = SecureRandom()

    /**
     * A fresh transmit salt for ONE video session: 31 random bits, with the direction bit
     * in the top bit of byte 0.
     *
     * @param isCaller true when we sent the offer. The two ends of a call always disagree
     *   on this, which is what makes their nonce spaces disjoint.
     */
    fun newSalt(isCaller: Boolean, random: SecureRandom = rng): ByteArray {
        val s = ByteArray(CallOffer.NONCE_SALT_SIZE)
        random.nextBytes(s)
        s[0] = ((s[0].toInt() and 0x7F) or (if (isCaller) 0x80 else 0x00)).toByte()
        return s
    }

    /** `salt(4) ‖ (counter | DOMAIN_BIT)(8 BE)`. */
    fun nonce(salt: ByteArray, counter: Long): ByteArray {
        require(salt.size == CallOffer.NONCE_SALT_SIZE) { "salt must be 4 bytes" }
        require(counter > 0) { "video counter starts at 1" }
        val v = counter or DOMAIN_BIT
        val n = ByteArray(12)
        salt.copyInto(n, 0)
        for (i in 0 until 8) n[4 + i] = ((v ushr (56 - i * 8)) and 0xFF).toByte()
        return n
    }

    /**
     * Seal one fragment.
     *
     * [counter] must strictly increase for the life of the video session; [MediaSequence]
     * is the thing that guarantees it. It is a parameter so the emitted bytes are a pure
     * function of the inputs and can be pinned in a test.
     */
    fun encode(sessionKey: ByteArray, salt: ByteArray, counter: Long, fragment: ByteArray): ByteArray {
        val n = nonce(salt, counter)
        val sealed = OSHICryptoV2.aesGcmSeal(sessionKey, n, fragment, ByteArray(0))
        val out = ByteArray(HEADER_SIZE + n.size + sealed.size)
        out[0] = TYPE_VIDEO.toByte()
        for (i in 0 until 8) out[1 + i] = ((counter ushr (56 - i * 8)) and 0xFF).toByte()
        n.copyInto(out, HEADER_SIZE)
        sealed.copyInto(out, HEADER_SIZE + n.size)
        return out
    }

    /**
     * One opened video envelope.
     *
     * [headerSeq] is the wire field, whose byte order depends on which client sent it —
     * see the divergence note on [VideoMediaFrame]. [counter] is the AUTHENTICATED one,
     * read out of the nonce GCM verified.
     */
    class Decoded(
        val headerSeq: Long,
        val nonce: ByteArray,
        val fragment: ByteArray,
    ) {
        /** The counter inside the nonce, with [DOMAIN_BIT] cleared. */
        val counter: Long get() {
            var v = 0L
            for (i in 4 until 12) v = (v shl 8) or (nonce[i].toLong() and 0xFF)
            return v and DOMAIN_BIT.inv()
        }

        /**
         * False for a peer old enough to emit the pre-fix `[counter LE(8)][0×4]` nonce.
         * Reported, never enforced: rejecting those peers would drop every video packet
         * they send, and the packet still authenticated under the key.
         */
        val hasVideoDomainBit: Boolean get() = (nonce[4].toInt() and 0x80) != 0
    }

    /** Open one video envelope, or null. The type byte must be [TYPE_VIDEO]. */
    fun decode(sessionKey: ByteArray, frame: ByteArray): Decoded? {
        if (frame.size < HEADER_SIZE + MIN_SEALED) return null
        if ((frame[0].toInt() and 0xFF) != TYPE_VIDEO) return null
        var seq = 0L
        for (i in 1 until 9) seq = (seq shl 8) or (frame[i].toLong() and 0xFF)
        val nonce = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + 12)
        val body = frame.copyOfRange(HEADER_SIZE + 12, frame.size)
        val plain = runCatching {
            OSHICryptoV2.aesGcmOpen(sessionKey, nonce, body, ByteArray(0))
        }.getOrNull() ?: return null
        return Decoded(seq, nonce, plain)
    }
}

/**
 * Replay protection for inbound video, over WHOLE nonces.
 *
 * [CallMediaFrame]'s audio window slides over a counter; this one cannot, and iOS explains
 * why in the code it added after shipping with no replay check at all (`swift:369-380`):
 * a peer running the old build emits `[counter LE(8)][0×4]`, whose bytes 4..11 are
 * constant zero, so ANY counter-based window reads its entire stream as "counter 0" and
 * rejects every packet after the first. Comparing whole nonces is layout-agnostic and
 * therefore safe against every peer version, at the cost of a set rather than a bitmask.
 *
 * **Checked only after the GCM tag verifies**, so an attacker cannot poison the window
 * with nonces it never had the key to use.
 *
 * [MAX] is about two seconds of fragments at 30 fps × 16 fragments per frame. The residual
 * risk iOS names: an old-build peer that toggles video off and on inside one call restarts
 * its counter, so its first fragments can alias nonces still in the window and are dropped
 * until it rolls — at most [MAX] fragments.
 */
class NonceReplayWindow(private val max: Int = MAX) {
    private class Key(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Key && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private val seen = LinkedHashSet<Key>()

    /** Frames refused as replays. */
    var drops = 0L
        private set

    /** True when this nonce is fresh. Records it. */
    @Synchronized
    fun accept(nonce: ByteArray): Boolean {
        if (!seen.add(Key(nonce.copyOf()))) {
            drops++
            return false
        }
        if (seen.size > max) {
            val it = seen.iterator()
            it.next()
            it.remove()
        }
        return true
    }

    companion object {
        /** ~2 s at 30 fps × up to 16 fragments per frame. */
        const val MAX = 1024
    }
}
