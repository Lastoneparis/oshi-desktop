package com.oshi.desktop.stego

import com.oshi.desktop.pairing.ContactQr
import com.oshi.messenger.service.stego.StegoPng
import com.oshi.messenger.service.stego.StegoV1
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/**
 * __PRIVACY_DISGUISE_2026_11_18__ Desktop glue for "Disguise as photo" (STEGO-v1,
 * docs/stego/STEGO-v1.md): covers in, PNG bytes out, reveal against the contact list.
 *
 * The format and the crypto are NOT here. They are [StegoV1] and [StegoPng], compiled from
 * the very files Android ships (build.gradle.kts include list), and Android's own vector
 * tests run against them in this module. What lives here is only what Android does with
 * `android.graphics.Bitmap` and what the desktop does with `java.awt.image.BufferedImage`:
 * reading a cover, scaling it, and the generated landscape. Mirrors Android's
 * `PhotoDisguise.kt` function for function so a reader can hold the two side by side.
 */
object DesktopPhotoDisguise {

    /** Long side of the cover after downscaling — Android's and iOS's number. */
    const val MAX_COVER_SIDE = 1600

    /** Readers refuse anything bigger than this before decoding (64 MiB, Android's limit). */
    const val MAX_INPUT_BYTES = 64 * 1024 * 1024

    sealed class Outcome {
        data class Found(val senderAddress: String, val payload: StegoV1.Payload) : Outcome()
        /** Made for us by [senderAddress], altered on the way. */
        data class Damaged(val senderAddress: String) : Outcome()
        /** Every other case — not for us, not a carrier, recompressed, not an image at all. */
        data object NotForYou : Outcome()
        data object Unreadable : Outcome()
    }

    /** An OSHI address (base64 or base64url X25519 key) → its 32 raw bytes, or null. */
    fun keyBytes(address: String): ByteArray? {
        val canonical = ContactQr.canonicalAddress(address) ?: return null
        return runCatching { Base64.getDecoder().decode(canonical) }.getOrNull()?.takeIf { it.size == 32 }
    }

    // ------------------------------------------------------------------ covers

