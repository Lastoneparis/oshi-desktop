package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.ui.OshiTheme

/**
 * How each [WallpaperId] is painted, and what it does to the bubbles on top of it.
 *
 * Every colour below is `OshiTheme.brand`, `OshiTheme.brandSecondary`, `OshiTheme.surface`,
 * `OshiTheme.background` or `OshiTheme.textPrimary` at a stated alpha. No new hue enters the
 * window through the wallpaper picker, which is the only reason a picker belongs here at all:
 * five washes of the house palette are a finish, sixteen invented ones would be a second
 * design system.
 */
object WallpaperPainter {

    /** The ground colour, always painted first so an alpha wash has something to sit on. */
    val ground: Color get() = OshiTheme.background

    fun brush(id: WallpaperId): Brush = when (id) {
        WallpaperId.NONE -> SolidColor(Color.Transparent)
        WallpaperId.PAPER -> SolidColor(OshiTheme.surface)
        WallpaperId.VIOLET -> Brush.linearGradient(
            listOf(OshiTheme.brand.copy(alpha = 0.11f), OshiTheme.brandSecondary.copy(alpha = 0.06f))
        )
        WallpaperId.BLUSH -> Brush.linearGradient(
            listOf(OshiTheme.brandSecondary.copy(alpha = 0.13f), OshiTheme.brandSecondary.copy(alpha = 0.03f))
        )
        WallpaperId.INK -> SolidColor(OshiTheme.textPrimary.copy(alpha = 0.055f))
    }

    /**
     * The incoming bubble's fill, which is NOT a constant.
     *
     * `OshiTheme.bubbleIncoming` is `surface` (`#F2F2F7`). On the PAPER wallpaper that is the
     * identical value, so an incoming bubble would disappear into the ground; on the tinted
     * washes it loses most of its separation too. The shipped app has the same problem and
     * solves it by blending the received bubble with brand at 10% in light mode
     * (`BubbleStyleManager.incomingFill`) — an OPAQUE colour, deliberately, because
     * translucency shimmers over an animated wallpaper.
     *
     * Here the resolution is simpler and stays inside the token set: with no wallpaper the
     * bubble is `surface` as the theme says; over any wallpaper it is `surfaceElevated`
     * (white) and keeps the hairline rim, so it reads as a card lifted off the ground. Both
     * are theme tokens and both hold `textPrimary` at 21:1 and 19:1 respectively.
     */
    fun incomingFill(id: WallpaperId): Color =
        if (id == WallpaperId.NONE) OshiTheme.bubbleIncoming else OshiTheme.surfaceElevated
}

/** The full-bleed backdrop drawn behind a thread's bubbles. */
@Composable
fun WallpaperBackdrop(id: WallpaperId, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(WallpaperPainter.ground)) {
        if (id != WallpaperId.NONE) {
            Box(Modifier.fillMaxSize().background(WallpaperPainter.brush(id)))
        }
    }
}

/**
 * The picker, presented as a panel rather than a menu so its honesty line has room.
 *
 * That line is not decoration. `🎨WALLPAPER_UPDATE🎨` exists in this repository as a codec
 * with no producer and no consumer; a user who set a wallpaper and was not told it stays here
 * would reasonably assume their peer sees it. The phone behaves the same way — per
 * conversation, in `UserDefaults`, never on the wire — so saying it plainly costs nothing and
 * closes the only way this control could mislead.
 */
@Composable
fun WallpaperPicker(
    current: WallpaperId,
    onPick: (WallpaperId) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(300.dp)
            .clip(OshiTheme.radiusMd)
            .background(OshiTheme.surfaceElevated)
            .border(Metrics.hairline, OshiTheme.separator, OshiTheme.radiusMd)
            .padding(OshiTheme.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Wallpaper",
                style = OshiTheme.typography.titleMedium,
                color = Ink.strong,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(Metrics.actionButton).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.CLOSE, Ink.soft, 14.dp) }
        }
        Box(Modifier.padding(vertical = OshiTheme.md)) {
            Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                WallpaperId.entries.forEach { option ->
                    WallpaperOption(option, option == current) { onPick(option) }
                }
            }
        }
        Hairline()
        Text(
            "This is a local appearance setting for this machine. It is stored beside your " +
                "message logs, it is never sent, and the person you are talking to will not " +
                "see it — the same way the phone stores its own wallpaper.",
            fontSize = 11.sp,
            color = Ink.soft,
            modifier = Modifier.padding(top = OshiTheme.md),
        )
    }
}

@Composable
private fun WallpaperOption(option: WallpaperId, selected: Boolean, onPick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusSm)
            .background(
                when {
                    selected -> OshiTheme.brand.copy(alpha = 0.10f)
                    hovered.value -> OshiTheme.surface
                    else -> Color.Transparent
                }
            )
            .focusRing(OshiTheme.radiusSm)
            .clickable(interactionSource = source, indication = null, onClick = onPick)
            .padding(OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(OshiTheme.radiusSm)
                .background(WallpaperPainter.ground)
                .border(Metrics.hairline, OshiTheme.separator, OshiTheme.radiusSm),
        ) {
            if (option != WallpaperId.NONE) {
                Box(Modifier.fillMaxSize().clip(OshiTheme.radiusSm).background(WallpaperPainter.brush(option)))
            }
        }
        Text(
            option.label,
            style = OshiTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = Ink.strong,
            modifier = Modifier.weight(1f),
        )
        if (selected) GlyphIcon(Glyph.CHECK, OshiTheme.brand, 16.dp)
    }
}
