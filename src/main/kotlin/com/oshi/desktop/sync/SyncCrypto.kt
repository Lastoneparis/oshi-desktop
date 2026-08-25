package com.oshi.desktop.sync

import com.oshi.messenger.network.v2.OSHICryptoV2
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The archive keys, and the seal/open around them — PARITY.md row 0.24.
 *
 * Two archives (see [SyncProtocol]) means two key derivations, from two DIFFERENT private
 * keys, and getting them the wrong way round produces a key that is 32 valid-looking bytes
 * and decrypts nothing:
 *
 *  1. **V2 archive** — `HKDF-SHA256(ikm = ed25519 signing private key, salt = 0x00 × 32,
 *     info = "oshi-mds-v1", len = 32)` (`V2SyncManager.swift:19-26,286-298`). The ikm is
 *     the raw Ed25519 private key from the `"signingKey"` Keychain item
 *     (`KeychainHelper.swift:91`, saved as `rawRepresentation` at
 *     `IdentityManager.swift:568-570`) — i.e. the REQUEST-SIGNING key.
 *  2. **Legacy archive** — `SHA-256(x25519 private key ‖ "oshi-sync-key")`
 *     (`MultiDeviceSyncManager.swift:1003-1010`, `.kt:1394-1409`). The ikm is the raw
 *     X25519 MESSAGING key from the `"privateKey"` Keychain item
 *     (`KeychainHelper.swift:90`, `IdentityManager.swift:563-566`) — a different key
 *     pair entirely. Not HKDF: a bare SHA-256 over the concatenation, salt appended
 *     rather than prepended, which is worth writing down because "SHA-256 with a salt"
 *     invites reimplementing it as `salt ‖ key` and that is a different 32 bytes.
 *
 * Both key pairs are raw 32-byte scalars on all three clients — iOS saves
 * `rawRepresentation`, Android exports `X25519PrivateKeyParameters.encoded`
 * (`CryptoManager.kt:47,152-155`), and this client's [com.oshi.desktop.store.IdentityStore]
 * stores exactly those bytes. So a desktop, an iPhone and an Android on one imported
 * identity derive the same two keys.
 *
 * ============================================================ THE SEALED LAYOUT
 *
 * Both archives use the same one, and it is Apple's `AES.GCM.SealedBox.combined`:
 *
 *     base64( nonce(12) ‖ ciphertext ‖ tag(16) )
 *
 * with an EMPTY AAD. Android had to build that by hand and says so
 * (`MultiDeviceSyncManager.kt:1427-1449`: *"to match Apple CryptoKit's
 * AES.GCM.SealedBox.combined layout"*); iOS gets it from `sealed.combined`
 * (`swift:1017`) and, on the V2 side, assembles it explicitly as `nonce + sealed`
 * (`V2SyncManager.swift:306-313`). The JCE's `Cipher.doFinal` already appends the tag to
 * the ciphertext, so [seal] concatenates the nonce and nothing else — this is the same
 * layout [com.oshi.desktop.net.V2BlobClient] and the ratchet use, and the shared
 * `OSHICryptoV2.aesGcmSeal` is what produces `ct ‖ tag`.
 *
 * **Standard base64, padded.** Not base64url. `Data(base64Encoded:)` on iOS and
 * `Base64.NO_WRAP` on Android both mean the standard alphabet; the base64URL spelling
 * appears in this protocol only as a URL PATH SEGMENT ([SyncProtocol.legacyPathKey]), never
 * as a payload — the same split PARITY.md row 0.22 records for the QR payload.
 *
 * ============================================================ WHAT [open] REFUSES
 *
 * A short blob is rejected rather than decoded. `V2SyncManager.swift:321` guards
 * `blob.count > nonceLen + tagLen` and throws `malformedCiphertext`; the reason to keep
 * that guard rather than let AES-GCM fail on its own is that the JCE's failure mode for a
 * 12-byte input is not a clean `AEADBadTagException` — an empty ciphertext with a
 * *truncated* tag is a different exception class, and a caller catching the tag exception
 * only would let it escape the per-item isolation in [SyncEngine] and abort the drain.
 *
 * Note the shipped bound is strictly-greater, so a blob of exactly 28 bytes — a valid
 * seal of ZERO plaintext bytes — is refused by iOS. This client reproduces that, because
 * agreeing with the shipped client about which items are undecryptable matters more than
 * being able to archive an empty record, and nothing archives an empty record.
 */
object SyncCrypto {

    /** `V2SyncManager.Config.archiveInfo` default (`V2SyncManager.swift:94`). */
    const val V2_ARCHIVE_INFO = "oshi-mds-v1"

    /** The legacy salt, APPENDED to the key (`swift:1005-1007`, `.kt:1397-1401`). */
    const val LEGACY_SALT = "oshi-sync-key"

    const val NONCE_LEN = OSHICryptoV2.GCM_NONCE_LEN
    const val TAG_LEN = 16

    private val B64: Base64.Encoder = Base64.getEncoder()
    private val B64D: Base64.Decoder = Base64.getDecoder()
    private val rng = SecureRandom()

    /**
     * V2 archive key. [ed25519Priv] is the raw signing private key — NOT the X25519 one.
     *
     * The length check is not decoration: HKDF happily expands a zero-length ikm into 32
     * plausible bytes, so an identity that failed to load reads as an archive key that
     * decrypts nothing, and the symptom is "every item skipped as corrupt" — an
     * indistinguishable-from-hostile-server failure. `V2SyncManager.swift:291` makes the
     * same check for the same reason.
     */
    fun v2ArchiveKey(ed25519Priv: ByteArray, info: String = V2_ARCHIVE_INFO): ByteArray {
        require(ed25519Priv.isNotEmpty()) {
            "no identity private key — cannot derive the V2 archive key (V2SyncManager.swift:291)"
        }
        val key = OSHICryptoV2.hkdf(
            ikm = ed25519Priv,
            salt = ByteArray(32),
            info = info.toByteArray(Charsets.UTF_8),
            length = 32,
        )
        check(key.size == 32) { "archive key derivation produced ${key.size} bytes" }
        return key
    }

    /**
     * Legacy archive key: `SHA-256(x25519Priv ‖ "oshi-sync-key")`.
     *
     * The order is load-bearing and is the reason this is spelled out with two `update`
     * calls in the same order both shipped clients use, rather than a one-line
     * `digest(salt + key)`.
     */
    fun legacyArchiveKey(x25519Priv: ByteArray): ByteArray {
        require(x25519Priv.isNotEmpty()) {
            "no messaging private key — cannot derive the legacy archive key " +
                "(MultiDeviceSyncManager.swift:1004)"
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(x25519Priv)
        md.update(LEGACY_SALT.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    /**
     * Seal [plaintext] into `base64(nonce ‖ ct ‖ tag)`.
     *
     * [nonce] is injectable for the byte-shape tests ONLY. It defaults to 12 fresh
     * [SecureRandom] bytes and there is no caller-facing reason to pass it: a repeated
     * nonce under a long-lived archive key — and the archive key is as long-lived as the
     * identity, that being the whole trade this archive makes
     * (`V2SyncModels.swift:14-20`) — is the classic GCM catastrophe, forgery of any
     * message under that key, not merely a confidentiality loss for the two that collided.
     */
    fun seal(key: ByteArray, plaintext: ByteArray, nonce: ByteArray = randomNonce()): String {
        require(key.size == 32) { "archive key must be 32 bytes, got ${key.size}" }
        require(nonce.size == NONCE_LEN) { "GCM nonce must be $NONCE_LEN bytes, got ${nonce.size}" }
        val ctAndTag = OSHICryptoV2.aesGcmSeal(key, nonce, plaintext, ByteArray(0))
        return B64.encodeToString(nonce + ctAndTag)
    }

    /**
     * Reverse of [seal]. Throws [SyncCryptoException] — never returns null and never
     * returns empty bytes for an undecryptable blob, because [SyncEngine] distinguishes
     * "this one item is corrupt" from "this record legitimately carries nothing", and a
     * silent empty would apply as a real record.
     */
    fun open(key: ByteArray, ciphertextB64: String): ByteArray {
        require(key.size == 32) { "archive key must be 32 bytes, got ${key.size}" }
        val blob = try {
            B64D.decode(ciphertextB64)
        } catch (e: IllegalArgumentException) {
            throw SyncCryptoException("archive item ciphertext is not valid base64", e)
        }
        if (blob.size <= NONCE_LEN + TAG_LEN) {
            throw SyncCryptoException(
                "archive item ciphertext is ${blob.size} bytes, needs more than " +
                    "${NONCE_LEN + TAG_LEN} (V2SyncManager.swift:321)"
            )
        }
        return try {
            OSHICryptoV2.aesGcmOpen(
                key,
                blob.copyOfRange(0, NONCE_LEN),
                blob.copyOfRange(NONCE_LEN, blob.size),
                ByteArray(0),
            )
        } catch (e: Exception) {
            throw SyncCryptoException("archive item failed to decrypt", e)
        }
    }

    fun randomNonce(): ByteArray = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
}

class SyncCryptoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
