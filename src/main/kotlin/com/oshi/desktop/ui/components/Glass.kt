package com.oshi.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.oshi.desktop.ui.GlassTokens
import com.oshi.desktop.ui.OshiTheme
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/**
 * __OLED_GLASS_2026_11_16__ "Crystal glass" on the OLED black — and nothing at all elsewhere.
 *
 * Every helper here reads `OshiTheme.glass`. In LIGHT and DARK that is null and each helper
 * draws EXACTLY what the call site drew before (its flat fill, its border if it had one), so
 * adopting a helper never changes those two themes. In OLED it draws glass:
 *
 *  * a translucent white fill, lighter at the top — over the black ground that reads as a
 *    pane of smoked glass rather than a grey box;
 *  * a 1 px rim lit from above (18% → 4% white), the single strongest "glass" cue;
 *  * a faint sheen under the top edge, capped at [SHEEN_DEPTH] so a tall card does not
 *    get a grey wash down its middle.
 *
 * All of it is two gradients and a stroke — no blur, no layer, no offscreen buffer — so it
 * is cheap enough for a list of bubbles. The one blurred thing is [brandGlow], used on a
 * handful of focus elements only (send, selected destination, primary buttons).
 */
private val SHEEN_DEPTH = 40.dp

private fun DrawScope.sheen(g: GlassTokens) {
    val depth = minOf(size.height * 0.6f, SHEEN_DEPTH.toPx())
    drawRect(Brush.verticalGradient(listOf(g.sheen, Color.Transparent), startY = 0f, endY = depth))
}

private fun rim(g: GlassTokens): Brush = Brush.verticalGradient(listOf(g.rimTop, g.rimBottom))

/**
 * A glass shape on the black ground: cards, panes' sections, chips, controls.
 *
 * @param flat what the call site painted before, and still paints outside OLED.
 * @param flatBorder the border it drew before (transparent = none).
 * @param raised for things carrying content in a list or sitting on another glass shape
 *   (incoming bubbles, the composer field, call controls): a denser fill.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    flat: Color,
    flatBorder: Color = Color.Transparent,
    raised: Boolean = false,
): Modifier {
    val g = OshiTheme.glass
    if (g == null) {
        val base = clip(shape).background(flat)
        return if (flatBorder.alpha > 0f) base.border(Metrics.hairline, flatBorder, shape) else base
    }
    val (top, bottom) = g.fill(raised)
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .drawBehind { sheen(g) }
        .border(Metrics.hairline, rim(g), shape)
}

/**
 * Glass over CONTENT — dialogs, the media viewer, popups. The base is opaque
 * ([GlassTokens.floating]); the rim and sheen are drawn over the content's edge.
 */
@Composable
fun Modifier.glassFloating(shape: Shape, flat: Color, flatBorder: Color = Color.Transparent): Modifier {
    val g = OshiTheme.glass
    if (g == null) {
        val base = clip(shape).background(flat)
        return if (flatBorder.alpha > 0f) base.border(Metrics.hairline, flatBorder, shape) else base
    }
    return this
        .clip(shape)
        .background(g.floating)
        .drawBehind { sheen(g) }
        .border(Metrics.hairline, rim(g), shape)
}

/**
 * Only the rim and sheen, drawn OVER the node — for Material containers that paint their
 * own opaque fill (AlertDialog), where a background modifier would be hidden beneath it.
 */
@Composable
fun Modifier.glassEdge(shape: Shape): Modifier {
    val g = OshiTheme.glass ?: return this
    return this.drawWithContent {
        drawContent()
        // The node is not clipped (the container under it is): clip the sheen to the shape
        // so it does not square off the rounded top corners.
        val outline = shape.createOutline(size, layoutDirection, this)
        clipPath(Path().apply { addOutline(outline) }) { sheen(g) }
    }.border(Metrics.hairline, rim(g), shape)
}

/**
 * A soft violet glow behind a focus element — OLED only. It paints OUTSIDE the node's
 * bounds, so it must come BEFORE any `clip` in the chain, and the parent must not clip.
 */
@Composable
fun Modifier.brandGlow(shape: Shape, radius: Dp = 12.dp, active: Boolean = true, strength: Float = 1f): Modifier {
    val g = OshiTheme.glass
    if (g == null || !active) return this
    return this.drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawIntoCanvas { canvas ->
            val paint = Paint().apply { color = g.glow.copy(alpha = g.glow.alpha * strength) }
            paint.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius.toPx() / 2f)
            canvas.drawOutline(outline, paint)
        }
    }
}

/** The rim colour as a [BorderStroke] for Material containers, or null outside OLED. */
@Composable
fun glassBorderStroke(): BorderStroke? = OshiTheme.glass?.let { BorderStroke(Metrics.hairline, rim(it)) }

/**
 * Material's `DropdownMenu`, glazed in OLED: the opaque floating base, a 12 dp radius and
 * the lit rim. Outside OLED it is Material's own menu, unchanged.
 */
@Composable
fun OshiDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalHiddenByLock.current) return   // __MERGE_AB_LOCK_HIDES_POPUPS__
    val g = OshiTheme.glass
    if (g == null) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset, content = content)
    } else {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier.drawBehind { sheen(g) },
            offset = offset,
            shape = OshiTheme.radiusMd,
            containerColor = g.floating,
            tonalElevation = 0.dp,
            shadowElevation = MenuDefaults.ShadowElevation,
            border = glassBorderStroke(),
            content = content,
        )
    }
}

/**
 * Material's `AlertDialog`, glazed in OLED: the opaque floating base, a 24 dp radius, the
 * lit rim and sheen drawn over its edge. Outside OLED it is Material's own dialog.
 */
@Composable
fun OshiAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    if (LocalHiddenByLock.current) return   // __MERGE_AB_LOCK_HIDES_POPUPS__
    val g = OshiTheme.glass
    if (g == null) {
        AlertDialog(
            onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier,
            dismissButton = dismissButton, title = title, text = text,
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest, confirmButton = confirmButton,
            modifier = modifier.glassEdge(OshiTheme.radiusXl),
            dismissButton = dismissButton, title = title, text = text,
            shape = OshiTheme.radiusXl,
            containerColor = g.floating,
            tonalElevation = 0.dp,
        )
    }
}

/**
 * A bare `BasicTextField` (the Radio and Peers panes draw theirs with no box at all) gets a
 * recessed glass well in OLED, so a field reads as a field on the glass card. Outside OLED
 * it changes nothing.
 */
@Composable
fun Modifier.glassField(): Modifier {
    if (OshiTheme.glass == null) return this
    return this
        .padding(end = OshiTheme.sm)
        .clip(OshiTheme.radiusSm)
        .background(OshiTheme.surface)
        .border(Metrics.hairline, Color.White.copy(alpha = 0.06f), OshiTheme.radiusSm)
        .padding(horizontal = 10.dp, vertical = 6.dp)
}

/**
 * __MERGE_AB_LOCK_HIDES_POPUPS__ True for UI composed under the passcode lock screen while it is
 * up. A popup (dialog, menu) would otherwise be drawn above the lock; [OshiAlertDialog] and
 * [OshiDropdownMenu] render nothing while it is true and come back on unlock.
 */
val LocalHiddenByLock = androidx.compose.runtime.staticCompositionLocalOf { false }
