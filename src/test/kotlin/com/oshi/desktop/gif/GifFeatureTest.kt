package com.oshi.desktop.gif

import java.io.File
import com.oshi.desktop.store.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `__GIF_PACK_2026_09_23__` — the desktop GIF picker's library, decoder, GIPHY parse and
 * wire label, against the REAL pack both phones ship (read from the Android tree by
 * `processResources`, byte-identical to `OSHI/GifPack`).
 */
class GifFeatureTest {

    private val lib = LocalGifLibrary(language = "fr")

    @Test
    fun `the whole iOS and Android pack is on the classpath`() {
        assertEquals("GifPack/manifest.json lists 1,718 GIFs, all present", 1718, lib.all(LocalGifLibrary.Kind.GIF).size)
        assertEquals("StickerPack/manifest.json lists 1,454 stickers, all present", 1454, lib.all(LocalGifLibrary.Kind.STICKER).size)
    }

    @Test
    fun `categories follow iOS's hand-picked order`() {
        assertEquals(LocalGifLibrary.PREFERRED_CATEGORIES, lib.categories(LocalGifLibrary.Kind.GIF))
        assertEquals(LocalGifLibrary.PREFERRED_CATEGORIES, lib.categories(LocalGifLibrary.Kind.STICKER))
    }

    @Test
    fun `drawn-only first, then the user's language, then English, then the rest`() {
        val langs = lib.all(LocalGifLibrary.Kind.GIF).map { it.lang }
        fun rank(l: String) = when (l) { "any" -> 0; "fr" -> 1; "en" -> 2; else -> 3 }
        assertEquals(langs.sortedBy(::rank), langs)
        assertEquals("any", langs.first())
        assertTrue(langs.indexOf("fr") < langs.indexOf("en"))
    }

    @Test
    fun `search is case- and accent-insensitive and category-scoped`() {
        val any = lib.search("THANK", LocalGifLibrary.Kind.GIF)
        assertTrue(any.isNotEmpty())
        val thanks = lib.search("", LocalGifLibrary.Kind.GIF, "Thanks")
        assertEquals(65, thanks.size)
        assertTrue(thanks.all { it.category == "Thanks" })
        assertEquals(LocalGifLibrary.fold("félicitations"), LocalGifLibrary.fold("FELICITATIONS"))
    }

    @Test
    fun `a malformed manifest entry or a missing file is skipped, never fatal`() {
        val files = mapOf(
            "gifpacks/GifPack/manifest.json" to """{"gifs":[{"id":"ok","file":"ok.gif","category":"Love","tags":["x"]},
                {"file":"noid.gif"},{"id":"missing","file":"missing.gif","category":"Love","tags":[]}, 42]}""".toByteArray(),
            "gifpacks/GifPack/ok.gif" to "GIF89a".toByteArray(),
        )
        val l = LocalGifLibrary(language = "en", loader = { files[it] }, exists = { it in files })
        assertEquals(listOf("ok"), l.all(LocalGifLibrary.Kind.GIF).map { it.id })
        assertEquals("en", l.all(LocalGifLibrary.Kind.GIF).single().lang)
        assertTrue(l.all(LocalGifLibrary.Kind.STICKER).isEmpty())
    }

    @Test
    fun `a pack GIF decodes to several animated frames with real delays`() {
        val item = lib.all(LocalGifLibrary.Kind.GIF).first { it.fileName == "p_m_fire.gif" }
        val bytes = lib.bytes(item)
        assertNotNull(bytes); bytes!!
        assertTrue(AnimatedGif.isGif(bytes))
        val frames = AnimatedGif.decodeUncached(bytes, 192)!!
        assertTrue("a pack GIF must animate, got ${frames.frames.size} frame(s)", frames.isAnimated)
        assertEquals(frames.frames.size, frames.delaysMs.size)
        assertTrue(frames.delaysMs.all { it >= 20 })
        assertTrue(frames.frames.all { it.width <= 192 })
    }

    @Test
    fun `every pack GIF decodes`() {
        // A cell that silently never draws is the failure iOS's manifest filter exists for.
        val failures = (lib.all(LocalGifLibrary.Kind.GIF) + lib.all(LocalGifLibrary.Kind.STICKER)).filter {
            AnimatedGif.decodeUncached(lib.bytes(it)!!, 96) == null
        }
        assertEquals("undecodable: ${failures.take(5).map { it.fileName }}", 0, failures.size)
    }

