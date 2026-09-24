package com.oshi.desktop.ui.components

import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.MediaVault
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
 * __PLAINTEXT_LEFTOVERS_2026_09_24__ SEALED on write ("OSHIMED1", [MediaVault]) like every
 * other stored attachment — it used to be plaintext and, living in a SUBdirectory, was never
 * reached by the start-up migration of `media/` (which now covers `media/gifs/` too). Which
 * GIF a user sent to whom is content even when the GIF itself is public. The send path
 * reads it back through the vault (`OshiClient.sendFile`), so the wire bytes are unchanged.
 */
internal object GifOutbox {
    fun write(
        bytes: ByteArray,
        name: String,
        dir: File = File(DesktopPaths.dataDir, "media/gifs"),
        vault: MediaVault? = MediaVault.current(),
    ): File {
        // No vault ⇒ no client to send it anyway; never fall back to plaintext.
        val v = vault ?: throw IllegalStateException("no media vault: GIF not written")
        DesktopPaths.ensurePrivateDir(dir)
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            .let { if (it.endsWith(".gif", ignoreCase = true)) it else "$it.gif" }
        val out = File(dir, "${UUID.randomUUID().toString().take(8)}-$safe")
        try {
            v.sealingStream(out).use { it.write(bytes) }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return out
    }
}
