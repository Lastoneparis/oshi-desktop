package com.oshi.desktop.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * __BUBBLE_MEDIA_2026_11_21__ How big a picture is drawn in a bubble — plain arithmetic, no
 * Compose, so `BubbleMediaLayoutTest` holds it to the spec (BUBBLE_MEDIA_SPEC §1).
 *
 * ============================================================ THE SIDE GAP IT REPLACES
 *
 * Until this file, every photo, GIF and video poster was drawn at a FIXED 250 dp
 * (`Metrics.mediaBubble`) inside a bubble padded 12 dp on every side and capped at 420 dp.
 * The bubble wraps its WIDEST child. A caption (or a channel post's text) is laid out up to
 * 420 − 24 = 396 dp, so as soon as the caption was longer than one short line the bubble grew
 * to ~420 dp and the 250 dp picture sat at its left with a ~146 dp band of bubble colour on
 * its right — the "part that is empty on the side" (seen in the before renders,
 * docs/design/bubbles-2026-11-21/before). Media-only bubbles had the smaller version of the
 * same defect: a 12 dp frame of bubble colour round every picture, and a monospace file name
 * under it.
 *
 * Now the picture's display width IS the bubble's width, and the caption wraps inside it.
 *
 * ============================================================ THE RULES
 *
 *  * Width = the natural width (pixels read as dp), clamped to [MIN_WIDTH] .. the bubble's
 *    max width: a small image is scaled UP to the minimum instead of leaving a gap, a large
 *    one is scaled down to the bubble.
 *  * Height keeps the aspect ratio, clamped to [MIN_ASPECT] × width (panorama) ..
 *    [MAX_ASPECT] × width (tall portrait); beyond those the picture is centre-cropped.
 *  * The bubble's max width: [CHAT_FRACTION] of the thread's width capped at [CHAT_CAP] in
 *    1:1 and groups; a channel post is a card, [CHANNEL_FRACTION] capped at [CHANNEL_CAP].
 */
object BubbleMediaLayout {
    const val MIN_WIDTH = 200f
    const val MAX_ASPECT = 1.3f
    const val MIN_ASPECT = 0.5f
    const val CHAT_FRACTION = 0.75f
    const val CHAT_CAP = 420f
    const val CHANNEL_FRACTION = 0.90f
    const val CHANNEL_CAP = 560f
    /** The aspect reserved while a picture's size is still unknown (no header yet). */
    const val UNKNOWN_ASPECT = 0.75f

    /**
     * The width photos are DECODED at, in dp — the largest any bubble draws one. One width for
     * every bubble, so a resize of the window never re-decodes, and a channel post is sharp.
     */
    val decodeWidth: Dp = CHANNEL_CAP.dp

    /** A width and height in dp. */
    data class Size(val width: Float, val height: Float)

    /** The widest a bubble may be in a thread [available] dp wide. */
    fun maxBubbleWidth(available: Float, channel: Boolean): Float {
        val fraction = if (channel) CHANNEL_FRACTION else CHAT_FRACTION
        val cap = if (channel) CHANNEL_CAP else CHAT_CAP
        val w = minOf(available * fraction, cap)
        // A very narrow thread still gets a usable bubble, but never one wider than itself.
        return maxOf(w, minOf(MIN_WIDTH, available)).coerceAtLeast(1f)
    }

    /**
     * The display size of a picture whose natural size is [naturalW] × [naturalH] pixels
     * (null or non-positive = not known yet) in a bubble at most [maxWidth] dp wide.
     */
    fun mediaSize(naturalW: Int?, naturalH: Int?, maxWidth: Float): Size {
        val known = naturalW != null && naturalH != null && naturalW > 0 && naturalH > 0
        val minW = minOf(MIN_WIDTH, maxWidth)
        val width = if (known) naturalW!!.toFloat().coerceIn(minW, maxWidth) else maxWidth
        val aspect = if (known) (naturalH!!.toFloat() / naturalW!!).coerceIn(MIN_ASPECT, MAX_ASPECT) else UNKNOWN_ASPECT
        return Size(width, width * aspect)
    }

    /**
     * A channel post's picture: it fills the card's [width] (a card is a column of fixed
     * width, like a feed), height from the aspect clamped as in [mediaSize].
     */
    fun cardMediaSize(naturalW: Int?, naturalH: Int?, width: Float): Size {
        val known = naturalW != null && naturalH != null && naturalW > 0 && naturalH > 0
        val aspect = if (known) (naturalH!!.toFloat() / naturalW!!).coerceIn(MIN_ASPECT, MAX_ASPECT) else UNKNOWN_ASPECT
        return Size(width, width * aspect)
    }

    /** Whether a picture of that natural size is cropped (rather than only scaled) to fit. */
    fun isCropped(naturalW: Int, naturalH: Int): Boolean {
        val a = naturalH.toFloat() / naturalW
        return a > MAX_ASPECT || a < MIN_ASPECT
    }
}
