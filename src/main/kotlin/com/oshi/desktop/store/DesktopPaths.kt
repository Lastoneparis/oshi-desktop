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
     * On POSIX this is 0700 and it is enforced. On Windows the JDK's `setReadable`
     * shims do nothing useful for ACLs — the honest position is that the directory is
     * protected by the user profile, and that the FILES inside are encrypted anyway
     * (see [KeyVault]). Never rely on directory permissions alone for secrets.
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
