package com.oshi.desktop.mascot

import com.oshi.desktop.gif.GifWire
import com.oshi.desktop.gif.LocalGifLibrary
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.ui.components.GifOutbox
import com.oshi.desktop.ui.components.mascotSearch
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode

/**
 * __OSHI_MASCOT_STICKERS_2026_11_21__ The OSHI sticker tab ([OshiStickerPack]): tab order,
 * pack order, what a click loads, that it rides the existing GIF wire, and that each GIF's
 * first frame — all iOS 1.0.43 shows — is the mascot's still pose.
 */
class OshiStickerPackTest {

    @Test
    fun `the OSHI tab comes first, opens by default and never calls GIPHY`() {
        assertEquals(listOf(OshiStickerPack.Tab.OSHI, OshiStickerPack.Tab.GIFS, OshiStickerPack.Tab.STICKERS), OshiStickerPack.TABS)
        assertEquals(OshiStickerPack.Tab.OSHI, OshiStickerPack.DEFAULT_TAB)
        assertFalse(OshiStickerPack.usesGiphy(OshiStickerPack.Tab.OSHI))
        assertTrue(OshiStickerPack.usesGiphy(OshiStickerPack.Tab.GIFS))
        assertNull(OshiStickerPack.packKind(OshiStickerPack.Tab.OSHI))
        assertEquals(LocalGifLibrary.Kind.GIF, OshiStickerPack.packKind(OshiStickerPack.Tab.GIFS))
        assertEquals(LocalGifLibrary.Kind.STICKER, OshiStickerPack.packKind(OshiStickerPack.Tab.STICKERS))
    }

    @Test
    fun `all 20 mascots are offered in manifest order, each once`() {
        val shown = OshiStickerPack.stickers()
        assertEquals(20, shown.size)
        assertEquals(OshiMascot.all, shown)
        assertEquals(shown.size, shown.map { it.name }.toSet().size)
        val manifest = JSONObject(String(OshiMascot.classpathBytes(OshiMascot.MANIFEST)!!, Charsets.UTF_8)).getJSONArray("animations")
        assertEquals((0 until manifest.length()).map { manifest.getJSONObject(it).getString("name") }, shown.map { it.name })
        assertEquals(shown, mascotSearch(""))
    }

    @Test
    fun `search by name, label, emoji, alias, and oshi for all`() {
        assertEquals(listOf("love"), OshiStickerPack.stickers("love").map { it.name })
        assertEquals(listOf("laugh"), OshiStickerPack.stickers("🤣").map { it.name })
        assertEquals(20, OshiStickerPack.stickers("OSHI").size)
        assertTrue(OshiStickerPack.stickers("zzzz-nothing").isEmpty())
    }

    @Test
    fun `every sticker loads as its bundled GIF89a with a gif name`() {
        for (m in OshiStickerPack.stickers()) {
            val s = OshiStickerPack.sticker(m)
            assertNotNull(m.name, s)
            assertArrayEquals(m.name, OshiMascot.classpathBytes(m.gifPath), s!!.bytes)
            assertEquals("oshi-${m.name}.gif", s.fileName)
            assertTrue(OshiStickerPack.isGif89a(s.bytes))
            val manifestBytes = JSONObject(String(OshiMascot.classpathBytes(OshiMascot.MANIFEST)!!, Charsets.UTF_8))
                .getJSONArray("animations").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                .single { it.getString("name") == m.name }.getJSONObject("bytes").getLong("gif")
            assertEquals(m.name, manifestBytes, s.bytes.size.toLong())
        }
    }

    @Test
    fun `a missing or non-GIF file is never sent in place of the sticker`() {
        val love = OshiMascot.named("love")!!
        assertNull(OshiStickerPack.sticker(love) { null })
        assertNull(OshiStickerPack.sticker(love) { throw java.io.IOException("gone") })
        assertNull(OshiStickerPack.sticker(love) { OshiMascot.classpathBytes(love.staticPath(128)) })
    }

    @Test
    fun `a sticker rides the existing GIF wire and is sealed byte for byte`() {
        val s = OshiStickerPack.sticker(OshiMascot.named("ok")!!)!!
        // GIFs travel labelled "image" so iOS 1.0.43 can show them (fix/gif-compat-ios1043-desktop).
        assertEquals("image", GifWire.label(MediaType.IMAGE, "image/gif"))
        val dir = kotlin.io.path.createTempDirectory("oshi-sticker").toFile()
        try {
            val vault = com.oshi.desktop.store.MediaVault(File(dir, "media"), File(dir, "media-tmp"), ByteArray(32) { 3 })
            val f = GifOutbox.write(s.bytes, s.fileName, dir, vault)
            assertTrue(f.name.endsWith(".gif"))
            assertTrue(vault.openStream(f).use { it.readBytes() }.contentEquals(s.bytes))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * iOS 1.0.43 (on sale) draws a received GIF with `UIImage(data:)`: frame 0, still. The
     * shipped GIFs start on the manifest's `still_frame`, so frame 0 is the same pose as
     * `static/<name>_128.png`. Measured mean |Δ| per channel on white at 128 px: ≤ 3.2 for
     * the shipped files, 6.7–48 for the original un-rotated ones (clap and cry excepted:
     * their frame 0 already was that pose).
     */
    @Test
    fun `the first frame of every sticker is its still pose`() {
        for (m in OshiMascot.all) {
            val first = firstFrame(OshiMascot.classpathBytes(m.gifPath)!!)
            val still = ImageIO.read(ByteArrayInputStream(OshiMascot.classpathBytes(m.staticPath(128))!!))
            val d = meanDiffOnWhite(scale(first, still.width), still)
            assertTrue("${m.name}: frame 0 is $d away from the still pose", d < 4.5)
        }
    }

    private fun firstFrame(bytes: ByteArray): BufferedImage {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            reader.input = input
            val frame = reader.read(0)
            val meta = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
            val desc = meta.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
            val canvas = BufferedImage(240, 240, BufferedImage.TYPE_INT_ARGB)
            canvas.createGraphics().apply {
                drawImage(frame, desc.getAttribute("imageLeftPosition").toInt(), desc.getAttribute("imageTopPosition").toInt(), null)
                dispose()
            }
            reader.dispose()
            return canvas
        }
    }

    private fun scale(src: BufferedImage, px: Int): BufferedImage {
        val out = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(src.getScaledInstance(px, px, Image.SCALE_AREA_AVERAGING), 0, 0, null); dispose() }
        return out
    }

    private fun meanDiffOnWhite(a: BufferedImage, b: BufferedImage): Double {
        var sum = 0L
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val pa = onWhite(a.getRGB(x, y)); val pb = onWhite(b.getRGB(x, y))
            for (c in 0..2) sum += kotlin.math.abs(pa[c] - pb[c])
        }
        return sum.toDouble() / (b.width * b.height * 3)
    }

    private fun onWhite(argb: Int): IntArray {
        val a = (argb ushr 24) and 0xFF
        return intArrayOf(16, 8, 0).map { sh -> (((argb ushr sh) and 0xFF) * a + 255 * (255 - a)) / 255 }.toIntArray()
    }
}