    /** Any AWT image → 8-bit RGB samples, downscaled so the long side is ≤ [MAX_COVER_SIDE]. */
    fun coverFromImage(source: BufferedImage): StegoV1.RgbImage {
        val scale = minOf(1.0, MAX_COVER_SIDE.toDouble() / maxOf(source.width, source.height))
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val rgbImage = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = rgbImage.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            // A transparent PNG cover is flattened on white, as a photo viewer would show it.
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(source, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return fromIntRgb(rgbImage)
    }

    /** A cover read from disk: null when the file is not an image this JVM can decode. */
    fun coverFromFile(file: File): StegoV1.RgbImage? = try {
        if (file.length() > MAX_INPUT_BYTES) null else ImageIO.read(file)?.let(::coverFromImage)
    } catch (_: Exception) {
        null
    }

    /**
     * An innocuous generated landscape (sky gradient, layered hills, film grain). Same
     * recipe as Android's `PhotoDisguise.generatedCover`; not part of the wire format, so
     * the two only need to look alike, not match bit for bit. Grain matters: a perfectly
     * smooth synthetic image would make the noisy LSB plane stand out more.
     */
    fun generatedCover(seed: Long = System.nanoTime(), w: Int = 1280, h: Int = 960): StegoV1.RgbImage {
        val r = java.util.Random(seed)
        val sky = when (r.nextInt(3)) {
            0 -> intArrayOf(255, 170, 120, 90, 120, 200)
            1 -> intArrayOf(120, 180, 240, 220, 235, 250)
            else -> intArrayOf(250, 200, 150, 160, 120, 170)
        }
        val hills = Array(4) {
            doubleArrayOf(0.45 + it * 0.12 + r.nextDouble() * 0.05, r.nextDouble() * 2 * PI, 1.5 + r.nextDouble() * 2.5, 0.03 + r.nextDouble() * 0.05)
        }
        val hillColor = Array(4) { k -> intArrayOf(40 + k * 18 + r.nextInt(20), 70 + k * 20 + r.nextInt(30), 50 + k * 10 + r.nextInt(20)) }
        val sunX = 0.2 + r.nextDouble() * 0.6
        val sunY = 0.25 + r.nextDouble() * 0.15
        val rgb = ByteArray(w * h * 3)
        for (y in 0 until h) {
            val fy = y.toDouble() / h
            for (x in 0 until w) {
                val fx = x.toDouble() / w
                var c = IntArray(3) { (sky[it] + (sky[it + 3] - sky[it]) * fy * 1.6).toInt() }
                val d = kotlin.math.hypot(fx - sunX, (fy - sunY) * h / w)
                if (d < 0.05) c = intArrayOf(255, 240, 210) else if (d < 0.12) {
                    val t = (0.12 - d) / 0.07
                    c = IntArray(3) { (c[it] + (255 - c[it]) * t * 0.5).toInt() }
                }
                for (k in hills.indices) {
                    val hk = hills[k]
                    val ridge = hk[0] + hk[3] * sin(fx * hk[2] * 2 * PI + hk[1]) + hk[3] * 0.4 * sin(fx * hk[2] * 5.3 * PI + hk[1] * 2)
                    if (fy > ridge) {
                        val shade = (fy - ridge) * 60
                        c = IntArray(3) { (hillColor[k][it] - shade).toInt() }
                    }
                }
                val o = (y * w + x) * 3
                for (ch in 0..2) rgb[o + ch] = (c[ch] + r.nextInt(7) - 3).coerceIn(0, 255).toByte()
            }
        }
        return StegoV1.RgbImage(w, h, rgb)
    }

    /** Body bytes this cover can carry, and what [payload] needs — for the capacity line. */
    fun capacity(cover: StegoV1.RgbImage): Int = StegoV1.capacityBytes(cover.slots)
    fun needed(payload: StegoV1.Payload): Int = StegoV1.encodePayload(payload).size

    // ------------------------------------------------------------------- hide

    /**
     * PNG bytes carrying [payload] for [recipientAddress], from this identity.
     * @throws StegoV1.CapacityException when the cover is too small.
     * @throws IllegalArgumentException when the address is not an X25519 key.
     */
    fun hide(
        selfPriv: ByteArray,
        selfPub: ByteArray,
        recipientAddress: String,
        cover: StegoV1.RgbImage,
        payload: StegoV1.Payload,
    ): ByteArray {
        val recipient = keyBytes(recipientAddress) ?: throw IllegalArgumentException("Invalid contact key")
        return StegoPng.encode(StegoV1.hide(cover, payload, selfPriv, selfPub, recipient))
    }

    /** A camera-like file name, the same shape Android's share sheet uses. */
    fun suggestedFileName(now: Date = Date()): String =
        "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".png"

    // ----------------------------------------------------------------- reveal

    /** Exact samples for a PNG; ImageIO for anything else (which then simply holds no message). */
    fun imageFromBytes(bytes: ByteArray): StegoV1.RgbImage? = try {
        imageFromBytesUnchecked(bytes)
    } catch (_: Exception) {
        // __STEGO_DAMAGED_NEVER_CRASHES_2026_11_18__ Android c308647e: whatever a hostile or
        // damaged file does to a decoder, the answer is "no hidden message", never a crash.
        null
    }

    private fun imageFromBytesUnchecked(bytes: ByteArray): StegoV1.RgbImage? {
        if (StegoPng.isPng(bytes)) {
            try { return StegoPng.decode(bytes) } catch (_: StegoPng.UnsupportedPng) { } catch (_: Exception) { return null }
        }
        // Read the bounds BEFORE decoding: a 48-MP camera JPEG dropped on "Reveal" was decoded
        // at full size (~190 MB) before the size check — it cannot carry a message anyway.
        val fits = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { iis ->
            val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return null
            try {
                reader.input = iis
                reader.getWidth(0).toLong() * reader.getHeight(0) * 3 <= StegoV1.MAX_SLOTS
            } finally { reader.dispose() }
        } ?: return null
        if (!fits) return null
        val img = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (_: Exception) { null } ?: return null
        if (img.width.toLong() * img.height * 3 > StegoV1.MAX_SLOTS) return null
        val copy = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = copy.createGraphics()
        try { g.drawImage(img, 0, 0, null) } finally { g.dispose() }
        return fromIntRgb(copy)
    }

    /**
     * Tries every contact as the sender. One [Outcome.NotForYou] for every way of not
     * finding a message, as on the phones: distinguishing "not a carrier" from "for someone
     * else" would tell anyone at this keyboard which photos are carriers.
     */
    fun reveal(selfPriv: ByteArray, selfPub: ByteArray, bytes: ByteArray, contactAddresses: Collection<String>): Outcome {
        if (bytes.size > MAX_INPUT_BYTES) return Outcome.Unreadable
        val image = imageFromBytes(bytes) ?: return Outcome.Unreadable
        val byKey = contactAddresses.mapNotNull { a -> keyBytes(a)?.let { it to a } }
        fun addressOf(k: ByteArray) = byKey.first { it.first.contentEquals(k) }.second
        val result = try { StegoV1.reveal(image, selfPriv, selfPub, byKey.map { it.first }) } catch (_: Exception) { return Outcome.NotForYou }
        return when (val r = result) {
            is StegoV1.RevealResult.Found -> Outcome.Found(addressOf(r.senderPublicKey), r.payload)
            is StegoV1.RevealResult.Damaged -> Outcome.Damaged(addressOf(r.senderPublicKey))
            StegoV1.RevealResult.NotForYou -> Outcome.NotForYou
        }
    }

    /** A revealed attachment's name made safe for a save dialog (Android's rule). */
    fun safeAttachmentName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "attachment" }

    /** True for a file a drop or chooser should hand to [reveal] rather than ignore. */
    fun looksLikeImageFile(file: File): Boolean =
        file.isFile && file.extension.lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "tif", "tiff")

    private fun fromIntRgb(img: BufferedImage): StegoV1.RgbImage {
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) {
            val c = px[i]
            rgb[i * 3] = (c shr 16).toByte()
            rgb[i * 3 + 1] = (c shr 8).toByte()
            rgb[i * 3 + 2] = c.toByte()
        }
        return StegoV1.RgbImage(w, h, rgb)
    }

    /** RGB samples → an AWT image, for the preview thumbnail. */
    fun toBufferedImage(image: StegoV1.RgbImage): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val px = IntArray(image.width * image.height) { i ->
            val o = i * 3
            ((image.rgb[o].toInt() and 0xFF) shl 16) or ((image.rgb[o + 1].toInt() and 0xFF) shl 8) or (image.rgb[o + 2].toInt() and 0xFF)
        }
        out.setRGB(0, 0, image.width, image.height, px, 0, image.width)
        return out
    }
}
