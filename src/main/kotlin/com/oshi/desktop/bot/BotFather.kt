package com.oshi.desktop.bot

/**
 * __BOTS_PHASE1_2026_11_24__ The in-app "BotFather": Telegram's guided bot creation, as six
 * plain-language steps (owner re-scope ADD-1). Pure state + rules here; the screens are in
 * `ui/components/BotFatherUi.kt`.
 *
 *   1 Name → 2 @username (live availability, suggestions if taken) → 3 What it does (emoji,
 *   about, description) → 4 Commands (one-tap /start /help + a template per kind) →
 *   5 Token (copy, keep-it-secret, curl + Python) → 6 Add it to a group or channel I administer.
 */
object BotFather {

    enum class Step { NAME, USERNAME, PROFILE, COMMANDS, TOKEN, ADD }

    /** Live state of the username field, as the screen shows it. */
    sealed interface Availability {
        data object Unchecked : Availability
        data object Checking : Availability
        /** `usernameAvailable` said yes; setMyProfile's 409 remains the final word (a race). */
        data object LooksFree : Availability
        data class Taken(val suggestions: List<String>) : Availability
        data object Mine : Availability
        data class Invalid(val problem: BotUsername.Problem) : Availability
        data object Unknown : Availability
    }

    /** The kinds of bot a person picks from, each with ready commands (step 4). */
    enum class Template(val emoji: String, val commands: List<BotCommand>) {
        POLL("🗳️", listOf(BotCommand("poll", "Start a vote: /poll <question>"), BotCommand("results", "Show the current results"))),
        WELCOME("👋", listOf(BotCommand("rules", "Show the group rules"), BotCommand("welcome", "Say hello to new members"))),
        REMINDERS("⏰", listOf(BotCommand("remind", "Remind the group: /remind <when> <what>"), BotCommand("reminders", "List upcoming reminders"))),
        FEED("📰", listOf(BotCommand("subscribe", "Follow a feed: /subscribe <url>"), BotCommand("latest", "Post the latest items"))),
        WEBHOOK("🔗", listOf(BotCommand("status", "Check the connected service"))),
    }

    /** Step 4's one-tap suggestions. */
    val SUGGESTED: List<BotCommand> = listOf(BotCommand("start", "Start the bot"), BotCommand("help", "What this bot can do"))

    data class Draft(
        val step: Step = Step.NAME,
        val name: String = "",
        val username: String = "",
        val usernameEdited: Boolean = false,
        val availability: Availability = Availability.Unchecked,
        val emoji: String = "🤖",
        val about: String = "",
        val description: String = "",
        val template: Template? = null,
        val commands: List<BotCommand> = emptyList(),
        /** The bot once saved (token minted, profile on the server). */
        val bot: OwnedBot? = null,
    ) {
        /** The username shown: what was typed, or one proposed from the name until the person types. */
        val shownUsername: String get() = if (usernameEdited) username else BotUsername.fromName(name)

        fun canLeave(s: Step = step): Boolean = when (s) {
            Step.NAME -> name.isNotBlank() && name.trim().length <= OwnedBot.MAX_NAME
            Step.USERNAME -> BotUsername.isValid(shownUsername) && availability !is Availability.Taken &&
                availability !is Availability.Checking && availability !is Availability.Invalid
            Step.PROFILE -> about.length <= OwnedBot.MAX_ABOUT && description.length <= OwnedBot.MAX_DESCRIPTION && emoji.isNotBlank()
            Step.COMMANDS -> BotCommands.problemOf(commands) == BotCommands.Problem.None
            Step.TOKEN -> bot != null
            Step.ADD -> true
        }

        fun next(): Draft = if (!canLeave()) this else copy(step = Step.entries.getOrElse(step.ordinal + 1) { step })
        fun back(): Draft = copy(step = Step.entries.getOrElse(step.ordinal - 1) { step })

        /** Add a suggested command if absent, remove it if present. */
        fun toggle(c: BotCommand): Draft =
            if (commands.any { it.name == c.name }) copy(commands = commands.filterNot { it.name == c.name })
            else copy(commands = (commands + c).take(BotCommand.MAX_COMMANDS))

        /** Pick a template: its emoji (unless one was chosen) and its commands merged after /start, /help. */
        fun withTemplate(t: Template): Draft {
            val merged = LinkedHashMap<String, BotCommand>()
            commands.forEach { merged[it.name] = it }
            t.commands.forEach { merged.putIfAbsent(it.name, it) }
            return copy(template = t, emoji = if (emoji == "🤖") t.emoji else emoji, commands = merged.values.toList())
        }

        /** The record to save, keeping the token of an earlier save. */
        fun toBot(): OwnedBot = (bot ?: OwnedBot(name = name.trim())).copy(
            name = name.trim(),
            username = BotUsername.normalize(shownUsername),
            emoji = emoji.trim().ifEmpty { "🤖" },
            about = about.trim(),
            description = description.trim(),
            commands = commands,
            isPublic = true,
        )
    }

