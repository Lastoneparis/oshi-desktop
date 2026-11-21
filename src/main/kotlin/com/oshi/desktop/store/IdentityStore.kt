package com.oshi.desktop.store

import com.oshi.desktop.DesktopIdentity
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom
import java.util.Base64

/**
 * The account — the two key pairs that ARE this OSHI install — held in the [KeyVault] so
 * it survives a restart.
 *
 * Until this existed, `DesktopIdentity.generate()` minted a fresh account on every run:
 * correct for a protocol demo, and the reason PARITY.md calls key storage the gate for
 * every row below it. An OSHI address is the base64 of the X25519 public key, so a client
 * that regenerates is a client that changes address — every contact keeps writing to an
 * account nobody reads, and nothing in the UI looks wrong.
 *
 * TWO KEYS, NEVER CONFLATED:
 *   - **X25519 identity** — the contact address: `from`/`to` on the wire, `x-oshi-user`
 *     in a header, IK in X3DH.
 *   - **Ed25519 signing key** — request auth and the signed-prekey signature only, in
 *     `x-oshi-signing-pubkey`.
 */
object IdentityStore {

    const val ACCOUNT_X25519_PRIV = "identity.x25519.priv"
    const val ACCOUNT_X25519_PUB = "identity.x25519.pub"
    const val ACCOUNT_ED25519_PRIV = "identity.ed25519.priv"
    const val ACCOUNT_ED25519_PUB = "identity.ed25519.pub"

    /**
     * __SHARED_NICKNAME_2026_09_22__ How this identity came to be on this machine, written in
     * the SAME atomic vault write as the keys. It matters because a recovery-key import makes
     * this desktop hold the PHONE'S key: anything it announces about "itself" (capabilities,
     * avatar) peers record against the phone. Absent on every identity created before the
     * marker existed ⇒ [Origin.UNKNOWN], which callers must treat like [Origin.IMPORTED].
     */
    const val ACCOUNT_ORIGIN = "identity.origin"

    enum class Origin(val wire: String?) {
        /** Minted by [loadOrCreate] on this desktop: no other device holds this key. */
        GENERATED("desktop-generated"),
        /** Restored from a phone's recovery key by [importRecoveryKey]: the key is SHARED. */
        IMPORTED("recovery-import"),
        /** No marker (older vault, or a path that did not say). Assume shared. */
        UNKNOWN(null),
    }

    fun origin(vault: KeyVault): Origin {
        val raw = vault.get(ACCOUNT_ORIGIN)?.toString(Charsets.UTF_8) ?: return Origin.UNKNOWN
        return Origin.entries.firstOrNull { it.wire == raw } ?: Origin.UNKNOWN
    }

    /** Load the account, creating it on first run. */
    fun loadOrCreate(vault: KeyVault): DesktopIdentity {
        load(vault)?.let { return it }

        if (hasAnyAccountMaterial(vault)) {
            throw IdentityImportException(
                "this desktop profile contains an incomplete identity; refusing to create a new address over it"
            )
        }

        val x = OSHICryptoV2.generateX25519()
        val ed = Ed25519PrivateKeyParameters(SecureRandom())
        val edPub = ed.generatePublicKey().encoded
        vault.putAll(identityEntries(DesktopIdentity(x, ed.encoded, edPub)) + originEntry(Origin.GENERATED))
        return DesktopIdentity(x, ed.encoded, edPub)
    }

    /** The account, if this vault holds a COMPLETE one. */
    fun load(vault: KeyVault): DesktopIdentity? {
        val xPriv = vault.get(ACCOUNT_X25519_PRIV) ?: return null
        val xPub = vault.get(ACCOUNT_X25519_PUB) ?: return null
        val edPriv = vault.get(ACCOUNT_ED25519_PRIV) ?: return null
        val edPub = vault.get(ACCOUNT_ED25519_PUB) ?: return null
        return DesktopIdentity(OSHICryptoV2.X25519Pair(xPriv, xPub), edPriv, edPub)
    }

    fun exists(vault: KeyVault): Boolean = load(vault) != null

    /** True for both complete accounts and a damaged/legacy partial identity. */
    fun hasAnyAccountMaterial(vault: KeyVault): Boolean = ACCOUNT_KEYS.any { vault.get(it) != null }

