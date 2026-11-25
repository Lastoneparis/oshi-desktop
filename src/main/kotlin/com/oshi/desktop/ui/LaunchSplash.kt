package com.oshi.desktop.ui

import com.oshi.desktop.mascot.OshiMascot
import java.awt.AWTEvent
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.GradientPaint
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ The launch splash: the plain OSHI mark, a quick crossfade
 * into the O-bubble mascot, and the mascot waving — shown ONLY while the window is not up yet.
 *
 * This client had no splash at all: between the double-click and the first frame it opens
 * the vault (an OS key-store round trip), builds [com.oshi.desktop.app.OshiClient] and
 * starts Compose, and the screen showed nothing. The splash fills exactly that gap and
 * NEVER extends it: it is an undecorated AWT window that owns nothing the app waits on,
 * and it closes itself the moment ANY other window of this process opens (the main
 * window, the first-run identity window, the startup-failure window…) — observed through
 * an [AWTEventListener], so no startup path had to learn about it. A 20 s cap closes it
 * if nothing ever opens.
 *
 * Timeline ([frameAt]): the logo alone for [LOGO_HOLD_MS], a [CROSSFADE_MS] crossfade to the
 * neutral mascot (the pack draws both on one canvas, so only the face and hands appear),
 * then the 40-frame wave at 20 fps, ending on the neutral pose it started from. The mascot
 * is reached at [WAVE_START_MS] (0.4 s) — well inside the ~1.2 s budget. Reduced motion
 * ([MotionPreference.systemReducedMotion]): the crossfade only, no wave.
 *
 * Not shown with `--hidden` (start at login, into the tray), nor without a display.
 *
 * __OLED_SPLASH_2026_11_16__ The mascot sits on a rounded card in the CHOSEN theme
 * ([Ground.of]): white in Light, #1C1C1E in Dark, true black with the glass rim in OLED.
 * `UiLauncher` attaches [AppearanceSettings] before calling [show], so the first frame is
 * already right; "System" asks the OS through skiko, as Compose's `isSystemInDarkTheme`
 * does. A floating mascot with no ground looked like a sticker over a white desktop in
 * Dark and over a black one in Light.
 */
object LaunchSplash {

    const val LOGO_HOLD_MS = 100L
    const val CROSSFADE_MS = 300L
    const val WAVE_START_MS = LOGO_HOLD_MS + CROSSFADE_MS
    const val WAVE_FPS = 20
    const val WAVE_FRAMES = 40
    const val MAX_SHOWN_MS = 20_000
    /** Logical size of the mascot on screen. */
    const val SIZE = 220
    /** Margin between the mascot and the edge of its card. */
    const val PAD = 20
    /** Card corner radius (logical px). */
    const val CORNER = 36

    /** The splash card's look in one theme. [rim] = its 1 px edge, top → bottom colour. */
    data class Ground(val fill: Color, val rim: Pair<Color, Color>?) {
        companion object {
            /** The palette [appearance] resolves to, read as AWT colours. */
            fun of(appearance: Appearance, systemDark: Boolean): Ground {
                val p = OshiPalette.resolve(appearance, systemDark)
                // OLED: the lit glass rim. Light/Dark: the palette's hairline, so a white card
                // on a white desktop (or a dark one on a dark desktop) still has an edge.
                val rim = p.glass?.let { awt(it.rimTop) to awt(it.rimBottom) } ?: (awt(p.separator) to awt(p.separator))
                return Ground(awt(p.background), rim)
            }

            /** What the launcher has chosen, before Compose (and its `systemDark`) exists. */
            fun current(): Ground = of(OshiTheme.appearance, systemIsDark())

            private fun systemIsDark(): Boolean = runCatching {
                org.jetbrains.skiko.currentSystemTheme == org.jetbrains.skiko.SystemTheme.DARK
            }.getOrDefault(false)

            private fun awt(c: androidx.compose.ui.graphics.Color): Color =
                Color(c.red, c.green, c.blue, c.alpha)
        }
    }

    /** What to draw at one instant. [waveIndex] null = draw the still neutral pose. */
    data class Frame(val logoAlpha: Float, val neutralAlpha: Float, val waveIndex: Int?) {
        val waving: Boolean get() = waveIndex != null
    }

