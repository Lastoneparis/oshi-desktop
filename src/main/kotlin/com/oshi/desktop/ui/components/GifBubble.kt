package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.oshi.desktop.gif.AnimatedGif
import com.oshi.desktop.gif.AnimatedGifImage
import com.oshi.desktop.store.MediaVault
import com.oshi.desktop.ui.OshiTheme
import java.io.File

/**
 * GIF bubbles — `__GIF_PACK_2026_09_23__`.
 *
 * Whether a bubble is a GIF is decided from the BYTES (`GIF87a`/`GIF89a`), exactly as iOS
 * decides it (`MediaManager.isGIFData`: "the ONLY reliable GIF test in the app"): the label
 * may be `gif` (iOS), `image` (Android, older iOS, this client) or missing, and a GIF must
 * animate whichever one it came with.
 */
internal object GifBubble {
    private val known = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 512
    }

    /** Six header bytes of a possibly SEALED attachment; memoised per path. */
    fun isGifFile(path: String): Boolean {
        synchronized(known) { known[path]?.let { return it } }
        val gif = runCatching {
            MediaVault.openAny(File(path)).use { s -> AnimatedGif.isGif(s.readNBytes(6)) }
        }.getOrDefault(false)
        synchronized(known) { known[path] = gif }
        return gif
    }
}

/** The inline GIF: same footprint and click-to-open as a photo, but animated. */
@Composable
internal fun GifInlineImage(media: MediaPresentation, inkSoft: Color) {
    val targetPx = with(LocalDensity.current) { Metrics.mediaBubble.roundToPx() }
    val opener = LocalMediaOpener.current
    val target = remember(media) { MediaViewerTarget.of(media) }
    Column(Modifier.width(Metrics.mediaBubble), verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
        AnimatedGifImage(
            cacheKey = "file:${media.path}",
            maxPx = targetPx,
            contentDescription = media.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(Metrics.mediaBubble)
                .heightIn(max = Metrics.mediaBubble)
                .clip(OshiTheme.radiusMd)
                .clickable(enabled = target != null) { target?.let(opener::open) },
            load = { MediaVault.readAny(File(media.path), MediaPresentation.MAX_INLINE_BYTES) },
            placeholder = {
                Box(Modifier.width(Metrics.mediaBubble).height(Metrics.mediaBubble * 0.6f).clip(OshiTheme.radiusMd).background(OshiTheme.surface))
            },
        )
        Text(media.fileName, fontSize = 10.sp, color = inkSoft, fontFamily = FontFamily.Monospace)
        media.note?.let { Text(it, fontSize = 10.sp, color = inkSoft) }
    }
}
