package com.oshi.desktop.media

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

/**
 * __IMAGE_PIXEL_BOMB_2026_11_20__ Decode an untrusted image only after its HEADER says the
 * pixel count is sane. `Image.makeFromEncoded` decodes at native resolution: a 256 KB PNG
 * declaring 60000×60000 asks for ~14 GB, and the inline thumbnail, the group picture (stored,
 * so it crashes every render), the contact photo, the mail image and the GIF first frame all
 * called it with only a BYTE cap. The full media viewer already did this probe; now every
 * path that decodes a peer's bytes goes through the same one.
 */
object SafeImage {
    /** The media viewer's ceiling (MediaViewerLimits.MAX_PIXELS). */
    const val MAX_PIXELS = 32_000_000L

    /** Width × height from the header, without decoding; null when unreadable. */
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val data = Data.makeFromBytes(bytes)
        try {
            val codec = Codec.makeFromData(data)
            try { codec.width to codec.height } finally { codec.close() }
        } finally { data.close() }
    }.getOrNull()

    fun withinBudget(w: Int, h: Int, maxPixels: Long = MAX_PIXELS): Boolean =
        w > 0 && h > 0 && w.toLong() * h.toLong() <= maxPixels

    /** The decoded image, or null when unreadable or over [maxPixels]. Never throws. */
    fun decode(bytes: ByteArray, maxPixels: Long = MAX_PIXELS): Image? {
        val (w, h) = dimensions(bytes) ?: return null
        if (!withinBudget(w, h, maxPixels)) return null
        return runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
    }
}