    /** What the live check concludes from `usernameAvailable` (S1), asked with the bot's own token when it exists. */
    fun availabilityOf(username: String, check: BotPlatform.UsernameCheck): Availability {
        val p = BotUsername.problemOf(username)
        if (p != BotUsername.Problem.None) return Availability.Invalid(p)
        return when (check) {
            BotPlatform.UsernameCheck.Available -> Availability.LooksFree
            BotPlatform.UsernameCheck.Taken -> Availability.Taken(BotUsername.suggestions(username))
            BotPlatform.UsernameCheck.Invalid -> Availability.Invalid(BotUsername.Problem.BadCharacters)
            is BotPlatform.UsernameCheck.Failed -> Availability.Unknown
        }
    }

    /** The same from a public lookup (fallback; a private bot looks free here). */
    fun availabilityOf(username: String, lookup: BotPlatform.Lookup, ownBotIds: Set<String>): Availability {
        val p = BotUsername.problemOf(username)
        if (p != BotUsername.Problem.None) return Availability.Invalid(p)
        return when (lookup) {
            is BotPlatform.Lookup.Found -> if (lookup.bot.botId in ownBotIds) Availability.Mine
                else Availability.Taken(BotUsername.suggestions(username))
            BotPlatform.Lookup.NotFound -> Availability.LooksFree
            is BotPlatform.Lookup.Failed -> Availability.Unknown
        }
    }

    /** "How to connect": a ready-to-copy curl that posts to a group, with the real base URL. */
    fun curlSnippet(baseUrl: String, token: String, groupId: String = "GROUP_ID"): String =
        "curl -X POST $baseUrl/api/bot/send \\\n" +
            "  -H 'Content-Type: application/json' \\\n" +
            "  -d '{\"token\":\"$token\",\"groupId\":\"$groupId\",\"content\":\"Hello from my bot\"}'"

    /** The same in Python: read commands with getUpdates, answer in the group. Standard library only. */
    fun pythonSnippet(baseUrl: String, token: String): String = """
        |import json, time, urllib.request
        |
        |API = "$baseUrl/api/bot"
        |TOKEN = "$token"  # keep it secret
        |
        |def call(method, body=None):
        |    req = urllib.request.Request(f"{API}/{method}", data=json.dumps(body or {}).encode() if body is not None else None,
        |                                 headers={"Content-Type": "application/json"})
        |    with urllib.request.urlopen(req) as r:
        |        return json.load(r)
        |
        |offset = 0
        |while True:
        |    for u in call(f"getUpdates?token={TOKEN}&offset={offset}&timeout=30").get("result", []):
        |        offset = u["update_id"] + 1
        |        msg = u.get("message") or {}
        |        if msg.get("text", "").startswith("/help"):
        |            call("send", {"token": TOKEN, "groupId": msg["chat"]["id"], "content": "Hi! I can help."})
        |    time.sleep(1)
    """.trimMargin()

    /** Step 6 lists only the groups and channels where this account is an admin (and that still exist). */
    fun adminGroups(groups: List<com.oshi.desktop.group.GroupDefinition>, self: String): List<com.oshi.desktop.group.GroupDefinition> =
        groups.filter { it.isAdmin(self) && !it.isDissolved }
}
