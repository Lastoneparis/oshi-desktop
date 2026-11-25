package com.oshi.desktop.bot

import com.oshi.desktop.store.SealedJsonFile
import org.json.JSONObject
import java.io.File

/**
 * __BOTS_PHASE1_2026_11_24__ What a bot post carries besides its text: which bot wrote it and
 * the inline keyboard under it. Kept beside the message store (keyed by the post's message id)
 * rather than inside [com.oshi.desktop.store.Message], whose JSON is shared with export/import
 * and the phones' archive format.
 */
data class BotPostExtra(
    val messageId: String,
    val groupId: String,
    val botId: String,
    val botName: String,
    /** Raw `reply_markup` JSON, parsed on demand ([InlineKeyboard.parse]) like Android does. */
    val replyMarkup: String? = null,
    val edited: Boolean = false,
    /** Pinned by the bot (pinChatMessage); drawn as a pin beside its badge. */
    val pinned: Boolean = false,
) {
    val keyboard: InlineKeyboard? get() = InlineKeyboard.parse(replyMarkup)
}

class BotPostStore(private val file: File, private val key: ByteArray?) {

    @Volatile private var cache: LinkedHashMap<String, BotPostExtra>? = null

    @Synchronized
    private fun map(): LinkedHashMap<String, BotPostExtra> = cache ?: load().also { cache = it }

    @Synchronized fun get(messageId: String): BotPostExtra? = map()[messageId]

    @Synchronized
    fun put(e: BotPostExtra) {
        val m = map()
        m.remove(e.messageId)
        m[e.messageId] = e
        while (m.size > MAX) m.remove(m.keys.first())
        write(m)
    }

    @Synchronized fun remove(messageId: String) { if (map().remove(messageId) != null) write(map()) }

    private fun load(): LinkedHashMap<String, BotPostExtra> {
        val out = LinkedHashMap<String, BotPostExtra>()
        val read = SealedJsonFile.read(file, key, PURPOSE) ?: return out
        val arr = runCatching { JSONObject(read.json).optJSONArray("posts") }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("messageId", "").ifEmpty { continue }
            out[id] = BotPostExtra(id, o.optString("groupId", ""), o.optString("botId", ""), o.optString("botName", ""),
                o.optString("replyMarkup", "").ifEmpty { null }, o.optBoolean("edited", false), o.optBoolean("pinned", false))
        }
        return out
    }

    private fun write(m: Map<String, BotPostExtra>) {
        val arr = org.json.JSONArray()
        m.values.forEach { e ->
            arr.put(JSONObject().put("messageId", e.messageId).put("groupId", e.groupId).put("botId", e.botId)
                .put("botName", e.botName).put("replyMarkup", e.replyMarkup ?: "").put("edited", e.edited).put("pinned", e.pinned))
        }
        SealedJsonFile.write(file, key, PURPOSE, JSONObject().put("v", 1).put("posts", arr).toString())
    }

    companion object {
        const val FILE_NAME = "bot-posts.json"
        /** The newest posts keep their keyboards; older ones show text only. */
        const val MAX = 4000
        val PURPOSE: String = com.oshi.desktop.store.LocalDataKeys.BOT_POSTS
    }
}

/**
 * The bot-side operations on a post that already exists in a group or channel (Telegram
 * `editMessageText`, `editMessageReplyMarkup`, `deleteMessage`, `pinChatMessage`,
 * `unpinChatMessage`), as server amendment S5 delivers them: a sealed queue item of OUTER type
 * `bot_message_action` whose INNER is `{action:"edit"|"delete"|"pin"|"unpin", targetMessageId,
 * groupId, botId, content?, replyMarkup?}`. For `edit`: `content` present = new text; the
 * `replyMarkup` KEY present = new keyboard (null removes it); key absent = keyboard unchanged.
 */
sealed interface BotPostOp {
    val targetMessageId: String
    val botId: String

    /** What an edit does to the keyboard. */
    sealed interface Markup {
        data object Unchanged : Markup
        data object Remove : Markup
        data class Set(val raw: String) : Markup
    }

    data class Edit(override val targetMessageId: String, override val botId: String, val text: String?, val markup: Markup) : BotPostOp
    data class Delete(override val targetMessageId: String, override val botId: String) : BotPostOp
    data class Pin(override val targetMessageId: String, override val botId: String, val pinned: Boolean) : BotPostOp

    companion object {
        const val OUTER_TYPE = "bot_message_action"

        /** The op of an opened action payload; [botId] is the OUTER envelope's (S5: the one to match). Null when unusable. */
        fun of(payload: JSONObject, botId: String): BotPostOp? {
            val target = payload.optString("targetMessageId", "").ifEmpty { return null }
            if (botId.isEmpty()) return null
            return when (payload.optString("action", "")) {
                "edit" -> {
                    val markup = when {
                        !payload.has("replyMarkup") && !payload.has("reply_markup") -> Markup.Unchanged
                        else -> (payload.optJSONObject("replyMarkup") ?: payload.optJSONObject("reply_markup"))
                            ?.let { Markup.Set(it.toString()) } ?: Markup.Remove
                    }
                    val text = if (payload.has("content")) payload.optString("content", "") else null
                    if (text == null && markup == Markup.Unchanged) null else Edit(target, botId, text, markup)
                }
                "delete" -> Delete(target, botId)
                "pin" -> Pin(target, botId, true)
                "unpin" -> Pin(target, botId, false)
                else -> null
            }
        }

        /**
         * Only the bot that wrote a post may change it (S5: "messageId == targetMessageId AND
         * botId == outer botId, else drop it"). An op for a post we never saw is dropped too.
         */
        fun mayApply(op: BotPostOp, existing: BotPostExtra?): Boolean =
            existing != null && existing.botId.isNotEmpty() && existing.botId == op.botId
    }
}
