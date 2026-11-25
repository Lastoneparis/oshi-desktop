package com.oshi.desktop.bot

import org.json.JSONArray
import org.json.JSONObject

/**
 * __BOTS_PHASE1_2026_11_24__ A bot as anyone may see it (`GET /api/bot/public`, `/search`):
 * contract §1 `{botId, username, name, description, about, avatarEmoji, commands, verified,
 * hosting, public}`. `ownerLabel` is omitted by the server in phase 1 (amendment A7).
 */
data class PublicBotProfile(
    val botId: String,
    val username: String,
    val name: String,
    val about: String = "",
    val description: String = "",
    val avatarEmoji: String = "🤖",
    val commands: List<BotCommand> = emptyList(),
    val verified: Boolean = false,
    /** "remote" (runs on its owner's server) or "local". Unknown values read as remote: the cautious notice. */
    val remote: Boolean = true,
    val public: Boolean = true,
) {
    val link: BotLink get() = BotLink.forUsername(username)

    companion object {
        const val MAX_ABOUT = 120
        const val MAX_DESCRIPTION = 512

        /** Lenient: null only without a botId. Over-long fields are cut, not refused. */
        fun fromJson(o: JSONObject?): PublicBotProfile? {
            o ?: return null
            val id = o.optString("botId", "").ifEmpty { return null }
            return PublicBotProfile(
                botId = id,
                username = BotUsername.normalize(o.optString("username", "")),
                name = o.optString("name", "").ifEmpty { o.optString("botName", "") }.take(OwnedBot.MAX_NAME),
                about = o.optString("about", "").take(MAX_ABOUT),
                description = o.optString("description", "").take(MAX_DESCRIPTION),
                avatarEmoji = o.optString("avatarEmoji", "").ifEmpty { "🤖" },
                commands = BotCommands.fromJson(o.optJSONArray("commands")),
                verified = o.optBoolean("verified", false),
                remote = o.optString("hosting", "remote") != "local",
                public = o.optBoolean("public", true),
            )
        }

        fun listFromJson(arr: JSONArray?): List<PublicBotProfile> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { fromJson(arr.optJSONObject(it)) }
    }
}

/**
 * What a bot may do in a group or channel, chosen by the admin who adds it (Telegram's admin
 * rights, re-scope ADD-2). Defaults: it may post and edit its own posts; deleting and pinning
 * are opt-in.
 */
data class BotPermissions(
    val canPost: Boolean = true,
    val canEdit: Boolean = true,
    val canDelete: Boolean = false,
    val canPin: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("canPost", canPost).put("canEdit", canEdit).put("canDelete", canDelete).put("canPin", canPin)

    companion object {
        fun fromJson(o: JSONObject?): BotPermissions = if (o == null) BotPermissions() else BotPermissions(
            canPost = o.optBoolean("canPost", true),
            canEdit = o.optBoolean("canEdit", true),
            canDelete = o.optBoolean("canDelete", false),
            canPin = o.optBoolean("canPin", false),
        )
    }
}

/**
 * An inline keyboard under a bot post: `{inline_keyboard:[[{text, callback_data}|{text, url}|
 * {text, web_app:{url}}]]}` — the shape the server validates (`bot_platform.js` validateReplyMarkup)
 * and the phones store raw (Android `Message.replyMarkup`). Parsed leniently: a button of an
 * unknown kind is dropped, not the keyboard.
 */
data class InlineKeyboard(val rows: List<List<Button>>) {
    sealed interface Button {
        val text: String
        data class Callback(override val text: String, val data: String) : Button
        data class Url(override val text: String, val url: String) : Button
        data class WebApp(override val text: String, val url: String) : Button
    }

    val isEmpty: Boolean get() = rows.all { it.isEmpty() }

    companion object {
        /** Telegram's limit on callback_data, in BYTES. */
        const val MAX_CALLBACK_BYTES = 64

        fun parse(markup: JSONObject?): InlineKeyboard? {
            val rows = markup?.optJSONArray("inline_keyboard") ?: return null
            val out = (0 until rows.length()).mapNotNull { r ->
                val row = rows.optJSONArray(r) ?: return@mapNotNull null
                (0 until row.length()).mapNotNull { b -> button(row.optJSONObject(b)) }.takeIf { it.isNotEmpty() }
            }
            return InlineKeyboard(out).takeIf { !it.isEmpty }
        }

        fun parse(raw: String?): InlineKeyboard? = raw?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

        private fun button(o: JSONObject?): Button? {
            o ?: return null
            val text = o.optString("text", "").trim().ifEmpty { return null }
            o.optString("callback_data", "").takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_CALLBACK_BYTES }
                ?.let { return Button.Callback(text, it) }
            o.optString("url", "").takeIf { isSafeUrl(it) }?.let { return Button.Url(text, it) }
            o.optJSONObject("web_app")?.optString("url", "")?.takeIf { isSafeUrl(it) }?.let { return Button.WebApp(text, it) }
            return null
        }

        /** Only https (and oshi bot links) open from a button: no javascript:, file:, data:. */
        fun isSafeUrl(u: String): Boolean =
            u.startsWith("https://", ignoreCase = true) || BotLink.parse(u) != null
    }
}
