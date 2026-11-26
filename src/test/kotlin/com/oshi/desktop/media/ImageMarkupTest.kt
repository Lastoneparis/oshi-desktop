package com.oshi.desktop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class ImageMarkupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun checker(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img ->
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, if ((x / 2 + y / 2) % 2 == 0) 0xFFFFFF else 0x000000)
    }

    @Test
    fun `a click lands where it was made on a letterboxed picture, and the letterbox is nothing`() {
        // 2000x1000 photo in an 800x800 view: scale 0.4, 200 px bars top and bottom.
        val f = MarkupGeometry.fit(2000f, 1000f, 800f, 800f)
        assertEquals(0.4f, f.scale, 1e-6f); assertEquals(200f, f.offsetY, 1e-4f)
        assertEquals(1000f to 500f, MarkupGeometry.toImage(400f, 400f, 2000f, 1000f, 800f, 800f))
        assertNull(MarkupGeometry.toImage(400f, 100f, 2000f, 1000f, 800f, 800f))
        assertEquals(1000f to 0f, MarkupGeometry.toImageClamped(400f, 100f, 2000f, 1000f, 800f, 800f))
        assertEquals(12.5f, MarkupGeometry.widthInImage(5f, 2000f, 1000f, 800f, 800f), 1e-4f)
    }

    @Test
    fun `a stroke is drawn in image pixels`() {
        val base = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val doc = MarkupDoc(100, 100).add(MarkupOp.Stroke(listOf(10f to 50f, 90f to 50f), 0xFFFF0000.toInt(), 6f))
        val out = ImageMarkup.render(base, doc)
        assertEquals(0xFF0000, out.getRGB(50, 50) and 0xFFFFFF)
        assertEquals(0, out.getRGB(50, 10) and 0xFFFFFF)
        assertEquals("the original is untouched", 0, base.getRGB(50, 50) and 0xFFFFFF)
    }

    @Test
    fun `blur destroys the detail inside the rectangle and nothing outside`() {
        val base = checker(96, 96)
        val out = ImageMarkup.render(base, MarkupDoc(96, 96).add(MarkupOp.Blur(PixelRect(0f, 0f, 48f, 48f))))
        // Inside: one 12 px cell is a single flat colour.
        val c = out.getRGB(1, 1)
        for (y in 0 until 12) for (x in 0 until 12) assertEquals(c, out.getRGB(x, y))
        // Outside: the checkerboard is intact.
        assertEquals(base.getRGB(70, 70), out.getRGB(70, 70))
        assertNotEquals(out.getRGB(70, 70), out.getRGB(72, 70))
    }

    @Test
    fun `crop keeps only the chosen pixels, in a fresh raster`() {
        val base = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB).also { it.setRGB(150, 60, 0x00FF00) }
        val doc = MarkupDoc(200, 100).withCrop(PixelRect(180f, 80f, 100f, 40f)) // any corner order
        val out = ImageMarkup.render(base, doc)
        assertEquals(80, out.width); assertEquals(40, out.height)
        assertEquals(0x00FF00, out.getRGB(50, 20) and 0xFFFFFF)
        assertTrue("not a view into the uncropped raster", out.raster.dataBuffer.size == 80 * 40)
        assertNull("a click is not a crop", MarkupDoc(10, 10).withCrop(PixelRect(1f, 1f, 2f, 2f)).crop)
    }

    @Test
    fun `undo takes back the last mark, then the crop`() {
        var d = MarkupDoc(10, 10).withCrop(PixelRect(0f, 0f, 8f, 8f))
        d = d.add(MarkupOp.Label(1f, 5f, "hi", -1, 4f))
        assertTrue(d.canUndo)
        d = d.undo(); assertEquals(0, d.ops.size); assertTrue(d.crop != null)
        d = d.undo(); assertNull(d.crop); assertFalse(d.canUndo)
    }

    @Test
    fun `what is sent is a fresh JPEG with no metadata, scaled to the cap`() {
        val big = BufferedImage(5000, 2500, BufferedImage.TYPE_INT_RGB)
        val sent = ImageMarkup.fitForSending(big)
        assertEquals(2560, sent.width); assertEquals(1280, sent.height)
        val bytes = ImageMarkup.encodeJpeg(sent)
        assertTrue(bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
        // No EXIF (APP1 "Exif") segment.
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Exif"))
        val f = tmp.root.resolve("a.jpg").apply { writeBytes(bytes) }
        assertEquals(2560, ImageIO.read(f).width)
    }

    @Test
    fun `load refuses what is not a picture and flattens transparency onto white`() {
        val txt = tmp.newFile("a.png").apply { writeText("not an image") }
        assertNull(ImageMarkup.load(txt))
        assertFalse(ImageMarkup.isImageFile(txt))
        val clear = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val png = tmp.root.resolve("clear.png").also { ImageIO.write(clear, "png", it) }
        assertTrue(ImageMarkup.isImageFile(png))
        assertEquals(0xFFFFFF, ImageMarkup.load(png)!!.getRGB(1, 1) and 0xFFFFFF)
    }
}
