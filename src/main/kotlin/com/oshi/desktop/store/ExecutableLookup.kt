package com.oshi.desktop.store

import java.io.File

/**
 * __WHICH_FORGOT_DOT_EXE_2026_11_19__ `which`, without a shell, on every desktop OS.
 *
 * Both copies this replaces looked for `File(dir, "ffmpeg")`. On Windows the file on PATH is
 * `ffmpeg.exe`, so the lookup returned null for every Windows user who HAD ffmpeg installed,
 * and every AAC/M4A voice note from a phone reported "ffmpeg: not on PATH" and did not play.
 * On Windows the name is now also tried with each PATHEXT extension (default .EXE;.COM;.BAT;.CMD).
 */
object ExecutableLookup {
    fun which(
        binary: String,
        path: String? = System.getenv("PATH"),
        windows: Boolean = DesktopPaths.isWindows,
        pathExt: String? = System.getenv("PATHEXT"),
        isExecutable: (File) -> Boolean = { it.isFile && (windows || it.canExecute()) },
    ): String? {
        if (path.isNullOrEmpty()) return null
        val names = if (!windows || binary.contains('.')) listOf(binary) else {
            // __WINDOWS_BARE_EXE_HIJACK_2026_11_20__ never a .bat/.cmd: it runs through cmd.exe,
            // which re-parses the arguments (BatBadBut, CVE-2024-24576 class) — and the arguments
            // here include file names that came from a peer.
            val exts = (pathExt?.takeIf { it.isNotBlank() } ?: ".EXE;.COM")
                .split(';').filter { it.isNotBlank() && !it.equals(".BAT", true) && !it.equals(".CMD", true) }
            listOf(binary) + exts.map { binary + it.lowercase() }
        }
        for (dir in path.split(if (windows) ';' else ':')) {
            val d = dir.trim().trim('"')
            // A relative entry ("." or "bin") resolves against the CURRENT directory, which a
            // link launch inherits from whatever started it.
            if (d.isEmpty() || !File(d).isAbsolute) continue
            for (n in names) {
                val f = File(d, n)
                if (isExecutable(f)) return f.absolutePath
            }
        }
        return null
    }
}
