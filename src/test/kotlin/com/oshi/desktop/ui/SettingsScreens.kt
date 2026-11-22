package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.oshi.desktop.devsync.DesktopDevSync
import com.oshi.desktop.ui.components.ContactsSettingsContent
import com.oshi.desktop.ui.components.DeleteAccountHost
import com.oshi.desktop.ui.components.DeleteAccountPane
import com.oshi.desktop.ui.components.LinkedDevicesContent
import com.oshi.desktop.ui.components.PhotoDisguiseHost
import com.oshi.desktop.ui.components.PrivacyDisguisePane
import com.oshi.desktop.ui.components.SettingsNavigation
import com.oshi.desktop.ui.components.SettingsPane
import com.oshi.desktop.ui.components.SettingsSection
import com.oshi.desktop.ui.components.SettingsSlots
import com.oshi.desktop.ui.components.SettingsActions
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.desktop.ui.state.Destination
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.ShellState
import com.oshi.messenger.network.v2.devsync.LinkedDevice
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * __DESKTOP_SETTINGS_2027__ Every Settings page and the first-run screen, rendered from the
 * REAL composables with fabricated state (no vault, no network), for a person to look at:
 *
 *     ./gradlew renderSettingsScreens → build/screens-settings/<lang>-<theme>/NN-name.png
 *
 * Languages en, fr, ar (Arabic is the right-to-left check) × light, dark, OLED, plus the
 * window at its narrowest (1024) and widest (2560) supported width.
 */
object SettingsScreens {
    private const val SCALE = 2f
    private const val W = 1180
    private const val H = 820

    private const val ME = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private fun addr(seed: Char) = seed.toString().repeat(43) + "="
    private const val SAFETY = "12345 67890 12345 67890 12345 67890 12345 67890 12345 67890 12345 67890"

    private val contacts = listOf(
        ContactRow(addr('a'), "aaaa…", "Camille", true, false, SAFETY),
        ContactRow(addr('b'), "bbbb…", "Bob", false, false, SAFETY),
        ContactRow(addr('c'), "cccc…", "Inès", false, true, SAFETY),
    )

    private fun state(withContacts: Boolean = true) = ShellState(
        selfAddress = ME,
        selfAddressShort = "3q2+7w…",
        displayName = "Hugo",
        relayUrl = "https://oshi-messenger.com",
        gateOpen = true,
        gateAnswered = true,
        deliveryReceiptsEnabled = true,
        readReceiptsEnabled = false,
        syncNotice = null,
        destination = Destination.MORE,
        contacts = if (withContacts) contacts else emptyList(),
        scheduled = emptyList(),
        shareText = "",
        pane = Pane.CONVERSATION,
        conversations = emptyList(),
        selectedId = null,
        thread = null,
        draft = "",
        busy = false,
        notice = null,
    )

    private val snapshot = DesktopDevSync.Snapshot(
        enabled = true,
        serverEnabled = true,
        override = null,
        running = true,
        deviceId = "d3adb33f0a",
        linked = listOf(
            LinkedDevice("p1", "dk", "iPhone 17 Pro", "iOS", System.currentTimeMillis() - 86_400_000L, null, System.currentTimeMillis() - 3_600_000L),
            LinkedDevice("p2", "dk", "Pixel 9", "Android", System.currentTimeMillis() - 86_400_000L, null, null),
        ),
        pending = emptyList(),
        sessions = emptyList(),
        lastAlert = null,
        legacyPurged = false,
    )

