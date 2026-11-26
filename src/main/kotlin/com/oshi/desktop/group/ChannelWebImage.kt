package com.oshi.desktop.group

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * __CHANNEL_WEB_2026_11_26__ PUBLIC_CHANNEL_WEB_SPEC §3.3 client duties for a post image:
 * GIF uploaded as is (animated allowed; the server strips comments and application extensions);
 * anything else this JVM can decode → EXIF orientation applied, long side ≤ [MAX_SIDE], re-encoded
 * as JPEG q[QUALITY] with NO metadata (fresh pixels: no EXIF, no GPS, no camera serial). null =
 * cannot be published (HEIC and WebP have no decoder in the JDK: the post then goes out as text).
 */
object ChannelWebImage {
    const val MAX_SIDE = 2560
    const val QUALITY = 0.82f
    private const val MAX_PIXELS = 60_000_000L

    fun isGif(b: ByteArray): Boolean =
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[3] == '8'.code.toByte() && (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) && b[5] == 'a'.code.toByte()

    fun prepare(data: ByteArray, maxSide: Int = MAX_SIDE, quality: Float = QUALITY): ByteArray? {
        if (data.isEmpty()) return null
        if (isGif(data)) return data.takeIf { it.size <= ChannelWeb.MAX_IMAGE_BYTES }
        val decoded = decode(data) ?: return null
        val oriented = applyOrientation(decoded, exifOrientation(data))
        val out = encodeJpeg(fit(oriented, maxSide), quality)
        return out.takeIf { it.size <= ChannelWeb.MAX_IMAGE_BYTES }
    }

    private fun decode(data: ByteArray): BufferedImage? = runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val w = reader.getWidth(0).toLong(); val h = reader.getHeight(0).toLong()
                if (w <= 0 || h <= 0 || w * h > MAX_PIXELS) return null
                reader.read(0)
            } finally { reader.dispose() }
        }
    }.getOrNull()

    /**
     * The EXIF Orientation tag (1..8) of a JPEG, 1 when absent or unreadable. Reads only the APP1
     * `Exif\0\0` TIFF IFD0 — the JDK's JPEG reader ignores it, which is why a phone photo taken
     * upright arrives sideways unless it is applied here.
     */
    fun exifOrientation(b: ByteArray): Int {
        if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return 1
        var p = 2
        while (p + 4 <= b.size) {
            if ((b[p].toInt() and 0xFF) != 0xFF) return 1
            val marker = b[p + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) return 1   // EOI / start of scan: no APP1 before the pixels
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (len < 2 || p + 2 + len > b.size) return 1
            if (marker == 0xE1 && len >= 16 && String(b, p + 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                return tiffOrientation(b, p + 10, p + 2 + len)
            }
            p += 2 + len
        }
        return 1
    }

    private fun tiffOrientation(b: ByteArray, start: Int, end: Int): Int {
        if (start + 8 > end) return 1
        val le = b[start] == 'I'.code.toByte() && b[start + 1] == 'I'.code.toByte()
        val be = b[start] == 'M'.code.toByte() && b[start + 1] == 'M'.code.toByte()
        if (!le && !be) return 1
        fun u16(o: Int): Int = if (o + 2 > end) -1 else if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int): Long = if (o + 4 > end) -1 else if (le)
            (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
            else ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
        val ifd = u32(start + 4)
        if (ifd < 8 || start + ifd + 2 > end) return 1
        val ifdAt = start + ifd.toInt()
        val count = u16(ifdAt)
        if (count <= 0) return 1
        for (i in 0 until count) {
            val e = ifdAt + 2 + i * 12
            if (e + 12 > end) return 1
            if (u16(e) == 0x0112) {
                val v = u16(e + 8)
                return if (v in 1..8) v else 1
            }
        }
        return 1
    }

    /** Pixels turned so that what is drawn is what the camera's EXIF said is up. */
    fun applyOrientation(src: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return src
        val w = src.width.toDouble(); val h = src.height.toDouble()
        val swap = orientation >= 5
        val t = AffineTransform()
        when (orientation) {
            2 -> { t.translate(w, 0.0); t.scale(-1.0, 1.0) }
            3 -> { t.translate(w, h); t.rotate(Math.PI) }
            4 -> { t.translate(0.0, h); t.scale(1.0, -1.0) }
            5 -> { t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0) }
            6 -> { t.translate(h, 0.0); t.rotate(Math.PI / 2) }
            7 -> { t.scale(-1.0, 1.0); t.translate(-h, 0.0); t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
            8 -> { t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
        }
        val out = BufferedImage(if (swap) src.height else src.width, if (swap) src.width else src.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, out.width, out.height)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(src, t, null)
        g.dispose()
        return out
    }

    /** Long side ≤ [maxSide] (never up), flattened on white into RGB (JPEG has no alpha). */
    private fun fit(img: BufferedImage, maxSide: Int): BufferedImage {
        val long = max(img.width, img.height)
        val s = if (long > maxSide) maxSide.toDouble() / long else 1.0
        val w = (img.width * s).roundToInt().coerceAtLeast(1); val h = (img.height * s).roundToInt().coerceAtLeast(1)
        if (s == 1.0 && img.type == BufferedImage.TYPE_INT_RGB) return img
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    /** JPEG of fresh pixels; `IIOImage(img, null, null)` = no metadata tree at all (only JFIF). */
    private fun encodeJpeg(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bos = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bos).use { out ->
                writer.output = out
                val p = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
                writer.write(null, IIOImage(img, null, null), p)
            }
        } finally { writer.dispose() }
        return bos.toByteArray()
    }
}
