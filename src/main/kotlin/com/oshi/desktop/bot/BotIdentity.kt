package com.oshi.desktop.bot

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * __BOTS_PHASE1_2026_11_24__ The three public names of a bot: its id, its `@username`, its link.
 *
 * Contract: scratchpad `bots-phase1-contract.md` §1–§2 plus the owner re-scope of the same
 * evening (a link opens the bot's PROFILE with "Add to group / Add to channel", never a chat).
 */
object BotIds {
    /**
     * The bot's PUBLIC id: `sha256("oshi-bot-id:" + token)` hex, first 16 chars — byte for byte
     * the server's `botIdFor` (`bot_platform.js:61`) and Android's `publicIdFor`. One-way, so it
     * names a bot without being able to act as it.
     */
    fun publicIdFor(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(("oshi-bot-id:$token").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
}

object BotUsername {
    /** §1: 5–32 chars, lowercase, starts with a letter, ends with `bot`. */
    val RULE = Regex("^[a-z][a-z0-9_]{2,28}bot$")
    const val MAX = 32

    /** What the person typed → what is stored: trim, drop one `@`, lowercase. */
    fun normalize(raw: String): String = raw.trim().removePrefix("@").trim().lowercase()

    fun isValid(raw: String): Boolean = RULE.matches(normalize(raw))

    /** Why [raw] is refused, for the live hint; [Problem.None] when it is fine. */
    enum class Problem { None, Empty, TooShort, TooLong, MustStartWithLetter, BadCharacters, MustEndWithBot }

    fun problemOf(raw: String): Problem {
        val u = normalize(raw)
        return when {
            u.isEmpty() -> Problem.Empty
            !u[0].isLetter() || u[0] !in 'a'..'z' -> Problem.MustStartWithLetter
            u.any { it !in 'a'..'z' && it !in '0'..'9' && it != '_' } -> Problem.BadCharacters
            u.length < 5 -> Problem.TooShort
            u.length > MAX -> Problem.TooLong
            !u.endsWith("bot") -> Problem.MustEndWithBot
            else -> Problem.None
        }
    }

    /**
     * A username to propose from a display name: "Weather Paris" → `weather_parisbot`. Always
     * valid (padded / cut to the rule), or "" when the name has no usable letter.
     */
    fun fromName(name: String): String {
        val base = name.lowercase()
            .map { c -> if (c in 'a'..'z' || c in '0'..'9') c else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
            .dropWhile { it !in 'a'..'z' }
            .removeSuffix("bot").trimEnd('_')
        if (base.isEmpty()) return ""
        val cut = base.take(MAX - 3).trimEnd('_')
        val withSuffix = if (cut.endsWith("_")) cut + "bot" else cut + "_bot"
        val candidate = if (withSuffix.length > MAX) cut.take(MAX - 3) + "bot" else withSuffix
        return if (isValid(candidate)) candidate else (cut + "xbot").take(MAX).let { if (isValid(it)) it else "" }
    }

    /** Alternatives when [taken] is taken (BotFather's "try another one"), all valid, in order. */
    fun suggestions(taken: String, count: Int = 3): List<String> {
        val u = normalize(taken)
        val stem = u.removeSuffix("bot").trimEnd('_').ifEmpty { "my" }
        val out = LinkedHashSet<String>()
        listOf("${stem}_oshibot", "${stem}_appbot", "${stem}2bot", "${stem}_hqbot", "the_${stem}bot", "${stem}3bot")
            .forEach { s -> val c = if (s.length > MAX) stem.take(MAX - 5) + "_" + s.takeLast(4) else s; if (isValid(c) && c != u) out.add(c) }
        return out.take(count)
    }
}

/**
 * `https://oshi-messenger.com/bot/<username>[?start=<payload>]` and `oshi://bot/<username>[…]`.
 * The `start` payload is kept (and validated) because the web page and the other clients put
 * it in links; in phase 1 as re-scoped there is no private chat to deliver it to, so the
 * desktop only carries it along.
 */
data class BotLink(val username: String, val startPayload: String? = null) {
    /** The canonical https link, what "Share link" copies and the QR encodes. */
    val https: String get() = WEB_PREFIX + username + (startPayload?.let { "?start=$it" } ?: "")
    val scheme: String get() = "oshi://bot/" + username + (startPayload?.let { "?start=$it" } ?: "")

    companion object {
        const val WEB_PREFIX = "https://oshi-messenger.com/bot/"
        val PAYLOAD = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val HOSTS = setOf("oshi-messenger.com", "www.oshi-messenger.com", "oshi-messenger.org", "www.oshi-messenger.org")

        fun forUsername(username: String): BotLink = BotLink(BotUsername.normalize(username))

        /** A bot link, or null. Accepts surrounding whitespace and a missing `https://`. */
        fun parse(raw: String?): BotLink? {
            val s = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return null
            val withScheme = when {
                s.startsWith("oshi://", ignoreCase = true) || s.startsWith("http://", ignoreCase = true) ||
                    s.startsWith("https://", ignoreCase = true) -> s
                HOSTS.any { s.startsWith("$it/", ignoreCase = true) } -> "https://$s"
                else -> return null
            }
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val segments: List<String> = when (uri.scheme?.lowercase()) {
                "oshi" -> {
                    if (!uri.host.equals("bot", ignoreCase = true)) return null
                    (uri.rawPath ?: "").trim('/').split('/').filter { it.isNotEmpty() }
                }
                "https", "http" -> {
                    if (uri.host?.lowercase() !in HOSTS) return null
                    val p = (uri.rawPath ?: "").trim('/').split('/').filter { it.isNotEmpty() }
                    if (p.size != 2 || !p[0].equals("bot", ignoreCase = true)) return null
                    p.drop(1)
                }
                else -> return null
            }
            if (segments.size != 1) return null
            val user = BotUsername.normalize(URLDecoder.decode(segments[0], Charsets.UTF_8))
            if (!BotUsername.isValid(user)) return null
            val start = (uri.rawQuery ?: "").split('&').firstNotNullOfOrNull { kv ->
                val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if (k == "start") URLDecoder.decode(v, Charsets.UTF_8) else null
            }?.takeIf { PAYLOAD.matches(it) }
            return BotLink(user, start)
        }

        private val IN_TEXT = Regex("""(?i)(?:oshi://bot/|(?:https?://)?(?:www\.)?oshi-messenger\.(?:com|org)/bot/)[A-Za-z0-9_]+(?:\?start=[A-Za-z0-9_-]{1,64})?""")

        /** Bot links inside a message, as (range, link) — what a bubble makes clickable. */
        fun findIn(text: String): List<Pair<IntRange, BotLink>> =
            IN_TEXT.findAll(text).mapNotNull { m -> parse(m.value)?.let { m.range to it } }.toList()
    }
}
