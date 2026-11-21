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
 * __OLED_GLASS_2026_11_16__ "Crystal glass" — born on the OLED black, and since
 * __GLASS_ALL_THEMES__ available in Light and Dark too, behind Settings › Appearance ›
 * "Glass effect".
 *
 * Every helper here reads the glass through [currentGlass]. With the switch off that is null
 * and each helper draws EXACTLY what the call site drew before (its flat fill, its border if
 * it had one), so adopting a helper never changes a flat theme. With it on it draws glass
 * (described here in OLED's numbers; Light and Dark have their own `GlassTokens`):
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

/**
 * __GLASS_ALL_THEMES__ True under UI that is dark in EVERY theme — the call screen, the
 * ringing card. Their grounds are near-black even in Light, so Light's frosted-white glass
 * there would put white glass under white text; they take the Dark glass instead.
 */
val LocalDarkGround = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Marks [content] as drawn on a dark ground whatever the theme (see [LocalDarkGround]). */
@Composable
fun ProvideDarkGround(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalDarkGround provides true, content = content)

/**
 * The glass to draw HERE: `OshiTheme.glass`, except on a [LocalDarkGround] in Light, where
 * it is [GlassTokens.DARK]. Null exactly when `OshiTheme.glass` is (the switch is off).
 */
@Composable
fun currentGlass(): GlassTokens? {
    val g = OshiTheme.glass ?: return null
    return if (LocalDarkGround.current && !OshiTheme.palette.isDark) GlassTokens.DARK else g
}

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
    val g = currentGlass()
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
 * __GLASS_ALL_THEMES__ The track a raised glass thumb slides in (segmented controls). On the
 * dark looks it is plain [glass] (OLED unchanged). In Light glass it is a recessed WELL —
 * `surface` with a faint dark rim — because a white glass track inside a white glass card
 * leaves the white raised segment nothing to stand out from.
 */
@Composable
fun Modifier.glassTrack(shape: Shape, flat: Color): Modifier {
    val g = currentGlass()
    if (g == null || OshiTheme.palette.isDark || LocalDarkGround.current) return glass(shape, flat)
    return this.clip(shape).background(OshiTheme.surface).border(Metrics.hairline, g.wellRim, shape)
}

/**
 * Glass over CONTENT — dialogs, the media viewer, popups. The base is opaque
 * ([GlassTokens.floating]); the rim and sheen are drawn over the content's edge.
 */
@Composable
fun Modifier.glassFloating(shape: Shape, flat: Color, flatBorder: Color = Color.Transparent): Modifier {
    val g = currentGlass()
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
    val g = currentGlass() ?: return this
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
    val g = currentGlass()
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
fun glassBorderStroke(): BorderStroke? = currentGlass()?.let { BorderStroke(Metrics.hairline, rim(it)) }

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
    val g = currentGlass()
    // __CALL_MENU_INK_2026_11_21__ Light + glass on a dark ground (the call screen) takes the DARK
    // glass for the menu's base — but its items kept Light's ink: black "Robot", "Alien"… on
    // #262629 in the call's voice-effect menu (seen in the running app). The menu's content gets
    // the Dark glass palette's colours too, so base and ink come from the same look.
    if (g != null && LocalDarkGround.current && !OshiTheme.palette.isDark) {
        androidx.compose.material3.MaterialTheme(colorScheme = com.oshi.desktop.ui.OshiPalette.DARK_GLASS.scheme) {
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
        return
    }
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
    val g = currentGlass()
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
    val g = currentGlass() ?: return this
    return this
        .padding(end = OshiTheme.sm)
        .clip(OshiTheme.radiusSm)
        .background(OshiTheme.surface)
        .border(Metrics.hairline, g.wellRim, OshiTheme.radiusSm)
        .padding(horizontal = 10.dp, vertical = 6.dp)
}

/**
 * __MERGE_AB_LOCK_HIDES_POPUPS__ True for UI composed under the passcode lock screen while it is
 * up. A popup (dialog, menu) would otherwise be drawn above the lock; [OshiAlertDialog] and
 * [OshiDropdownMenu] render nothing while it is true and come back on unlock.
 */
val LocalHiddenByLock = androidx.compose.runtime.staticCompositionLocalOf { false }
