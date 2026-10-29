package com.oshi.desktop.crypto

import com.oshi.desktop.lora.LoRaSecureMessage
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.encryption.DoubleRatchet
import com.oshi.messenger.network.encryption.IOSRatchetMessage
import com.oshi.messenger.network.encryption.RatchetBase64
import org.json.JSONObject

/**
 * The legacy `EncryptedMessage` envelope — the one the LAN mesh and LoRa carry — opened and
 * sealed exactly as the phones do it.
 *
 * __RATCHET_IS_SHARED_SOURCE_2026_10_28__ PARITY.md row 0.16. This client speaks V2 on the
 * relay, and the mesh lanes have never carried V2: Android builds an `EncryptedEnvelope`
 * with `encryptWithDoubleRatchet(...)` and pushes it straight into the mesh
 * (`MessageRepository.kt:5547`), and iOS does the same through
 * `IdentityManager.wrapRatchetMessage` (`OSHI/IdentityManager.swift:579`). So a desktop
 * that could not open one could receive a message over LoRa and only report that it had.
 *
 * The RATCHET itself is not reimplemented here — this build compiles the phones' own
 * `DoubleRatchet.kt` (see `build.gradle.kts`). What this file is, is the four steps around
 * it, and every one of them is a place a port goes wrong silently:
 *
 * ```
 *   {"ciphertext": b64, "signature": …, "senderPublicKey": …, "timestamp": appleEpoch}
 *        │  ciphertext  = AES-256-GCM( nonce(12) || ct || tag(16) ), NO additional data
 *        │  key         = HKDF-SHA256( X25519(ourPriv, theirPub), salt="", info="" )
 *        ▼
 *   base64 text
 *        ▼
 *   DoubleRatchetMessage JSON  →  IOSRatchetMessage  →  ratchet  →  plaintext
 * ```
 *
 * The outer layer is STATIC-STATIC: the same two identity keys every time, so it provides
 * no forward secrecy on its own and is not where this lane's security lives. The ratchet
 * underneath is. That is the shipped design on both phones and this file reproduces it
 * rather than improving on it, because an improvement here is a message a phone cannot read.
 */
