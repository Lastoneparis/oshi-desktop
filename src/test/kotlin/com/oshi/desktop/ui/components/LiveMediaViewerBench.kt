package com.oshi.desktop.ui.components

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * A photograph crossing the live relay and OPENING IN THE VIEWER — PARITY row 1.4.
 *
 * Row 0.15 already proves the bytes survive the blob store. This proves the rest of the
 * chain, which is where row 1.4 lives and which no unit test covers end to end: the blob is
 * fetched, written through `MediaVault.sealingStream` so the plaintext never lands on disk,
 * the row reaches the UI as a `MediaPresentation`, and `MediaViewerLoader` decrypts it IN
 * MEMORY past its size, signature, header and pixel-count gates and hands back something
 * with the sender's actual pixels in it.
 *
 * The picture is one flat, unmistakable colour, so "it decoded" and "it decoded the RIGHT
 * image" are different assertions. A viewer that silently drew a placeholder, or decoded a
 * truncated blob into a grey rectangle, passes "not blank" and fails this.
 *
 * Gate: `OSHI_LIVE_MEDIA=1`. Throwaway identities on production.
 */
class LiveMediaViewerBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun live() = Assume.assumeTrue("set OSHI_LIVE_MEDIA=1", System.getenv("OSHI_LIVE_MEDIA") == "1")

    /** A colour no UI chrome produces, so finding it means the photo arrived. */
    private val fixture = 0xFFFF00FF.toInt()

    private fun client(name: String, dir: File): OshiClient {
        val c = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = name,
            log = { logs += "[$name] $it" })
        var open = false
        for (attempt in 0 until 3) {
            runCatching { c.router.refreshConfig() }
            if (c.config.isEnabledCached()) { open = true; break }
            Thread.sleep(1_000)
        }
        assertTrue("$name: v2 gate closed after 3 config fetches", open)
        assertTrue("$name: bundle publish", c.router.publishBundleIfNeeded())
        runCatching { c.deviceMailbox.tick() }
        return c
    }

    private fun pump(vararg cs: OshiClient) {
        for (c in cs) { runCatching { c.deviceMailbox.tick() }; runCatching { c.router.poll() } }
        Thread.sleep(700)
    }

    private fun until(what: String, vararg cs: OshiClient, timeoutMs: Long = 120_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            pump(*cs)
        }
        synchronized(logs) { logs.takeLast(50).forEach(::println) }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun photoOpensInTheViewerOnProduction() {
        live()
        val tag = java.util.UUID.randomUUID().toString().take(8)
        val aDir = Files.createTempDirectory("oshi-live-media-a-$tag").toFile()
        val bDir = Files.createTempDirectory("oshi-live-media-b-$tag").toFile()
        val a = client("a", aDir); val b = client("b", bDir)

        val png = File(aDir, "canary-$tag.png")
        BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB).also { img ->
            for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, fixture)
            ImageIO.write(img, "png", png)
        }
        assertTrue("the fixture did not write", png.length() > 100)

        val outcome = a.sendFile(b.address, png, MediaType.IMAGE)
        println("LIVE_MEDIA sendFile: $outcome  (${png.length()} bytes)")

        // B receives the row and the blob lands on B's disk, sealed.
        until("B receives the photo row", a, b) {
            b.messages.messages(a.address).any { it.mediaType == MediaType.IMAGE && it.mediaRef != null }
        }
        val row = b.messages.messages(a.address).first { it.mediaType == MediaType.IMAGE && it.mediaRef != null }
        val path = row.mediaRef!!
        assertTrue("the received media file is not on B's disk: $path", File(path).isFile)

        // Sealed at rest: the plaintext PNG signature must NOT be sitting in the file.
        val head = File(path).readBytes().take(8)
        val pngMagic = listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(
            "the received photo is stored as PLAINTEXT PNG on disk — MediaVault did not seal it",
            head != pngMagic,
        )

        // ...and the VIEWER opens it: decrypt in memory, past every gate, right pixels.
        val load = MediaViewerLoader.load(path)
        println("LIVE_MEDIA viewer load: ${load::class.simpleName}")
        assertTrue("the viewer refused the received photo: $load", load is MediaLoad.Ready)
        val ready = load as MediaLoad.Ready
        assertEquals("the viewer decoded the wrong width", 240, ready.width)
        assertEquals("the viewer decoded the wrong height", 160, ready.height)
        assertEquals("PNG", ready.format)

        // THE PIXELS, not just the dimensions. Without this the comment above this test is
        // a claim it does not make: a viewer that drew a placeholder of the right size, or
        // decoded a truncated blob into a grey rectangle, satisfies everything else here.
        val px = IntArray(ready.width * ready.height)
        ready.image.readPixels(px, 0, 0, ready.width, ready.height)
        val wrong = px.count { it != fixture }
        assertEquals(
            "the viewer decoded $wrong of ${px.size} pixels as something other than the " +
                "colour that was sent — it opened SOMETHING, but not this photograph",
            0, wrong,
        )

        println("LIVE_MEDIA OK — ${ready.width}x${ready.height} ${ready.format}, sealed on disk, opened in the viewer")
        aDir.deleteRecursively(); bDir.deleteRecursively()
    }
}