    /**
     * The recovery-key format used by iOS and Android: standard padded base64 of
     * `X25519-private(32) || Ed25519-private(32)`.  It intentionally contains no
     * device metadata, server address or archive key: those are derived or configured
     * locally, and accepting a second format here would make a pasted secret ambiguous.
     *
     * This function never prints or persists the recovery string. Callers must show an
     * explicit destructive-replacement confirmation before invoking [importRecoveryKey].
     */
    fun exportRecoveryKey(identity: DesktopIdentity): String = Base64.getEncoder()
        .encodeToString(identity.identity.priv + identity.signingPrivateBytes())

    /**
     * Parse and validate a mobile-compatible recovery key without changing the vault.
     * Validation derives both public halves, rather than trusting data supplied alongside
     * the secret; this is the only representation the mobile clients export.
     */
    fun parseRecoveryKey(recoveryKey: String): DesktopIdentity {
        val text = recoveryKey.trim().removePrefix("oshi-recovery:")
        if (text.length > MAX_RECOVERY_KEY_TEXT_CHARS) {
            throw IdentityImportException("recovery key is too long")
        }
        val raw = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw IdentityImportException("recovery key is not standard base64", e)
        }
        if (raw.size != RECOVERY_KEY_BYTES) {
            throw IdentityImportException("recovery key must decode to $RECOVERY_KEY_BYTES bytes, got ${raw.size}")
        }
        if (Base64.getEncoder().encodeToString(raw) != text) {
            raw.fill(0)
            throw IdentityImportException("recovery key must use canonical padded base64")
        }
        val xPriv = raw.copyOfRange(0, X25519_PRIVATE_BYTES)
        val edPriv = raw.copyOfRange(X25519_PRIVATE_BYTES, RECOVERY_KEY_BYTES)
        raw.fill(0)
        return try {
            val xPublic = X25519PrivateKeyParameters(xPriv, 0).generatePublicKey().encoded
            val ed = Ed25519PrivateKeyParameters(edPriv, 0)
            DesktopIdentity(OSHICryptoV2.X25519Pair(xPriv, xPublic), ed.encoded, ed.generatePublicKey().encoded)
        } catch (e: Exception) {
            throw IdentityImportException("recovery key does not contain valid X25519 and Ed25519 private keys", e)
        }
    }

    /**
     * Import only into a completely empty account slot.  Replacing an account also
     * requires clearing ratchets, prekeys, contacts and messages atomically; doing only
     * the four key writes would leave ciphertext and sessions owned by another identity.
     */
    fun importRecoveryKey(vault: KeyVault, recoveryKey: String): DesktopIdentity {
        if (hasAnyAccountMaterial(vault)) {
            throw IdentityImportException("this desktop already has an identity; create a new empty profile before importing")
        }
        val identity = parseRecoveryKey(recoveryKey)
        vault.putAll(identityEntries(identity) + originEntry(Origin.IMPORTED))
        return identity
    }

    /** Account deletion, local half. The server half is `DELETE /v2/account`. */
    fun erase(vault: KeyVault) {
        vault.deleteAll(ACCOUNT_KEYS + ACCOUNT_ORIGIN)
    }

    private fun originEntry(origin: Origin): Map<String, ByteArray> =
        mapOf(ACCOUNT_ORIGIN to origin.wire!!.toByteArray(Charsets.UTF_8))

    private const val X25519_PRIVATE_BYTES = 32
    private const val RECOVERY_KEY_BYTES = 64
    /** A 64-byte secret is 88 padded Base64 characters; bound untrusted pasted input. */
    private const val MAX_RECOVERY_KEY_TEXT_CHARS = 256
    private val ACCOUNT_KEYS = listOf(ACCOUNT_X25519_PRIV, ACCOUNT_X25519_PUB, ACCOUNT_ED25519_PRIV, ACCOUNT_ED25519_PUB)

    private fun identityEntries(identity: DesktopIdentity): Map<String, ByteArray> = mapOf(
        ACCOUNT_X25519_PUB to identity.identity.pub,
        ACCOUNT_ED25519_PUB to identity.signingPub,
        ACCOUNT_X25519_PRIV to identity.identity.priv,
        ACCOUNT_ED25519_PRIV to identity.signingPrivateBytes(),
    )
}

class IdentityImportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
