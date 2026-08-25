package com.oshi.desktop.sync

import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.KeyVault

/**
 * The one place an archive key is produced from the stored account — PARITY.md row 0.24.
 *
 * Desktop equivalent of iOS's `V2ArchiveKeySource` (`V2SyncManager.swift:43-67`), which
 * exists there for the same two reasons: the derivation needs raw private-key bytes, and a
 * manager that reached into the Keychain itself could not be unit-tested. Here the seam is
 * the [KeyVault] rather than a protocol, because [IdentityStore] already owns the mapping
 * from account to bytes and a second abstraction over it would be a second place for the
 * two key pairs to get swapped.
 *
 * **And swapping them is the failure this file exists to prevent.** The two archives derive
 * from two DIFFERENT key pairs and both derivations accept any 32 bytes:
 *
 *   - [v2] takes the **Ed25519 signing** key (`IdentityStore.ACCOUNT_ED25519_PRIV`), matching
 *     iOS's `"signingKey"` Keychain item (`KeychainHelper.swift:91`,
 *     `V2SyncManager.swift:55-66`).
 *   - [legacy] takes the **X25519 messaging** key (`IdentityStore.ACCOUNT_X25519_PRIV`),
 *     matching iOS's `"privateKey"` item (`KeychainHelper.swift:90`,
 *     `MultiDeviceSyncManager.swift:1004`) and Android's `cryptoManager.exportPrivateKeyBytes()`
 *     (`CryptoManager.kt:47,152-155`).
 *
 * Hand either derivation the other pair's key and you get 32 valid-looking bytes that
 * decrypt nothing — and the symptom is "every archive item is corrupt", which reads as a
 * server problem rather than a client one. Two named functions over two named vault
 * accounts is the whole mitigation, and it is worth a file because the alternative is a
 * `ByteArray` parameter at each call site with a comment saying which one.
 *
 * All three clients store these as raw 32-byte scalars — iOS saves `rawRepresentation`
 * (`IdentityManager.swift:563-570`), Android exports `X25519PrivateKeyParameters.encoded`,
 * and [IdentityStore] writes exactly those bytes — so a desktop, an iPhone and an Android
 * on one imported identity derive the same two keys and read each other's archives.
 *
 * **No caching.** iOS caches the derived key on the actor (`V2SyncManager.swift:133,287`);
 * this does not, because the vault read is already memory-resident and a cached copy of a
 * long-lived symmetric key is one more place it outlives the object that needed it. The
 * caller holds it for as long as its [SyncEngine] lives, which is the same lifetime iOS's
 * cache has, without a second copy.
 */
object ArchiveKeys {

    /**
     * The V2 archive key for the identity in [vault].
     *
     * @throws MissingIdentityException when the vault holds no signing key — never a
     *   zero-length derivation. See [SyncCrypto.v2ArchiveKey]: HKDF over nothing is a
     *   perfectly valid 32 bytes, and an identity that failed to load must not read as an
     *   archive whose every item is corrupt.
     */
    fun v2(vault: KeyVault): ByteArray =
        SyncCrypto.v2ArchiveKey(
            vault.get(IdentityStore.ACCOUNT_ED25519_PRIV)
                ?: throw MissingIdentityException(
                    "no Ed25519 signing key in the vault — cannot derive the /v2/sync archive key. " +
                        "This is the key iOS reads from the \"signingKey\" Keychain item " +
                        "(V2SyncManager.swift:55-66), NOT the X25519 messaging key."
                )
        )

    /**
     * The legacy `/api/sync` archive key for the identity in [vault]. Note this reads the
     * X25519 key — the OTHER pair. See the class doc.
     */
    fun legacy(vault: KeyVault): ByteArray =
        SyncCrypto.legacyArchiveKey(
            vault.get(IdentityStore.ACCOUNT_X25519_PRIV)
                ?: throw MissingIdentityException(
                    "no X25519 messaging key in the vault — cannot derive the /api/sync archive key. " +
                        "This is the key iOS reads from the \"privateKey\" Keychain item " +
                        "(MultiDeviceSyncManager.swift:1004), NOT the Ed25519 signing key."
                )
        )
}

class MissingIdentityException(message: String) : RuntimeException(message)
