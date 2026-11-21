package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.msg.OshiReaction
import com.oshi.desktop.ui.OshiTheme

/**
 * __OSHI_SMILE_REACTION_2026_11_09__ The OSHI mark — a thick ring with a small
 * speech-bubble tail at its lower-left — smiling. Drawn as vector geometry so it
 * is crisp at reaction-chip size. Same geometry as the Android copy
 * (`OSHI-Android/app/src/main/java/com/oshi/messenger/ui/components/OshiSmileMark.kt`).
 *
 * Geometry: `OSHI/LoRaFirmware/tools/oshi_boot_logo.py` (ring r_out 13, tail
 * wedge (-10,7) (-3,10) (-8,20) in ring units). The hole is opened a little
 * (0.6 of the outer radius instead of 7/13) so a smile fits inside at 14sp.
 */
@Composable
fun OshiSmileMark(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = OshiTheme.brand,
    face: Boolean = true,
    // null = decorative (the row around it already says what it is, e.g. the comments bar).
    label: String? = "OSHI",
) {
    Canvas(
        modifier
            .size(size)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
    ) {
        val s = this.size.minDimension
        val rOut = 0.36f * s
        val ringWidth = 0.40f * rOut
        val cx = 0.54f * s
        val cy = 0.40f * s
        val k = rOut / 13f

        // Tail first, so the ring's stroke overlaps its root cleanly.
        val tail = Path().apply {
            moveTo(cx - 10f * k, cy + 7f * k)
            lineTo(cx - 3f * k, cy + 10f * k)
            lineTo(cx - 8f * k, cy + 20f * k)
            close()
        }
        drawPath(tail, color)

        // Ring: a stroke centred between the outer and inner radius.
        drawCircle(
            color = color,
            radius = rOut - ringWidth / 2f,
            center = Offset(cx, cy),
            style = Stroke(width = ringWidth),
        )

        // Face inside the hole — none on the plain OSHI "O" (__OSHI_O_REACTION_2026_11_15__).
        if (!face) return@Canvas
        val rIn = rOut - ringWidth
        val eyeR = 0.13f * rIn
        drawCircle(color, eyeR, Offset(cx - 0.38f * rIn, cy - 0.22f * rIn))
        drawCircle(color, eyeR, Offset(cx + 0.38f * rIn, cy - 0.22f * rIn))
        val smileR = 0.52f * rIn
        drawArc(
            color = color,
            startAngle = 25f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(cx - smileR, cy - smileR * 0.85f),
            size = Size(smileR * 2f, smileR * 2f),
            style = Stroke(width = 0.16f * rIn, cap = StrokeCap.Round),
        )
    }
}

/**
 * One reaction as it is drawn on a chip or in the picker: the exact OSHI wire
 * value becomes [OshiSmileMark]; an emoji with an O-bubble mascot (canonical or
 * alias, `__O_BUBBLE_MASCOT_2026_11_16__`) becomes that mascot; everything else is
 * text, as before. Only the DRAWING changes — the key is stored and sent as it came.
 */
@Composable
fun ReactionGlyph(code: String, fontSize: TextUnit = 14.sp, modifier: Modifier = Modifier) {
    val mascot = com.oshi.desktop.mascot.OshiMascot.forEmoji(code)
    if (OshiReaction.isOshiO(code)) {
        // __OSHI_O_REACTION_2026_11_15__ the plain "⭕" is the brand O, ring + tail, no face.
        OshiSmileMark(size = (fontSize.value * 1.25f).dp, modifier = modifier, face = false)
    } else if (OshiReaction.isOshiSmile(code)) {
        // An emoji at N sp occupies roughly 1.25·N of line height.
        OshiSmileMark(size = (fontSize.value * 1.25f).dp, modifier = modifier)
    } else if (mascot != null) {
        MascotReactionGlyph(mascot, fontSize, modifier)
    } else {
        Text(text = code, fontSize = fontSize, modifier = modifier)
    }
}
