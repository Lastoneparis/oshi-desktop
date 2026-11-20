package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * __DESKTOP_SETTINGS_2027__ The settings window's icons.
 *
 * Kept OUT of [Glyph] on purpose: `Foundation.kt` is shared by every screen and is being
 * edited in parallel by the conversation and call work, so the icons only Settings needs
 * live here and fall back to [Glyph] for the ones that already exist. Same drawing grammar
 * as [GlyphIcon] (unit square, 8.5 % round stroke), so the two sets sit side by side.
 */
enum class SettingsIcon(val glyph: Glyph? = null, val mirrorsInRtl: Boolean = false) {
    PROFILE(Glyph.PERSON),
    APPEARANCE(Glyph.PALETTE),
    NOTIFICATIONS(Glyph.BELL),
    PRIVACY(Glyph.LOCK),
    DISGUISE(Glyph.MASK),
    CONTACTS(Glyph.PEOPLE),
    INFO(Glyph.INFO),
    DELETE(Glyph.TRASH),
    ALERT(Glyph.ALERT),
    COPY(Glyph.COPY),
    QR(Glyph.QR),
    LINK(Glyph.LINK),
    CHEVRON(Glyph.CHEVRON_RIGHT, mirrorsInRtl = true),
    CHECK(Glyph.CHECK),
    CLOCK(Glyph.CLOCK),
    TEXT(Glyph.TEXT_LINES),
    IMAGE(Glyph.IMAGE),
    SMILE(Glyph.SMILE),
    DEVICES,
    ARCHIVE,
    GLOBE,
    KEY,
    ANTENNA,
    POWER,
    LAPTOP_OFF,
    EXPORT,
    IMPORT,
    EYE,
    /** __DESKTOP_HELP_2027__ a question mark in a circle. */
    HELP,
    /** __DESKTOP_GROUP_DISCOVERY_2027__ a magnifier over a group. */
    DISCOVER,
}

