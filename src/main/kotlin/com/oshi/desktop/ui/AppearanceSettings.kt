package com.oshi.desktop.ui

import com.oshi.desktop.store.AppearancePreferences
import java.io.File

/**
 * __OLED_BLACK_2026_11_16__ The one place the appearance choice is read and written.
 *
 * [attach] runs at launch, BEFORE the first window, so the identity windows that can open
 * ahead of the main one already draw in the chosen theme. [update] writes the file and
 * the live state together: [OshiTheme.appearance] is snapshot state, so every composable
 * that has read a colour recomposes — the setting applies without a restart.
 */
object AppearanceSettings {
    @Volatile private var store: AppearancePreferences? = null

    fun attach(home: File) {
        val prefs = AppearancePreferences(File(home, AppearancePreferences.FILE_NAME))
        store = prefs
        OshiTheme.appearance = prefs.load()
    }

    val current: Appearance get() = OshiTheme.appearance

    fun update(appearance: Appearance) {
        OshiTheme.appearance = appearance
        // A failed write keeps the live change for this session; it must not throw out of a
        // click handler over a cosmetic file.
        runCatching { store?.save(appearance) }
    }

    fun setMode(mode: ThemeMode) = update(current.copy(mode = mode))
    fun setOledBlack(on: Boolean) = update(current.copy(oledBlack = on))
    /** __GLASS_ALL_THEMES__ "Glass effect": once touched, the choice holds in every theme. */
    fun setGlass(on: Boolean) = update(current.copy(glass = on))
}

/**
 * __MERGED_CHATS_2026_11_18__ "Combine messages and groups" (More › Appearance), live.
 *
 * [attach] runs beside [AppearanceSettings.attach], before the vault is opened, which is what
 * lets it tell a FRESH profile (no `keyvault.json` yet) from an existing one. The default is
 * decided once and saved (`ChatListMerge.defaultFor`): ON for a new profile, OFF for anyone
 * who already had the two-half sidebar.
 */
object ChatListSettings {
    private val state = androidx.compose.runtime.mutableStateOf(false)
    @Volatile private var store: com.oshi.desktop.store.ChatListPreferences? = null

    val merged: Boolean get() = state.value

    /** __MESSAGES_SCREENS_2027__ the headless renderer draws both layouts without a profile. */
    internal fun overrideForRender(merged: Boolean) { state.value = merged }

    fun attach(home: File) {
        val prefs = com.oshi.desktop.store.ChatListPreferences(
            File(home, com.oshi.desktop.store.ChatListPreferences.FILE_NAME),
        )
        store = prefs
        val decided = prefs.load() ?: com.oshi.desktop.ui.state.ChatListMerge.defaultFor(
            profileIsFresh = !File(home, com.oshi.desktop.store.KeyVault.FILE_NAME).exists(),
        ).also { runCatching { prefs.save(it) } }
        state.value = decided
    }

    fun setMerged(on: Boolean) {
        state.value = on
        runCatching { store?.save(on) }
    }
}
