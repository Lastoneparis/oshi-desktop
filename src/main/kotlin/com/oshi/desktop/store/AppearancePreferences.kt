package com.oshi.desktop.store

import com.oshi.desktop.ui.Appearance
import com.oshi.desktop.ui.ThemeMode
import java.io.File
import org.json.JSONObject

/**
 * __OLED_BLACK_2026_11_16__ Theme and "OLED black", for THIS machine only.
 *
 * Same terms as `NotificationPreferences` and the wallpaper map: a small JSON file in the
 * profile's home, never sent, never synced. The phones keep their appearance in their own
 * local settings and so does this window — choosing OLED here changes nothing on a phone.
 *
 * It never throws on read. A missing, corrupt or future-shaped file loads as
 * [Appearance.DEFAULT] (Light, OLED off) — the window this build replaced — because failing
 * to open the app over a cosmetic preference would be a far worse bug than a lost theme.
 */
class AppearancePreferences(private val file: File) {

    @Synchronized fun load(): Appearance = try {
        if (!file.isFile) Appearance.DEFAULT
        else {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            Appearance(
                mode = ThemeMode.fromId(json.optString(KEY_MODE, null)),
                oledBlack = json.optBoolean(KEY_OLED, false),
                // __GLASS_ALL_THEMES__ absent (every file written before the switch) = the
                // per-theme default; anything not a boolean also reads as absent.
                glass = if (json.has(KEY_GLASS)) (json.opt(KEY_GLASS) as? Boolean) else null,
            )
        }
    } catch (_: Exception) {
        Appearance.DEFAULT
    }

    @Synchronized fun save(appearance: Appearance) {
        AtomicFile.write(
            file,
            JSONObject()
                .put(KEY_MODE, appearance.mode.id)
                .put(KEY_OLED, appearance.oledBlack)
                .apply { appearance.glass?.let { put(KEY_GLASS, it) } }
                .toString()
                .toByteArray(Charsets.UTF_8),
        )
    }

    companion object {
        const val FILE_NAME = "appearance-preferences.json"
        const val KEY_MODE = "theme"
        const val KEY_OLED = "oledBlack"
        const val KEY_GLASS = "glass"
    }
}
