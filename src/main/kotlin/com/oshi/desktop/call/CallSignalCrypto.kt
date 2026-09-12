package com.oshi.desktop.call

import com.oshi.messenger.network.v2.OSHICryptoV2
import java.security.SecureRandom

/**
 * The seal on a call signalling packet — and the place to read that it is NOT the ratchet.
 *
 * ============================================================ THE SCHEME
 *
 * ```
 * shared  = X25519(myPrivateIdentity, theirPublicIdentity)      // STATIC-static
 * key     = HKDF-SHA256(ikm = shared,
 *                       salt = "OSHI-VoiceCall-v1",
 *                       info = "signaling",
 *                       L    = 32)
 * sealed  = nonce(12) ‖ AES-256-GCM(key, nonce, packet)          // ‖ tag(16)
 * ```
 *
 * iOS, `OSHI/VoiceCallManager.swift:12833-12866`:
 *
 * ```swift
 * let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: peerPublicKeyObj)
 * let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
 *     using: SHA256.self,
 *     salt: "OSHI-VoiceCall-v1".data(using: .utf8)!,
 *     sharedInfo: "signaling".data(using: .utf8)!,
 *     outputByteCount: 32)
 * let sealed = try AES.GCM.seal(data, using: symmetricKey)
 * return sealed.combined!
 * ```
 *
 * Android, `network/encryption/CryptoManager.kt:538-548`, derives the same key with the
 * same salt and info and produces the same `nonce ‖ ciphertext ‖ tag` layout (`:412`).
 *
 * CryptoKit's `.combined` IS `nonce ‖ ciphertext ‖ tag` with a 12-byte nonce, which is
 * why the two agree byte for byte. This port uses the SHARED `OSHICryptoV2` primitives
 * compiled out of the Android tree — the same `dh`, `hkdf`, `aesGcmSeal` PARITY.md row
 * 0.1 already vector-tested — so there is no third X25519 and no third HKDF here.
 *
 * ============================================================ THERE IS NO FORWARD SECRECY
 *
 * Both keys in that agreement are the peers' LONG-TERM identity keys. Nothing ratchets,
 * nothing is ephemeral, and the derived key is identical for every call between the same
 * two people, forever. Anyone who later obtains one identity private key can decrypt
 * every call signal that pair ever exchanged, including recorded ones.
 *
 * This matters more than it looks, because of what the offer body contains: the
 * **32-byte AES session key for the call's media** ([CallOffer]). So compromising a
 * long-term identity key retroactively unlocks not just the signalling but every recorded
 * media frame of every past call.
 *
 * `AUDIT_VOIP_TELECOM.md:78` claims "Double Ratchet forward secrecy" for calls. That is a
 * DOC claim with no code behind it: the ratchet lives in `MessageManager` / `V2Session`
 * and `encryptForPeer` never touches it. PARITY.md's rule is to record the disagreement
 * and take the stricter reading; the stricter reading is that call signalling has no
 * forward secrecy, and this file says so rather than inheriting the audit's sentence.
 *
 * The desktop does not fix it: using the ratchet here would produce packets no phone can
 * open, which is the row 0.16 mistake. It is recorded as a property of the protocol.
 *
 * ============================================================ WHY THE ADDRESS IS THE KEY
 *
 * In OSHI a contact's address IS their X25519 public key, base64. So [seal] needs nothing
 * but the peer's address, and [open] authenticates the sender implicitly: a packet that
 * opens under `DH(me, them)` could only have been sealed by someone holding one of those
 * two private keys. That is the same TOFU-anchored assumption row 0.12 relies on, and it
 * is the ONLY authentication in the call path — the server has none
 * (`call_server.js:65`, `SIGNATURE_REQUIRED = false`).
 *
 * A consequence worth naming: the seal proves the packet came from that identity, but
 * says nothing about WHICH CALL it belongs to, because the accept, decline and end bodies
 * carry no callId (see [CallAccept]). The state machine has to supply that correlation.
 */
object CallSignalCrypto {

    /** `"OSHI-VoiceCall-v1"` — the HKDF salt, identical on both platforms. */
    const val HKDF_SALT = "OSHI-VoiceCall-v1"

    /** `"signaling"` — the HKDF info. American spelling, as shipped. */
    const val HKDF_INFO = "signaling"

    /** AES-GCM nonce length. CryptoKit's default and Android's, so it is the wire's. */
    const val NONCE_SIZE = 12

    /** AES-GCM tag length in bytes. */
    const val TAG_SIZE = 16

    /**
     * Derive the signalling key for a peer.
     *
     * Exposed rather than kept private so a test can assert the derivation against a
     * known-answer vector without going through a seal — the derivation is the part that
     * has to agree with two other implementations, and an end-to-end round trip inside
     * ONE implementation would prove only self-consistency (the vacuous-test shape this
     * project has already been burned by).
     */
    fun deriveKey(myPrivateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val shared = OSHICryptoV2.dh(myPrivateKey, peerPublicKey)
        return OSHICryptoV2.hkdf(
            ikm = shared,
            salt = HKDF_SALT.toByteArray(Charsets.UTF_8),
            info = HKDF_INFO.toByteArray(Charsets.UTF_8),
            length = 32,
        )
    }

    /**
     * Seal a [CallPacket] for a peer: `nonce ‖ ciphertext ‖ tag`.
     *
     * [nonce] is a parameter with a random default so the emitted bytes can be pinned in a
     * test. A caller that passes a fixed nonce in production would reuse a (key, nonce)
     * pair under a key that never changes — catastrophic for GCM — so the default is the
     * only thing any non-test caller should use.
     */
    fun seal(
        myPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        packet: ByteArray,
        nonce: ByteArray = randomNonce(),
    ): ByteArray {
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        val key = deriveKey(myPrivateKey, peerPublicKey)
        val ct = OSHICryptoV2.aesGcmSeal(key, nonce, packet, ByteArray(0))
        return nonce + ct
    }

    /**
     * Open a sealed signal, or **null** when it does not authenticate.
     *
     * Null rather than a throw, because this is fed by bytes from a server with no
     * authentication at all: an unopenable blob is the expected outcome of someone POSTing
     * garbage under our peer's name, not an exceptional condition. The caller counts it.
     *
     * A too-short input is refused before the cipher sees it — `nonce ‖ tag` is already 28
     * bytes, so anything shorter cannot be a sealed packet and handing it to AES-GCM would
     * turn a length check into an exception.
     */
    fun open(myPrivateKey: ByteArray, peerPublicKey: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < NONCE_SIZE + TAG_SIZE) return null
        val key = deriveKey(myPrivateKey, peerPublicKey)
        val nonce = sealed.copyOfRange(0, NONCE_SIZE)
        val body = sealed.copyOfRange(NONCE_SIZE, sealed.size)
        return runCatching { OSHICryptoV2.aesGcmOpen(key, nonce, body, ByteArray(0)) }.getOrNull()
    }

    private val rng = SecureRandom()

    fun randomNonce(): ByteArray = ByteArray(NONCE_SIZE).also { rng.nextBytes(it) }

    /** A fresh 32-byte media session key for an outgoing offer. */
    fun randomSessionKey(): ByteArray = ByteArray(CallOffer.SESSION_KEY_SIZE).also { rng.nextBytes(it) }

    /** A fresh 4-byte directional nonce salt for an outgoing offer. */
    fun randomNonceSalt(): ByteArray = ByteArray(CallOffer.NONCE_SALT_SIZE).also { rng.nextBytes(it) }
}
