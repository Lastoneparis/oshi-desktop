package com.oshi.desktop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** __IMAGE_PIXEL_BOMB_2026_11_20__ */
class SafeImageTest {
    private fun png(w: Int, h: Int, rows: Int = h): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            d.writeInt(body.size); val t = type.toByteArray(); d.write(t); d.write(body)
            val crc = CRC32(); crc.update(t); crc.update(body); d.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 0, 0, 0, 0)) } }.toByteArray()
        chunk("IHDR", ihdr)
        val raw = ByteArray(minOf(rows, 4) * (w + 1))
        val def = Deflater().apply { setInput(raw); finish() }
        val buf = ByteArray(65536); val n = def.deflate(buf)
        chunk("IDAT", buf.copyOf(n))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun `a tiny file declaring sixty thousand squared pixels is refused before decoding`() {
        val bomb = png(60_000, 60_000, rows = 1)
        assertEquals(60_000 to 60_000, SafeImage.dimensions(bomb))
        assertNull(SafeImage.decode(bomb))
    }

    @Test
    fun `an ordinary image decodes`() {
        val ok = png(4, 4)
        assertNotNull(SafeImage.decode(ok))
    }
}
