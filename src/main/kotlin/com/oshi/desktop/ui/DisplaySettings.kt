package com.oshi.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.store.AtomicFile
import com.oshi.desktop.ui.components.WallpaperId
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * __DESKTOP_SETTINGS_2027__ The three display preferences the phones have and this window
 * lacked: the app's LANGUAGE (Android's per-app language; iOS's Settings › OSHI › Language),
 * the TEXT SIZE (`settings.appearance.fontSize`) and the DEFAULT CHAT WALLPAPER
 * (`settings.appearance.wallpaper`).
 *
 * All three are snapshot state read by [OshiMaterialTheme], so they apply live:
 *  * the language re-keys the whole composition (every `t(…)` is a plain read of
 *    [Strings.activeTag], which is not state — re-keying is what makes them read again)
 *    and flips the layout direction for Arabic, Hebrew and Persian;
 *  * the text size multiplies the window's font scale, so every `sp` grows and no `dp`
 *    does — the layout keeps its grid while the words get bigger, as on the phones.
 *
 * Stored beside the appearance file in the profile, and like it, a missing or corrupt file
 * reads as the defaults rather than failing the launch.
 */
object DisplaySettings {

    /**
     * The OS's language, captured once before any override replaces [Strings.activeTag].
     * __SYSTEM_LANGUAGE_LIST_2027__ the whole preference list, not only the JVM's first entry
     * (see [com.oshi.desktop.i18n.SystemLanguage]).
     */
    val systemTag: String = com.oshi.desktop.i18n.SystemLanguage.detect()

    /** null = follow the system. Otherwise one of [Strings.availableTags]. */
    var languageOverride: String? by mutableStateOf(null)
        private set

    /** Multiplies the font scale: 0.9, 1.0, 1.15 or 1.3. */
    var textScale: Float by mutableStateOf(1f)
        private set

    /** The wallpaper a conversation shows when it has none of its own. */
    var defaultWallpaper: WallpaperId by mutableStateOf(WallpaperId.NONE)
        private set

    val TEXT_SCALES = listOf(0.9f, 1f, 1.15f, 1.3f)

    @Volatile private var file: File? = null

    fun attach(home: File) {
        val f = File(home, FILE_NAME)
        file = f
        runCatching {
            if (f.isFile) {
                val json = JSONObject(f.readText(Charsets.UTF_8))
                val lang = json.optString(KEY_LANGUAGE, "").takeIf { it.isNotBlank() && it in Strings.availableTags }
                languageOverride = lang
                textScale = json.optDouble(KEY_TEXT_SCALE, 1.0).toFloat().takeIf { it in TEXT_SCALES } ?: 1f
                defaultWallpaper = WallpaperId.fromId(json.optString(KEY_WALLPAPER, null))
            }
        }
        applyLanguage()
    }

    /** The tag the window is drawn in right now. */
    val activeTag: String get() = languageOverride ?: systemTag

    fun setLanguage(tag: String?) {
        languageOverride = tag?.takeIf { it in Strings.availableTags }
        applyLanguage()
        save()
    }

    fun changeTextScale(scale: Float) {
        textScale = scale
        save()
    }

    fun changeDefaultWallpaper(paper: WallpaperId) {
        defaultWallpaper = paper
        save()
    }

    /** Test / renderer seam: draw in [tag] without touching any file. */
    fun preview(tag: String?) {
        languageOverride = tag
        applyLanguage()
    }

    private fun applyLanguage() {
        Strings.activeTag = activeTag
    }

    /** Arabic, Hebrew and Persian lay out right to left; the 31 others left to right. */
    fun isRtl(tag: String = activeTag): Boolean =
        Strings.normalize(tag).substringBefore('-').lowercase(Locale.ROOT) in RTL_LANGUAGES

    /**
     * A language's own name, in that language ("Français", "العربية"), which is how every
     * language picker on the phones lists them — someone who cannot read the current
     * language can still find their own.
     */
    fun nativeName(tag: String): String {
        val loc = Locale.forLanguageTag(tag)
        val name = when (tag) {
            "zh-Hans" -> "简体中文"
            "zh-Hant" -> "繁體中文"
            "pt-BR" -> "Português (Brasil)"
            "pt-PT" -> "Português (Portugal)"
            else -> loc.getDisplayLanguage(loc)
        }
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(loc) else it.toString() }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            AtomicFile.write(
                f,
                JSONObject()
                    .put(KEY_LANGUAGE, languageOverride ?: "")
                    .put(KEY_TEXT_SCALE, textScale.toDouble())
                    .put(KEY_WALLPAPER, defaultWallpaper.id)
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            )
        }
    }

    private val RTL_LANGUAGES = setOf("ar", "he", "iw", "fa", "ur", "yi")

    const val FILE_NAME = "display-preferences.json"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_TEXT_SCALE = "textScale"
    private const val KEY_WALLPAPER = "defaultWallpaper"
}
