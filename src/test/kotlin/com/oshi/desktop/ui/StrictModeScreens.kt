package com.oshi.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oshi.desktop.security.RemoteControlDetector
import com.oshi.desktop.security.RemoteControlWatch
import com.oshi.desktop.store.AppLock
import com.oshi.desktop.store.StrictMode
import com.oshi.desktop.ui.components.SettingsActions
import com.oshi.desktop.ui.components.SettingsNavigation
import com.oshi.desktop.ui.components.SettingsPane
import com.oshi.desktop.ui.components.SettingsSection
import com.oshi.desktop.ui.components.SettingsSlots
import com.oshi.desktop.ui.components.StrictModeBanner
import com.oshi.desktop.ui.components.StrictModeNavigation
import com.oshi.desktop.ui.state.Destination
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.ShellState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * __STRICT_MODE_2026_11_22__ Settings › Security with strict mode off / on, the explanation page,
 * Settings › Privacy with its forced rows, and the remote-control banner — Light and Dark — from
 * the REAL composables with fabricated state. A tool, gated so the suite never renders by accident:
 *
 *     OSHI_RENDER_STRICT=1 OSHI_STRICT_OUT=/abs/dir ./gradlew test --tests 'com.oshi.desktop.ui.StrictModeScreens'
 */
class StrictModeScreens {

    private fun state() = ShellState(
        selfAddress = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", selfAddressShort = "3q2+7w…", displayName = "Hugo",
        relayUrl = "https://oshi-messenger.com", gateOpen = true, gateAnswered = true,
        deliveryReceiptsEnabled = true, readReceiptsEnabled = true, syncNotice = null, destination = Destination.MORE,
        contacts = emptyList(), scheduled = emptyList(), shareText = "", pane = Pane.CONVERSATION,
        conversations = emptyList(), selectedId = null, thread = null, draft = "", busy = false, notice = null,
    )

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_STRICT") == "1")
        val base = File(System.getenv("OSHI_STRICT_OUT") ?: "build/screens-strict").apply { mkdirs() }
        val langs = (System.getenv("OSHI_RENDER_LANGS") ?: "en").split(',')
        // A passcode is "set" (the stored values are never checked by rendering).
        AppLock.preview(AppLock.Config(saltB64 = "AAAA", hashB64 = "AAAA"), isLocked = false)
        val hits = listOf(
            RemoteControlDetector.Hit("anydesk", "AnyDesk", RemoteControlDetector.Category.REMOTE_CONTROL),
            RemoteControlDetector.Hit("claude", "Claude (desktop app / Claude Code)", RemoteControlDetector.Category.AI_AGENT),
        )
        val st = state()
        try {
            for (lang in langs) for (theme in listOf("light", "dark")) {
                OshiTheme.appearance = RenderTheme.appearance(theme)
                DisplaySettings.preview(lang)
                val p = if (lang == "en") "" else "$lang-"

                StrictMode.preview(StrictMode.Values())
                RemoteControlWatch.preview(emptyList())
                StrictModeNavigation.explaining = false
                SettingsNavigation.section = SettingsSection.SECURITY
                shot(File(base, "${p}01-security-strict-off-$theme.png"), 1180, 1400) { SettingsPane(st, SettingsSlots(), SettingsActions()) }

                StrictMode.preview(StrictMode.Values(enabled = true))
                RemoteControlWatch.preview(hits)
                shot(File(base, "${p}02-security-strict-on-$theme.png"), 1180, 1700) { SettingsPane(st, SettingsSlots(), SettingsActions()) }

                StrictModeNavigation.explaining = true
                shot(File(base, "${p}03-explanation-$theme.png"), 1180, 2200) { SettingsPane(st, SettingsSlots(), SettingsActions()) }
                StrictModeNavigation.explaining = false

                SettingsNavigation.section = SettingsSection.PRIVACY
                shot(File(base, "${p}04-privacy-forced-$theme.png"), 1180, 1900) { SettingsPane(st, SettingsSlots(), SettingsActions()) }

                shot(File(base, "${p}05-banner-$theme.png"), 900, 260) {
                    Box(Modifier.fillMaxSize().background(OshiTheme.paneGround)) {
                        StrictModeBanner(Modifier.align(Alignment.TopCenter).padding(top = 24.dp, start = 16.dp, end = 16.dp))
                    }
                }
            }
        } finally {
            StrictMode.preview(StrictMode.Values())
            RemoteControlWatch.preview(emptyList())
            AppLock.preview(AppLock.Config(), false)
            DisplaySettings.preview(null)
        }
    }

    private fun shot(target: File, wDp: Int, hDp: Int, scale: Float = 1.5f, content: @Composable () -> Unit) {
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            OshiMaterialTheme { Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { content() } }
        }
        try {
            for (f in 0 until 3) scene.render(f * 50_000_000L)
            val image = scene.render(3 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("[strict] wrote ${target.absolutePath}")
        } finally { scene.close() }
    }
}
