package com.oshi.desktop.ui

/**
 * __GLASS_ALL_THEMES__ The theme names the render tools take: `light`, `dark`, `oled` (each
 * with its default glass), or any of them suffixed `-glass` / `-flat` to force the switch.
 */
object RenderTheme {
    fun appearance(theme: String): Appearance {
        val base = theme.substringBefore('-')
        val glass = when (theme.substringAfter('-', "")) {
            "glass" -> true
            "flat" -> false
            else -> null
        }
        return when (base) {
            "dark" -> Appearance(ThemeMode.DARK, oledBlack = false, glass = glass)
            "oled" -> Appearance(ThemeMode.DARK, oledBlack = true, glass = glass)
            else -> Appearance(ThemeMode.LIGHT, oledBlack = false, glass = glass)
        }
    }
}