    private fun slots(st: ShellState): SettingsSlots {
        val priv = ByteArray(32) { 7 }
        val pub = com.oshi.messenger.network.v2.OSHICryptoV2.x25519PubFromPriv(priv)
        val host = PhotoDisguiseHost(priv, pub) { st.contacts }
        return SettingsSlots(
            contacts = {
                ContactsSettingsContent(st, {}, { _, _ -> }, { _, _ -> }, { _, _ -> false }, {})
            },
            linkedDevices = { LinkedDevicesContent(snapshot, {}, { _, _ -> }, {}, {}) },
            disguise = { PrivacyDisguisePane(host, st.contacts, coverOn = true, onCoverChange = {}) },
            deleteAccount = {
                DeleteAccountPane(object : DeleteAccountHost {
                    override fun delete() = Result.failure<com.oshi.desktop.net.AccountDeletionReceipt>(IllegalStateException("demo"))
                })
            },
            recoveryKey = { "demo-recovery-key" },
            version = "1.5.0",
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val base = File(args.firstOrNull() ?: "build/screens-settings")
        val langs = (System.getenv("OSHI_RENDER_LANGS") ?: "en,fr,ar").split(',')
        val themes = (System.getenv("OSHI_RENDER_THEMES") ?: "light,dark,oled").split(',')
        for (lang in langs) for (theme in themes) {
            OshiTheme.appearance = RenderTheme.appearance(theme)
            DisplaySettings.preview(lang)
            val out = File(base, "$lang-$theme").apply { mkdirs() }
            val st = state()
            // __GLASS_ALL_THEMES__ OSHI_RENDER_SECTIONS=appearance,privacy renders only those pages.
            val sections = System.getenv("OSHI_RENDER_SECTIONS")?.split(',')?.map { it.trim().uppercase() }?.toSet()
            SettingsSection.entries.forEachIndexed { i, section ->
                if (sections != null && section.name !in sections) return@forEachIndexed
                SettingsNavigation.section = section
                render(File(out, "%02d-settings-%s.png".format(i + 1, section.name.lowercase())), W, H, frames = 3) {
                    SettingsPane(st, slots(st), SettingsActions())
                }
            }
            if (sections != null) continue
            SettingsNavigation.section = SettingsSection.CONTACTS
            render(File(out, "20-settings-contacts-empty.png"), W, H, frames = 3) {
                val empty = state(withContacts = false)
                SettingsPane(empty, slots(empty), SettingsActions())
            }
            render(File(out, "30-first-run.png"), 960, 640, frames = 3) {
                FirstRunScreen(false, "", null, {}, {}, {}, {}, {}, {})
            }
            render(File(out, "31-first-run-import.png"), 960, 640, frames = 3) {
                FirstRunScreen(true, "", null, {}, {}, {}, {}, {}, {})
            }
            render(File(out, "32-first-run-narrow.png"), 600, 720, frames = 3) {
                FirstRunScreen(false, "", null, {}, {}, {}, {}, {}, {})
            }
            if (theme == themes.first()) {
                // The supported window widths, at 1 px per dp so the numbers are real pixels.
                SettingsNavigation.section = SettingsSection.APPEARANCE
                render(File(out, "40-width-1024.png"), 1024, 768, scale = 1f, frames = 3) {
                    SettingsPane(st, slots(st), SettingsActions())
                }
                render(File(out, "41-width-2560.png"), 2560, 1200, scale = 1f, frames = 3) {
                    SettingsPane(st, slots(st), SettingsActions())
                }
                // Every page at full height, so nothing below the fold goes unseen.
                SettingsSection.entries.forEachIndexed { i, section ->
                    SettingsNavigation.section = section
                    render(File(out, "%02d-tall-%s.png".format(50 + i, section.name.lowercase())), W, 2400, scale = 1f, frames = 3) {
                        SettingsPane(st, slots(st), SettingsActions())
                    }
                }
            }
        }
        DisplaySettings.preview(null)
        println("settings screens → ${base.absolutePath}")
    }

    private fun render(target: File, wDp: Int, hDp: Int, scale: Float = SCALE, frames: Int = 1, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = (wDp * scale).toInt(),
            height = (hDp * scale).toInt(),
            density = Density(scale),
        ) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        try {
            for (f in 1 until frames) { scene.render(f * 50_000_000L); Thread.sleep(60) }
            val image = scene.render(frames * 50_000_000L)
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed: $target")
            target.writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }
}
