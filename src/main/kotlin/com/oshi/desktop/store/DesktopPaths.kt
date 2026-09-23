package com.oshi.desktop.store

import java.io.File

/**
 * Where a desktop OSHI client keeps its data, per OS convention.
 *
 * Small, boring, and worth its own file because every store below it — keys, sessions,
 * messages, blobs — inherits these decisions, and getting them wrong is the kind of bug
 * that only shows up on someone else's machine:
 *
 *  - **Windows** `%APPDATA%\OSHI` — roaming, which is what a messaging identity wants on
 *    a domain-joined machine. Not `%LOCALAPPDATA%`, not the install directory.
 *  - **macOS** `~/Library/Application Support/OSHI` — the documented location, and the
 *    one Time Machine backs up.
 *  - **Linux** `$XDG_DATA_HOME/oshi`, defaulting to `~/.local/share/oshi` per the XDG
 *    Base Directory spec. Not `~/.oshi`: a dotfile in $HOME is what an application wrote
 *    in 1995, and on a machine with a small $HOME quota it is the wrong disk.
 *
 * `OSHI_HOME` overrides everything, which is what the tests use and what a portable
 * install on a USB stick would need.
 */
object DesktopPaths {

    val os: String = System.getProperty("os.name").orEmpty().lowercase()

    val isWindows: Boolean get() = os.contains("win")
    val isMac: Boolean get() = os.contains("mac") || os.contains("darwin")
    val isLinux: Boolean get() = !isWindows && !isMac

    /** The root data directory. Created on first access, with owner-only permissions. */
    val dataDir: File by lazy { resolveDataDir().also { ensurePrivateDir(it) } }

    fun file(name: String): File = File(dataDir, name)

    private fun resolveDataDir(): File {
        System.getenv("OSHI_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val home = System.getProperty("user.home") ?: "."
        return when {
            isWindows -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "OSHI")
            isMac -> File(home, "Library/Application Support/OSHI")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "oshi")
        }
    }

    /**
     * Create a directory only its owner can read.
     *
     * On POSIX this is 0700 and it is enforced. On Windows the JDK's `setReadable` shims
     * do nothing useful for ACLs, so there the directory is protected by the user profile
     * and by nothing else this code does.
     *
     * **THIS COMMENT USED TO SAY "the FILES inside are encrypted anyway" AND THAT WAS
     * FALSE** — contacts, groups, scheduled messages and every received attachment sat in
     * plaintext beside the encrypted vault. Since __LOCAL_DATA_AT_REST_2026_09_22__ it is true,
     * and here is exactly what is and is not covered:
     *
     *  - `keyvault.json` — [KeyVault], under the OS-held master key ([SecretStore]).
     *  - `messages/` — [MessageStore], per-record AES-GCM under a vault entry.
     *  - `contacts.json`, `groups.json`, `scheduled-messages.json` — [SealedJsonFile]
     *    envelopes under HKDF subkeys of the vault entry [LocalDataKeys.ACCOUNT].
     *  - `media/` — [MediaVault] "OSHIMED1" chunked AES-GCM, sealed on arrival; older
     *    plaintext files are sealed in place at client start.
     *  - `media-tmp/` — DECRYPTED scratch copies (voice-note playback, "open in another
     *    app") and audio recording/transcoding work files. Plaintext by necessity; deleted
     *    after use where possible, at exit, and swept at every start.
     *  - NOT encrypted: small non-content state — `receipt-preferences.json`,
     *    `router-state.json` (relay cursor + recently seen message ids), `sync-cursor.json`,
     *    `call-rating.json`, `ui-wallpapers.json`, `maps/`, the welcome marker — and anything
     *    the user explicitly exports or saves elsewhere.
     *
     * Full-disk encryption remains the layer that protects the scratch window and the
     * metadata (file sizes, counts, timestamps) this layout still exposes.
     *
     * Never rely on directory permissions alone for secrets.
     */
    fun ensurePrivateDir(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("cannot create data directory: $dir")
        }
        if (!isWindows) {
            dir.setReadable(false, false); dir.setReadable(true, true)
            dir.setWritable(false, false); dir.setWritable(true, true)
            dir.setExecutable(false, false); dir.setExecutable(true, true)
        }
    }

    /** Same idea for a file: 0600 on POSIX, best-effort elsewhere. */
    fun makePrivate(file: File) {
        if (isWindows) return
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
    }
}
