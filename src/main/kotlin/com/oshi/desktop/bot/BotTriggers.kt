package com.oshi.desktop.bot

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The bot COMMAND grammar and template renderer — the other half of PARITY.md row 0.26.
 *
 * This is the part of the row that is not a wire format: what makes a bot fire, and what it
 * says when it does. It is byte-testable in a way the transport is not, because Android
 * ships assertions for it — `BotTemplateParityTest.kt` — and those are used as vectors here
 * rather than anything this file produces.
 *
 * ============================================================ THE TRIGGER TYPES, AND WHY
 * TWO OF THEM CAN NEVER FIRE ACROSS PLATFORMS
 *
 * Six types. The raw strings are what a synced or exported bot definition carries, and two
 * of them **do not match between the platforms**:
 *
 * | iOS raw (`BotManager.swift:71-78`) | Android literal (`BotManager.kt:250-257`) | agree? |
 * |---|---|---|
 * | `keyword` | `keyword` | yes |
 * | `command` | `command` | yes |
 * | `regex` | `regex` | yes |
 * | `memberJoin` | `member_join` | **NO** |
 * | `memberLeave` | `member_leave` | **NO** |
 * | `schedule` | `schedule` | yes |
 *
 * A `memberJoin` trigger authored on an iPhone is an unknown type on Android and vice
 * versa, so it silently never fires. Both spellings are accepted by [TriggerType.parse] —
 * refusing either would reproduce the bug rather than survive it — and [TriggerType.wire]
 * emits the **iOS** spelling, because iOS is the platform where the trigger engine is
 * actually live (see REACHABILITY below).
 *
 * ============================================================ AND THE MATCHING RULES
 * DISAGREE TOO
 *
 * Same trigger, same message, different answer, on four of the six:
 *
 * | type | iOS | Android | taken |
 * |---|---|---|---|
 * | `keyword` | `localizedCaseInsensitiveContains` (`swift:737`) | `lowercase().contains(lowercase())` (`kt:251`) | case-INsensitive (they agree) |
 * | `command` | prepends `/` if missing, then case-INsensitive `hasPrefix` on a whitespace-trimmed message (`swift:739-740`) | `content.trim().startsWith(condition)` — no `/` normalisation, **case-sensitive** (`kt:252`) | **iOS** |
 * | `regex` | `.caseInsensitive` (`swift:742`) | `Regex(condition)` — **case-sensitive** (`kt:253`) | **iOS** |
 * | `memberJoin`/`memberLeave` | `content.contains("[MEMBER_JOIN]")` — substring (`swift:748-751`) | `content == "[MEMBER_JOIN]"` — **exact equality** (`kt:254-255`) | **Android** |
 * | `schedule` | always false, timer-handled (`swift:752`) | same | false |
 *
 * iOS wins the first three because it is the live implementation and because a
 * case-sensitive `/HELP` that ignores `/help` is a bug in anyone's reading. **Android wins
 * the member events**, and that one is the stricter side in the sense PARITY.md working
 * rule 2 means: `contains` fires on any message that merely mentions `[MEMBER_JOIN]`
 * anywhere in its text, so on iOS a user can trigger another member's welcome bot by
 * typing the sentinel into an ordinary message. Exact equality cannot be spoofed that way.
 * This is the one place the two rules differ in SECURITY rather than in convenience, and
 * that is what decides it.
 *
 * ============================================================ THE SCHEDULE UNIT IS A
 * FACTOR OF 1000
 *
 * `schedule`'s condition is a number in a string, and the two platforms read it in
 * different units:
 *
 *  - iOS: `TimeInterval(trigger.condition)` — **seconds**, floored at 60 by
 *    `max(interval, 60)` (`BotManager.swift:778-779`).
 *  - Android: `toLongOrNull()` — **milliseconds**, or one of the named literals
 *    `every_15min` / `every_30min` / `every_hour` / `every_6h` / `every_day`, defaulting to
 *    `3_600_000` (`BotManager.kt:515-524`).
 *
 * The shipped "reminder" template's condition is the literal `"86400"`
 * (`BotManager.swift:1180`). That is **24 hours on iOS and 86.4 seconds on Android** — the
 * same stored bot, a thousandfold difference in how often it posts. [parseScheduleSeconds]
 * takes iOS's reading (seconds, floor 60), which is the one that matches the template's
 * evident intent, and [SCHEDULE_ALIASES] is kept so an Android-authored condition still
 * resolves rather than being read as a millisecond count.
 *
 * ============================================================ THERE IS NO ARGUMENT PARSER
 *
 * Worth stating because its absence is a design decision someone will otherwise try to
 * "fix": there is no tokenizer, no argv split, no flag handling. `{{input}}` is the **whole
 * raw message text**, command word included (`swift:759`). The `echo` template is literally
 * `"{{input}}"` (`swift:1207`), so `/echo hello` replies `/echo hello`, not `hello`. Adding
 * a splitter here would make a desktop bot answer differently from the same bot on a phone.
 *
 * ============================================================ REACHABILITY, BECAUSE IT
 * DECIDES WHAT THIS FILE IS FOR
 *
 * The trigger engine is **live on iOS and dead on Android**:
 *
 *  - iOS runs it on both the outbound and the inbound group path, with no flag guarding
 *    either (`GroupMessaging.swift:2509` and `:3357`).
 *  - Android's `BotManager.processMessage(content:groupId:senderKey:)` has **zero
 *    callers** in the whole tree — only its declaration at `BotManager.kt:232`. So no
 *    Android trigger has ever fired, which transitively means `fireWebhook`,
 *    `renderTemplate` in production, and the entire keyword/command/regex engine are dead
 *    there. `BotTemplateParityTest` passes because it calls `renderTemplate` directly.
 *
 * So this file is the DESKTOP's engine for locally-run bots, and it is written to agree
 * with the platform that runs one. It deliberately does not include a webhook client: iOS's
 * `sendWebhookEvent` has zero callers (`BotManager.swift:806-825`), so a `webhook`-type bot
 * on an iPhone registers with the server and **never calls its webhook**; Android's does
 * fire but with different JSON keys, no `Authorization` header, and a different reply
 * grammar. There is no agreed protocol to port — implementing either would be inventing a
 * third dialect, which is the error PARITY.md row 0.17-a names. And iOS's version sends the
 * **full bot token in an `Authorization: OSHI-Bot/<token>` header to an arbitrary
 * third-party URL** (`swift:811`), which is a bearer credential handed to whoever the bot
 * author typed in.
 */
