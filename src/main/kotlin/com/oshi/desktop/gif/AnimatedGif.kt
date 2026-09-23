package com.oshi.desktop.gif

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * Animated GIFs for Compose Desktop, which has no GIF animation of its own: `Image` and
 * `makeFromEncoded` draw the FIRST FRAME only — the exact flattening iOS fixed with
 * `AnimatedImageView` ("the usual image path would squash the GIF to its first frame").
 *
 * Frames are decoded with skia's own [Codec] (already in the runtime — no new dependency),
 * off the UI thread, downscaled to the width they are drawn at, and cached. Three bounds keep
 * a hostile or merely huge GIF from taking the window down:
 *  - [MAX_FRAMES] frames at most — longer animations are SUBSAMPLED (every k-th frame, its
 *    delay summed so the loop keeps its real length) rather than cut;
 *  - [FRAME_PIXEL_BUDGET] downscaled pixels per GIF, subsampling further if needed;
 *  - an LRU of [CACHE_PIXEL_BUDGET] pixels across all GIFs on screen.
 * Anything skia cannot decode returns null and the caller shows its usual fallback.
 */
object AnimatedGif {

    class Frames(val frames: List<ImageBitmap>, val delaysMs: IntArray) {
        val pixels: Long get() = frames.sumOf { it.width.toLong() * it.height }
        val isAnimated: Boolean get() = frames.size > 1
    }

    const val MAX_FRAMES = 240
    const val FRAME_PIXEL_BUDGET = 6_000_000L
    const val CACHE_PIXEL_BUDGET = 48_000_000L
    /** Browsers clamp 0-10 ms GIF delays to 100 ms; iOS/ImageIO does the same. */
    private const val MIN_DELAY_MS = 20
    private const val DEFAULT_DELAY_MS = 100

    fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) && bytes[5] == 'a'.code.toByte()

    private val cache = object : LinkedHashMap<String, Frames>(64, 0.75f, true) {}
    private var cachedPixels = 0L

    fun cached(key: String, px: Int): Frames? = synchronized(cache) { cache["$px@$key"] }

    /** Blocking: call off the UI thread. [maxPx] is the drawn width in pixels (≤0 = native). */
    fun decode(key: String, bytes: ByteArray, maxPx: Int): Frames? {
        cached(key, maxPx)?.let { return it }
        val frames = runCatching { decodeUncached(bytes, maxPx) }.getOrNull() ?: return null
        synchronized(cache) {
            cache["$maxPx@$key"] = frames
            cachedPixels += frames.pixels
            val it = cache.entries.iterator()
            while (cachedPixels > CACHE_PIXEL_BUDGET && it.hasNext()) {
                val e = it.next()
                if (e.value === frames) continue
                cachedPixels -= e.value.pixels
                it.remove()
            }
        }
        return frames
    }

    internal fun decodeUncached(bytes: ByteArray, maxPx: Int): Frames? {
        // skia THROWS (IllegalArgumentException "Unsupported format") on bytes it cannot read.
        val codec = runCatching { Codec.makeFromData(Data.makeFromBytes(bytes)) }.getOrNull() ?: return null
        try {
            val w = codec.width
            val h = codec.height
            if (w <= 0 || h <= 0) return null
            val scale = if (maxPx in 1 until w) maxPx.toFloat() / w else 1f
            val ow = maxOf(1, (w * scale).toInt())
            val oh = maxOf(1, (h * scale).toInt())
            val total = maxOf(1, codec.frameCount)
            val info = if (total > 1) codec.framesInfo else emptyArray()
            // Subsample so the frame count and the pixel budget both hold.
            val byBudget = (FRAME_PIXEL_BUDGET / (ow.toLong() * oh)).coerceAtLeast(1)
            val keep = minOf(MAX_FRAMES.toLong(), byBudget).toInt()
            val step = if (total <= keep) 1 else (total + keep - 1) / keep

            val bitmap = Bitmap()
            bitmap.allocPixels(codec.imageInfo)
            val out = ArrayList<ImageBitmap>()
            val delays = ArrayList<Int>()
            var last = -1
            for (i in 0 until total) {
                val required = info.getOrNull(i)?.requiredFrame ?: -1
                // Decode every frame (skia needs the chain), keep every `step`-th.
                if (required >= 0 && required == last) codec.readPixels(bitmap, i, last)
                else {
                    bitmap.erase(0)
                    codec.readPixels(bitmap, i)
                }
                last = i
                val raw = info.getOrNull(i)?.duration ?: DEFAULT_DELAY_MS
                val d = if (raw < MIN_DELAY_MS) DEFAULT_DELAY_MS else raw
                if (i % step == 0) {
                    out += snapshot(bitmap, w, h, ow, oh)
                    delays += d
                } else {
                    // A skipped frame's time goes to the kept frame before it: same loop length.
                    delays[delays.lastIndex] = delays.last() + d
                }
            }
            bitmap.close()
            if (out.isEmpty()) return null
            return Frames(out, delays.toIntArray())
        } finally {
            codec.close()
        }
    }

    private fun snapshot(bitmap: Bitmap, w: Int, h: Int, ow: Int, oh: Int): ImageBitmap {
        val img = SkiaImage.makeFromBitmap(bitmap)
        if (ow == w && oh == h) {
            // makeFromBitmap SHARES pixels with a bitmap we are about to overwrite: copy.
            val surface = Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImage(img, 0f, 0f)
            return surface.makeImageSnapshot().toComposeImageBitmap().also { surface.close(); img.close() }
        }
        val surface = Surface.makeRasterN32Premul(ow, oh)
        surface.canvas.drawImageRect(
            img, Rect.makeWH(w.toFloat(), h.toFloat()), Rect.makeWH(ow.toFloat(), oh.toFloat()),
            SamplingMode.MITCHELL, null, true,
        )
        return surface.makeImageSnapshot().toComposeImageBitmap().also { surface.close(); img.close() }
    }
}

/**
 * Draws a GIF, animated. [cacheKey] identifies the bytes for the cache (a path or a pack id);
 * [load] supplies them and runs on IO. [placeholder] is shown while decoding and, via
 * [onFailed], the caller learns when skia could not decode it at all.
 */
@Composable
fun AnimatedGifImage(
    cacheKey: String,
    maxPx: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    load: () -> ByteArray?,
    placeholder: @Composable () -> Unit = {},
    onFailed: @Composable () -> Unit = placeholder,
) {
    val state by produceState<Any?>(AnimatedGif.cached(cacheKey, maxPx) ?: LOADING, cacheKey, maxPx) {
        if (value !== LOADING) return@produceState
        value = withContext(Dispatchers.IO) {
            load()?.let { AnimatedGif.decode(cacheKey, it, maxPx) } ?: FAILED
        }
    }
    when (val s = state) {
        LOADING -> placeholder()
        is AnimatedGif.Frames -> {
            var index by remember(s) { mutableIntStateOf(0) }
            if (s.isAnimated) {
                LaunchedEffect(s) {
                    while (true) {
                        delay(s.delaysMs[index].toLong())
                        index = (index + 1) % s.frames.size
                    }
                }
            }
            Image(
                bitmap = s.frames[index.coerceIn(0, s.frames.lastIndex)],
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier,
            )
        }
        else -> onFailed()
    }
}

private val LOADING = Any()
private val FAILED = Any()
