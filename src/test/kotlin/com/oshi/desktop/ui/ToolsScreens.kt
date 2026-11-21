package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.mail.FakeMailRelay
import com.oshi.desktop.mail.MailClient
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.ui.components.LocalMailPreview
import com.oshi.desktop.ui.components.MailPreview
import com.oshi.desktop.ui.components.MailScreen
import com.oshi.desktop.ui.components.MailSection
import com.oshi.desktop.ui.state.MailModel
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.Executor

/**
 * __DESKTOP_TOOLS_2027__ The tool screens (Mail, Map, AI, Radio, calls, scheduled, peers,
 * linked devices, covert text, limits) rendered from the REAL composables with fabricated
 * state, in English, French and Arabic × light, dark and OLED. A TOOL, gated like
 * `ThreadChromeScreens`:
 *
 *     OSHI_RENDER_TOOLS=1 ./gradlew test --tests 'com.oshi.desktop.ui.ToolsScreens'
 *
 * Written to `$OSHI_TOOLS_OUT` (default `build/screens/tools`) as `<screen>-<lang>-<theme>.png`.
 * `OSHI_TOOLS_ONLY=mail,map` limits the screens; `OSHI_TOOLS_LANGS` / `OSHI_TOOLS_THEMES`
 * the matrix.
 */
class ToolsScreens {
    private val scale = 1.25f
    private fun addr(c: Char) = c.toString().repeat(43) + "="

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_TOOLS") == "1")
        val out = File(System.getenv("OSHI_TOOLS_OUT") ?: "build/screens/tools").apply { mkdirs() }
        val langs = (System.getenv("OSHI_TOOLS_LANGS") ?: "en,fr,ar").split(',')
        val themes = (System.getenv("OSHI_TOOLS_THEMES") ?: "light,dark,oled").split(',')
        val only = System.getenv("OSHI_TOOLS_ONLY")?.split(',')?.toSet()
        fun want(name: String) = only == null || only.any { name.startsWith(it) }
        val savedTag = Strings.activeTag
        try {
            for (lang in langs) for (theme in themes) {
                Strings.activeTag = lang
                OshiTheme.appearance = when (theme) {
                    "dark" -> Appearance(ThemeMode.DARK, oledBlack = false)
                    "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
                    else -> Appearance.DEFAULT
                }
                fun shot(name: String, w: Int = 1280, h: Int = 800, content: @Composable () -> Unit) {
                    if (!want(name)) return
                    render(File(out, "$name-$lang-$theme.png"), w, h, content)
                }
                renderMail(::shot)
                ToolsFixtures.all(::shot)
            }
        } finally {
            Strings.activeTag = savedTag
            OshiTheme.appearance = Appearance.DEFAULT
        }
    }

    private fun renderMail(shot: (String, Int, Int, @Composable () -> Unit) -> Unit) {
        FakeMailRelay().use { relay ->
            val home = Files.createTempDirectory("oshi-mail-tools").toFile()
            try {
                val vault = KeyVault.open(home.resolve("vault"), InMemorySecretStore(), null)
                val client = MailClient(DesktopIdentity.generate(), vault, relay.baseUrl)
                val mail = MailModel(client, Executor { it.run() })
                mail.refresh()
                shot("mail-onboarding", 1280, 800) { MailScreen(mail, mail.state, pickFile = { null }) }
                mail.claim("camille") { require(it == null) }
                shot("mail-empty", 1280, 800) { MailScreen(mail, mail.state, pickFile = { null }) }
                fun put(id: String, from: String, subject: String, date: String, snippet: String, body: String, seen: Boolean, attach: Boolean = false, images: Boolean = false) {
                    val env = org.json.JSONObject().put("from", from).put("to", "camille@${MailClient.MAIL_DOMAIN}")
                        .put("subject", subject).put("date", date).put("snippet", snippet)
                        .put("hasAttachments", attach).put("attachmentCount", if (attach) 1 else 0).put("hasImages", images).toString()
                    val mime = "From: $from\r\nTo: camille@${MailClient.MAIL_DOMAIN}\r\nSubject: $subject\r\nDate: Wed, 18 Nov 2026 09:12:00 +0100\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n$body"
                    relay.addMessage(FakeMailRelay.StoredMessage(
                        id = id, folder = "inbox", seen = seen,
                        sealedEnvelope = Base64.getEncoder().encodeToString(client.sealForSelf(env.toByteArray())),
                        sealedBody = client.sealForSelf(mime.toByteArray()),
                    ))
                }
                put("m1", "Inès Haddad <ines@riseup.net>", "Thursday distribution — final list", "2026-11-19T08:42:00Z",
                    "Here is the final list for Thursday. We start at 7 at the north gate; bring the boxes from the van.",
                    "Hi Camille,\n\nHere is the final list for Thursday. We start at 7 at the north gate; bring the boxes from the van.\n\nThe map is at https://oshi-messenger.com/lora/ if anyone needs the radio page.\n\nThanks!\nInès", seen = false, attach = true)
                put("m2", "Bob Martin <bob@proton.me>", "Re: radio range test", "2026-11-18T17:05:00Z",
                    "Two nodes, 3.4 km across the river, LongFast. Messages arrived in under a minute.", "Two nodes, 3.4 km across the river.", seen = false, images = true)
                put("m3", "OSHI <hello@oshi-messenger.com>", "Your mailbox is ready", "2026-11-12T10:00:00Z",
                    "Mail arriving here is encrypted with a key that only your devices hold.", "Welcome.", seen = true)
                put("m4", "Yusuf <yusuf@posteo.de>", "Photos from Saturday", "2026-10-02T12:00:00Z",
                    "A few pictures from the market, as promised.", "A few pictures.", seen = true, attach = true, images = true)
                mail.refresh()
                shot("mail-inbox", 1280, 800) { MailScreen(mail, mail.state, pickFile = { null }) }
                mail.open("m1")
                shot("mail-reader", 1440, 900) { MailScreen(mail, mail.state, pickFile = { null }) }
                shot("mail-narrow", 1024, 720) { MailScreen(mail, mail.state, pickFile = { null }) }
                shot("mail-wide", 2560, 1300) { MailScreen(mail, mail.state, pickFile = { null }) }
                mail.closeOpen()
                mail.newDraft()
                mail.editDraft(mail.state.editingDraft!!.copy(to = "ines@riseup.net", subject = "Thursday", body = "Perfect — see you at the north gate."))
                shot("mail-compose", 1280, 800) { MailScreen(mail, mail.state, pickFile = { null }) }
                mail.clearEditingDraft()
                shot("mail-account", 1280, 800) {
                    CompositionLocalProvider(LocalMailPreview provides MailPreview(section = MailSection.ACCOUNT)) { MailScreen(mail, mail.state, pickFile = { null }) }
                }
                shot("mail-drive", 1280, 800) {
                    CompositionLocalProvider(LocalMailPreview provides MailPreview(section = MailSection.DRIVE)) { MailScreen(mail, mail.state, pickFile = { null }) }
                }
            } finally {
                home.deleteRecursively()
            }
        }
    }

    private fun render(target: File, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until 14) { scene.render(f * 50_000_000L); Thread.sleep(40) }
            val image = scene.render(14 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