@Composable
fun SettingsIconView(icon: SettingsIcon, tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    // __MERGE_2027__ a Glyph-backed icon is mirrored by GlyphIcon itself (Foundation.kt's
    // DIRECTIONAL_GLYPHS); mirroring it here too flipped the chevron twice, so in Arabic it
    // pointed the English way. Only the icons drawn below mirror here.
    icon.glyph?.let { GlyphIcon(it, tint, size, modifier); return }
    val mirrored = icon.mirrorsInRtl && LocalLayoutDirection.current == LayoutDirection.Rtl
    val m = if (mirrored) modifier.scale(scaleX = -1f, scaleY = 1f) else modifier
    Canvas(m.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = w, cap = StrokeCap.Round)
        when (icon) {
            SettingsIcon.DEVICES -> {
                // A laptop with a phone standing in front of it.
                drawRoundRect(tint, topLeft = p(0.14f, 0.22f), size = Size(s * 0.56f, s * 0.40f),
                    cornerRadius = CornerRadius(s * 0.05f), style = stroke)
                line(0.06f, 0.74f, 0.62f, 0.74f)
                drawRoundRect(tint, topLeft = p(0.66f, 0.40f), size = Size(s * 0.24f, s * 0.42f),
                    cornerRadius = CornerRadius(s * 0.05f), style = stroke)
            }
            SettingsIcon.LAPTOP_OFF -> {
                drawRoundRect(tint, topLeft = p(0.18f, 0.24f), size = Size(s * 0.64f, s * 0.42f),
                    cornerRadius = CornerRadius(s * 0.05f), style = stroke)
                line(0.08f, 0.78f, 0.92f, 0.78f)
                line(0.14f, 0.14f, 0.86f, 0.86f)
            }
            SettingsIcon.ARCHIVE -> {
                drawRoundRect(tint, topLeft = p(0.14f, 0.20f), size = Size(s * 0.72f, s * 0.18f),
                    cornerRadius = CornerRadius(s * 0.04f), style = stroke)
                drawPath(Path().apply {
                    moveTo(0.20f * s, 0.38f * s); lineTo(0.20f * s, 0.80f * s); lineTo(0.80f * s, 0.80f * s); lineTo(0.80f * s, 0.38f * s)
                }, tint, style = stroke)
                line(0.40f, 0.54f, 0.60f, 0.54f)
            }
            SettingsIcon.EXPORT, SettingsIcon.IMPORT -> {
                // A tray with an arrow leaving (export) or arriving (import).
                drawPath(Path().apply {
                    moveTo(0.16f * s, 0.58f * s); lineTo(0.16f * s, 0.84f * s); lineTo(0.84f * s, 0.84f * s); lineTo(0.84f * s, 0.58f * s)
                }, tint, style = stroke)
                line(0.5f, 0.14f, 0.5f, 0.64f)
                if (icon == SettingsIcon.EXPORT) {
                    line(0.32f, 0.32f, 0.5f, 0.14f); line(0.68f, 0.32f, 0.5f, 0.14f)
                } else {
                    line(0.32f, 0.46f, 0.5f, 0.64f); line(0.68f, 0.46f, 0.5f, 0.64f)
                }
            }
            SettingsIcon.GLOBE -> {
                drawCircle(tint, radius = s * 0.38f, center = p(0.5f, 0.5f), style = stroke)
                drawOval(tint, topLeft = p(0.33f, 0.12f), size = Size(s * 0.34f, s * 0.76f), style = stroke)
                line(0.14f, 0.5f, 0.86f, 0.5f)
            }
            SettingsIcon.KEY -> {
                drawCircle(tint, radius = s * 0.17f, center = p(0.32f, 0.5f), style = stroke)
                line(0.49f, 0.5f, 0.88f, 0.5f)
                line(0.76f, 0.5f, 0.76f, 0.66f)
                line(0.88f, 0.5f, 0.88f, 0.62f)
            }
            SettingsIcon.ANTENNA -> {
                line(0.5f, 0.46f, 0.5f, 0.88f)
                drawCircle(tint, radius = w * 0.9f, center = p(0.5f, 0.40f))
                drawArc(tint, 150f, 60f, false, topLeft = p(0.22f, 0.12f), size = Size(s * 0.56f, s * 0.56f), style = stroke)
                drawArc(tint, -30f, 60f, false, topLeft = p(0.22f, 0.12f), size = Size(s * 0.56f, s * 0.56f), style = stroke)
                drawArc(tint, 145f, 70f, false, topLeft = p(0.08f, -0.02f), size = Size(s * 0.84f, s * 0.84f), style = stroke)
                drawArc(tint, -35f, 70f, false, topLeft = p(0.08f, -0.02f), size = Size(s * 0.84f, s * 0.84f), style = stroke)
            }
            SettingsIcon.POWER -> {
                drawArc(tint, -60f, 300f, false, topLeft = p(0.18f, 0.20f), size = Size(s * 0.64f, s * 0.64f), style = stroke)
                line(0.5f, 0.12f, 0.5f, 0.48f)
            }
            SettingsIcon.EYE -> {
                drawPath(Path().apply {
                    moveTo(0.08f * s, 0.5f * s)
                    cubicTo(0.30f * s, 0.18f * s, 0.70f * s, 0.18f * s, 0.92f * s, 0.5f * s)
                    cubicTo(0.70f * s, 0.82f * s, 0.30f * s, 0.82f * s, 0.08f * s, 0.5f * s)
                    close()
                }, tint, style = stroke)
                drawCircle(tint, radius = s * 0.12f, center = p(0.5f, 0.5f), style = stroke)
            }
            SettingsIcon.HELP -> {
                drawCircle(tint, radius = s * 0.38f, center = p(0.5f, 0.5f), style = stroke)
                drawPath(Path().apply {
                    moveTo(0.38f * s, 0.40f * s)
                    cubicTo(0.38f * s, 0.26f * s, 0.62f * s, 0.26f * s, 0.62f * s, 0.40f * s)
                    cubicTo(0.62f * s, 0.50f * s, 0.50f * s, 0.50f * s, 0.50f * s, 0.60f * s)
                }, tint, style = stroke)
                drawCircle(tint, radius = w * 0.75f, center = p(0.5f, 0.72f))
            }
            SettingsIcon.DISCOVER -> {
                drawCircle(tint, radius = s * 0.26f, center = p(0.42f, 0.42f), style = stroke)
                line(0.61f, 0.61f, 0.86f, 0.86f)
                drawCircle(tint, radius = s * 0.07f, center = p(0.42f, 0.36f), style = stroke)
                drawArc(tint, 200f, 140f, false, topLeft = p(0.30f, 0.44f), size = Size(s * 0.24f, s * 0.16f), style = stroke)
            }
            else -> Unit
        }
    }
}

/** The tinted rounded square a settings row or section shows its icon in. */
@Composable
fun SettingsIconBadge(
    icon: SettingsIcon,
    tint: Color,
    size: Dp = 30.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        SettingsIconView(icon, tint, size * 0.58f)
    }
}
