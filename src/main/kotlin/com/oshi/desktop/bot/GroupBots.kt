package com.oshi.desktop.bot

import com.oshi.desktop.store.SealedJsonFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * __BOTS_PHASE1_2026_11_24__ One bot serving one group or channel, as this device knows it.
 *
 * The desktop had no way to name "the bots in this group": the server keeps the roster and
 * answers only the bot's owner. This record is written when an admin ADDS a bot here (with the
 * permissions they chose) and when a bot post arrives carrying a `botId` — so the `/` menu, the
 * group `/cmd` dispatch and the inline buttons all know which bot to talk to.
 */
data class GroupBot(
    val groupId: String,
    val botId: String,
    val username: String = "",
    val name: String = "",
    val emoji: String = "🤖",
    val commands: List<BotCommand> = emptyList(),
    val permissions: BotPermissions = BotPermissions(),
    /** True when this account owns the bot (its token is in [OwnedBotStore]). */
    val owned: Boolean = false,
    val addedAtMillis: Long = System.currentTimeMillis(),
)

/**
 * The [GroupBot] records, sealed at rest under [com.oshi.desktop.store.LocalDataKeys.GROUP_BOTS]:
 * which bots serve which groups is a membership graph, the same class of data as the group store.
 */
class GroupBotRoster(private val file: File, private val key: ByteArray?) {

    @Volatile private var cache: List<GroupBot>? = null

    @Synchronized fun all(): List<GroupBot> = cache ?: load().also { cache = it }

    fun forGroup(groupId: String): List<GroupBot> = all().filter { it.groupId.equals(groupId, ignoreCase = true) }

    fun find(groupId: String, botId: String): GroupBot? = forGroup(groupId).firstOrNull { it.botId == botId }

    /** Insert or replace by (group, bot). */
    @Synchronized
    fun put(b: GroupBot) = write(all().filterNot { it.groupId.equals(b.groupId, true) && it.botId == b.botId } + b)

    /** Learn a bot from one of its posts, keeping what is already known about it. */
    @Synchronized
    fun noteSeen(groupId: String, botId: String, name: String) {
        if (botId.isEmpty() || groupId.isEmpty()) return
        val known = find(groupId, botId)
        if (known != null && (known.name == name || name.isEmpty())) return
        put((known ?: GroupBot(groupId = groupId, botId = botId)).copy(name = name.ifEmpty { known?.name ?: "" }))
    }

    @Synchronized
    fun remove(groupId: String, botId: String) = write(all().filterNot { it.groupId.equals(groupId, true) && it.botId == botId })

    private fun load(): List<GroupBot> {
        val read = SealedJsonFile.read(file, key, PURPOSE) ?: return emptyList()
        val arr = runCatching { JSONObject(read.json).optJSONArray("bots") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decode) }
    }

    private fun write(list: List<GroupBot>) {
        val json = JSONObject().put("v", 1).put("bots", JSONArray().apply { list.forEach { put(encode(it)) } }).toString()
        SealedJsonFile.write(file, key, PURPOSE, json)
        cache = list
    }

    companion object {
        const val FILE_NAME = "group-bots.json"
        val PURPOSE: String = com.oshi.desktop.store.LocalDataKeys.GROUP_BOTS

        internal fun encode(b: GroupBot): JSONObject = JSONObject()
            .put("groupId", b.groupId).put("botId", b.botId).put("username", b.username).put("name", b.name)
            .put("emoji", b.emoji).put("commands", BotCommands.toJson(b.commands))
            .put("permissions", b.permissions.toJson()).put("owned", b.owned).put("addedAt", b.addedAtMillis)

        internal fun decode(o: JSONObject): GroupBot? {
            val g = o.optString("groupId", "").ifEmpty { return null }
            val id = o.optString("botId", "").ifEmpty { return null }
            return GroupBot(
                groupId = g, botId = id,
                username = o.optString("username", ""), name = o.optString("name", ""),
                emoji = o.optString("emoji", "🤖").ifEmpty { "🤖" },
                commands = BotCommands.fromJson(o.optJSONArray("commands")),
                permissions = BotPermissions.fromJson(o.optJSONObject("permissions")),
                owned = o.optBoolean("owned", false),
                addedAtMillis = o.optLong("addedAt", 0L),
            )
        }
    }
}

/**
 * Contract §3 "Group commands" + A8: which bots of a group a typed `/cmd` goes to.
 *
 *  - Only a message that STARTS with `/` is a command, and only its first line leaves the device
 *    (the server keeps only that line anyway — read scope "matched", never the thread).
 *  - `/cmd@username` goes to that bot only (case-insensitive), whatever its command list says.
 *  - Plain `/cmd` goes to every bot of the group whose command list contains `cmd`. A bot whose
 *    list is unknown (learned from a post, never fetched) gets nothing: guessing would send a
 *    stranger's bot every slash line typed in the group.
 */
object GroupCommandRouter {

    data class Parsed(val command: String, val addressee: String?, val line: String)

    /** `/Help@weatherbot  arg` → (help, weatherbot, "/Help@weatherbot  arg"); null for a non-command. */
    fun parse(text: String): Parsed? {
        val line = text.trimStart().lineSequence().firstOrNull()?.trimEnd() ?: return null
        if (!line.startsWith("/") || line.length < 2) return null
        val head = line.substring(1).takeWhile { !it.isWhitespace() }
        val (cmd, at) = head.split('@', limit = 2).let { it[0] to it.getOrNull(1) }
        val name = cmd.lowercase()
        if (!BotCommand.isValidName(name)) return null
        return Parsed(name, at?.let { BotUsername.normalize(it) }?.takeIf { it.isNotEmpty() }, line.take(4096))
    }

    fun targets(text: String, bots: List<GroupBot>): List<GroupBot> {
        val p = parse(text) ?: return emptyList()
        if (p.addressee != null) return bots.filter { it.username.isNotEmpty() && it.username.equals(p.addressee, ignoreCase = true) }.take(1)
        return bots.filter { b -> b.commands.any { it.name == p.command } }
    }
}
