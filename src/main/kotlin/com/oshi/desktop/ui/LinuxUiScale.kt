package com.oshi.desktop.ui

import java.util.concurrent.TimeUnit

/**
 * __LINUX_HIDPI_2026_11_21__ HiDPI on an X11 Linux session.
 *
 * The JDK (Temurin, as packaged) takes its X11 scale from `J2D_UISCALE`, `GDK_SCALE` or GNOME's
 * `scaling-factor` setting — and current GNOME leaves that setting at 0 ("auto") whatever the
 * display scale is, while XFCE's "window scaling" and similar settings only publish `Xft.dpi`
 * (192 at 200 %). So on a 4K screen OSHI opened at HALF size, unreadable, while every GTK and Qt
 * app around it was scaled.
 *
 * Before AWT starts, and only when nothing has expressed a scale already: read `Xft.dpi` from
 * the X resource database and ask for that INTEGER scale (X11 AWT has no fractional scale; 1.5
 * would truncate to 1, so 144 dpi stays 1 and the text-scaling of the desktop is left alone).
 *
 * NOT under Wayland: there the compositor scales Xwayland windows itself, and asking AWT to
 * scale as well would draw OSHI at four times the size on a 200 % GNOME.
 */
object LinuxUiScale {

    /** The integer scale for an `Xft.dpi` value, or null to leave the JDK's default. */
    fun scaleFor(xftDpi: Double?): Int? {
        if (xftDpi == null || xftDpi <= 0) return null
        val s = (xftDpi / 96.0 + 0.25).toInt()          // 168 dpi (175 %) -> 2; 144 (150 %) -> 1
        return s.takeIf { it >= 2 }?.coerceAtMost(4)
    }

    /** `Xft.dpi:	192` from `xrdb -query`. */
    fun parseXrdb(output: String): Double? =
        Regex("""(?m)^Xft\.dpi:\s*([0-9.]+)\s*$""").find(output)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Whether something has already chosen a scale (then we never override it). */
    fun alreadyChosen(env: (String) -> String?, prop: (String) -> String?): Boolean =
        prop("sun.java2d.uiScale") != null || !env("GDK_SCALE").isNullOrBlank() || !env("J2D_UISCALE").isNullOrBlank()

    fun applyIfNeeded(
        linux: Boolean = com.oshi.desktop.store.DesktopPaths.isLinux,
        env: (String) -> String? = System::getenv,
        prop: (String) -> String? = System::getProperty,
        readXrdb: () -> String? = ::queryXrdb,
        set: (String, String) -> Unit = { k, v -> System.setProperty(k, v) },
    ): Int? {
        if (!linux || alreadyChosen(env, prop)) return null
        if (env("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true) || !env("WAYLAND_DISPLAY").isNullOrBlank()) return null
        if (env("DISPLAY").isNullOrBlank()) return null
        val scale = scaleFor(readXrdb()?.let(::parseXrdb)) ?: return null
        set("sun.java2d.uiScale", scale.toString())
        return scale
    }

    private fun queryXrdb(): String? = runCatching {
        val xrdb = com.oshi.desktop.store.ExecutableLookup.which("xrdb") ?: return null
        val p = ProcessBuilder(xrdb, "-query").redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out
    }.getOrNull()
}