    /**
     * Pure timeline. [waveStartMs] is when the wave may start — later than [WAVE_START_MS]
     * only if its frames were not decoded in time (the neutral pose holds until then).
     */
    fun frameAt(tMs: Long, reducedMotion: Boolean, waveStartMs: Long = WAVE_START_MS, waveFrames: Int = WAVE_FRAMES): Frame {
        if (tMs < LOGO_HOLD_MS) return Frame(1f, 0f, null)
        if (tMs < LOGO_HOLD_MS + CROSSFADE_MS) {
            val p = ((tMs - LOGO_HOLD_MS).toFloat() / CROSSFADE_MS).coerceIn(0f, 1f)
            val eased = p * p * (3f - 2f * p) // smoothstep
            // The logo stays OPAQUE under the fading-in mascot: both share one canvas and the
            // mascot covers every logo pixel, so this is a clean crossfade — fading the logo
            // out at the same time would let the desktop show through mid-way.
            return Frame(1f, eased, null)
        }
        if (reducedMotion || waveFrames <= 0 || tMs < waveStartMs) return Frame(0f, 1f, null)
        val index = ((tMs - waveStartMs) * WAVE_FPS / 1000).toInt()
        return if (index < waveFrames) Frame(0f, 1f, index) else Frame(0f, 1f, null)
    }

    /** True once nothing will change on screen any more (the timer can stop). */
    fun isSettled(tMs: Long, reducedMotion: Boolean, waveStartMs: Long = WAVE_START_MS): Boolean =
        if (reducedMotion) tMs >= LOGO_HOLD_MS + CROSSFADE_MS
        else tMs >= waveStartMs + WAVE_FRAMES * 1000L / WAVE_FPS

    // ------------------------------------------------------------------ images

    class Images(
        val logo: BufferedImage?,
        val neutral: BufferedImage?,
        val wave: List<BufferedImage>,
        /** The O's hole in the mascot's face colour — see [holeFill]. */
        val hole: BufferedImage? = logo?.let { holeFill(it) },
    )

    /** The mascot's face fill (neutral_1024.png at the centre of the O). */
    val FACE = Color(0xF3, 0xED, 0xFF)

    /**
     * __SPLASH_GREY_FACE_2026_11_18__ The plain logo's O is a RING: its centre is transparent.
     * During the crossfade the mascot's light face (#F3EDFF) is drawn at alpha 0..1 over that
     * hole, so on the Dark (#1C1C1E) or OLED (#000) card the face went through every grey
     * between the two — "a grey face shows through the O". The phones never show it because
     * their logo sits on a ground the face colour fades in from cleanly.
     *
     * Fix: fill the O's hole with the face colour itself, under the logo, from the first frame.
     * The O then reads as the mascot's head before the features fade in, and nothing in the
     * crossfade is ever a mix of the face and a dark ground. Computed from the logo's own
     * alpha (flood fill from the centre of its opaque bounds), so the antialiased inner edge
     * of the ring is matched exactly and no geometry is hard-coded. Null if the logo has no
     * transparent centre.
     */
    fun holeFill(logo: BufferedImage, face: Color = FACE): BufferedImage? {
        val w = logo.width; val h = logo.height
        fun alpha(x: Int, y: Int) = (logo.getRGB(x, y) ushr 24) and 0xFF
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (alpha(x, y) > 128) {
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return null
        // The ring's centre: the middle of the opaque bounds, nudged up until transparent
        // (the speech-bubble tail pulls the bounds' centre down a little).
        val cx = (minX + maxX) / 2
        var cy = (minY + maxY) / 2
        while (cy > minY && alpha(cx, cy) > 8) cy--
        if (alpha(cx, cy) > 8) return null
        val seen = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        stack.addLast(cy * w + cx); seen[cy * w + cx] = true
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val rgb = face.rgb and 0xFFFFFF
        var touchedEdge = false
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w; val y = i / w
            val a = alpha(x, y)
            // Under the ring's soft inner edge too, so no dark seam survives the blend.
            out.setRGB(x, y, (255 shl 24) or rgb)
            if (a >= 250) continue
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { touchedEdge = true; continue }
            for (n in intArrayOf(i - 1, i + 1, i - w, i + w)) if (!seen[n]) { seen[n] = true; stack.addLast(n) }
        }
        // Leaked to the border = there was no closed ring; draw nothing rather than a square.
        return if (touchedEdge) null else out
    }

