package com.oshi.desktop.ui.components

import com.oshi.desktop.store.DesktopPaths
import java.io.File
import java.util.UUID

/**
 * Where a picked GIF is written before it goes out — `__GIF_PACK_2026_09_23__`.
 *
 * The send path (`OshiClient.sendFile` / `sendGroupFile`) takes a FILE and keeps its path as
 * the sender's own bubble, so the GIF is written once under `media/gifs/` and stays there:
 * a scratch file would be swept and leave our own message pointing at nothing. The bytes are
 * written UNTOUCHED (iOS `processGIF`: re-encoding a GIF flattens it) and the name keeps its
 * `.gif` extension, which is what makes the probed MIME `image/gif`.
 *
 * Plaintext on disk, like any file the user attaches from their own disk; `media/` is sealed
 * in place at the next client start. The content is a public pack or GIPHY file either way.
 */
internal object GifOutbox {
    fun write(bytes: ByteArray, name: String, dir: File = File(DesktopPaths.dataDir, "media/gifs")): File {
        dir.mkdirs()
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            .let { if (it.endsWith(".gif", ignoreCase = true)) it else "$it.gif" }
        val out = File(dir, "${UUID.randomUUID().toString().take(8)}-$safe")
        out.writeBytes(bytes)
        return out
    }
}
