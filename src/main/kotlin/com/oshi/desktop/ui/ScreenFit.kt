package com.oshi.desktop.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * __WINDOW_TALLER_THAN_THE_LAPTOP_2026_11_19__ The main window opened at 1180×780 dp. On the most
 * common Windows laptop setups — 1920×1080 at the recommended 150 % (≈1280×690 dp usable above
 * the taskbar) and 1366×768 at 100 % — that is taller than the screen, Windows does not shrink a
 * Java window to the work area, and the bottom of the window, i.e. the message composer, sat
 * under the taskbar. Every first-opened window is now clamped to the usable screen area.
 *
 * AWT's `maximumWindowBounds` is in user-space units on a HiDPI JDK, which is what Compose
 * Desktop's dp are, so the two compare directly.
 */
object ScreenFit {
    /** The bundled 256 px logo (window icon, tray icon). */
    const val LOGO_RESOURCE = "/branding/oshi-256.png"

    /** Leave this much air so the title bar and the edge of the window stay reachable. */
    private const val MARGIN_DP = 24f

    fun fit(desired: DpSize, availableWidthDp: Float?, availableHeightDp: Float?): DpSize {
        if (availableWidthDp == null || availableHeightDp == null || availableWidthDp <= 0f || availableHeightDp <= 0f) {
            return desired
        }
        val w = minOf(desired.width.value, availableWidthDp - MARGIN_DP).coerceAtLeast(minOf(desired.width.value, 480f))
        val h = minOf(desired.height.value, availableHeightDp - MARGIN_DP).coerceAtLeast(minOf(desired.height.value, 360f))
        return DpSize(w.dp, h.dp)
    }

    /** [desired], shrunk to the primary screen's usable area (taskbar/dock excluded). */
    fun fitToScreen(desired: DpSize): DpSize {
        val b = runCatching {
            if (java.awt.GraphicsEnvironment.isHeadless()) null
            else java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        }.getOrNull()
        return fit(desired, b?.width?.toFloat(), b?.height?.toFloat())
    }
}
