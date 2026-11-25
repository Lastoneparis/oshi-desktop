package com.oshi.desktop.bot

import com.oshi.desktop.store.SealedJsonFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * __DESKTOP_BOT_COMMANDS_2026_11_24__ The bots THIS account owns, configured on this computer.
 *
 * Shaped like the phones' local bot record (Android `data/model/Bot.kt`, iOS
 * `BotManager.swift:15-36`) minus what the desktop does not run (triggers engine, scripts,
 * permissions): a name, a `@username`, a description, an emoji, the `/` command list, the
 * groups the bot serves and its token.
 *
 * The token is minted like Android's (`BotManager.kt:290,858-862`: SHA-256 of 32 random
 * bytes, hex) and is the bot's only credential on the server — so this file is SEALED at rest
 * under its own purpose ([com.oshi.desktop.store.LocalDataKeys.OWNED_BOTS]), like contacts.
 */
data class OwnedBot(
    val id: String = UUID.randomUUID().toString(),
    val token: String = OwnedBotStore.mintToken(),
    val name: String,
    val username: String = "",
    val description: String = "",
    /** __BOTS_PHASE1_2026_11_24__ one line for the public profile (≤120). */
    val about: String = "",
    val emoji: String = "🤖",
    val commands: List<BotCommand> = emptyList(),
    /** Group ids this bot answers in; its commands are offered in those groups' `/` menu. */
    val assignedGroups: List<String> = emptyList(),
    /** True once `POST /api/bot/register` accepted the token (needed by setMyCommands/send). */
    val registered: Boolean = false,
    /** True when the server holds exactly [commands] (last setMyCommands succeeded). */
    val commandsPublished: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** __BOTS_PHASE1_2026_11_24__ listed in bot search (`public` on setMyProfile). */
    val isPublic: Boolean = false,
    /** True when the server holds this profile (last setMyProfile succeeded). */
    val profilePublished: Boolean = false,
    /**
     * The bot's PUBLIC id as the server knows it. S3: it is STABLE across a token revoke, so the
     * one learned from the server is stored ([publicId]); only a bot never revoked derives it
     * from its token (which is then the same value).
     */
    val publicId: String = "",
) {
    val botId: String get() = publicId.ifEmpty { BotIds.publicIdFor(token) }

    /** The share link, once the bot has a username. */
    val link: BotLink? get() = username.takeIf { BotUsername.isValid(it) }?.let { BotLink.forUsername(it) }

    companion object {
        const val MAX_NAME = 64
        const val MAX_DESCRIPTION = 512
        const val MAX_ABOUT = 120

        /** __BOTS_PHASE1_2026_11_24__ `@username` without the at, contract §1 ([BotUsername]). Blank is allowed (not chosen yet). */
        fun isValidUsername(u: String): Boolean = u.isEmpty() || BotUsername.isValid(u) && u == BotUsername.normalize(u)
    }
}

class OwnedBotStore(private val file: File, private val key: ByteArray?) {

    @Volatile private var cache: List<OwnedBot>? = null

    @Synchronized
    fun all(): List<OwnedBot> = cache ?: load().also { cache = it }

    @Synchronized
    fun get(id: String): OwnedBot? = all().firstOrNull { it.id == id }

    /** Insert or replace by id. */
    @Synchronized
    fun save(bot: OwnedBot) {
        val list = all().filterNot { it.id == bot.id } + bot
        write(list.sortedBy { it.createdAtMillis })
    }

    @Synchronized
    fun delete(id: String) = write(all().filterNot { it.id == id })

    /** Every command a bot of ours offers in [groupId], tagged with the bot. */
    fun commandsForGroup(groupId: String): List<Pair<OwnedBot, BotCommand>> =
        all().filter { b -> b.assignedGroups.any { it.equals(groupId, ignoreCase = true) } }
            .flatMap { b -> b.commands.map { b to it } }

