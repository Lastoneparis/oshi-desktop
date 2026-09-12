package com.oshi.desktop.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.oshi.desktop.ui.OshiTheme
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The six OSHI chat wallpapers, ported from `OSHI/OSHIWallpapers.swift`.
 *
 * These are not decoration and they are not a colour picker. Every one of them is a
 * picture of something this product actually does, which is why they are worth porting
 * byte-for-byte rather than substituting a palette of flat fills:
 *
 * | wallpaper | what it draws |
 * |---|---|
 * | [AURORA] | brand blooms, drifting |
 * | [CONSTELLATION] | peers and the links between them — **OSHI's own picture**, the mesh |
 * | [SIGNAL] | concentric transmission rings |
 * | [TOPOGRAPHY] | contour lines, from the offline maps |
 * | [CIPHER] | a slow field of ciphertext |
 * | [WAVES] | long LoRa wavefronts — the thing that works with no network |
 *
 * Every constant below — bloom radii, the 46 mesh nodes and their `0x5EED` generator, the
 * 14 contour bands, the 26 px cipher cell and its `% 7` sparsity, the seven wavefronts —
 * is transcribed from the Swift. Two things in particular are load-bearing and are NOT
 * free choices:
 *
 *  - **`ink` is 0.13 in light mode** (`OSHIWallpapers.swift:68`). The shipped comment says
 *    light mode "has to stay very quiet". A wallpaper you can comfortably read a message
 *    on is the entire design constraint; anything louder is a screenshot that looks good
 *    and a chat you cannot use.
 *  - **The mesh layout comes from a fixed LCG seeded `0x5EED`, never a random**
 *    (`:279-288`). The Swift says why: *"a mesh that reshuffles every time you open a chat
 *    is jarring"*. Same seed, same constants, so the desktop draws the same sky the phone
 *    does.
 *
 * The animation periods are the phone's, and they are slow on purpose — the Swift notes
 * that a fast cycle "reads as an animation; these read as weather".
 */
enum class OshiWallpaper(val id: String, val label: String, val periodSeconds: Int) {
    AURORA("aurora", "Aurora", 40),
    CONSTELLATION("constellation", "Constellation", 60),
    SIGNAL("signal", "Signal", 26),
    TOPOGRAPHY("topography", "Topography", 70),
    CIPHER("cipher", "Cipher", 50),
    WAVES("waves", "Waves", 34);

    companion object {
        fun byId(id: String?): OshiWallpaper? = entries.firstOrNull { it.id == id }
    }
}

/** `OSHIWallpapers.swift:63` — the accent used beside the brand in every scene. */
private val WallpaperAccent = Color(0.180f, 0.320f, 0.960f)

/** `:136` — the third aurora bloom. */
private val AuroraThird = Color(0.85f, 0.25f, 0.56f)

/**
 * Light-mode ink (`:68`). The dark-mode 0.26 is carried for the day this window grows a
 * dark theme; it is deliberately unused rather than deleted, so the pair stays together.
 */
private const val INK_LIGHT = 0.13f

@Suppress("unused")
private const val INK_DARK = 0.26f

/**
 * Draw one wallpaper behind a thread.
 *
 * @param animated false freezes `phase` at 0. The infinite transition is the only
 *        continuously-running animation in this window, and a caller that is rendering a
 *        screenshot, running a test, or honouring reduced-motion needs to be able to
 *        stop it. Frozen still draws the full scene — never a blank.
 */
