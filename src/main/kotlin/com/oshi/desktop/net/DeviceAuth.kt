package com.oshi.desktop.net

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Base64

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ The per-device mailbox auth strings, byte for byte
 * (`ServerPatches/per_device_mailbox/CLIENT_SPEC.md` §2, server `device_registry.js`).
 *
 *     deviceId          hex(SHA-256("OSHI-DEVSYNC-id" ‖ dk_raw))[0..32]  — the devsync id
 *     register proof    OSHI-DEVICE-REGISTER/1\n<identity>\n<deviceId>\n<dk>\n<dsk>\n<ts>      signed by dsk
 *     approval          OSHI-DEVICE-APPROVE/1\n<identity>\n<newDeviceId>\n<newDsk>\n<ts>        signed by the APPROVER's dsk
 *     device request    OSHI-DEVICE/1\n<deviceId>\n<METHOD>\n<path>\n<sha256hex(body)>\n<ts>    signed by dsk
 *
 * `\n` is 0x0A, no trailing newline, UTF-8. Signatures are Ed25519, STANDARD padded base64.
 * `<path>` is the path as sent (percent-encoded, query excluded) — the same string the
 * account signature covers — and `<ts>` IS that request's `x-oshi-timestamp`.
 *
 * The spec asks for these to live beside `OSHICryptoV2` so Android and Desktop compile one
 * copy. That folder is shared Android source this change may not edit, so they live here for
 * now; `DeviceAuthVectorsTest` pins them to `device_vectors.json` so a later move is a
 * rename, not a re-derivation.
 */
object DeviceAuth {

    private val ID_PREFIX = "OSHI-DEVSYNC-id".toByteArray(Charsets.UTF_8)
    private val B64 = Base64.getEncoder()

    /** 32 lowercase hex: the first 16 bytes of SHA-256("OSHI-DEVSYNC-id" ‖ dk). */
    fun deviceIdFor(dkPub: ByteArray): String {
        require(dkPub.size == 32) { "device key must be 32 bytes" }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ID_PREFIX)
        md.update(dkPub)
        return hex(md.digest().copyOf(16))
    }

    fun isDeviceId(s: String?): Boolean = s != null && s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    fun registerProofString(identity: String, deviceId: String, dk: String, dsk: String, ts: String): String =
        "OSHI-DEVICE-REGISTER/1\n$identity\n$deviceId\n$dk\n$dsk\n$ts"

    fun approvalString(identity: String, newDeviceId: String, newDsk: String, ts: String): String =
        "OSHI-DEVICE-APPROVE/1\n$identity\n$newDeviceId\n$newDsk\n$ts"

    fun deviceRequestString(deviceId: String, method: String, path: String, bodySha256Hex: String, ts: String): String =
        "OSHI-DEVICE/1\n$deviceId\n$method\n$path\n$bodySha256Hex\n$ts"

    /** Ed25519 public key of a 32-byte seed. */
    fun publicKey(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    /** Ed25519(seed, utf8(message)), standard base64. Deterministic (RFC 8032). */
    fun sign(seed: ByteArray, message: String): String {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        val b = message.toByteArray(Charsets.UTF_8)
        s.update(b, 0, b.size)
        return B64.encodeToString(s.generateSignature())
    }

    fun verify(pubB64: String, message: String, sigB64: String): Boolean = try {
        val v = Ed25519Signer()
        v.init(false, Ed25519PublicKeyParameters(Base64.getDecoder().decode(pubB64), 0))
        val b = message.toByteArray(Charsets.UTF_8)
        v.update(b, 0, b.size)
        v.verifySignature(Base64.getDecoder().decode(sigB64))
    } catch (_: Exception) {
        false
    }

    /**
     * The identity in its NORMALISED spelling: standard alphabet, padded. The server
     * normalises before it builds the proof strings, and compares them byte for byte, so a
     * base64url or unpadded spelling here is a silent `bad-device-proof`.
     */
    fun normalizeIdentity(s: String): String {
        val std = s.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        return std + "=".repeat((4 - std.length % 4) % 4)
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
