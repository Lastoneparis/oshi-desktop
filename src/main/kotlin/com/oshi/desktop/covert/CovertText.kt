package com.oshi.desktop.covert

import com.oshi.desktop.pairing.ContactQr
import com.oshi.messenger.network.v2.OSHICryptoV2
import java.util.Base64

/**
 * The seam that makes [TextSteganography] reachable by a person.
 *
 * ============================================================ WHY THIS FILE EXISTS
 *
 * [TextSteganography] is 456 lines, ported byte for byte from `OSHI/TextSteganography.swift`
 * by way of the Android port, and it has its own test suite. It also had **zero call sites
 * in `src/main` outside its own folder** — not in the CLI, not in the UI. A feature that
 * compiles, passes its tests, and cannot be reached by any user is not a feature; it is a
 * library nobody imports.
 *
 * That is not a desktop-only gap. Android injects `TextSteganography` into
 * `MessageRepository` and the comment there says it plainly — "Kept injected but
 * intentionally UNUSED on every message path […] Android has no covert receiver yet
 * (audit P0 #7)". On iOS it is reached only from the covert relay. So until this file, the
 * text channel was unreachable by hand on every platform OSHI ships.
 *
 * ============================================================ THE KEY, WHICH IS THE PART
 * THAT IS EASY TO GET WRONG
 *
 * The secret is the **raw 32-byte X25519 agreement** between our identity private key and
 * the peer's identity public key — nothing else. Not a passphrase, not base64, not
 * pre-hashed, and NOT the ratchet's session secret.
 *
 *   iOS   `IdentityManager.computeSharedSecret` → `sharedSecret.withUnsafeBytes { Data($0) }`
 *         (`OSHI/MessageManager.swift:4288`)
 *   here  [OSHICryptoV2.dh], whose own docstring says "Raw X25519 shared secret (no
 *         hashing), matching CryptoKit sharedSecret bytes"
 *
 * [TextSteganography] then applies the single hash iOS applies, `SHA256(secret ‖
 * "TEXT_STEG_V1")`, inside `deriveKeyBytes`. Android once added a SHA256 + label + base64
 * layer of its own here and the note in `MessageRepository.deriveStegoSecret` records the
 * consequence: nothing would ever have decrypted across platforms, even with the framing
 * fixed. So this file passes the agreement output through untouched, and that is
 * deliberate rather than lazy.
 *
 * A static-static ECDH is also why no change to the ratchet was needed to ship this. The
 * covert channel deliberately does not ride the Double Ratchet: a carrier is written once
 * and may be read weeks later, out of order, by a recipient who never saw the messages
 * around it, so a per-message key that advances is the wrong shape. The cost is honest and
 * worth stating: **the covert text channel has no forward secrecy.** One compromised
 * identity key opens every carrier ever written to or from that contact. The ordinary
 * message path does have forward secrecy; this one buys deniability instead.
 *
 * ============================================================ WHAT INTEROPERATES AND WHAT
 * DOES NOT
 *
 * At the STEGANOGRAPHY layer this is byte-compatible with iOS and Android: the same
 * carrier, the same key derivation, the same three methods in the same auto-detect order.
 *
 * Above that layer it is not, and pretending otherwise would be the lie that matters. When
 * iOS sends over the covert *relay* it embeds a JSON `CovertFragment`, and its receiver
 * JSON-decodes whatever it extracts. This file embeds the raw UTF-8 of the message, because
 * a person pasting a sentence into a box is not sending a fragment of a relay transfer.
 * So a carrier written here reveals in OSHI Desktop; an iPhone's covert receiver will
 * extract the bytes and fail to decode them. Desktop-to-desktop today, and the format to
 * converge on later is iOS's, not this one.
 */
class CovertText(private val selfPrivateKey: ByteArray) {

    private val stego = TextSteganography()

    /** What came of an attempt to write a carrier. Each case is something the UI must say. */
    sealed interface Hidden {
        /** The carrier text, ready to be copied into any channel that carries text. */
        data class Carrier(val text: String) : Hidden

        /** The peer address is not a usable OSHI address, so no key could be agreed. */
        data object UnknownPeer : Hidden

        /** Nothing to hide. Kept distinct so the UI does not report it as a capacity problem. */
        data object EmptyMessage : Hidden

        /**
         * The cover text is too short to carry this message.
         *
         * Both numbers are in PAYLOAD bytes, which is what the user cannot see and what
         * the explanation therefore has to name: [needed] is the message plus the 28-byte
         * AES-GCM overhead (12-byte nonce + 16-byte tag), [available] is what this cover
         * text and method can hold.
         */
        data class CoverTooShort(val needed: Int, val available: Int) : Hidden

        /** The embed refused for a reason the method alone knows — e.g. homoglyph coverage. */
        data object Refused : Hidden
    }

