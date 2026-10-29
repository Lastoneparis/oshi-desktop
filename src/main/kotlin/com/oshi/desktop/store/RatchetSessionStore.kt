package com.oshi.desktop.store

import com.oshi.messenger.network.encryption.DoubleRatchet
import java.io.File

/**
 * Where this client keeps the legacy ratchet state — the desktop's half of the seam whose
 * Android half is `DoubleRatchetAndroidStore.kt`.
 *
 * __RATCHET_IS_SHARED_SOURCE_2026_10_28__ The ratchet itself is shared source; WHERE its
 * sessions live is per-platform by design. Android uses `EncryptedSharedPreferences` over
 * the Keystore; this uses a [SealedJsonFile] under [LocalDataKeys.RATCHET_SESSIONS], which
 * is the same standard [ContactStore] gets.
 *
 * ============================================================ FAIL CLOSED, LIKE ANDROID
 *
 * The serialized sessions are the root key, both chain keys, every skipped message key and
 * the ratchet private key. Android refuses to degrade to plaintext prefs on a Keystore
 * hiccup and iOS returns without writing on `KeychainError.locked`; this refuses for the
 * same reason — [save] throws rather than writing an unsealed file when an at-rest key was
 * expected and is missing.
 *
 * ============================================================ A FAILED SAVE IS NOT A
 * COSMETIC PROBLEM
 *
 * If a write does not land while the in-memory chain has already advanced, the next launch
 * rewinds to a state whose message keys are already on the wire — an AES-256-GCM key reused
 * across two plaintexts. That is why [save] does not swallow, and why the send path persists
 * BEFORE the bytes leave.
 */
class RatchetSessionStore(
    private val file: File = DesktopPaths.file(FILE_NAME),
    private val atRestKey: ByteArray? = null,
) {

    /** Load persisted sessions into [ratchet]. Returns how many loaded. */
    @Synchronized
    fun load(ratchet: DoubleRatchet): Int {
        val read = try {
            SealedJsonFile.read(file, atRestKey, LocalDataKeys.RATCHET_SESSIONS) ?: return 0
        } catch (_: Exception) {
            // Unreadable state is empty state: the peer's next message re-establishes a
            // session. Refusing to start would be a worse answer than starting without it.
            return 0
        }
        return try {
            ratchet.importSessionsJson(read.json)
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Persist every session.
     *
     * Deliberately NOT wrapped in a catch — see the class doc. A caller that cannot
     * tolerate the throw must decide that for itself, in view of the key-reuse it is
     * choosing to risk.
     */
    @Synchronized
    fun save(ratchet: DoubleRatchet) {
        SealedJsonFile.write(file, atRestKey, LocalDataKeys.RATCHET_SESSIONS, ratchet.exportSessionsJson())
    }

    companion object {
        const val FILE_NAME = "ratchet-sessions.json"
    }
}
