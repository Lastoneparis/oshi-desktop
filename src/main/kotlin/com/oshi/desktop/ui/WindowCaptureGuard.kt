package com.oshi.desktop.ui

import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.ScreenCapturePreference
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent

/**
 * __SCREEN_CAPTURE_PROTECTION_2026_11_20__ Applies [ScreenCapturePreference] to EVERY window
 * this process opens — the main window, the incoming-call window, the photo-markup window, the
 * identity/startup windows and every Compose dialog that is its own OS window — through one
 * AWT window listener, so a window added later is covered without anyone remembering to.
 *
 * Compose popups (menus, alert dialogs) draw inside their owner window and are covered by it.
 * The tray's popup menu is a native Win32 menu (Open / Quit only), not a window.
 */
object WindowCaptureGuard {
    /** user32 `WDA_*` values. */
    const val WDA_NONE = 0x0
    const val WDA_MONITOR = 0x1
    const val WDA_EXCLUDEFROMCAPTURE = 0x11

    /** dwmapi attributes: live thumbnail → icon, and no Aero Peek of the content. */
    private const val DWMWA_FORCE_ICONIC_REPRESENTATION = 7
    private const val DWMWA_DISALLOW_PEEK = 11

    /** Pure: the affinities to try, in order, for a wanted state. */
    fun affinities(protect: Boolean): List<Int> =
        if (protect) listOf(WDA_EXCLUDEFROMCAPTURE, WDA_MONITOR) else listOf(WDA_NONE)

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun SetWindowDisplayAffinity(hWnd: Pointer, dwAffinity: Int): Boolean
    }

    @Suppress("FunctionName")
    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: IntByReference, size: Int): Int
    }

    private val user32: User32? by lazy {
        if (!DesktopPaths.isWindows) null else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }
    private val dwm: DwmApi? by lazy {
        if (!DesktopPaths.isWindows) null else runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
    }

    /** The affinity last applied successfully, for the settings row; null = not applied. */
    @Volatile var lastApplied: Int? = null
        private set

    @Volatile private var installed = false

    fun install() {
        if (installed || !ScreenCapturePreference.supported) return
        installed = true
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
                if (e is WindowEvent && (e.id == WindowEvent.WINDOW_OPENED || e.id == WindowEvent.WINDOW_ACTIVATED)) {
                    apply(e.window)
                }
            }, AWTEvent.WINDOW_EVENT_MASK)
        }
        ScreenCapturePreference.onChange { java.awt.EventQueue.invokeLater { Window.getWindows().forEach(::apply) } }
        // __STRICT_MODE_2026_11_22__ strict mode forces protection on (and gives it back when off).
        com.oshi.desktop.store.StrictMode.addChangeListener { _, _ -> java.awt.EventQueue.invokeLater { Window.getWindows().forEach(::apply) } }
        java.awt.EventQueue.invokeLater { Window.getWindows().forEach(::apply) }
    }

    fun apply(w: Window?) {
        if (w == null || !w.isDisplayable) return
        val u = user32 ?: return
        val hwnd = runCatching { Native.getComponentPointer(w) }.getOrNull() ?: return
        val protect = ScreenCapturePreference.effective
        lastApplied = affinities(protect).firstOrNull { runCatching { u.SetWindowDisplayAffinity(hwnd, it) }.getOrDefault(false) }
        dwm?.let { d ->
            val v = IntByReference(if (protect) 1 else 0)
            runCatching { d.DwmSetWindowAttribute(hwnd, DWMWA_FORCE_ICONIC_REPRESENTATION, v, 4) }
            runCatching { d.DwmSetWindowAttribute(hwnd, DWMWA_DISALLOW_PEEK, v, 4) }
        }
    }
}
