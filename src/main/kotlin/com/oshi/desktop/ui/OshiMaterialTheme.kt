package com.oshi.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * __OLED_BLACK_2026_11_16__ The one root every OSHI window composes under.
 *
 * It replaces four copies of `MaterialTheme(colorScheme = OshiTheme.colors, …)`, and adds
 * the two things a light-only window never needed:
 *
 *  * it reports the OS theme into [OshiTheme.systemDark] BEFORE anything below reads a
 *    colour, so "System" resolves on the first frame rather than one frame late;
 *  * it sets [LocalContentColor] to the palette's text colour. Material3's default is
 *    black, which was invisible as a choice while the window was white and becomes black
 *    text on a black window the moment it is not.
 *
 * `isSystemInDarkTheme()` follows the OS live on Windows and macOS. On Linux Compose
 * reports what the desktop exposes, and "System" may resolve to light there; forcing Dark
 * in the setting works everywhere.
 */
@Composable
fun OshiMaterialTheme(content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    if (OshiTheme.systemDark != systemDark) OshiTheme.systemDark = systemDark
    MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
        // __DESKTOP_SETTINGS_2027__ language, direction and text size (see DisplaySettings).
        // Reading `languageOverride` (through `activeTag`) subscribes this root to it; `key`
        // then re-runs every `t(…)` below in the new language.
        // __RTL_2027__ ONE app-wide direction switch: Arabic, Persian and Hebrew lay the
        // whole window out right-to-left (sidebar included), because Compose mirrors
        // Row/padding/alignment from this local. The direction is taken from the language
        // the strings are actually drawn in (`Strings.activeTag`, which DisplaySettings
        // keeps equal to its own tag), so a renderer that sets the catalog tag directly
        // still gets the matching direction. Paragraph-level bidi inside the tool screens
        // (ToolsKit) is separate and stays.
        val tag = DisplaySettings.activeTag
        val drawnTag = com.oshi.desktop.i18n.Strings.activeTag
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalContentColor provides OshiTheme.textPrimary,
            LocalLayoutDirection provides layoutDirectionFor(drawnTag),
            LocalDensity provides Density(base.density, base.fontScale * DisplaySettings.textScale),
        ) {
            key(tag, drawnTag) { content() }
        }
    }
}

/** __RTL_2027__ The layout direction of a catalog tag: right-to-left for ar, fa, he (and ur/yi if ever added). */
fun layoutDirectionFor(tag: String): LayoutDirection =
    if (DisplaySettings.isRtl(tag)) LayoutDirection.Rtl else LayoutDirection.Ltr
