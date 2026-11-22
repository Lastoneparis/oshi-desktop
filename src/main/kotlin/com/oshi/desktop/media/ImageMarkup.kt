package com.oshi.desktop.media

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * __PHOTO_MARKUP_DESKTOP_2027__ Mark up a picture before it is sent: draw, write, blur, crop.
 * The phones' `PhotoMarkup` (iOS `PhotoMarkup.swift`, Android `MarkupGeometry`) draws pen
 * strokes; the desktop adds the three tools a mouse and a keyboard make easy.
 *
 * Everything is stored in IMAGE pixels, never in view points — [MarkupGeometry] does the
 * conversion, rule for rule the phones' — so a mark lands where it was made whatever the
 * window size and whatever the picture's aspect ratio.
 *
 * [render] flattens into NEW pixels and [encodeJpeg] re-encodes them: what leaves the computer
 * carries no trace of what was blurred or cropped away, and no metadata of the original file
 * (no EXIF, no GPS, no camera serial).
 */
object MarkupGeometry {
    data class Fitted(val offsetX: Float, val offsetY: Float, val scale: Float)

    fun fit(imageW: Float, imageH: Float, viewW: Float, viewH: Float): Fitted {
        if (imageW <= 0 || imageH <= 0 || viewW <= 0 || viewH <= 0) return Fitted(0f, 0f, 1f)
        val scale = min(viewW / imageW, viewH / imageH)
        return Fitted((viewW - imageW * scale) / 2, (viewH - imageH * scale) / 2, scale)
    }

    /** A view point → image point, or null in the letterbox (never clamped: a mark the user never made). */
    fun toImage(x: Float, y: Float, imageW: Float, imageH: Float, viewW: Float, viewH: Float): Pair<Float, Float>? {
        val f = fit(imageW, imageH, viewW, viewH)
        if (f.scale <= 0) return null
        val ix = (x - f.offsetX) / f.scale
        val iy = (y - f.offsetY) / f.scale
        if (ix < 0 || iy < 0 || ix > imageW || iy > imageH) return null
        return ix to iy
    }

    /** Same, clamped to the picture — for a RECTANGLE dragged past the edge (crop, blur). */
    fun toImageClamped(x: Float, y: Float, imageW: Float, imageH: Float, viewW: Float, viewH: Float): Pair<Float, Float> {
        val f = fit(imageW, imageH, viewW, viewH)
        val s = if (f.scale > 0) f.scale else 1f
        return ((x - f.offsetX) / s).coerceIn(0f, imageW) to ((y - f.offsetY) / s).coerceIn(0f, imageH)
    }

    /** A pen chosen on screen, in image pixels — or a 5 pt stroke on a 4000 px photo is a hairline. */
    fun widthInImage(viewWidth: Float, imageW: Float, imageH: Float, viewW: Float, viewH: Float): Float {
        val f = fit(imageW, imageH, viewW, viewH)
        return if (f.scale > 0) viewWidth / f.scale else viewWidth
    }
}

/** A rectangle in image pixels, any corner order. */
data class PixelRect(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val left get() = min(x1, x2)
    val top get() = min(y1, y2)
    val right get() = max(x1, x2)
    val bottom get() = max(y1, y2)
    val width get() = right - left
    val height get() = bottom - top
    /** Too small to mean anything — a click, not a drag. */
    val isTiny get() = width < 4 || height < 4
}

sealed interface MarkupOp {
    /** A pen stroke: points in image pixels, colour 0xAARRGGBB, width in image pixels. */
    data class Stroke(val points: List<Pair<Float, Float>>, val argb: Int, val width: Float) : MarkupOp
    /** A line of text whose baseline starts at ([x], [y]), [size] in image pixels. */
    data class Label(val x: Float, val y: Float, val text: String, val argb: Int, val size: Float) : MarkupOp
    /** Pixelate a region (irreversible once flattened). */
    data class Blur(val rect: PixelRect) : MarkupOp
}

data class MarkupDoc(
    val width: Int,
    val height: Int,
    val ops: List<MarkupOp> = emptyList(),
    /** null = the whole picture. */
    val crop: PixelRect? = null,
) {
    fun add(op: MarkupOp) = copy(ops = ops + op)
    fun undo(): MarkupDoc = when {
        ops.isNotEmpty() -> copy(ops = ops.dropLast(1))
        crop != null -> copy(crop = null)
        else -> this
    }
    val canUndo get() = ops.isNotEmpty() || crop != null
    fun withCrop(r: PixelRect?) = copy(crop = r?.takeUnless { it.isTiny })
}

object ImageMarkup {
    /** The phones' six pens (iOS `PhotoMarkupView.pens`). */
    val PENS: List<Int> = listOf(
        0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(), 0xFF33C759.toInt(), 0xFF0A84FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
    )
    const val PEN_WIDTH_ON_SCREEN = 5f
    const val TEXT_SIZE_ON_SCREEN = 28f
    /** A picture larger than this is refused rather than decoded into the heap. */
    const val MAX_PIXELS = 40_000_000L
    /** The long side of what is sent; a 48 MP camera frame is not what a chat needs. */
    const val MAX_SENT_SIDE = 2560