    /**
     * Hide [message] for [peerAddress] inside [coverText].
     *
     * @param method defaults to [TextSteganography.Method.COMBINED], which is zero-width
     *   with a homoglyph fallback — iOS's own default order. Zero-width is invisible and
     *   survives most channels; homoglyph survives channels that strip zero-width
     *   characters, which some messaging platforms do.
     */
    fun hide(
        message: String,
        coverText: String,
        peerAddress: String,
        method: TextSteganography.Method = TextSteganography.Method.COMBINED,
    ): Hidden {
        if (message.isEmpty()) return Hidden.EmptyMessage
        val secret = agree(peerAddress) ?: return Hidden.UnknownPeer
        try {
            val payload = message.toByteArray(Charsets.UTF_8)
            // Checked BEFORE the embed so the failure can be explained in bytes rather
            // than reported as a bare "it did not work". `embed` returning null is
            // otherwise indistinguishable from a method-specific refusal.
            val needed = payload.size + GCM_OVERHEAD_BYTES
            val available = stego.capacity(coverText, method)
            if (available < needed) return Hidden.CoverTooShort(needed, available)
            val carrier = stego.embed(payload, coverText, secret, method)
                ?: return Hidden.Refused
            return Hidden.Carrier(carrier)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Read a hidden message out of [carrier], if it holds one for [peerAddress].
     *
     * Returns null for every failure alike — wrong peer, no payload, corrupted payload,
     * bytes that are not UTF-8 — and that flattening is on purpose. Telling a caller
     * *which* of those happened tells anyone who can run this code whether a given text is
     * a carrier addressed to a given contact, which is the one thing a covert channel must
     * not confirm.
     */
    fun reveal(carrier: String, peerAddress: String): String? {
        val secret = agree(peerAddress) ?: return null
        try {
            val plain = stego.extract(carrier, secret) ?: return null
            // STRICT UTF-8. `ByteArray.toString(UTF_8)` never fails — it substitutes
            // U+FFFD — so using it here would turn "these bytes are not our format" into a
            // screenful of replacement characters presented as the peer's own message.
            val text = strictUtf8(plain) ?: return null
            // The AES-GCM tag already proved the key, so a payload that is valid UTF-8 and
            // still not ours belongs to another sender shape: iOS's covert relay embeds a
            // JSON `CovertFragment`. Handing its JSON to the user as "the hidden message"
            // would be wrong, and parsing it is a separate feature.
            if (text.startsWith("{") && text.endsWith("}")) return null
            return text
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Cheap pre-filter for "is this worth trying to reveal?", delegated to
     * [TextSteganography.containsHiddenData] so the UI can dim a button.
     *
     * It needs no key and proves nothing: it says a carrier *parses*, not that it is
     * addressed to anyone in particular. Never present its answer as "this is a message
     * for you".
     */
    fun looksLikeCarrier(text: String): Boolean = stego.containsHiddenData(text)

    /** How many bytes of message this cover text and method can carry, overhead removed. */
    fun roomFor(coverText: String, method: TextSteganography.Method): Int =
        maxOf(0, stego.capacity(coverText, method) - GCM_OVERHEAD_BYTES)

    /** A plausible-looking cover text to start from, in one of five registers. */
    fun suggestCover(style: TextSteganography.CoverTextStyle): String =
        stego.generateCoverText(style)

    /** Strip every carrier character, leaving the cover text a reader would have seen. */
    fun strip(text: String): String = stego.clean(text)

    /**
     * The raw X25519 agreement with [peerAddress], or null when the address is not one.
     *
     * Callers must zero the result; every one above does, in a `finally`.
     */
    /**
     * Decode [bytes] as UTF-8, or null if they are not valid UTF-8.
     *
     * `CharsetDecoder` with REPORT rather than the default REPLACE: the whole point is to
     * distinguish "this is text" from "this decrypted to something that is not text".
     */
    private fun strictUtf8(bytes: ByteArray): String? =
        runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    private fun agree(peerAddress: String): ByteArray? {
        val canonical = ContactQr.canonicalAddress(peerAddress) ?: return null
        val peerPub = runCatching { Base64.getDecoder().decode(canonical) }.getOrNull() ?: return null
        return runCatching { OSHICryptoV2.dh(selfPrivateKey, peerPub) }.getOrNull()
    }

    companion object {
        /**
         * AES-GCM's cost on top of the message: a 12-byte nonce prepended and a 16-byte
         * tag appended, per `TextSteganography.encryptForText`. Named because the number
         * appears in a message shown to a user and an unexplained 28 invites someone to
         * "simplify" it.
         */
        const val GCM_OVERHEAD_BYTES: Int = 12 + 16
    }
}
