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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/**
 * __WALLPAPER_BLUR_2026_11_21__ Frosted chat bars: the conversation header and the composer
 * float over the thread (wallpaper + messages run edge to edge underneath them) and blur
 * whatever scrolls behind them, like the phones' navigation and input bars.
 *
 * ============================================================ HOW THE BLUR IS DRAWN
 *
 * Not a second copy of the message list, not an offscreen snapshot: a Skia `saveLayer` with a
 * BACKDROP image filter. When it runs, Skia copies what is already in the destination under
 * the bar's bounds, blurs it and starts the layer with it; the bar's tint and contents then
 * draw on top. Because it is a draw OPERATION and not a captured bitmap, it reads whatever is
 * under the bar at the time the frame is played back — a scrolled list is blurred as it is
 * now, never as it was when the bar last recomposed. It only works where the bar draws into
 * the same canvas as the thread under it, which is why the bars carry no `graphicsLayer` of
 * their own and are placed AFTER the thread (see `ThreadPane`'s `FrostedThreadLayout`).
 *
 * ============================================================ THE GLASS IS THE THEME'S
 *
 * With the "Glass effect" switch off the tint is the theme's `background` at [FROST_ALPHA] and
 * the content-facing edge is the `separator` hairline the bars always had. With it on, the
 * same [GlassTokens] every other glass shape uses: its fill gradient and sheen over a thinner
 * `background` wash, and its lit rim as the edge. No colour here is new.
 *
 * ============================================================ WHEN THERE IS NO BLUR
 *
 * [BackdropBlur.enabled] false — a software renderer, `OSHI_CHAT_BLUR=0`, or the frame
 * governor having seen the window fall under 30 fps with bars on screen — gives the same bar
 * almost opaque ([SOLID_ALPHA]): still tinted by the wallpaper a little, never text over a
 * sharp message.
 */
object BackdropBlur {
    /** Off by `OSHI_CHAT_BLUR=0` (or `-Doshi.chatBlur=false`) before anything is drawn. */
    var enabled: Boolean by androidx.compose.runtime.mutableStateOf(
        System.getenv("OSHI_CHAT_BLUR") != "0" && System.getProperty("oshi.chatBlur") != "false"
    )
        private set

    /** Why [enabled] went false at run time, for the log and the tests. Null while on. */
    @Volatile var disabledBecause: String? = null
        private set

    /** Frosted bars currently on screen; the governor only counts frames while there are some. */
    internal val barsOnScreen = java.util.concurrent.atomic.AtomicInteger(0)

    /** The blur's radius in dp (Skia sigma is half of it). */
    val RADIUS = 24.dp

    /**
     * The backdrop filter: blur at 1/[downscale] of the resolution, then scale back up. A blur
     * this wide keeps no detail a quarter-size copy would lose, and it is 16x fewer pixels to
     * blur: in the CPU harness that took the frosted bars from +25 ms to +4 ms a frame
     * (`FrostedBarTest.benchScroll`), and `FrostedBarTest` checks the result against the
     * full-resolution blur pixel by pixel.
     */
    internal fun filter(sigma: Float, downscale: Float = DOWNSCALE): org.jetbrains.skia.ImageFilter {
        val f = downscale
        if (f <= 1f) return org.jetbrains.skia.ImageFilter.makeBlur(sigma, sigma, org.jetbrains.skia.FilterTileMode.CLAMP)
        val linear = org.jetbrains.skia.FilterMipmap(org.jetbrains.skia.FilterMode.LINEAR, org.jetbrains.skia.MipmapMode.NONE)
        val down = org.jetbrains.skia.ImageFilter.makeMatrixTransform(org.jetbrains.skia.Matrix33.makeScale(1f / f), linear, null)
        val blur = org.jetbrains.skia.ImageFilter.makeBlur(sigma / f, sigma / f, org.jetbrains.skia.FilterTileMode.CLAMP, down)
        return org.jetbrains.skia.ImageFilter.makeMatrixTransform(org.jetbrains.skia.Matrix33.makeScale(f), linear, blur)
    }

    /** 1 = full-resolution blur. Read at draw time; tests compare the two. */
    @Volatile internal var DOWNSCALE = 4f

    fun disable(why: String) {
        if (!enabled) return
        disabledBecause = why
        enabled = false
        println("[blur] chat bar blur off: $why")
    }

    /** For tests and benches: switch it back on (or off) without a reason. */
    fun set(on: Boolean) { enabled = on; if (on) disabledBecause = null }

