package com.oshi.messenger.network.encryption

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The five primitives the shared [DoubleRatchet] asks its `cryptoManager` for — and
 * nothing else.
 *
 * __RATCHET_IS_SHARED_SOURCE_2026_10_28__ The desktop compiles Android's actual
 * `DoubleRatchet.kt` (see `build.gradle.kts`), so it must supply the collaborator that file
 * is constructed with. Android's `CryptoManager` is 701 lines of Keystore, prefs and
 * `android.util.*`; the ratchet uses **five** methods of it, every one of them pure JDK or
 * BouncyCastle, and this is those five, copied from
 * `OSHI-Android/.../encryption/CryptoManager.kt` with the bodies unchanged.
 *
 * ============================================================ WHY THIS IS THE SEAM
 *
 * The thing that must not diverge between a phone and this client is the PROTOCOL — the
 * KDF chain, the AAD layout, the session id, the header bytes — and that is exactly what is
 * shared rather than reimplemented. What is reimplemented here is five textbook operations
 * with no OSHI-specific choices left in them except three, which are the ones to check:
 *
 *   1. **HKDF with an EMPTY salt means 32 zero bytes**, per RFC 5869, which is what
 *      CryptoKit's `hkdfDerivedSymmetricKey(salt: Data())` does on iOS. BouncyCastle's
 *      `HKDFParameters(ikm, salt, info)` treats a null OR empty salt the same way, so this
 *      passes the zeros explicitly rather than relying on that.
 *   2. **AES-GCM is nonce(12) || ciphertext || tag(16)**, concatenated in that order. That
 *      is Swift's `AES.GCM.SealedBox.combined` layout, and the reason this file does not
 *      simply use a different arrangement.
 *   3. **X25519 keys are RAW 32 bytes**, never SPKI/PKCS#8 wrappers.
 *
 * Each of those is pinned by a test against bytes produced by the phones, because a
 * primitive that is subtly different does not fail to compile — it fails to decrypt, once,
 * on someone's radio.
 */
class CryptoManager {

    /**
     * HKDF-SHA256. Empty salt and empty info by default, to match iOS.
     *
     * BouncyCastle's generator performs extract-then-expand internally, which is the same
     * two steps Android's copy spells out by hand; the output is defined by RFC 5869 and
     * does not depend on which of the two does it.
     */
    fun hkdfDerive(
        sharedSecret: ByteArray,
        salt: ByteArray = ByteArray(0),
        info: ByteArray = ByteArray(0),
        outputLength: Int = 32,
    ): ByteArray {
        // "When salt is empty, HKDF spec says to use a string of HashLen zeros" — the
        // comment on Android's copy, and the behaviour this reproduces explicitly.
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, actualSalt, info))
        val out = ByteArray(outputLength)
        hkdf.generateBytes(out, 0, outputLength)
        return out
    }

    /** The string-info overload the ratchet calls with "RootKey", "MessageKey", … */
    fun hkdfDerive(
        sharedSecret: ByteArray,
        salt: ByteArray? = null,
        info: String = "",
        outputLength: Int = 32,
    ): ByteArray = hkdfDerive(
        sharedSecret = sharedSecret,
        salt = salt ?: ByteArray(0),
        info = info.toByteArray(Charsets.UTF_8),
        outputLength = outputLength,
    )

    /** X25519 raw-key agreement. */
    fun deriveSharedSecretWithKeys(privateKeyBytes: ByteArray, publicKeyBytes: ByteArray): ByteArray {
        require(privateKeyBytes.size == KEY_SIZE) { "Private key must be 32 bytes" }
        require(publicKeyBytes.size == KEY_SIZE) { "Public key must be 32 bytes" }

        val privateKey = X25519PrivateKeyParameters(privateKeyBytes, 0)
        val publicKey = X25519PublicKeyParameters(publicKeyBytes, 0)

        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(publicKey, sharedSecret, 0)
        return sharedSecret
    }

    /** A fresh EPHEMERAL X25519 pair for the DH ratchet — never an identity key. */
    fun generateEphemeralKeyPair(): Pair<ByteArray, ByteArray> {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()
        val priv = pair.private as X25519PrivateKeyParameters
        val pub = pair.public as X25519PublicKeyParameters
        return Pair(priv.encoded, pub.encoded)
    }

    /** AES-256-GCM with additional data. Returns nonce(12) || ciphertext || tag(16). */
    fun encryptAESGCMWithAAD(plaintext: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes for AES-256" }

        val nonce = ByteArray(GCM_NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** The inverse. Throws `AEADBadTagException` on a forged or mis-keyed box. */
    fun decryptAESGCMWithAAD(sealedBox: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes for AES-256" }
        require(sealedBox.size > GCM_NONCE_LENGTH + GCM_TAG_LENGTH / 8) { "Sealed box too short" }

        val nonce = sealedBox.sliceArray(0 until GCM_NONCE_LENGTH)
        val body = sealedBox.sliceArray(GCM_NONCE_LENGTH until sealedBox.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(body)
    }

    companion object {
        const val KEY_SIZE = 32

        /** 12 bytes — the only length iOS's `AES.GCM.Nonce()` produces. */
        const val GCM_NONCE_LENGTH = 12

        /** BITS, not bytes: `GCMParameterSpec` takes the tag length in bits. */
        const val GCM_TAG_LENGTH = 128
    }
}
