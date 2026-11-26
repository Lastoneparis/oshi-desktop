package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oshi.desktop.bot.BotCommand
import com.oshi.desktop.bot.BotFather
import com.oshi.desktop.bot.BotPermissions
import com.oshi.desktop.bot.BotPlatform
import com.oshi.desktop.bot.BotPostExtra
import com.oshi.desktop.bot.OwnedBot
import com.oshi.desktop.bot.PublicBotProfile
import com.oshi.desktop.ui.components.BotGroupChoice
import com.oshi.desktop.ui.components.BotSheet
import com.oshi.desktop.ui.components.BotUiHost
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.MessageRow
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * __BOTS_PHASE1_2026_11_24__ Contact sheets for bots phase 1 (BotFather, profile sheet with "Add
 * to channel" + permissions + remote notice, `@` search, a bot post with its keyboard). A tool:
 *
 *     OSHI_RENDER_BOTS1=1 ./gradlew test --tests '*BotsPhase1Screens*'
 *
 * writes PNGs to `build/screens-bots-phase1/` (or `$OSHI_BOTS1_OUT`).
 */
class BotsPhase1Screens {

    private val weather = PublicBotProfile(
        botId = "w1", username = "weather_paris_bot", name = "Weather Paris", avatarEmoji = "🌦️",
        about = "Forecasts for Paris, on demand and every morning at 7.",
        description = "Type /now for the current weather or /week for the next 7 days. Buttons let you pick a district.",
        commands = listOf(BotCommand("now", "Current weather"), BotCommand("week", "The next 7 days"), BotCommand("alerts", "Storm and heat alerts")),
    )
    private val results = listOf(
        weather,
        PublicBotProfile("q1", "quiz_night_bot", "Quiz Night", avatarEmoji = "🧠", about = "A trivia game for your group"),
        PublicBotProfile("v1", "vote_bot", "Vote", avatarEmoji = "🗳️", about = "Polls with live results in the post"),
    )
    private val groups = listOf(
        BotGroupChoice("c1", "Paris News (demo)", isChannel = true),
        BotGroupChoice("c2", "Neighbourhood board (demo)", isChannel = true),
        BotGroupChoice("g1", "Team (demo)", isChannel = false),
    )
    private val host = object : BotUiHost {
        override fun lookup(username: String) = BotPlatform.Lookup.Found(weather)
        override fun search(query: String) = Result.success(results)
        override fun adminGroups() = groups
        override fun ownBotIds() = emptySet<String>()
        override fun add(bot: PublicBotProfile, groupId: String, permissions: BotPermissions) = Result.success(Unit)
        override fun copy(text: String) {}
    }

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_BOTS1") == "1")
        val base = File(System.getenv("OSHI_BOTS1_OUT") ?: "build/screens-bots-phase1").apply { mkdirs() }
        val r = OnboardingBotsScreens()
        val themes = (System.getenv("OSHI_RENDER_THEMES") ?: "light,oled").split(',')
        val langs = (System.getenv("OSHI_RENDER_LANGS") ?: "en,fr").split(',')
        for (lang in langs) for (theme in themes) {
            OshiTheme.appearance = when (theme) {
                "dark" -> Appearance(ThemeMode.DARK, oledBlack = false, glass = false)
                "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
                else -> Appearance(ThemeMode.LIGHT, oledBlack = false, glass = false)
            }
            DisplaySettings.preview(lang)
            val tag = "$lang-$theme"
            val wrap: @androidx.compose.runtime.Composable (@androidx.compose.runtime.Composable () -> Unit) -> Unit = { content ->
                CompositionLocalProvider(com.oshi.desktop.ui.components.LocalBotUiHost provides host) { content() }
            }

            // BotFather, step by step.
            val draft = BotFather.Draft(name = "Weather Paris")
            r.render(File(base, "botfather-1-name-$tag.png"), 760, 520) { wrap { Settings { Wizard(draft) } } }
            r.render(File(base, "botfather-2-username-taken-$tag.png"), 760, 560) {
                wrap { Settings { Wizard(draft.copy(step = BotFather.Step.USERNAME, username = "weather_bot", usernameEdited = true,
                    availability = BotFather.Availability.Taken(com.oshi.desktop.bot.BotUsername.suggestions("weather_bot"))), checkTo = null) } }
            }
            r.render(File(base, "botfather-3-profile-$tag.png"), 760, 720) {
                wrap { Settings { Wizard(draft.copy(step = BotFather.Step.PROFILE, emoji = "🌦️", about = weather.about, description = weather.description)) } }
            }
            val withCmds = draft.copy(step = BotFather.Step.COMMANDS).toggle(BotFather.SUGGESTED[0]).toggle(BotFather.SUGGESTED[1])
                .withTemplate(BotFather.Template.POLL)
            r.render(File(base, "botfather-4-commands-$tag.png"), 760, 760) { wrap { Settings { Wizard(withCmds) } } }
            val saved = withCmds.toBot().copy(token = "3f9a0c27d1e84b6fa0c2e9d14b7a5e61", username = "weather_paris_bot")
            r.render(File(base, "botfather-5-token-$tag.png"), 820, 1180) {
                wrap { Settings { Wizard(withCmds.copy(step = BotFather.Step.TOKEN, bot = saved)) } }
            }
            r.render(File(base, "botfather-6-add-$tag.png"), 760, 600) {
                wrap { Settings { Wizard(withCmds.copy(step = BotFather.Step.ADD, bot = saved)) } }
            }

            // The editor: username rule, about, public toggle, share link + QR, revoke.
            val mine = OwnedBot(id = "m", token = saved.token, name = "Weather Paris", username = "weather_paris_bot", emoji = "🌦️",
                about = weather.about, description = weather.description, commands = weather.commands, registered = true, isPublic = true)
            r.render(File(base, "bot-editor-share-$tag.png"), 820, 1900) {
                wrap { Settings { com.oshi.desktop.ui.components.BotsPage(listOf(mine), emptyList(), com.oshi.desktop.ui.components.BotsActions(), initiallyEditing = mine) } }
            }

            // Profile sheet from a link: as opened, then "Add to channel" with permissions + notice.
            r.render(File(base, "bot-profile-sheet-$tag.png"), 560, 560) {
                wrap { Sheet { com.oshi.desktop.ui.components.BotProfileContent(BotSheet.State.Shown(weather), host) } }
            }
            r.render(File(base, "bot-profile-add-channel-$tag.png"), 560, 900) {
                wrap { Sheet { com.oshi.desktop.ui.components.BotProfileContent(BotSheet.State.Shown(weather), host, initialTarget = true, initialGroup = "c1") } }
            }
            r.render(File(base, "bot-profile-not-found-$tag.png"), 520, 280) {
                wrap { Sheet { com.oshi.desktop.ui.components.BotProfileContent(BotSheet.State.NotFound("ghost_bot"), host) } }
            }

            // New chat: "@wea" lists public bots.
            r.render(File(base, "bot-search-new-chat-$tag.png"), 760, 760) {
                wrap {
                    CompositionLocalProvider(
                        com.oshi.desktop.ui.components.LocalNewPanePreset provides "@wea",
                        com.oshi.desktop.ui.components.LocalBotSearchPreset provides Result.success(results),
                    ) {
                        com.oshi.desktop.ui.components.NewPane(state = shell(), onStartConversation = {}, onCopy = {}, onPickQrImage = { null })
                    }
                }
            }

            // A bot post in a channel: BOT badge, inline keyboard (one button waiting), then edited in place.
            val post = BotPostExtra("p1", "c1", "v1", "Vote", """{"inline_keyboard":[[{"text":"🥐 Croissant · 12","callback_data":"v:c"},{"text":"🥖 Baguette · 9","callback_data":"v:b"}],[{"text":"Results on the web","url":"https://example.org/poll"}]]}""", edited = true)
            r.render(File(base, "bot-post-channel-$tag.png"), 560, 420) {
                CompositionLocalProvider(com.oshi.desktop.ui.components.LocalBotPostOf provides { id -> if (id == "p1") post else null }) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.oshi.desktop.ui.components.MessageBubbleRow(
                            MessageRow(id = "p1", fromMe = false, who = "Vote", body = "Breakfast for Saturday's meetup? Tap to vote — results update in this post.",
                                attachment = null, stamp = "09:12", status = null, failed = false, edited = true, deleted = false, reactions = ""),
                            false, true, WallpaperId.NONE, false, false, {},
                        )
                        com.oshi.desktop.ui.components.MessageBubbleRow(
                            MessageRow(id = "p2", fromMe = false, who = "Weather Paris", body = "Add me to your channel: https://oshi-messenger.com/bot/weather_paris_bot",
                                attachment = null, stamp = "09:14", status = null, failed = false, edited = false, deleted = false, reactions = ""),
                            false, true, WallpaperId.NONE, false, false, {},
                        )
                    }
                }
            }
        }
    }

    private fun shell() = com.oshi.desktop.ui.state.ShellState(
        selfAddress = "3q2+7wDemoKeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", selfAddressShort = "3q2+7w…", displayName = "Hugo",
        relayUrl = "https://oshi-messenger.com", gateOpen = true, gateAnswered = true, deliveryReceiptsEnabled = true,
        readReceiptsEnabled = true, syncNotice = null, destination = com.oshi.desktop.ui.state.Destination.NEW, contacts = emptyList(),
        scheduled = emptyList(), shareText = "Add me on OSHI: oshi://add?key=demo", pane = com.oshi.desktop.ui.state.Pane.CONVERSATION,
        conversations = emptyList(), selectedId = null, thread = null, draft = "", busy = false, notice = null,
    )

    @androidx.compose.runtime.Composable
    private fun Settings(content: @androidx.compose.runtime.Composable () -> Unit) {
        com.oshi.desktop.ui.components.SettingsPageFrame { content() }
    }

    @androidx.compose.runtime.Composable
    private fun Sheet(content: @androidx.compose.runtime.Composable () -> Unit) {
        Column(Modifier.fillMaxSize().padding(24.dp)) { content() }
    }

    @androidx.compose.runtime.Composable
    private fun Wizard(d: BotFather.Draft, checkTo: BotFather.Availability? = BotFather.Availability.LooksFree) {
        com.oshi.desktop.ui.components.BotFatherWizard(
            initial = d, adminGroups = groups, serverUrl = "https://oshi-messenger.com",
            actions = com.oshi.desktop.ui.components.BotFatherActions(checkUsername = { checkTo ?: d.availability }),
            onClose = {},
        )
    }
}
