package com.oshi.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * __DESKTOP_ONBOARDING_BOTS_2026_11_24__ Contact sheets for the first-run screen, the `@`
 * (tickers + mentions) rendering and the bot command surfaces. A tool, not an assertion:
 *
 *     OSHI_RENDER_ONBOARDING=1 ./gradlew test --tests '*OnboardingBotsScreens*'
 *
 * writes PNGs to `build/screens-onboarding-bots/` (or `$OSHI_ONBOARDING_OUT`).
 */
class OnboardingBotsScreens {

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_ONBOARDING") == "1")
        val base = File(System.getenv("OSHI_ONBOARDING_OUT") ?: "build/screens-onboarding-bots").apply { mkdirs() }
        val themes = (System.getenv("OSHI_RENDER_THEMES") ?: "light,dark,oled").split(',')
        val langs = (System.getenv("OSHI_RENDER_LANGS") ?: "en").split(',')
        for (lang in langs) for (theme in themes) {
            OshiTheme.appearance = when (theme) {
                "dark" -> Appearance(ThemeMode.DARK, oledBlack = false, glass = false)
                "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
                else -> Appearance(ThemeMode.LIGHT, oledBlack = false, glass = false)
            }
            DisplaySettings.preview(lang)
            val tag = "$lang-$theme"
            render(File(base, "first-run-$tag.png"), 1040, 680) {
                FirstRunScreen(false, "", null, {}, {}, {}, {}, {}, {})
            }
            render(File(base, "first-run-import-$tag.png"), 1040, 680) {
                FirstRunScreen(true, "AAAA", "Invalid recovery key. Must be a valid base64-encoded 64-byte key.", {}, {}, {}, {}, {}, {})
            }
            render(File(base, "first-run-narrow-$tag.png"), 560, 820) {
                FirstRunScreen(false, "", null, {}, {}, {}, {}, {}, {})
            }
            OnboardingBotsFixtures.extra(this, base, tag)
        }
    }

    internal fun render(target: File, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
        val scale = 2f
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until 12) { scene.render(f * 50_000_000L); Thread.sleep(30) }
            val image = scene.render(12 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("[onboarding-bots] wrote ${target.absolutePath}")
        } finally {
            scene.close()
        }
    }
}

/** The `@` (tickers + mentions) and `/` (bot commands) surfaces, drawn in isolation. */
internal object OnboardingBotsFixtures {
    private val members = listOf(
        com.oshi.desktop.group.MentionWire.Mention("Q2hsb2UtZGVtby1rZXk=", "Chloé"),
        com.oshi.desktop.group.MentionWire.Mention("Q2hhcmxpZS1kZW1vLWtleQ==", "Charlie"),
        com.oshi.desktop.group.MentionWire.Mention("Q2hlbi1kZW1vLWtleQ==", "Chen Wei"),
    )

    fun extra(screens: OnboardingBotsScreens, base: File, tag: String) {
        screens.render(File(base, "at-mentions-tickers-$tag.png"), 640, 560) { AtSheet() }
        BotFixtures.extra(screens, base, tag)
    }

    @Composable
    private fun AtSheet() {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            // An incoming group bubble: a person mention, a stock and a crypto ticker, bold markup.
            Box(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .background(OshiTheme.bubbleIncoming)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                androidx.compose.foundation.layout.Column {
                    com.oshi.desktop.ui.components.MentionAwareText(
                        "@Chloé look: @AAPL beat, @NASDAQ:TSLA *flat*, and @btc again",
                        listOf(members[0]),
                        style = OshiTheme.typography.bodyLarge,
                        color = OshiTheme.bubbleIncomingText,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Normal,
                        tint = OshiTheme.brand,
                    )
                }
            }
            // The composer while "@Ch" is being typed in a group, second row selected.
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            com.oshi.desktop.ui.components.MentionPicker(members, {}, selected = 1)
            com.oshi.desktop.ui.components.DraftField(
                draft = "ping @Ch",
                busy = false,
                onDraft = {},
                onEnter = {},
                modifier = Modifier.fillMaxWidth(),
                highlight = com.oshi.desktop.ui.components.MentionHighlight(emptyList(), OshiTheme.brand),
            )
            com.oshi.desktop.ui.components.DraftField(
                draft = "watch @AAPL and @gold today",
                busy = false,
                onDraft = {},
                onEnter = {},
                modifier = Modifier.fillMaxWidth(),
                highlight = com.oshi.desktop.ui.components.MentionHighlight(emptyList(), OshiTheme.brand),
            )
        }
    }
}