object BotTriggers {

    /** `[MEMBER_JOIN]` / `[MEMBER_LEAVE]` — literal sentinels, no emoji, unlike
     *  [com.oshi.desktop.msg.ControlPrefix]'s catalogue. */
    const val MEMBER_JOIN_SENTINEL = "[MEMBER_JOIN]"
    const val MEMBER_LEAVE_SENTINEL = "[MEMBER_LEAVE]"

    /** iOS's floor on a schedule interval: `max(interval, 60)` (`BotManager.swift:779`). */
    const val MIN_SCHEDULE_SECONDS: Long = 60

    /** Android's named intervals (`BotManager.kt:515-524`), in SECONDS. */
    val SCHEDULE_ALIASES: Map<String, Long> = mapOf(
        "every_15min" to 15L * 60,
        "every_30min" to 30L * 60,
        "every_hour" to 60L * 60,
        "every_6h" to 6L * 60 * 60,
        "every_day" to 24L * 60 * 60,
    )

    enum class TriggerType(val wire: String, val androidWire: String) {
        KEYWORD("keyword", "keyword"),
        COMMAND("command", "command"),
        REGEX("regex", "regex"),
        MEMBER_JOIN("memberJoin", "member_join"),
        MEMBER_LEAVE("memberLeave", "member_leave"),
        SCHEDULE("schedule", "schedule");

        companion object {
            /**
             * Accept EITHER platform's spelling. Exact match only — no case folding, because
             * neither platform folds and `MemberJoin` is not a value either one writes.
             */
            fun parse(raw: String): TriggerType? =
                entries.firstOrNull { it.wire == raw || it.androidWire == raw }
        }
    }

    data class Trigger(
        val type: TriggerType,
        val condition: String,
        val responseTemplate: String,
        val active: Boolean = true,
    )

