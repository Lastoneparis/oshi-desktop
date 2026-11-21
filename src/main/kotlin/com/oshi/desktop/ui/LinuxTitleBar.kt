package com.oshi.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.delay

/**
 * __LINUX_DARK_TITLE_BAR_2026_11_21__ The title bar follows OSHI's theme on Linux, as it does on
 * macOS (NSAppearance) and Windows.
 *
 * AWT windows on Linux are X11 windows (Xwayland on a Wayland session) decorated by the window
 * manager. Mutter (GNOME), Metacity, Marco, Muffin and xfwm4 choose a dark or light frame from the
 * client's `_GTK_THEME_VARIANT` property — the one GTK apps set for `prefer-dark-theme` — and
 * re-read it when it changes. So: "dark" in Dark/OLED, "light" in Light, and the property REMOVED
 * when the theme follows the system (the WM's own theme then decides, as before).
 *
 * MEASURED LIMIT (linux-qa, GNOME Shell 46 on Wayland, app on Xwayland): the hint is on the
 * window (xprop: "dark") but the frame mutter-x11-frames draws stays LIGHT, whether the hint is set
 * before mapping or after, and after an unmap/map. GNOME's frame follows the desktop-wide style,
 * not this hint, there. Not measured: Metacity/Marco/Muffin/xfwm4, which document honouring it.
 *
 * KDE's KWin ignores it (it reads `_KDE_NET_WM_COLOR_SCHEME`, a colour-scheme FILE path), so KDE is
 * left alone. Everything is guarded: no Xlib, no DISPLAY, an unknown window, any native error — it
 * does nothing, and a title bar is never a reason for the window to fail.
 */
object LinuxTitleBar {

    /** "dark", "light", "" (remove the property: follow the window manager) or null (do nothing here). */
    fun variant(
        linux: Boolean,
        env: (String) -> String?,
        systemMode: Boolean,
        dark: Boolean,
    ): String? {
        if (!linux || env("DISPLAY").isNullOrBlank()) return null
        val desktop = (env("XDG_CURRENT_DESKTOP").orEmpty() + ":" + env("DESKTOP_SESSION").orEmpty()).lowercase()
        if ("kde" in desktop || "plasma" in desktop || "lxqt" in desktop) return null
        if (systemMode) return ""
        return if (dark) "dark" else "light"
    }

    @Suppress("FunctionName")
    private interface X11 : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XCloseDisplay(d: Pointer): Int
        fun XInternAtom(d: Pointer, name: String, onlyIfExists: Boolean): NativeLong
        fun XChangeProperty(d: Pointer, w: NativeLong, prop: NativeLong, type: NativeLong, format: Int, mode: Int, data: ByteArray, n: Int): Int
        fun XDeleteProperty(d: Pointer, w: NativeLong, prop: NativeLong): Int
        fun XQueryTree(d: Pointer, w: NativeLong, root: NativeLongByReference, parent: NativeLongByReference, children: PointerByReference, n: IntByReference): Int
        fun XFree(p: Pointer): Int
        fun XFlush(d: Pointer): Int
    }

    /**
     * The variant for the theme as it is NOW (Compose snapshot state, read on the EDT), or null.
     */
    fun currentVariant(): String? = runCatching {
        variant(com.oshi.desktop.store.DesktopPaths.isLinux, System::getenv,
            OshiTheme.appearance.mode == ThemeMode.SYSTEM, OshiTheme.palette.isDark)
    }.getOrNull()

    @Volatile private var installed = false

    /**
     * Sets the hint on every OSHI window the moment AWT creates its X window — BEFORE it is mapped.
     * Mutter (GNOME 44+ draws frames in a separate client) reads `_GTK_THEME_VARIANT` when it
     * frames a window; a hint set after the window is on screen was stored but not drawn (seen on
     * GNOME 46 in linux-qa). Call once, before the first window.
     */
    fun install() {
        if (installed || !com.oshi.desktop.store.DesktopPaths.isLinux) return
        installed = true
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
                if (e is java.awt.event.HierarchyEvent &&
                    (e.changeFlags and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
                ) {
                    val w = e.component as? java.awt.Window
                    if (w != null && w.isDisplayable) currentVariant()?.let { apply(w, it) }
                }
            }, java.awt.AWTEvent.HIERARCHY_EVENT_MASK)
        }
    }

    private val x11: X11? by lazy { runCatching { Native.load("X11", X11::class.java) }.getOrNull() }

    /** Applies [variant] to [window]'s X11 window and every ancestor below the root (the WM reads the client top level). */
    fun apply(window: java.awt.Window, variant: String): Boolean = runCatching {
        if (!window.isDisplayable) return false
        val x = x11 ?: return false
        val id = Native.getComponentID(window).takeIf { it != 0L } ?: return false
        val d = x.XOpenDisplay(null) ?: return false
        try {
            val prop = x.XInternAtom(d, "_GTK_THEME_VARIANT", false)
            val utf8 = x.XInternAtom(d, "UTF8_STRING", false)
            var w = NativeLong(id)
            repeat(8) {
                if (variant.isEmpty()) x.XDeleteProperty(d, w, prop)
                else variant.toByteArray(Charsets.UTF_8).let { b -> x.XChangeProperty(d, w, prop, utf8, 8, 0 /* PropModeReplace */, b, b.size) }
                val root = NativeLongByReference(); val parent = NativeLongByReference()
                val kids = PointerByReference(); val n = IntByReference()
                if (x.XQueryTree(d, w, root, parent, kids, n) == 0) return@repeat
                kids.value?.let { x.XFree(it) }
                val p = parent.value
                if (p.toLong() == 0L || p.toLong() == root.value.toLong()) { x.XFlush(d); return true }
                w = p
            }
            x.XFlush(d)
            true
        } finally {
            x.XCloseDisplay(d)
        }
    }.getOrDefault(false)
}

/** The Linux half of [MacTitleBarAppearance]: re-applied whenever the theme changes. */
@Composable
internal fun LinuxTitleBarAppearance(window: java.awt.Window) {
    if (!com.oshi.desktop.store.DesktopPaths.isLinux) return
    val mode = OshiTheme.appearance.mode
    val dark = OshiTheme.palette.isDark
    // The first value is set before the window is mapped (LinuxTitleBar.install); this effect
    // keeps the hint in step with later theme changes (WMs that re-read it pick it up).
    val framed = androidx.compose.runtime.remember(window) {
        arrayOf(LinuxTitleBar.variant(true, System::getenv, mode == ThemeMode.SYSTEM, dark))
    }
    LaunchedEffect(window, mode, dark) {
        val v = LinuxTitleBar.variant(true, System::getenv, mode == ThemeMode.SYSTEM, dark) ?: return@LaunchedEffect
        // The X window exists only once AWT has realised the frame; retry briefly until it has.
        repeat(20) {
            if (LinuxTitleBar.apply(window, v)) { framed[0] = v; return@LaunchedEffect }
            delay(250)
        }
    }
}
