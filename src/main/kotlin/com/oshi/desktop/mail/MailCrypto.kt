package com.oshi.desktop.mail

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens (and creates) the sealed blobs of OSHI Mail — the desktop half of
 * `crypto_mail.js seal()` on the server, `OSHIMailClient.seal/open` on iOS, and
 * `com.oshi.messenger.mail.MailCrypto` on Android (byte-for-byte port of that file).
 *
 * This is the ZERO-ACCESS scheme: the server never holds a readable copy of any mail. A
 * mistake here does not corrupt one message, it makes the WHOLE mailbox unreadable, on
 * every platform, forever — there is no server-side plaintext to recover from. The frozen
 * cross-platform vector in `MailCryptoTest` exists to catch exactly that before it ships.
 *
 * Wire format, identical on all three:
 *
 *     epk(32) ‖ iv(12) ‖ tag(16) ‖ ciphertext
 *     shared = X25519(ephemeral_private, recipient_public)
 *     key    = HKDF-SHA256(ikm = shared, salt = epk ‖ recipientPublicKey, info = "oshi-mail-v1", len 32)
 *     AES-256-GCM, 128-bit tag
 *
 * BouncyCastle rather than the JDK's own `KeyPairGenerator("XDH")`/`KeyAgreement("XDH")`:
 * this project's desktop identity ([com.oshi.desktop.DesktopIdentity]) and the vendored
 * `OSHICryptoV2` core already do every X25519 operation through
 * `org.bouncycastle:bcprov-jdk18on`, which is already a `build.gradle.kts` dependency. The
 * JVM's native XDH support (available since 11, no BouncyCastle needed on 17) would be a
 * SECOND X25519 implementation living beside the first for no reason — two ways to do the
 * same math is how the two quietly drift, and BouncyCastle is also what Android's own
 * `MailCrypto.kt` uses, so staying on it is what keeps this file a faithful port rather
 * than a rewrite.
 *
 * Note the field order: AES-GCM implementations in Java append the tag to the ciphertext,
 * so the two are split apart on seal and re-joined on open. Getting that wrong produces a
 * blob that this code can read and the other two platforms cannot.
 */
object MailCrypto {

    private const val INFO = "oshi-mail-v1"
    private const val EPK_LEN = 32
    private const val IV_LEN = 12
    private const val TAG_LEN = 16
    const val SEAL_OVERHEAD = EPK_LEN + IV_LEN + TAG_LEN

    private val random = SecureRandom()

    class SealedBlobError(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun deriveKey(shared: ByteArray, epk: ByteArray, recipientPub: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, epk + recipientPub, INFO.toByteArray(Charsets.UTF_8)))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }

    /** Encrypts to [recipientPub]; only the matching private key can reverse it. */
    fun seal(plaintext: ByteArray, recipientPub: ByteArray): ByteArray {
        require(recipientPub.size == 32) { "recipient public key must be 32 bytes" }

        val ephemeralPriv = X25519PrivateKeyParameters(random)
        val epk = ephemeralPriv.generatePublicKey().encoded

        val shared = ByteArray(32)
        X25519Agreement().apply {
            init(ephemeralPriv)
            calculateAgreement(X25519PublicKeyParameters(recipientPub, 0), shared, 0)
        }

        val key = deriveKey(shared, epk, recipientPub)
        shared.fill(0)

        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, iv))
        val ctAndTag = cipher.doFinal(plaintext)
        key.fill(0)

        // Java puts the tag last; the wire format puts it before the ciphertext.
        val ct = ctAndTag.copyOfRange(0, ctAndTag.size - TAG_LEN)
        val tag = ctAndTag.copyOfRange(ctAndTag.size - TAG_LEN, ctAndTag.size)
        return epk + iv + tag + ct
    }

    /** Reverses [seal]. Throws [SealedBlobError] when this device is not the recipient. */
    fun open(blob: ByteArray, recipientPriv: ByteArray): ByteArray {
        if (blob.size < SEAL_OVERHEAD) throw SealedBlobError("blob too short to be sealed")
        require(recipientPriv.size == 32) { "recipient private key must be 32 bytes" }

        val epk = blob.copyOfRange(0, EPK_LEN)
        val iv = blob.copyOfRange(EPK_LEN, EPK_LEN + IV_LEN)
        val tag = blob.copyOfRange(EPK_LEN + IV_LEN, SEAL_OVERHEAD)
        val ct = blob.copyOfRange(SEAL_OVERHEAD, blob.size)

        val priv = X25519PrivateKeyParameters(recipientPriv, 0)
        val recipientPub = priv.generatePublicKey().encoded

        val shared = ByteArray(32)
        X25519Agreement().apply {
            init(priv)
            calculateAgreement(X25519PublicKeyParameters(epk, 0), shared, 0)
        }

        val key = deriveKey(shared, epk, recipientPub)
        shared.fill(0)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, iv))
            cipher.doFinal(ct + tag)
        } catch (e: Exception) {
            // A wrong key and a tampered blob are indistinguishable here, and should be:
            // saying which would tell an attacker whether they guessed the key.
            throw SealedBlobError("sealed for another device, or tampered with", e)
        } finally {
            key.fill(0)
        }
    }

    /** Generates the device's mail key. The private half must never leave the device. */
    fun generateMailKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(random)
        return priv.encoded to priv.generatePublicKey().encoded
    }

    fun publicKeyFor(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