    private fun load(): List<OwnedBot> {
        val read = SealedJsonFile.read(file, key, PURPOSE) ?: return emptyList()
        val arr = JSONObject(read.json).optJSONArray("bots") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decode) }
    }

    private fun write(list: List<OwnedBot>) {
        val json = JSONObject().put("v", 1).put("bots", JSONArray().apply { list.forEach { put(encode(it)) } }).toString()
        SealedJsonFile.write(file, key, PURPOSE, json)
        cache = list
    }

    companion object {
        const val FILE_NAME = "owned-bots.json"
        val PURPOSE: String = com.oshi.desktop.store.LocalDataKeys.OWNED_BOTS

        private val random = SecureRandom()

        /** 64 hex chars: SHA-256 of 32 random bytes (Android `BotManager.generateToken`). */
        fun mintToken(): String {
            val seed = ByteArray(32).also { random.nextBytes(it) }
            return MessageDigest.getInstance("SHA-256").digest(seed).joinToString("") { "%02x".format(it) }
        }

        internal fun encode(b: OwnedBot): JSONObject = JSONObject()
            .put("id", b.id).put("token", b.token).put("name", b.name).put("username", b.username)
            .put("description", b.description).put("about", b.about).put("emoji", b.emoji)
            .put("public", b.isPublic).put("profilePublished", b.profilePublished).put("botId", b.publicId)
            .put("commands", BotCommands.toJson(b.commands))
            .put("assignedGroups", JSONArray(b.assignedGroups))
            .put("registered", b.registered).put("commandsPublished", b.commandsPublished)
            .put("createdAt", b.createdAtMillis)

        internal fun decode(o: JSONObject): OwnedBot? {
            val id = o.optString("id", "").ifEmpty { return null }
            val token = o.optString("token", "").ifEmpty { return null }
            val groups = o.optJSONArray("assignedGroups")
            return OwnedBot(
                id = id,
                token = token,
                name = o.optString("name", "").take(OwnedBot.MAX_NAME),
                username = o.optString("username", ""),
                description = o.optString("description", "").take(OwnedBot.MAX_DESCRIPTION),
                about = o.optString("about", "").take(OwnedBot.MAX_ABOUT),
                isPublic = o.optBoolean("public", false),
                profilePublished = o.optBoolean("profilePublished", false),
                publicId = o.optString("botId", ""),
                emoji = o.optString("emoji", "🤖").ifEmpty { "🤖" },
                commands = BotCommands.fromJson(o.optJSONArray("commands")),
                assignedGroups = if (groups == null) emptyList() else (0 until groups.length()).map { groups.optString(it) }.filter { it.isNotEmpty() },
                registered = o.optBoolean("registered", false),
                commandsPublished = o.optBoolean("commandsPublished", false),
                createdAtMillis = o.optLong("createdAt", 0L),
            )
        }
    }
}

/**
 * Commands of bots that serve a group but that this account does NOT own — the remote half of
 * a Telegram-style `/` menu for a group MEMBER.
 *
 * ============================================================ THE MISSING ENDPOINT
 *
 * The server can store a bot's commands (`setMyCommands`) but only hands them back to the
 * bot's owner: `getMyCommands` authenticates with the secret token (`bot_platform.js:305-311,
 * 479`). A group member has no way to ask "which commands do the bots in this group offer?".
 * What is needed (NOT deployed, NOT written — described for the server owner):
 *
 *     GET /api/bot/group-commands?groupId=<id>&publicKey=<member key>
 *       → 200 {success:true, bots:[{botId, botName, commands:[{command, description}]}]}
 *       gated by the same `isMember(bot, publicKey, groupId)` check `/api/bot/dispatch`
 *       already applies (bot_platform.js:295-303), rate-limited like `dispatch`;
 *       404/empty when no registered bot serves the group.
 *
 * Until it exists this interface is answered by [Unavailable]; the composer then offers the
 * built-in commands plus the commands of the account's own bots, exactly like iOS and
 * Android do today (both list only bots the device owns: `SlashCommands.swift:87-115`,
 * `BotManager.slashCommandsForGroup`).
 */
fun interface GroupBotCommandDirectory {
    /** Remote bots' commands for [groupId]: (bot display name, command). Never throws. */
    fun commandsFor(groupId: String): List<Pair<String, BotCommand>>

    companion object {
        val Unavailable = GroupBotCommandDirectory { emptyList() }
    }
}
