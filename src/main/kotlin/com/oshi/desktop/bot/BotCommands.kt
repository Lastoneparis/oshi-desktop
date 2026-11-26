package com.oshi.desktop.bot

import org.json.JSONArray
import org.json.JSONObject

/**
 * __DESKTOP_BOT_COMMANDS_2026_11_24__ A Telegram-style bot command: `/name` + description.
 *
 * The rules are the SERVER's, not invented here — `bot_platform.js` `POST /api/bot/setMyCommands`
 * (ServerPatches/bot_e2e/patched/bot_platform.js:457): the leading `/` is stripped, the name
 * must match `^[a-z0-9_]{1,32}$`, the description is cut at 256, at most 100 commands, and an
 * empty list clears them. Validating with the same rules locally means the editor refuses
 * what the server would refuse, before anything is sent. The Android port of the same screen
 * uses the same model (`/name` lowercase a-z0-9_, max 32, description max 256).
 */
data class BotCommand(
    /** Without the slash, lowercase: `help`. */
    val name: String,
    val description: String,
) {
    /** As typed in a chat: `/help`. */
    val slash: String get() = "/$name"

    companion object {
        const val MAX_NAME = 32
        const val MAX_DESCRIPTION = 256
        const val MAX_COMMANDS = 100
        private val NAME = Regex("^[a-z0-9_]{1,$MAX_NAME}$")

        /** What the user typed → the stored name: trim, drop one leading `/`, lowercase. */
        fun normalizeName(raw: String): String = raw.trim().removePrefix("/").trim().lowercase()

        fun isValidName(name: String): Boolean = NAME.matches(name)
    }
}

object BotCommands {

    /** Why a command list cannot be saved; [None] when it can. */
    enum class Problem { None, EmptyName, BadName, DescriptionTooLong, Duplicate, TooMany }

    /** The first problem of one row (index-free), for the editor's inline message. */
    fun problemOf(nameRaw: String, description: String): Problem {
        val n = BotCommand.normalizeName(nameRaw)
        return when {
            n.isEmpty() -> Problem.EmptyName
            !BotCommand.isValidName(n) -> Problem.BadName
            description.length > BotCommand.MAX_DESCRIPTION -> Problem.DescriptionTooLong
            else -> Problem.None
        }
    }

    /** The whole list: rows first, then duplicates and the count. */
    fun problemOf(list: List<BotCommand>): Problem {
        list.forEach { c -> problemOf(c.name, c.description).let { if (it != Problem.None) return it } }
        if (list.map { it.name }.toSet().size != list.size) return Problem.Duplicate
        if (list.size > BotCommand.MAX_COMMANDS) return Problem.TooMany
        return Problem.None
    }

    /** Normalised command from editor input, or null if invalid. */
    fun parse(nameRaw: String, description: String): BotCommand? =
        if (problemOf(nameRaw, description) == Problem.None) BotCommand(BotCommand.normalizeName(nameRaw), description.trim()) else null

    /** `[{"command":"help","description":"…"}]` — the server's own shape, no slash. */
    fun toJson(list: List<BotCommand>): JSONArray = JSONArray().apply {
        list.forEach { put(JSONObject().put("command", it.name).put("description", it.description)) }
    }

    /** Lenient: skips malformed entries, accepts a leading slash, keeps the first of duplicates. */
    fun fromJson(arr: JSONArray?): List<BotCommand> {
        if (arr == null) return emptyList()
        val out = LinkedHashMap<String, BotCommand>()
        for (i in 0 until minOf(arr.length(), BotCommand.MAX_COMMANDS)) {
            val o = arr.optJSONObject(i) ?: continue
            val name = BotCommand.normalizeName(o.optString("command", ""))
            if (!BotCommand.isValidName(name) || name in out) continue
            out[name] = BotCommand(name, o.optString("description", "").take(BotCommand.MAX_DESCRIPTION))
        }
        return out.values.toList()
    }
}
