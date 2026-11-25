package com.oshi.desktop.group

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * __GROUP_PARITY_2026_09_23__ The group picture, as iOS sets it
 * (`OSHI/GroupViews.swift:1385` → `GroupManager.updateGroupPicture`, `GroupMessaging.swift:2080`):
 * a JPEG at quality 0.8, compressed until it is at most 500 KB, carried base64 in the
 * definition's `groupPictureData`.
 *
 * The desktop also caps the long side at [MAX_SIDE] before encoding. iOS hands the full
 * camera image to its compressor; a 12-megapixel JPEG base64'd into EVERY definition
 * broadcast is the size the phones' 500 KB cap exists to prevent, and the avatar is drawn at
 * 44 pt. The cap only makes the payload smaller, which every receiver accepts.
 */
object GroupPicture {

    /** iOS `compressImageData(imageData, maxSizeKB: 500)`. */
    const val MAX_BYTES = 500 * 1024

    const val MAX_SIDE = 512

    /** A decodable image file → JPEG bytes ≤ [MAX_BYTES], or null when it is not an image. */
    fun prepare(source: ByteArray): ByteArray? {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(source)) }.getOrNull() ?: return null
        var side = MAX_SIDE
        var quality = 0.8f
        while (side >= 64) {
            val jpeg = encode(scaled(img, side), quality)
            if (jpeg.size <= MAX_BYTES) return jpeg
            if (quality > 0.45f) quality -= 0.15f else side /= 2
        }
        return null
    }

    /** Decode for display, or null for anything that is not a readable picture. */
    fun decodeBase64(b64: String?): ByteArray? =
        b64?.takeIf { it.isNotBlank() }?.let { runCatching { java.util.Base64.getMimeDecoder().decode(it) }.getOrNull() }

    fun encodeBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun scaled(src: BufferedImage, maxSide: Int): BufferedImage {
        val longSide = maxOf(src.width, src.height)
        val f = if (longSide > maxSide) maxSide.toDouble() / longSide else 1.0
        val w = maxOf(1, (src.width * f).toInt())
        val h = maxOf(1, (src.height * f).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = java.awt.Color.WHITE // JPEG has no alpha: flatten onto white, as UIImage.jpegData does
        g.fillRect(0, 0, w, h)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun encode(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            writer.write(null, IIOImage(img, null, null), p)
            writer.dispose()
        }
        return out.toByteArray()
    }
}
