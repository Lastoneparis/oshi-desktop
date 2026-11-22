package com.oshi.desktop

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/**
 * The two key pairs an OSHI account is, restated for the desktop.
 *
 * OSHI has TWO identities and conflating them breaks everything (V2Signer.kt in the
 * Android tree spells this out):
 *
 *   - the X25519 identity  = the contact address. It is what `from`/`to` carry on the
 *     wire, what `x-oshi-user` carries in a header, and what X3DH uses as IK. Its
 *     STANDARD PADDED base64 string IS the user's address.
 *   - the Ed25519 signing key = request auth only. It signs the canonical request
 *     string and the signed-prekey, and rides in `x-oshi-signing-pubkey`.
 *
 * Storage is deliberately NOT implemented here. On Android these live in the Keystore,
 * on iOS in the Keychain; on desktop this is the single largest unsolved problem and it
 * is a per-OS problem (see PLAN.md, "Where this Mac is not a proxy for Linux/Windows").
 * This class holds keys in memory only, which is correct for a protocol skeleton and
 * categorically not shippable.
 */
class DesktopIdentity(
    val identity: OSHICryptoV2.X25519Pair,
    private val signingPriv: ByteArray,
    val signingPub: ByteArray,
) {

    /** Internal copy for the recovery-key codec; never expose the backing array itself. */
    fun signingPrivateBytes(): ByteArray = signingPriv.copyOf()

    /** The contact address: standard padded base64 of the X25519 public key. */
    val userKey: String get() = B64.encodeToString(identity.pub)

    /**
     * The OSHI Mail key material, HKDF-SHA256 over this identity's X25519 ENCRYPTION
     * private key (its raw 32 bytes). A byte-for-byte mirror of iOS
     * `IdentityManager.deriveMailKeyMaterial()` and Android
     * `CryptoManager.deriveMailKeyMaterial()`:
     *
     *   ikm  = the 32-byte X25519 identity private key
     *   salt = UTF-8 "oshi-mail"
     *   info = UTF-8 "oshi-mail-x25519-v1"
     *   out  = 32 bytes
     *
     * Deriving the mailbox key from the identity — rather than minting a random one — is
     * what makes the mailbox and drive recoverable from the identity seed / key backup
     * alone: restore the account on any device and the same mail key falls out. The 32
     * bytes are used directly as an X25519 private key (BouncyCastle, like iOS's
     * Curve25519 and Android, clamps on agreement, so any 32 bytes are a valid key). The
     * server never sees this material: zero-access is unchanged.
     */
    fun deriveMailKeyMaterial(): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(identity.priv, MAIL_HKDF_SALT, MAIL_HKDF_INFO))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }

    /** Ed25519 over arbitrary bytes — matches Android `CryptoManager.sign`. */
    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(signingPriv, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    companion object {
        /**
         * Android encodes every wire key with `Base64.NO_WRAP`, which is the STANDARD
         * alphabet, WITH `=` padding, no line breaks. `java.util.Base64.getEncoder()`
         * is byte-identical to it. It is NOT base64url — a `-`/`_` here would silently
         * produce an address no other platform can resolve. See WireFormatTest.
         */
        val B64: Base64.Encoder = Base64.getEncoder()
        val B64D: Base64.Decoder = Base64.getDecoder()

        /** HKDF salt/info for [deriveMailKeyMaterial], shared verbatim with iOS/Android. */
        private val MAIL_HKDF_SALT = "oshi-mail".toByteArray(Charsets.UTF_8)
        private val MAIL_HKDF_INFO = "oshi-mail-x25519-v1".toByteArray(Charsets.UTF_8)

        fun generate(): DesktopIdentity {
            val identity = OSHICryptoV2.generateX25519()
            val priv = Ed25519PrivateKeyParameters(SecureRandom())
            return DesktopIdentity(identity, priv.encoded, priv.generatePublicKey().encoded)
        }

        /** Ed25519 verify — matches Android `CryptoManager.verify`. */
        fun verify(data: ByteArray, signature: ByteArray, signingPub: ByteArray): Boolean = try {
            val v = Ed25519Signer()
            v.init(false, Ed25519PublicKeyParameters(signingPub, 0))
            v.update(data, 0, data.size)
            v.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }
}
