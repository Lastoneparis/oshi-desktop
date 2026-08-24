package com.oshi.desktop

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
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

    /** The contact address: standard padded base64 of the X25519 public key. */
    val userKey: String get() = B64.encodeToString(identity.pub)

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