    /** Read a picture the user chose; null when it is not one this computer can read. */
    fun load(file: File): BufferedImage? = runCatching {
        ImageIO.createImageInputStream(file).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val w = reader.getWidth(0).toLong(); val h = reader.getHeight(0).toLong()
                if (w <= 0 || h <= 0 || w * h > MAX_PIXELS) return null
                toRgb(reader.read(0))
            } finally { reader.dispose() }
        }
    }.getOrNull()

    fun isImageFile(file: File): Boolean =
        file.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "tif", "tiff") &&
            runCatching { ImageIO.createImageInputStream(file).use { ImageIO.getImageReaders(it).hasNext() } }.getOrDefault(false)

    private fun toRgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_RGB) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color.WHITE // a transparent PNG flattens onto white, not black
        g.fillRect(0, 0, src.width, src.height)
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return out
    }

    /** Every op drawn on a copy of [base], then cropped. [base] is never touched. */
    fun render(base: BufferedImage, doc: MarkupDoc, applyCrop: Boolean = true): BufferedImage {
        val out = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.drawImage(base, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            for (op in doc.ops) when (op) {
                is MarkupOp.Blur -> pixelate(out, op.rect)
                is MarkupOp.Stroke -> {
                    g.color = Color(op.argb, true)
                    g.stroke = BasicStroke(op.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    if (op.points.size == 1) {
                        val (x, y) = op.points[0]
                        val r = op.width / 2
                        g.fill(java.awt.geom.Ellipse2D.Float(x - r, y - r, op.width, op.width))
                    } else {
                        val p = Path2D.Float()
                        op.points.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                        g.draw(p)
                    }
                }
                is MarkupOp.Label -> {
                    g.font = Font(Font.SANS_SERIF, Font.BOLD, op.size.roundToInt().coerceAtLeast(6))
                    // A dark halo under light text and a light one under dark, so it reads on any photo.
                    val c = Color(op.argb, true)
                    val lum = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue)
                    val halo = if (lum > 140) Color(0, 0, 0, 150) else Color(255, 255, 255, 170)
                    val o = (op.size / 14f).coerceAtLeast(1f)
                    g.color = halo
                    for (dx in listOf(-o, o)) for (dy in listOf(-o, o)) g.drawString(op.text, op.x + dx, op.y + dy)
                    g.color = c
                    g.drawString(op.text, op.x, op.y)
                }
            }
        } finally {
            g.dispose()
        }
        val crop = doc.crop?.takeIf { applyCrop } ?: return out
        val x = crop.left.roundToInt().coerceIn(0, out.width - 1)
        val y = crop.top.roundToInt().coerceIn(0, out.height - 1)
        val w = crop.width.roundToInt().coerceIn(1, out.width - x)
        val h = crop.height.roundToInt().coerceIn(1, out.height - y)
        // A COPY, not getSubimage: the cropped-away pixels must not ride along in the raster.
        val cropped = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        cropped.createGraphics().apply { drawImage(out, -x, -y, null); dispose() }
        return cropped
    }

    /**
     * Mosaic over [r]: each block takes its average colour. Blocks are a fixed fraction of the
     * picture (at least 12 px), coarse enough that text and faces are not recoverable — a
     * Gaussian blur of small text can be partly undone, a mosaic of 12 px cells cannot.
     */
    fun pixelate(img: BufferedImage, r: PixelRect) {
        val x0 = r.left.toInt().coerceIn(0, img.width); val y0 = r.top.toInt().coerceIn(0, img.height)
        val x1 = r.right.toInt().coerceIn(0, img.width); val y1 = r.bottom.toInt().coerceIn(0, img.height)
        if (x1 - x0 < 1 || y1 - y0 < 1) return
        val block = max(12, max(img.width, img.height) / 48)
        var by = y0
        while (by < y1) {
            var bx = x0
            val bh = min(block, y1 - by)
            while (bx < x1) {
                val bw = min(block, x1 - bx)
                var rs = 0L; var gs = 0L; var bs = 0L
                for (y in by until by + bh) for (x in bx until bx + bw) {
                    val p = img.getRGB(x, y); rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF
                }
                val n = (bw * bh).toLong()
                val avg = (0xFF shl 24) or ((rs / n).toInt() shl 16) or ((gs / n).toInt() shl 8) or (bs / n).toInt()
                for (y in by until by + bh) for (x in bx until bx + bw) img.setRGB(x, y, avg)
                bx += block
            }
            by += block
        }
    }

    /** Scale down to [MAX_SENT_SIDE] on the long side (never up). */
    fun fitForSending(img: BufferedImage, maxSide: Int = MAX_SENT_SIDE): BufferedImage {
        val long = max(img.width, img.height)
        if (long <= maxSide) return img
        val s = maxSide.toDouble() / long
        val w = (img.width * s).roundToInt().coerceAtLeast(1); val h = (img.height * s).roundToInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    /** JPEG bytes of [img] — fresh pixels, no metadata. */
    fun encodeJpeg(img: BufferedImage, quality: Float = 0.88f): ByteArray {
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
