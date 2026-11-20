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
            val exts = (pathExt?.takeIf { it.isNotBlank() } ?: ".EXE;.COM;.BAT;.CMD")
                .split(';').filter { it.isNotBlank() }
            listOf(binary) + exts.map { binary + it.lowercase() }
        }
        for (dir in path.split(if (windows) ';' else ':')) {
            val d = dir.trim().trim('"')
            if (d.isEmpty()) continue
            for (n in names) {
                val f = File(d, n)
                if (isExecutable(f)) return f.absolutePath
            }
        }
        return null
    }
}
