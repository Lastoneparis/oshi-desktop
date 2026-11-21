package com.oshi.desktop.diag

import com.oshi.desktop.store.DesktopPaths
import com.oshi.messenger.service.diag.CallFileLogger
import java.io.File

/**
 * __CALL_LOG_AT_REST_2026_09_23__ The desktop seam of the sealed call log.
 *
 * The logger itself ([CallFileLogger], OSHILOG1) is compiled from the Android tree, like the
 * crypto core, so iOS, Android and this client write the same file format and export the
 * same redacted text. What is desktop-specific is only this:
 *
 *  - **The key** is an HKDF subkey of the vault's local-data root
 *    ([com.oshi.desktop.store.LocalDataKeys.CALL_LOG]), i.e. under the OS-held master key
 *    ([com.oshi.desktop.store.SecretStore]) — the same infrastructure that seals contacts,
 *    groups and media. No new secret to back up or lose.
 *  - **The file** is `call_logs/call_debug.log` in the app data dir, owner-only on POSIX.
 *  - **The export** goes where the person picks in a save dialog, never to a temp folder.
 *
 * Installed by `OshiClient` once the vault is open; before that (and in tests that never
 * build a client) every call here is a no-op.
 */
object DesktopCallLog {
    const val LOG_DIR = "call_logs"
    const val EXPORT_SCRATCH_DIR = "call-diagnostics-tmp"

    @Volatile var logger: CallFileLogger? = null
        private set

    @Synchronized
    fun install(home: File, key: ByteArray) {
        if (logger != null) return
        val dir = File(home, LOG_DIR)
        runCatching { DesktopPaths.ensurePrivateDir(dir) }
        val keyCopy = key.copyOf()
        val created = CallFileLogger(
            directory = dir,
            // Unused by the desktop flow (it writes straight to the chosen file) but purged at
            // every start, so a scratch export from a future code path cannot linger.
            exportDirectory = File(home, EXPORT_SCRATCH_DIR),
            keyProvider = { keyCopy },
            onFileCreated = DesktopPaths::makePrivate,
        )
        logger = created
        // Quitting is the desktop's "background": seal what is buffered on the way out.
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread({ runCatching { created.flushAndWait() } }, "callLogFlush"))
        }
    }

    fun log(line: String) {
        logger?.log(line)
    }

    fun flush() {
        logger?.flush()
    }

    val hasDiagnostics: Boolean get() = logger?.hasDiagnostics == true

    /**
     * Decrypt, apply the cross-platform allow-list redaction, write [target] as UTF-8 text.
     * @return the file written, or null when there is nothing to export. Off the UI thread.
     */
    fun exportTo(target: File): File? {
        val text = logger?.redactedExportText() ?: return null
        val out = if (target.extension.equals("txt", ignoreCase = true)) target else File(target.path + ".txt")
        out.writeText(text, Charsets.UTF_8)
        return out
    }
}
