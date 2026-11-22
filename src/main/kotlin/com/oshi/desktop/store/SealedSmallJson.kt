package com.oshi.desktop.store

import java.io.File

/**
 * __SOCIAL_GRAPH_LEAK_AT_REST_2026_11_20__ Read/write for the small per-conversation JSON maps
 * that were still plaintext beside a sealed contact list.
 *
 * `contacts.json` and `groups.json` are sealed because they are the social graph. Five other
 * files in the same directory were not, and each one is keyed BY that graph:
 *
 *  - `contact-links.json` — every contact's public key → the social-media links they shared
 *    (the one file here that can put a real name on an OSHI address);
 *  - `poll-ballots.json` — who voted what, by voter key;
 *  - `offline-only.json` — conversation ids plus the keys that confirmed;
 *  - `conversation-preferences.json` — pinned/muted conversation ids (peer keys, group ids);
 *  - `pinned-messages.json` — conversation id → pinned message id.
 *
 * A copied profile, a backup or a seized disk therefore listed who the person talks to even
 * though the contact list itself was encrypted. These now go through [SealedJsonFile] under
 * their own [LocalDataKeys] purpose.
 *
 * Rules, the same as every sealed store:
 *  - a legacy plaintext file is read and REWRITTEN sealed at once (migration);
 *  - a sealed file that does not open (wrong key, tampered) is never overwritten — the store
 *    runs in memory for that process ([Loaded.writable] = false), so nothing is destroyed;
 *  - [key] null = the historical plaintext behaviour (tests, pre-vault callers only).
 */
internal object SealedSmallJson {

    class Loaded(val json: String?, val writable: Boolean, val needsMigration: Boolean)

    fun load(file: File, key: ByteArray?, purpose: String): Loaded {
        if (!file.isFile) return Loaded(null, writable = true, needsMigration = false)
        return try {
            val r = SealedJsonFile.read(file, key, purpose)
                ?: return Loaded(null, writable = true, needsMigration = false)
            Loaded(r.json, writable = true, needsMigration = key != null && !r.sealed)
        } catch (_: SealedStoreException) {
            // Not JSON at all (torn legacy write) → nothing worth keeping, safe to replace.
            // A sealed envelope that fails to open → keep it untouched.
            val looksSealed = runCatching { file.readText(Charsets.UTF_8).contains(SealedJsonFile.FORMAT) }.getOrDefault(true)
            Loaded(null, writable = !looksSealed, needsMigration = false)
        } catch (_: Exception) {
            Loaded(null, writable = false, needsMigration = false)
        }
    }

    fun save(file: File, key: ByteArray?, purpose: String, json: String) {
        if (key == null) {
            file.parentFile?.mkdirs()
            AtomicFile.write(file, json.toByteArray(Charsets.UTF_8))
        } else {
            SealedJsonFile.write(file, key, purpose, json)
        }
    }
}