    /**
     * Does [trigger] fire on [content]?
     *
     * Per-type rules are the table in the class doc. An inactive trigger never fires, and a
     * `regex` whose pattern does not compile returns false rather than throwing — iOS's
     * `try?` swallows it the same way (`BotManager.swift:742-746`), and a bot whose author
     * typed a bad pattern should be quiet, not fatal.
     */
    fun shouldFire(trigger: Trigger, content: String): Boolean {
        if (!trigger.active) return false
        return when (trigger.type) {
            TriggerType.KEYWORD ->
                content.lowercase().contains(trigger.condition.lowercase())

            TriggerType.COMMAND -> {
                val cmd = if (trigger.condition.startsWith("/")) trigger.condition else "/${trigger.condition}"
                content.trim().lowercase().startsWith(cmd.lowercase())
            }

            TriggerType.REGEX ->
                runCatching { Regex(trigger.condition, RegexOption.IGNORE_CASE).containsMatchIn(content) }
                    .getOrDefault(false)

            // Android's exact-equality rule, not iOS's `contains`. See the class doc: iOS's
            // lets any member fire a welcome bot by typing the sentinel into a message.
            TriggerType.MEMBER_JOIN -> content == MEMBER_JOIN_SENTINEL
            TriggerType.MEMBER_LEAVE -> content == MEMBER_LEAVE_SENTINEL

            // Timer-driven on both platforms; never matched against message text.
            TriggerType.SCHEDULE -> false
        }
    }

    /**
     * `schedule`'s interval in SECONDS — iOS's unit — or null when the condition is neither
     * a number nor a known alias.
     *
     * Floored at [MIN_SCHEDULE_SECONDS], matching `max(interval, 60)`. A zero or negative
     * value is null rather than 60: iOS's `interval > 0` guard skips such a trigger
     * entirely (`swift:778`), and turning "don't schedule this" into "schedule it every
     * minute" would be worse than doing nothing.
     */
    fun parseScheduleSeconds(condition: String): Long? {
        val key = condition.trim().lowercase()
        SCHEDULE_ALIASES[key]?.let { return it }
        val n = condition.trim().toDoubleOrNull() ?: return null
        if (!n.isFinite() || n <= 0) return null
        return maxOf(n.toLong(), MIN_SCHEDULE_SECONDS)
    }

    /**
     * iOS's `looksLikeACommand` (`BotManager.swift:627-632`), used there only to decide
     * whether to show a once-per-group "there is no bot here" hint.
     *
     * Four conditions, all of them: trimmed of whitespace AND newlines, starts with `/`,
     * longer than one character, and the SECOND character is a letter. The last one is what
     * stops `//`, `/1` and a bare `/` from being read as commands.
     */
    fun looksLikeACommand(content: String): Boolean {
        val t = content.trim()
        if (!t.startsWith("/")) return false
        if (t.length <= 1) return false
        return t[1].isLetter()
    }

    /**
     * Substitute the template variables.
     *
     * Order and set are Android's `BotManager.renderTemplate`
     * (`BotManager.kt:57-77`), which is a superset of iOS's four
     * (`BotManager.generateResponse`, `swift:756-763`) — iOS has `{{input}}`, `{{bot_name}}`,
     * `{{date}}`, `{{timestamp}}`; Android adds the three legacy aliases `{{message}}`,
     * `{{sender}}`, `{{member}}` and its own header records why: before that pass the two
     * platforms had **zero overlap**, so a template authored on one rendered its own
     * placeholders as literal text on the other. Taking the superset means a template from
     * either platform renders; taking iOS's four alone would leave `{{sender}}` printed
     * verbatim in a reply.
     *
     * `{{timestamp}}` is **Unix SECONDS** on both (`swift:762` `Int(timeIntervalSince1970)`,
     * `kt:72` `nowMillis / 1000`) — and the integer division stays here rather than becoming
     * a [com.oshi.desktop.msg.WireClock] call, because this is a TEMPLATE VARIABLE rendered
     * into display text, not a field on any wire. It has to print as an integer with no
     * fractional part to match `Int(...)` and Kotlin `Long` division; a converter returning
     * a Double would change the rendered string. WireClock owns the epochs that reach a
     * peer, and this value never does.
     *
     * `{{date}}` is the one variable that cannot be made to agree: iOS uses
     * `DateFormatter.localizedString(dateStyle: .medium, timeStyle: .short)`, Android
     * `SimpleDateFormat("d MMM yyyy, HH:mm")` — both locale-dependent and neither
     * reproducible from the other. Android's pattern is taken, since it is the one with a
     * written-down format string, and the shipped assertion for it only requires that no
     * `{{date}}` placeholder survives (`BotTemplateParityTest.kt:28-33`).
     *
     * Unknown placeholders are left **verbatim**, not blanked — asserted at
     * `BotTemplateParityTest.kt:52-56`.
     *
     * @param zone the time zone `{{date}}` renders in. Defaulted rather than hardcoded to
     *   UTC because the phones render in the device's zone, and exposed rather than read
     *   from the JVM default because a test that depends on the machine's time zone is a
     *   test that fails on one runner and passes on another.
     */
    fun renderTemplate(
        template: String,
        botName: String,
        input: String,
        senderKey: String,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val shortSender = senderKey.take(SENDER_PREFIX_LEN)
        val dateText = SimpleDateFormat(DATE_PATTERN, locale)
            .also { it.timeZone = zone }
            .format(Date(nowMillis))
        return template
            // iOS's set — authoritative
            .replace("{{input}}", input)
            .replace("{{bot_name}}", botName)
            .replace("{{date}}", dateText)
            .replace("{{timestamp}}", (nowMillis / 1000).toString())
            // Android's legacy aliases
            .replace("{{message}}", input)
            .replace("{{sender}}", shortSender)
            .replace("{{member}}", shortSender)
    }