/** Bot surfaces: the `/` menu, the bot channel's Menu, Settings → Bots (list and editor). */
internal object BotFixtures {
    private val bot = com.oshi.desktop.bot.OwnedBot(
        id = "demo-bot", token = "0f3a9c27" + "0".repeat(52) + "be41",
        name = "Standup Bot", username = "standup_bot", emoji = "🗓",
        description = "Collects the team's daily standup and posts a summary at 10:00.",
        commands = listOf(
            com.oshi.desktop.bot.BotCommand("standup", "Post today's update"),
            com.oshi.desktop.bot.BotCommand("summary", "Show today's summary"),
            com.oshi.desktop.bot.BotCommand("skip", "Skip me today"),
            com.oshi.desktop.bot.BotCommand("help", "What I can do"),
        ),
        assignedGroups = listOf("g-demo-1"), registered = true, commandsPublished = true,
    )
    private val groups = listOf("g-demo-1" to "Team (demo)", "g-demo-2" to "Family (demo)")
    private val extra = com.oshi.desktop.msg.SlashCommands.forBots(bot.commands.map { bot to it })

    fun extra(screens: OnboardingBotsScreens, base: File, tag: String) {
        screens.render(File(base, "bot-slash-menu-$tag.png"), 640, 520) { SlashSheet("/s", 1) }
        screens.render(File(base, "bot-slash-menu-all-$tag.png"), 640, 520) { SlashSheet("/", 0) }
        screens.render(File(base, "bot-channel-menu-$tag.png"), 640, 420) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.oshi.desktop.ui.components.LocalBotCommands provides { _ -> extra },
            ) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    BotChannelPreview()
                }
            }
        }
        screens.render(File(base, "bot-settings-list-$tag.png"), 820, 640) {
            com.oshi.desktop.ui.components.SettingsPageFrame {
                com.oshi.desktop.ui.components.SettingsPageHeader(com.oshi.desktop.ui.components.SettingsIcon.BOT,
                    com.oshi.desktop.i18n.t("bot.management_title"), com.oshi.desktop.i18n.t("bot.empty_desc"))
                com.oshi.desktop.ui.components.BotsPage(listOf(bot, bot.copy(id = "b2", name = "Echo Bot", username = "", emoji = "🤖",
                    description = "Echoes back messages containing a keyword", commands = listOf(com.oshi.desktop.bot.BotCommand("echo", "")),
                    commandsPublished = false)), groups, com.oshi.desktop.ui.components.BotsActions())
            }
        }
        screens.render(File(base, "bot-settings-empty-$tag.png"), 820, 640) {
            com.oshi.desktop.ui.components.SettingsPageFrame {
                com.oshi.desktop.ui.components.BotsPage(emptyList(), groups, com.oshi.desktop.ui.components.BotsActions())
            }
        }
        screens.render(File(base, "bot-settings-editor-$tag.png"), 820, 1500) {
            com.oshi.desktop.ui.components.SettingsPageFrame {
                com.oshi.desktop.ui.components.BotsPage(listOf(bot), groups, com.oshi.desktop.ui.components.BotsActions(),
                    initiallyEditing = bot.copy(commands = bot.commands + com.oshi.desktop.bot.BotCommand("standup", "dup")))
            }
        }
    }

    @Composable
    private fun BotChannelPreview() {
        // What a bot channel's footer shows: the read-only notice is drawn by ThreadPane; this is its menu.
        com.oshi.desktop.ui.components.BotThreadMenuPreview("bot!g-demo-1")
    }

    @Composable
    private fun SlashSheet(draft: String, selected: Int) {
        val rows = com.oshi.desktop.msg.SlashCommands.matches(draft, extra)
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(16.dp)) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            com.oshi.desktop.ui.components.CompletionPopup(
                rows.map { c ->
                    com.oshi.desktop.ui.components.CompletionRow(c.command, c.description, c.source,
                        com.oshi.desktop.ui.components.CompletionLeading.Command(c.emoji ?: "/"))
                },
                selected, {},
            )
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                com.oshi.desktop.ui.components.BotMenuButton(open = draft == "/", enabled = true) {}
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                com.oshi.desktop.ui.components.DraftField(draft = draft, busy = false, onDraft = {}, onEnter = {}, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
