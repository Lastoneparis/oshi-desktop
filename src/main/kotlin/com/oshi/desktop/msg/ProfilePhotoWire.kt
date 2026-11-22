package com.oshi.desktop.msg

import org.json.JSONObject
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * __PROFILE_PHOTOS_DESKTOP_2027__ The `profileImageData` key of `📸PROFILE_UPDATE📸` — the
 * same key, the same base64 JPEG, both phones send and read.
 *
 *  - iOS (`ContactPresenceManager.processProfileUpdate`) decodes the key as `String?` and
 *    REPLACES the stored picture with it: absent or `null` ⇒ the picture is removed. That is
 *    the rule [read] follows, so a contact who removes their picture on an iPhone loses it
 *    here too.
 *  - Android (`MessageRepository.handleProfileUpdate`) refuses anything over 256 KiB
 *    decoded (`MAX_INBOUND_AVATAR_BYTES`) — the same gate is applied here, encoded length
 *    first because decoding is what allocates — and [prepare] keeps our own picture far
 *    below it so Android never drops it.
 *  - Outbound size: iOS sends 512 px at JPEG 0.8 (`SettingsView`), Android a smaller
 *    compressed square. [prepare] makes a centred 512 px square at 0.8 and steps down until
 *    it fits [TARGET_BYTES].
 */
object ProfilePhotoWire {
    const val WIRE_KEY = "profileImageData"
    const val MAX_INBOUND_BYTES = 256 * 1024
    const val MAX_INBOUND_B64_CHARS = (MAX_INBOUND_BYTES / 3 + 1) * 4
    const val TARGET_BYTES = 120 * 1024
    const val SIDE = 512

    sealed class Read {
        /** Not a profile update at all. */
        data object NotProfile : Read()
        /** No picture (absent, null or empty): iOS's rule, the stored one is removed. */
        data object Clear : Read()
        /** Present but refused (too large, not base64). Keep what we have. */
        data object Refused : Read()
        data class Set(val base64: String) : Read()
    }

    fun read(text: String): Read {
        if (ControlPrefix.match(text) != ControlPrefix.PROFILE_UPDATE) return Read.NotProfile
        val o = runCatching { JSONObject(ControlPrefix.strip(text)) }.getOrNull() ?: return Read.NotProfile
        return read(o)
    }

    fun read(o: JSONObject): Read {
        if (!o.has(WIRE_KEY) || o.isNull(WIRE_KEY)) return Read.Clear
        val raw = (o.opt(WIRE_KEY) as? String)?.trim() ?: return Read.Refused
        if (raw.isEmpty() || raw == "null") return Read.Clear
        if (raw.length > MAX_INBOUND_B64_CHARS) return Read.Refused
        val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(raw) }.getOrNull() ?: return Read.Refused
        if (bytes.isEmpty() || bytes.size > MAX_INBOUND_BYTES) return Read.Refused
        if (!looksLikeImage(bytes)) return Read.Refused
        return Read.Set(java.util.Base64.getEncoder().encodeToString(bytes))
    }

    /** JPEG, PNG, GIF, WebP or HEIC magic — anything else is not drawn and not stored. */
    fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()
        val gif = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()
        val riff = String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        val heic = String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp"
        return jpeg || png || gif || riff || heic
    }

    /** Our own picture, ready for the wire: centred square, ≤ [SIDE] px, JPEG, ≤ [TARGET_BYTES]. Null if unreadable. */
    fun prepare(source: ByteArray): ByteArray? {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(source)) }.getOrNull() ?: return null
        val square = cropSquare(img)
        var side = SIDE
        var quality = 0.8f
        while (side >= 64) {
            val jpeg = encode(scaled(square, side), quality)
            if (jpeg.size <= TARGET_BYTES) return jpeg
            if (quality > 0.5f) quality -= 0.15f else side /= 2
        }
        return null
    }

    private fun cropSquare(src: BufferedImage): BufferedImage {
        val s = minOf(src.width, src.height)
        return src.getSubimage((src.width - s) / 2, (src.height - s) / 2, s, s)
    }

    private fun scaled(src: BufferedImage, maxSide: Int): BufferedImage {
        val side = minOf(maxSide, src.width)
        val out = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, side, side)
        g.drawImage(src, 0, 0, side, side, null)
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