    /** A software rasteriser blurs on the CPU every frame; the bars go solid instead. */
    fun noteRenderer(api: org.jetbrains.skiko.GraphicsApi) {
        if (api == org.jetbrains.skiko.GraphicsApi.SOFTWARE_FAST || api == org.jetbrains.skiko.GraphicsApi.SOFTWARE_COMPAT) {
            disable("software renderer ($api)")
        }
    }

    /** Frames looked at per verdict, and the share of them that must be slow to turn blur off. */
    internal const val WINDOW = 120
    internal const val SLOW_MS = 33.4
    internal const val SLOW_SHARE = 0.5

    /**
     * Pure: given the frame deltas (ns) of one window, is it too slow to keep blurring? Gaps
     * over [FrameSampler.ACTIVE_GAP_MS] are the clock resting, not slow frames, and are ignored.
     */
    internal fun tooSlow(deltasNs: List<Long>): Boolean {
        val active = deltasNs.map { it / 1e6 }.filter { it <= com.oshi.desktop.ui.FrameSampler.ACTIVE_GAP_MS }
        if (active.size < WINDOW / 2) return false
        return active.count { it > SLOW_MS } >= active.size * SLOW_SHARE
    }

    /**
     * The governor, installed once by the real window (never by the render harness, whose
     * clock is synthetic). It watches frame intervals only while frosted bars are on screen,
     * and turns the blur off for the session if the window drops under 30 fps for most of a
     * [WINDOW]-frame stretch.
     */
    @Composable
    fun Govern() {
        if (!enabled) return
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val deltas = ArrayList<Long>(WINDOW)
            var last = 0L
            while (enabled) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                if (barsOnScreen.get() > 0 && last != 0L) deltas.add(now - last)
                last = now
                if (deltas.size >= WINDOW) {
                    if (tooSlow(deltas)) disable("frames over ${SLOW_MS} ms with the bars on screen")
                    deltas.clear()
                }
            }
        }
    }
}

/** Tint alpha of a frosted bar over a blurred thread (no glass). */
internal const val FROST_ALPHA = 0.62f

/** With glass on, the `background` wash under the glass fill is thinner: the glass adds its own. */
internal const val FROST_GLASS_ALPHA = 0.40f

/** Without blur the bar is nearly opaque, so text never sits on a sharp message. */
internal const val SOLID_ALPHA = 0.94f

/**
 * A frosted bar over the thread (see [BackdropBlur]). [edgeAtBottom] = the header (its edge
 * faces the messages below it); false = the composer (edge on top).
 */
@Composable
fun Modifier.frostedBar(edgeAtBottom: Boolean): Modifier {
    val g = currentGlass()
    val blur = BackdropBlur.enabled
    val ground = OshiTheme.background
    val separator = OshiTheme.separator
    androidx.compose.runtime.DisposableEffect(Unit) {
        BackdropBlur.barsOnScreen.incrementAndGet()
        onDispose { BackdropBlur.barsOnScreen.decrementAndGet() }
    }
    return this.drawBehind {
        if (blur) {
            val sigma = BackdropBlur.RADIUS.toPx() / 2f
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val bounds = org.jetbrains.skia.Rect.makeWH(size.width, size.height)
                // The CLIP is what confines the backdrop to the bar: the layer's bounds alone
                // are not honoured for a backdrop (measured: without it the whole pane blurred).
                native.save()
                native.clipRect(bounds)
                native.saveLayer(
                    org.jetbrains.skia.Canvas.SaveLayerRec(
                        bounds = bounds,
                        backdrop = BackdropBlur.filter(sigma),
                    )
                )
                native.restore()
                native.restore()
            }
        }
        val alpha = when {
            !blur -> SOLID_ALPHA
            g != null -> FROST_GLASS_ALPHA
            else -> FROST_ALPHA
        }
        drawRect(ground.copy(alpha = alpha))
        if (g != null) {
            val (top, bottom) = g.fill(raised = false)
            drawRect(Brush.verticalGradient(if (edgeAtBottom) listOf(top, bottom) else listOf(bottom, top)))
            if (edgeAtBottom) sheen(g)
        }
        val hair = Metrics.hairline.toPx()
        val edge = if (g != null) (if (edgeAtBottom) g.rimBottom else g.rimTop) else separator
        val y = if (edgeAtBottom) size.height - hair else 0f
        drawRect(edge, topLeft = androidx.compose.ui.geometry.Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, hair))
    }
}
