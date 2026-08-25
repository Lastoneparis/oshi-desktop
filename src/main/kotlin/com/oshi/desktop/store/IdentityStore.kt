package com.oshi.desktop.store

import com.oshi.desktop.DesktopIdentity
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom

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

    /** Load the account, creating it on first run. */
    fun loadOrCreate(vault: KeyVault): DesktopIdentity {
        load(vault)?.let { return it }

        val x = OSHICryptoV2.generateX25519()
        val ed = Ed25519PrivateKeyParameters(SecureRandom())
        val edPub = ed.generatePublicKey().encoded
        // Public halves first, privates last. A crash between the two writes then leaves
        // a vault that reads as "no identity" and starts over cleanly — the recoverable
        // direction. The reverse order would leave privates with no addresses, which
        // reads as an account and cannot be used as one.
        vault.put(ACCOUNT_X25519_PUB, x.pub)
        vault.put(ACCOUNT_ED25519_PUB, edPub)
        vault.put(ACCOUNT_X25519_PRIV, x.priv)
        vault.put(ACCOUNT_ED25519_PRIV, ed.encoded)
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

    /** Account deletion, local half. The server half is `DELETE /v2/account`. */
    fun erase(vault: KeyVault) {
        listOf(ACCOUNT_X25519_PRIV, ACCOUNT_X25519_PUB, ACCOUNT_ED25519_PRIV, ACCOUNT_ED25519_PUB)
            .forEach { vault.delete(it) }
    }
}