@Composable
fun WallpaperBackdrop(paper: OshiWallpaper, animated: Boolean = true, modifier: Modifier = Modifier) {
    val phase by if (animated) {
        rememberInfiniteTransition(label = paper.id).animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(paper.periodSeconds * 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
    } else {
        remember { androidx.compose.runtime.mutableStateOf(0f) }
    }

    val brand = OshiTheme.brand
    Canvas(modifier.fillMaxSize()) {
        when (paper) {
            OshiWallpaper.AURORA -> aurora(phase, brand)
            OshiWallpaper.CONSTELLATION -> constellation(phase, brand)
            OshiWallpaper.SIGNAL -> signal(phase, brand)
            OshiWallpaper.TOPOGRAPHY -> topography(phase, brand)
            OshiWallpaper.CIPHER -> cipherField(phase, brand)
            OshiWallpaper.WAVES -> waves(phase, brand)
        }
    }
}

// --------------------------------------------------------------------------- scenes

/** `OSHIWallpapers.swift:126-140`. Three radial blooms sliding against each other. */
private fun DrawScope.aurora(phase: Float, brand: Color) {
    val d = max(size.width, size.height)
    val t = sin(phase * 2f * PI.toFloat())
    bloom(brand, INK_LIGHT * 1.6f, d * 0.9f, Offset(-d * 0.10f * t, d * 0.06f * t))
    bloom(WallpaperAccent, INK_LIGHT * 1.2f, d * 0.8f, Offset(d * 0.12f * t, -d * 0.05f * t))
    bloom(AuroraThird, INK_LIGHT * 0.8f, d * 0.7f, Offset(d * 0.06f * t, d * 0.10f * t))
}

private fun DrawScope.bloom(c: Color, alpha: Float, radius: Float, offset: Offset) {
    val centre = Offset(size.width / 2 + offset.x, size.height / 2 + offset.y)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(c.copy(alpha = alpha), Color.Transparent),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
}

/**
 * `:142-171` — the mesh. Links first so nodes sit on top of them, and only near
 * neighbours are joined: the Swift's own comment is that joining everything turns the
 * field into "a solid web".
 */
private fun DrawScope.constellation(phase: Float, brand: Color) {
    val nodes = meshNodes(size)
    val drift = sin(phase * 2f * PI.toFloat()) * 6f

    val links = Path()
    for (i in nodes.indices) {
        for (j in (i + 1) until nodes.size) {
            val a = nodes[i]
            val b = nodes[j]
            val dx = a.x - b.x
            val dy = a.y - b.y
            if (sqrt(dx * dx + dy * dy) >= size.width * 0.30f) continue
            links.moveTo(a.x + drift, a.y)
            links.lineTo(b.x - drift, b.y)
        }
    }
    drawPath(links, color = brand.copy(alpha = INK_LIGHT * 0.55f), style = Stroke(width = 1f))

    nodes.forEachIndexed { idx, n ->
        val r = if (idx % 5 == 0) 3.2f else 1.8f
        val x = n.x + if (idx % 2 == 0) drift else -drift
        drawCircle(
            color = WallpaperAccent.copy(alpha = (INK_LIGHT * 1.5f).coerceAtMost(1f)),
            radius = r,
            center = Offset(x, n.y),
        )
    }
}

/**
 * `:279-288` — a fixed LCG, never a random. 46 nodes, seed `0x5EED`, the same multiplier
 * and increment, so this draws the same sky the phone draws.
 */
private fun meshNodes(size: Size): List<Offset> {
    var seed = 0x5EEDUL
    fun next(): Float {
        seed = seed * 6364136223846793005UL + 1442695040888963407UL
        return ((seed shr 33) % 10_000UL).toFloat() / 10_000f
    }
    return List(46) { Offset(next() * size.width, next() * size.height) }
}

/** `:173-188` — five rings, each on its own offset so the set reads as continuous. */
private fun DrawScope.signal(phase: Float, brand: Color) {
    val d = max(size.width, size.height)
    val centre = Offset(size.width / 2, size.height / 2)
    for (i in 0 until 5) {
        val local = (phase + i / 5f) % 1f
        drawCircle(
            color = brand.copy(alpha = (INK_LIGHT * (1 - local) * 1.4f).coerceIn(0f, 1f)),
            radius = d * (0.15f + local * 1.15f) / 2f,
            center = centre,
            style = Stroke(width = 1.4f),
        )
    }
}

/** `:191-212` — 14 contour bands, every third one heavier. The quietest of the set. */
private fun DrawScope.topography(phase: Float, brand: Color) {
    val shift = sin(phase * 2f * PI.toFloat()) * 10f
    for (band in 0 until 14) {
        val path = Path()
        val baseY = size.height * (band / 13f)
        val amp = 16f + ((band * 7) % 22).toFloat()
        val wl = 120f + ((band * 31) % 90).toFloat()
        path.moveTo(-20f, baseY)
        var x = -20f
        while (x <= size.width + 20f) {
            path.lineTo(x, baseY + sin((x + shift * ((band % 3) + 1)) / wl) * amp)
            x += 6f
        }
        val heavy = band % 3 == 0
        drawPath(
            path,
            color = brand.copy(alpha = INK_LIGHT * if (heavy) 0.8f else 0.45f),
            style = Stroke(width = if (heavy) 1.2f else 0.8f),
        )
    }
}

/**
 * `:216-239` — a sparse field of hex glyphs drifting upward.
 *
 * The Swift's note is the important part: the glyphs come from a FIXED sequence, so this
 * "never renders anything that could be mistaken for real content". A wallpaper made of
 * plausible-looking text in a messenger would be a genuinely bad idea.
 *
 * Drawn as marks rather than text: Compose's DrawScope has no text primitive without a
 * measurer, and at 0.13 ink the glyph shapes are indistinguishable from ticks. The grid,
 * the sparsity rule and the drift are the phone's.
 */
private fun DrawScope.cipherField(phase: Float, brand: Color) {
    val cell = 26f
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 2
    val rise = phase * cell * 2

    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val seed = (r * 31 + c * 17) % 100
            if (seed % 7 != 0) continue
            val y = (r * cell - rise).mod(size.height + cell)
            val alpha = INK_LIGHT * if (seed % 3 == 0) 1.1f else 0.6f
            drawRect(
                color = brand.copy(alpha = alpha.coerceIn(0f, 1f)),
                topLeft = Offset(c * cell + cell / 2 - 3f, y - 5f),
                size = Size(6f, 10f),
            )
        }
    }
}

/** `:242-268` — seven wavefronts, each stroked with the brand→accent→brand gradient. */
private fun DrawScope.waves(phase: Float, brand: Color) {
    val shift = phase * size.width
    val sweep = Brush.linearGradient(
        colors = listOf(
            brand.copy(alpha = INK_LIGHT * 0.2f),
            WallpaperAccent.copy(alpha = INK_LIGHT),
            brand.copy(alpha = INK_LIGHT * 0.2f),
        ),
        start = Offset.Zero,
        end = Offset(size.width, 0f),
    )
    for (i in 0 until 7) {
        val path = Path()
        val baseY = size.height * (0.18f + i * 0.12f)
        val amp = 20f + i * 5f
        val wl = size.width / (1.1f + i * 0.25f)
        path.moveTo(-40f, baseY)
        var x = -40f
        while (x <= size.width + 40f) {
            val y = baseY + sin((x + shift * (0.4f + i * 0.1f)) / wl * 2f * PI.toFloat()) * amp
            path.lineTo(x, y)
            x += 8f
        }
        drawPath(path, brush = sweep, style = Stroke(width = 1.6f))
    }
}