class LegacyRatchetEnvelope(
    private val ourPrivateKey: ByteArray,
    ourPublicKeyBase64: String,
    private val crypto: CryptoManager = CryptoManager(),
    val ratchet: DoubleRatchet = DoubleRatchet(CryptoManager()),
) {

    /** Our own address in the one spelling this file uses for everything. See [canonicalKey]. */
    private val ourPublicKeyBase64: String = canonicalKey(ourPublicKeyBase64)

    /** Thrown when the envelope's own sender field disagrees with the routed sender. */
    class ImpersonationException(message: String) : SecurityException(message)

    /**
     * Open an envelope, or throw.
     *
     * @param routedSenderKey the identity the TRANSPORT attributed this to — for LoRa, the
     *        `senderPublicKey` of the reassembled `SecureMessage`. See the refusal below for
     *        why this, and not the field inside the envelope, is the one used.
     */
    fun open(envelope: JSONObject, routedSenderKey: String): String {
        // 🔴 IMPERSONATION. Android carries this fix at `MessageRepository.kt:5384-5413`
        // and the reasoning is worth repeating where the code is: decrypting with one
        // identity and FILING the result under another is the whole bug. An attacker
        // encrypts to you under their own keypair, sets the inner field to their key and
        // the outer routing field to your contact's, and the message renders as coming
        // from your contact — no secret of the contact's required, since their public key
        // is on a QR code. iOS never had it because iOS has ONE sender field.
        //
        // A differing inner value has no legitimate cause, so it is refused loudly rather
        // than ignored: a forgery must not fall through to something that happens to work.
        val claimedInner = envelope.optString("senderPublicKey", "")
        if (claimedInner.isNotEmpty() &&
            LoRaSecureMessage.normalizeKey(claimedInner) != LoRaSecureMessage.normalizeKey(routedSenderKey)
        ) {
            throw ImpersonationException(
                "envelope claims sender ${claimedInner.take(8)}… but was routed from ${routedSenderKey.take(8)}…"
            )
        }

        // __IDENTITY_CANONICAL__ One spelling from here down. The routed key may arrive
        // base64url or unpadded, and it is used for THREE things that must agree: the
        // X25519 agreement, the session id, and the conversation it is filed under. A
        // second spelling of one peer is a second SESSION — which does not fail to
        // compile and does not fail to parse, it just never decrypts.
        val senderKey = canonicalKey(routedSenderKey)

        val sealedBox = RatchetBase64.decode(envelope.getString("ciphertext"))
        val sharedSecret = sharedSecretWith(senderKey)
        // Empty salt AND empty info — iOS's `hkdfDerivedSymmetricKey(salt: Data(),
        // sharedInfo: Data())`. No additional data on this layer.
        val symmetricKey = crypto.hkdfDerive(sharedSecret, ByteArray(0), ByteArray(0), 32)
        val innerBase64 = String(crypto.decryptAESGCMWithAAD(sealedBox, symmetricKey, ByteArray(0)), Charsets.UTF_8)

        val ratchetJson = JSONObject(String(RatchetBase64.decode(innerBase64), Charsets.UTF_8))
        require(ratchetJson.has("header") && ratchetJson.has("ciphertext")) {
            "inner payload is not a DoubleRatchetMessage"
        }
        val ratchetMessage = IOSRatchetMessage.fromJSON(ratchetJson)

        // CV-010, copied from Android: the HEADER decides the session version, not what we
        // happen to have learned about the peer. Capability arrives asynchronously, so two
        // clients that have both updated can disagree about each other, and a strict check
        // would kill a conversation between two capable peers. A header may only RAISE the
        // version of a session being CREATED here; an established one is never pulled down,
        // because a header is attacker-controlled.
        val session = ratchet.getOrCreateSession(
            theirPublicKey = senderKey,
            ourPublicKey = ourPublicKeyBase64,
            sharedSecret = sharedSecret,
            ratcheting = ratchetMessage.header.sessionVersion >= DoubleRatchet.RATCHETING_SESSION_VERSION,
        )
        return ratchet.decrypt(session, ratchetMessage)
    }

    /**
     * Seal a plaintext for one recipient, in the shape both phones parse.
     *
     * ⚠️ The caller MUST persist the ratchet after this returns and before the bytes go out.
     * The sending chain has advanced in memory; if the write does not land, process death
     * rewinds it and the next send re-derives a message key that is already on the wire —
     * an AES-256-GCM key reused across two plaintexts. Android states the same rule at its
     * call site and deliberately does not catch the failure there.
     */
    fun seal(plaintext: String, recipientKeyRaw: String): JSONObject {
        val recipientKey = canonicalKey(recipientKeyRaw)
        val sharedSecret = sharedSecretWith(recipientKey)
        // FORCE v3 when we are the one starting, as Android does: the peer's advertised
        // capability is no longer consulted, and a peer too old to read it is told to
        // update rather than silently given a weaker session.
        val session = ratchet.getOrCreateSession(
            theirPublicKey = recipientKey,
            ourPublicKey = ourPublicKeyBase64,
            sharedSecret = sharedSecret,
            ratcheting = true,
        )
        val ratchetMessage = ratchet.encrypt(session, plaintext)
        val ratchetBase64 = RatchetBase64.encodeToString(
            ratchetMessage.toJSON().toString().toByteArray(Charsets.UTF_8)
        )
        val symmetricKey = crypto.hkdfDerive(sharedSecret, ByteArray(0), ByteArray(0), 32)
        val sealedBox = crypto.encryptAESGCMWithAAD(
            ratchetBase64.toByteArray(Charsets.UTF_8), symmetricKey, ByteArray(0)
        )
        return JSONObject()
            .put("ciphertext", RatchetBase64.encodeToString(sealedBox))
            // iOS does not verify this field on decrypt, and Android sends it empty. An
            // invented signature would be a new wire fact neither phone checks.
            .put("signature", "")
            .put("senderPublicKey", ourPublicKeyBase64)
            // Swift's JSONDecoder uses .deferredToDate, i.e. seconds since 2001-01-01.
            // Unix seconds here decode on iOS as a year-2055 date.
            .put("timestamp", (System.currentTimeMillis() / 1000.0) - APPLE_EPOCH_OFFSET)
    }

    private fun sharedSecretWith(peerKeyBase64: String): ByteArray =
        crypto.deriveSharedSecretWithKeys(ourPrivateKey, decodeKey(peerKeyBase64))

    private fun decodeKey(base64: String): ByteArray {
        val raw = RatchetBase64.decode(base64.trim())
        require(raw.size == 32) { "an identity key is 32 raw bytes, got ${raw.size}" }
        return raw
    }

    companion object {
        /** Seconds between the Unix epoch and Apple's 2001-01-01 reference date. */
        const val APPLE_EPOCH_OFFSET = 978_307_200.0

        /**
         * One identity, one spelling: standard base64, PADDED — what both phones hold.
         *
         * [LoRaSecureMessage.normalizeKey] folds base64url and drops padding, which is the
         * right thing for COMPARING two keys and the wrong thing to feed a decoder or a
         * session id. The session id is `SHA256(sorted([a, b]).joined(":"))` computed over
         * whatever spelling each side happens to hold, so if this client used the unpadded
         * form while a phone used the padded one, the two would build different session ids
         * from the same pair of keys and nothing would ever decrypt — with no error, at any
         * layer, on either side.
         */
        fun canonicalKey(key: String): String {
            val folded = LoRaSecureMessage.normalizeKey(key)
            if (folded.isEmpty()) return folded
            val remainder = folded.length % 4
            return if (remainder == 0) folded else folded + "=".repeat(4 - remainder)
        }
    }
}
