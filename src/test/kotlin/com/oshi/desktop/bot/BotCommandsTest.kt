package com.oshi.desktop.bot

import com.oshi.desktop.msg.SlashCommands
import com.oshi.desktop.ui.components.BotEditorDraft
import com.oshi.desktop.ui.components.CommandDraft
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** __DESKTOP_BOT_COMMANDS_2026_11_24__ Telegram-style `/` commands for the desktop's bots. */
class BotCommandsTest {

    private val server = BotQueueServer()
    private val api get() = BotApi(baseUrl = server.baseUrl)

    @After
    fun tearDown() = server.close()

    @Test
    fun `names follow the server's rule — lowercase a-z0-9_, 1 to 32, slash optional`() {
        assertEquals("help", BotCommand.normalizeName(" /Help "))
        assertTrue(BotCommand.isValidName("start_2"))
        assertFalse(BotCommand.isValidName("with-dash"))
        assertFalse(BotCommand.isValidName("a".repeat(33)))
        assertTrue(BotCommand.isValidName("a".repeat(32)))
        assertEquals(BotCommands.Problem.EmptyName, BotCommands.problemOf("/", ""))
        assertEquals(BotCommands.Problem.BadName, BotCommands.problemOf("/héllo", ""))
        assertEquals(BotCommands.Problem.DescriptionTooLong, BotCommands.problemOf("/ok", "x".repeat(257)))
        assertEquals(BotCommands.Problem.None, BotCommands.problemOf("/ok", "x".repeat(256)))
        assertEquals(BotCommands.Problem.Duplicate, BotCommands.problemOf(listOf(BotCommand("a", ""), BotCommand("a", "b"))))
        assertEquals(BotCommands.Problem.TooMany, BotCommands.problemOf((0..100).map { BotCommand("c$it", "") }))
    }

    @Test
    fun `json is the server's shape and the parser is lenient`() {
        val json = BotCommands.toJson(listOf(BotCommand("help", "Show help")))
        assertEquals(1, json.length())
        assertEquals("help", json.getJSONObject(0).getString("command"))
        assertEquals("Show help", json.getJSONObject(0).getString("description"))
        assertEquals(setOf("command", "description"), json.getJSONObject(0).keySet())
        val back = BotCommands.fromJson(JSONArray("""[{"command":"/Help","description":"x"},{"command":"bad name"},{"command":"help"},{}]"""))
        assertEquals(listOf(BotCommand("help", "x")), back)
    }

    @Test
    fun `setMyCommands publishes and getMyCommands reads back, against the fake server`() {
        val token = OwnedBotStore.mintToken()
        api.register(token, "Ops bot", "owner+key", listOf(BotApi.BotGroup("g1", "Ops", listOf("alice")))).getOrThrow()
        val cmds = listOf(BotCommand("help", "Show help"), BotCommand("status", "Build status"))
        assertEquals(cmds, api.setMyCommands(token, cmds).getOrThrow())
        assertEquals(cmds, api.getMyCommands(token).getOrThrow())
        // An empty list clears them, as on the server.
        assertEquals(emptyList<BotCommand>(), api.setMyCommands(token, emptyList()).getOrThrow())
        assertEquals(emptyList<BotCommand>(), api.getMyCommands(token).getOrThrow())
    }

    @Test
    fun `an invalid list is refused locally and an unregistered token by the server`() {
        assertTrue(api.setMyCommands("t", listOf(BotCommand("Bad", ""))).isFailure)
        val r = api.setMyCommands("never-registered", listOf(BotCommand("ok", "")))
        assertEquals(401, (r.exceptionOrNull() as BotApi.BotApiException).status)
    }

    @Test
    fun `the store round-trips sealed and finds a group's commands`() {
        val dir = Files.createTempDirectory("owned-bots").toFile()
        try {
            val key = ByteArray(32) { it.toByte() }
            val f = java.io.File(dir, OwnedBotStore.FILE_NAME)
            val bot = OwnedBot(name = "Ops", username = "ops_bot", commands = listOf(BotCommand("help", "Help")), assignedGroups = listOf("G1"))
            OwnedBotStore(f, key).save(bot)
            assertFalse("token must not be stored in clear", f.readText().contains(bot.token))
            val again = OwnedBotStore(f, key)
            assertEquals(listOf(bot), again.all())
            assertEquals(listOf(bot to BotCommand("help", "Help")), again.commandsForGroup("g1"))
            again.delete(bot.id)
            assertTrue(OwnedBotStore(f, key).all().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tokens are 64 hex characters and unique`() {
        val a = OwnedBotStore.mintToken()
        assertTrue(Regex("^[0-9a-f]{64}$").matches(a))
        assertNotEquals(a, OwnedBotStore.mintToken())
    }

    @Test
    fun `bot commands join the slash menu after OSHI's own, filtered as typed`() {
        val bot = OwnedBot(name = "Ops", emoji = "🛠", commands = listOf(BotCommand("help", "Help"), BotCommand("payroll", "Run payroll"), BotCommand("pay", "clash")))
        val extra = SlashCommands.forBots(bot.commands.map { bot to it })
        val all = SlashCommands.matches("/", extra)
        assertEquals("/pay", all.first().command)
        assertEquals("OSHI", all.first().source)
        // The bot's "/pay" is dropped: the built-in is what the composer executes.
        assertEquals(1, all.count { it.command == "/pay" })
        assertEquals(listOf("/pay", "/payroll"), SlashCommands.matches("/pay", extra).map { it.command })
        assertEquals("Ops", SlashCommands.matches("/he", extra).single().source)
        // Contains-matches come after prefix matches.
        assertEquals(listOf("/payroll"), SlashCommands.matches("/roll", extra).map { it.command })
    }

    @Test
    fun `the editor only saves what the server would accept`() {
        val base = OwnedBot(name = "")
        var d = BotEditorDraft(base, commands = listOf(CommandDraft("/help", "Help")))
        assertFalse("no name", d.canSave)
        d = d.copy(name = "Ops")
        assertTrue(d.canSave)
        assertFalse(d.copy(username = "1bad").canSave)
        assertFalse(d.copy(commands = d.commands + CommandDraft("/Help", "")).canSave)
        assertEquals(BotCommands.Problem.Duplicate, d.copy(commands = d.commands + CommandDraft("help", "")).rowProblems()[1])
        assertFalse(d.copy(commands = listOf(CommandDraft("/with space", ""))).canSave)
        val built = d.copy(username = "@ops_bot", groups = setOf("g2", "g1")).build()
        assertEquals("ops_bot", built.username)
        assertEquals(listOf(BotCommand("help", "Help")), built.commands)
        assertEquals(listOf("g1", "g2"), built.assignedGroups)
        assertEquals(base.token, built.token)
    }

    @Test
    fun `editing published commands marks them unpublished`() {
        val pub = OwnedBot(name = "Ops", commands = listOf(BotCommand("help", "Help")), registered = true, commandsPublished = true)
        assertTrue(BotEditorDraft(pub).build().commandsPublished)
        assertFalse(BotEditorDraft(pub, commands = listOf(CommandDraft("/start", ""))).build().commandsPublished)
    }
}