    private fun read(path: String, px: Int): BufferedImage? =
        OshiMascot.classpathBytes(path)?.let { bytes ->
            runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        }?.let { scaled(it, px) }

    fun loadStills(px: Int): Pair<BufferedImage?, BufferedImage?> =
        read("${OshiMascot.ROOT}/launch/logo_1024.png", px) to read("${OshiMascot.ROOT}/launch/neutral_1024.png", px)

    fun loadWave(px: Int): List<BufferedImage> =
        (0 until WAVE_FRAMES).mapNotNull { read("${OshiMascot.ROOT}/launch/wave_512/%02d.png".format(it), px) }

    /** Downscale by repeated halving, then one bilinear step: no aliasing from 1024 to ~440. */
    internal fun scaled(src: BufferedImage, px: Int): BufferedImage {
        var img = toArgb(src)
        var w = img.width
        while (w / 2 >= px) {
            w /= 2
            img = drawScaled(img, w)
        }
        return if (w == px) img else drawScaled(img, px)
    }

    private fun toArgb(src: BufferedImage): BufferedImage =
        if (src.type == BufferedImage.TYPE_INT_ARGB) src
        else BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB).also {
            val g = it.createGraphics(); g.drawImage(src, 0, 0, null); g.dispose()
        }

    private fun drawScaled(src: BufferedImage, px: Int): BufferedImage {
        val out = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, px, px, null)
        g.dispose()
        return out
    }

    /** Draw [frame] into [g] over a [size]×[size] square at the origin. */
    fun paint(g: Graphics2D, size: Int, frame: Frame, images: Images) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        fun draw(img: BufferedImage?, alpha: Float) {
            if (img == null || alpha <= 0f) return
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
            g.drawImage(img, 0, 0, size, size, null)
        }
        val wave = frame.waveIndex?.let { images.wave.getOrNull(it) }
        if (wave != null) {
            draw(wave, 1f)
        } else {
            // The hole first and at the LOGO's alpha: the O is the mascot's head from frame 0,
            // so the crossfade only brings in eyes, cheeks, smile and hands.
            draw(images.hole, frame.logoAlpha)
            draw(images.logo, frame.logoAlpha)
            draw(images.neutral ?: images.logo, frame.neutralAlpha)
        }
        g.composite = AlphaComposite.SrcOver
    }

    /** The rounded card behind the mascot, filling [w]×[h] at the origin. */
    fun paintGround(g: Graphics2D, w: Int, h: Int, ground: Ground, rounded: Boolean) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = ground.fill
        if (rounded) g.fill(RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), CORNER * 2f, CORNER * 2f))
        else g.fillRect(0, 0, w, h)
        ground.rim?.let { (top, bottom) ->
            g.paint = GradientPaint(0f, 0f, top, 0f, h.toFloat(), bottom)
            g.stroke = BasicStroke(1f)
            if (rounded) g.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, CORNER * 2f, CORNER * 2f))
            else g.drawRect(0, 0, w - 1, h - 1)
        }
    }

    /** One frame ON ITS CARD, as the window draws it — for the screenshot tool. */
    fun renderCard(tMs: Long, reducedMotion: Boolean, px: Int, ground: Ground, images: Images? = null): BufferedImage {
        val side = px + 2 * PAD
        val out = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        paintGround(g, side, side, ground, rounded = true)
        g.translate(PAD, PAD)
        g.drawImage(renderFrame(tMs, reducedMotion, px, images), 0, 0, null)
        g.dispose()
        return out
    }

    /** One frame as an image, for the screenshot tool and tests. */
    fun renderFrame(tMs: Long, reducedMotion: Boolean, px: Int, images: Images? = null): BufferedImage {
        val imgs = images ?: loadStills(px).let { (l, n) -> Images(l, n, loadWave(px)) }
        val out = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        paint(g, px, frameAt(tMs, reducedMotion), imgs)
        g.dispose()
        return out
    }

    // ------------------------------------------------------------------ the window

    @Volatile private var window: JWindow? = null
    @Volatile private var listener: AWTEventListener? = null
    @Volatile private var timer: Timer? = null

    /**
     * Show the splash, if there is a display and the launch pack is in this build. Returns at
     * once: the window is built on the EDT, the wave frames decode on a daemon thread.
     */
    fun show() {
        if (GraphicsEnvironment.isHeadless() || System.getProperty("oshi.noSplash") == "true") return
        if (OshiMascot.classpathBytes("${OshiMascot.ROOT}/launch/logo_1024.png") == null) return
        MotionPreference.startProbe()
        requested = true
        SwingUtilities.invokeLater { if (!dismissed) runCatching { open() } }
    }

    @Volatile private var requested = false
    @Volatile private var dismissed = false

    /** Close it. Idempotent, any thread; a no-op (no AWT touched) if it was never shown. */
    fun dismiss() {
        if (!requested) return
        dismissed = true
        SwingUtilities.invokeLater {
            timer?.stop(); timer = null
            listener?.let { runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(it) } }
            listener = null
            window?.let { it.isVisible = false; it.dispose() }
            window = null
        }
    }

    private fun open() {
        if (window != null) return
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val scale = runCatching { device.defaultConfiguration.defaultTransform.scaleX }.getOrDefault(1.0)
        val px = (SIZE * scale).roundToInt().coerceAtLeast(SIZE)
        val (logo, neutral) = loadStills(px)
        if (logo == null) return
        // Written and read on the EDT only (paint, timer, and the invokeLater below).
        var images = Images(logo, neutral, emptyList())
        var waveLoaded = false
        val translucent = device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        val pad = PAD
        val ground = Ground.current()
        val started = System.nanoTime()
        var waveStart = WAVE_START_MS
        val canvas = object : JComponent() {
            override fun paintComponent(g0: Graphics) {
                val g = g0.create() as Graphics2D
                try {
                    // Rounded where the window can be see-through; a plain square elsewhere.
                    paintGround(g, width, height, ground, rounded = translucent)
                    g.translate(pad, pad)
                    val t = (System.nanoTime() - started) / 1_000_000
                    paint(g, SIZE, frameAt(t, MotionPreference.systemReducedMotion, waveStart, images.wave.size), images)
                } finally { g.dispose() }
            }
        }.apply { preferredSize = Dimension(SIZE + 2 * pad, SIZE + 2 * pad); isOpaque = !translucent }

        val w = JWindow().apply {
            if (translucent) background = Color(0, 0, 0, 0)
            contentPane = canvas
            pack()
            setLocationRelativeTo(null)
            focusableWindowState = false
        }
        window = w
        val l = AWTEventListener { e ->
            if (e.id == WindowEvent.WINDOW_OPENED && e.source !== w) dismiss()
        }
        listener = l
        Toolkit.getDefaultToolkit().addAWTEventListener(l, AWTEvent.WINDOW_EVENT_MASK)
        w.isVisible = true

        Thread({
            val wave = runCatching { loadWave(px) }.getOrDefault(emptyList())
            val now = (System.nanoTime() - started) / 1_000_000
            SwingUtilities.invokeLater {
                if (wave.size == WAVE_FRAMES) {
                    waveStart = maxOf(WAVE_START_MS, now)
                    images = Images(logo, neutral, wave)
                }
                waveLoaded = true
            }
        }, "oshi-splash-frames").apply { isDaemon = true }.start()

        timer = Timer(1000 / 60) {
            val t = (System.nanoTime() - started) / 1_000_000
            canvas.repaint()
            if (t > MAX_SHOWN_MS) dismiss()
            else if (waveLoaded && isSettled(t, MotionPreference.systemReducedMotion, waveStart)) timer?.stop()
        }.apply { start() }
        // The cap holds even after the repaint timer stops.
        Timer(MAX_SHOWN_MS) { dismiss() }.apply { isRepeats = false; start() }
    }

}
