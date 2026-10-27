package com.oshi.desktop.store

import com.oshi.messenger.network.v2.OSHICryptoV2
import java.security.SecureRandom

/**
 * __LOCAL_DATA_AT_REST_2026_09_22__ Keys for the local stores that are not message history.
 *
 * ONE new vault entry ([ACCOUNT], 32 random bytes) and one HKDF-SHA256 subkey per purpose
 * derived from it. The subkeys are never stored: they are recomputed from the vault entry on
 * every start, so there is exactly one secret to back up, restore or destroy, and it lives
 * where every other secret lives — in [KeyVault], under the OS-held master key.
 *
 * Why a NEW entry and not a subkey of [MessageStore.HISTORY_KEY_ACCOUNT]: that key is also
 * what an encrypted history export is keyed against (another agent's code), and tying the
 * contact list to it would make the two impossible to rotate separately.
 *
 * Distinct `info` per purpose means a sealed `groups.json` cannot be opened with the
 * contacts key even if the files are swapped on disk; each [SealedJsonFile] envelope also
 * binds its purpose in the AEAD's associated data, so the swap fails twice.
 */
object LocalDataKeys {
    const val ACCOUNT = "local-data-key-v1"

    const val CONTACTS = "contacts-v1"
    const val GROUPS = "groups-v1"
    const val SCHEDULED = "scheduled-messages-v1"
    const val MEDIA = "media-v1"
    /** __CALL_LOG_AT_REST_2026_09_23__ The sealed call diagnostics log ([com.oshi.desktop.diag.DesktopCallLog]). */
    const val CALL_LOG = "call-log-v1"
    /** __BLOCKED_NOTICE_SWITCH_2026_09_23__ [com.oshi.desktop.block.BlockedNoticeGate] stamps. */
    const val BLOCKED_NOTICE = "blocked-notice-v1"
    /** __BLOCKED_BY_PEER_2026_09_24__ [com.oshi.desktop.block.BlockedByPeerStore] records. */
    const val BLOCKED_BY_PEER = "blocked-by-peer-v1"
    /** __DESKTOP_REPORT_2026_09_23__ [com.oshi.desktop.net.V2ReportClient] local report log. */
    const val REPORTS = "reports-v1"

    private val SALT = "oshi-desktop-local-data-at-rest-v1".toByteArray(Charsets.UTF_8)

    /** Read-or-create the root in one vault operation, so two callers cannot mint two roots. */
    fun root(vault: KeyVault): ByteArray =
        vault.getOrCreate(ACCOUNT) { ByteArray(32).also(SecureRandom()::nextBytes) }

    fun derive(root: ByteArray, purpose: String): ByteArray {
        require(root.size == 32) { "local data root key must be 32 bytes" }
        return OSHICryptoV2.hkdf(root, SALT, "oshi-desktop/$purpose".toByteArray(Charsets.UTF_8), 32)
    }
}