    /** `senderKey.take(8)` (`BotManager.kt:69`). */
    const val SENDER_PREFIX_LEN = 8

    /** Android's `{{date}}` pattern (`BotManager.kt:65`). */
    const val DATE_PATTERN = "d MMM yyyy, HH:mm"
}

/**
 * The six shipped bot templates — the closest thing this product has to a bot directory.
 *
 * `BotManager.templates` (`BotManager.swift:1135-1230`) is a hardcoded static array, and
 * there is no endpoint that lists bots you do not own ([BotApi]'s class doc). Reproduced
 * here for the trigger TYPE and CONDITION of each, which is the part that is protocol; the
 * names, descriptions and response bodies are localisation keys on iOS
 * (`"bot.template.faq.response".localized`) and are therefore not wire values to copy.
 *
 * The `reminder` row is the one to read twice: its condition is the literal `"86400"`,
 * which [BotTriggers.parseScheduleSeconds] reads as 24 hours and Android's parser reads as
 * 86.4 seconds. See BotTriggers' SCHEDULE section.
 */
object BotTemplates {

    /**
     * [botType] is what decides whether the bot exists on the SERVER at all: both platforms
     * register only `webhook` (`BotManager.swift:872-874`, `BotManager.kt:572`). So five of
     * these six templates produce a bot with no server presence and therefore no
     * `/api/bot/send` — they are purely local, and on Android purely inert, since the
     * trigger engine there has no caller.
     */
    data class Template(
        val key: String,
        val botType: String,
        val triggerType: BotTriggers.TriggerType?,
        val condition: String,
        val responseTemplate: String,
    )

    val ALL: List<Template> = listOf(
        // NOTE the condition: the literal `"member_join"`, NOT the `[MEMBER_JOIN]` sentinel
        // the matcher looks for. For a member-event trigger the condition field is DEAD —
        // `shouldFireTrigger` never reads it, it tests the message text against the
        // hardcoded sentinel (`BotManager.swift:748-751`). So a user editing this
        // template's condition changes nothing, on either platform. Reproduced verbatim
        // because it is what a template exported from a phone carries.
        Template("welcome", "moderator", BotTriggers.TriggerType.MEMBER_JOIN, "member_join", ""),
        Template("faq", "automation", BotTriggers.TriggerType.COMMAND, "/help", ""),
        Template("reminder", "scheduled", BotTriggers.TriggerType.SCHEDULE, "86400", ""),
        // The webhook template ships with NO triggers at all — and on iOS its webhook is
        // never called (BotManager.swift:806-825 has zero callers), so it is a template for
        // a bot that does nothing on the platform that runs the engine.
        Template("webhook", "webhook", null, "", ""),
        // The only template whose responseTemplate is a real wire value rather than a
        // localisation key — and it is why `/echo hello` replies with `/echo hello`.
        Template("echo", "automation", BotTriggers.TriggerType.COMMAND, "/echo", "{{input}}"),
        Template("stats", "automation", BotTriggers.TriggerType.COMMAND, "/stats", ""),
    )

    fun byKey(key: String): Template? = ALL.firstOrNull { it.key == key }
}
