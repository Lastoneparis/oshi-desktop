package com.oshi.desktop.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont

/**
 * __LINUX_UI_FONT_2026_11_21__ Which system family draws the UI on Linux.
 *
 * `FontFamily.SansSerif` is whatever fontconfig calls "sans-serif", which on Ubuntu/Debian is
 * DejaVu Sans: wide, heavy in bold, a 2004 screen face. Next to the macOS build (SF) and the
 * Windows one (Segoe UI) the same screens looked cramped and dated, and wider text wrapped
 * earlier in fixed-width cards (first-run, Settings rows).
 *
 * Still the OSHI rule (OshiTheme: no bundled face, the platform's own one): only INSTALLED
 * families are used, in the order closest to SF/Segoe — Inter, Noto Sans (installed by default
 * on Ubuntu, Fedora and KDE distros), Ubuntu, Cantarell — and with none of them present nothing
 * changes. Scripts those faces do not cover (CJK, Arabic, Thai…) fall back through fontconfig
 * exactly as before.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object LinuxFonts {
    val PREFERRED = listOf("Inter", "Noto Sans", "Ubuntu", "Cantarell")

    fun pick(installed: (String) -> Boolean): String? = PREFERRED.firstOrNull(installed)

    fun family(linux: Boolean = com.oshi.desktop.store.DesktopPaths.isLinux): FontFamily? {
        if (!linux || System.getenv("OSHI_UI_FONT") == "system") return null
        return runCatching {
            val mgr = org.jetbrains.skia.FontMgr.default
            val name = pick { n -> mgr.matchFamily(n).count() > 0 } ?: return null
            FontFamily(
                listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
                    .map { SystemFont(name, it) },
            )
        }.getOrNull()
    }
}