    @Test
    fun `garbage is not a GIF and does not decode`() {
        assertFalse(AnimatedGif.isGif("GIF8".toByteArray()))
        assertFalse(AnimatedGif.isGif(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0)))
        assertNull(AnimatedGif.decodeUncached(ByteArray(64) { 7 }, 96))
    }

    @Test
    fun `long animations are subsampled, keeping the loop's length`() {
        val bytes = syntheticGif(frames = 400, delayCs = 5)
        val f = AnimatedGif.decodeUncached(bytes, 0)!!
        assertTrue("kept ${f.frames.size}", f.frames.size <= AnimatedGif.MAX_FRAMES)
        assertEquals("the loop must last 400 × 50 ms whatever was dropped", 400 * 50, f.delaysMs.sum())
    }

    @Test
    fun `GIPHY renditions are chosen as on iOS`() {
        val body = """{"data":[
            {"id":"a","title":"Hi","images":{
              "fixed_width_small":{"url":"https://m/a_s.gif","size":"1000"},
              "downsized_small":{"url":"https://m/a_ds.gif","size":"9999999"},
              "fixed_width":{"url":"https://m/a_fw.gif","size":"200000"},
              "original":{"url":"https://m/a_o.gif","size":"300000"}}},
            {"id":"b","images":{"preview_gif":{"url":"https://m/b_p.gif"},"fixed_width":{"url":"https://m/b_fw.gif"}}},
            {"id":"c","images":{}}]}"""
        val items = GiphyClient.parse(body)
        assertEquals(listOf("a", "b"), items.map { it.id })
        assertEquals("https://m/a_s.gif", items[0].previewUrl)
        assertEquals("downsized_small is over 5 MB, so fixed_width is sent", "https://m/a_fw.gif", items[0].sendUrl)
        assertEquals("https://m/b_p.gif", items[1].previewUrl)
    }

    @Test
    fun `with no key GIPHY is off and asks the network nothing`() {
        val c = GiphyClient(apiKey = null)
        assertFalse(c.isConfigured)
        assertTrue(c.feed(GiphyClient.Kind.GIF, 0).isEmpty())
        assertTrue(c.search("cat", GiphyClient.Kind.STICKER).isEmpty())
        assertEquals(10, GiphyClient.FEED_TERMS.size)
    }

    @Test
    fun `a GIF goes out labelled gif and everything else keeps its label`() {
        assertEquals("gif", GifWire.label(MediaType.IMAGE, "image/gif"))
        assertEquals("image", GifWire.label(MediaType.IMAGE, "image/jpeg"))
        assertEquals("document", GifWire.label(MediaType.DOCUMENT, "image/gif"))
        assertEquals("iOS's `gif` label is an image here, as on Android", MediaType.IMAGE, MediaType.fromWire("gif"))
    }

    @Test
    fun `a picked GIF is written sealed, byte-for-byte through the vault, with a gif name`() {
        val dir = kotlin.io.path.createTempDirectory("gif-outbox").toFile()
        try {
            val vault = com.oshi.desktop.store.MediaVault(File(dir, "media"), File(dir, "media-tmp"), ByteArray(32) { 7 })
            val bytes = "GIF89a-body".toByteArray()
            val f = com.oshi.desktop.ui.components.GifOutbox.write(bytes, "../../evil name", dir, vault)
            assertEquals(dir, f.parentFile)
            assertTrue(f.name.endsWith(".gif"))
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ never plaintext at rest.
            assertTrue(com.oshi.desktop.store.MediaVault.isSealed(f))
            assertTrue(!f.readBytes().contentEquals(bytes))
            assertTrue(vault.openStream(f).use { it.readBytes() }.contentEquals(bytes))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A minimal valid GIF89a: [frames] 1×1 frames, each [delayCs] centiseconds. */
    private fun syntheticGif(frames: Int, delayCs: Int): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write("GIF89a".toByteArray())
        o.write(byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0))          // 1×1, GCT 2 colours
        o.write(byteArrayOf(0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        o.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); o.write("NETSCAPE2.0".toByteArray()); o.write(byteArrayOf(3, 1, 0, 0, 0))
        repeat(frames) { i ->
            o.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, (delayCs and 0xFF).toByte(), (delayCs shr 8).toByte(), 0, 0))
            o.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0))
            o.write(byteArrayOf(2, 2, (if (i % 2 == 0) 0x44 else 0x4C).toByte(), 1, 0))
        }
        o.write(0x3B)
        return o.toByteArray()
    }
}
