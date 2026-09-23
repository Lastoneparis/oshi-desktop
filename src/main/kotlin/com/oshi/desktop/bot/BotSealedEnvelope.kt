package com.oshi.desktop.bot

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject

/**
 * __BOT_E2E_2026_09_23__ The recipient side of `bot-seal-v1` — bot messages that reach the
 * queue as ciphertext only. Normative spec: `ServerPatches/bot_e2e/BOT_SEAL_SPEC.md` §2;
 * reference: `ServerPatches/bot_e2e/patched/bot_seal.js` (`openSealed`); vectors:
 * `ServerPatches/bot_e2e/test/vectors.json` (BotSealedEnvelopeTest opens both recipients).
 *
 * Same primitive set as [com.oshi.desktop.mail.MailCrypto] (BouncyCastle X25519 + HKDF-SHA256,
 * JCE AES-256-GCM) — but NOT its blob layout: here the tag is LAST (`iv || ct || tag`), which
 * is what Java's GCM already emits, and there is an AAD on both layers.
 *
 * ```
 * wrap (92 B) = epk(32) || iv(12) || ct(32) || tag(16)
 *   k = HKDF-SHA256(ikm = X25519(ownPriv, epk), salt = epk || ownPub, info = "bot-seal-v1", 32)
 *   K = AES-256-GCM-open(k, iv, ct||tag, aad = "bot-seal-v1|wrap|<messageId>|<kid>")
 * body = iv(12) || ct || tag(16)
 *   inner = AES-256-GCM-open(K, iv, ct||tag, aad = "bot-seal-v1|body|<messageId>")
 * kid = lowercase hex(SHA-256(ownPub))[0..16]
 * ```
 *
 * Every failure is a [SealOpenException] whose message names a REASON and never a byte of
 * content, so a caller can log it as it is.
 */
object BotSealedEnvelope {

    const val SEAL_VERSION = "bot-seal-v1"
    const val WRAP_LEN = 32 + 12 + 32 + 16
    private const val IV_LEN = 12
    private const val TAG_LEN = 16

    class SealOpenException(reason: String, cause: Throwable? = null) : Exception(reason, cause)

    /** Spec §1 detection: `v == "bot-seal-v1"` and `sealed` is an object. Anything else is legacy. */
    fun isSealed(outer: JSONObject): Boolean =
        outer.optString("v", "") == SEAL_VERSION && outer.optJSONObject("sealed") != null

    /** `kid = lowercase hex(SHA-256(pub))[0..16]`. */
    fun kidFor(pub: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(pub)
        return d.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /**
     * Open [outer]'s `sealed` object with this identity's X25519 key pair and run the §2.7
     * checks. Returns the inner (legacy-shaped) JSON.
     *
     * @throws SealOpenException no wrap for this key, bad sizes, bad tag, bad JSON, or an
     *   inner messageId / groupId that does not match the outer one.
     */
    fun open(outer: JSONObject, ownPriv: ByteArray, ownPub: ByteArray): JSONObject {
        if (ownPriv.size != 32 || ownPub.size != 32) throw SealOpenException("identity key is not 32 bytes")
        val sealed = outer.optJSONObject("sealed") ?: throw SealOpenException("no sealed object")
        if (sealed.optString("v", "") != SEAL_VERSION) throw SealOpenException("sealed.v is not $SEAL_VERSION")
        val messageId = outer.optString("messageId", "")
        if (messageId.isEmpty()) throw SealOpenException("outer messageId missing")

        val kid = kidFor(ownPub)
        val wrapB64 = sealed.optJSONObject("wraps")?.optString(kid, "")?.ifEmpty { null }
            ?: throw SealOpenException("no wrap for this key")
        val w = b64(wrapB64, "wrap")
        if (w.size != WRAP_LEN) throw SealOpenException("bad wrap length ${w.size}")

        val epk = w.copyOfRange(0, 32)
        val shared = ByteArray(32)
        try {
            X25519Agreement().apply {
                init(X25519PrivateKeyParameters(ownPriv, 0))
                calculateAgreement(X25519PublicKeyParameters(epk, 0), shared, 0)
            }
        } catch (e: Exception) {
            throw SealOpenException("X25519 agreement failed", e)
        }
        val k = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(shared, epk + ownPub, SEAL_VERSION.toByteArray(Charsets.UTF_8)))
            generateBytes(k, 0, k.size)
        }
        shared.fill(0)

        val contentKey = try {
            gcmOpen(k, w.copyOfRange(32, WRAP_LEN), "$SEAL_VERSION|wrap|$messageId|$kid", "wrap")
        } finally {
            k.fill(0)
        }
        if (contentKey.size != 32) throw SealOpenException("content key is not 32 bytes")

        val body = b64(sealed.optString("body", ""), "body")
        val plaintext = try {
            gcmOpen(contentKey, body, "$SEAL_VERSION|body|$messageId", "body")
        } finally {
            contentKey.fill(0)
        }

        val inner = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SealOpenException("inner is not a JSON object")
        } finally {
            plaintext.fill(0)
        }

        // §2.7 — the AAD binds the ciphertext to the outer id, these bind the READABLE fields.
        if (!inner.optString("messageId", "").equals(messageId, ignoreCase = true)) {
            throw SealOpenException("inner messageId does not match the outer one")
        }
        val outerType = outer.optString("type", "")
        if (outerType == BotEnvelope.TYPE &&
            !inner.optString("groupId", "").equals(outer.optString("groupId", ""), ignoreCase = true)
        ) {
            throw SealOpenException("inner groupId does not match the outer one")
        }
        // Not in §2.7, but the dispatch is on the OUTER type; an inner claiming another kind
        // would be rendered under the wrong one.
        val innerType = inner.optString("type", "")
        if (innerType.isNotEmpty() && innerType != outerType) {
            throw SealOpenException("inner type does not match the outer one")
        }
        return inner
    }

    private fun gcmOpen(key: ByteArray, blob: ByteArray, aad: String, what: String): ByteArray {
        if (blob.size < IV_LEN + TAG_LEN) throw SealOpenException("$what too short")
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, blob, 0, IV_LEN))
            c.updateAAD(aad.toByteArray(Charsets.UTF_8))
            c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        } catch (e: Exception) {
            // Wrong key and tampering are indistinguishable here, and should be.
            throw SealOpenException("$what does not authenticate")
        }
    }

    private fun b64(s: String, what: String): ByteArray = try {
        Base64.getDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        throw SealOpenException("$what is not standard base64")
    }
}
