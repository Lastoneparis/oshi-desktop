package com.oshi.desktop.bot

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.msg.SlashCommands
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.Message
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections

/**
 * __BOTS_PHASE1_2026_11_24__ The desktop half of OSHI Bots phase 1 (contract + owner re-scope):
 * names and links, the HTTP client against a local server that answers like the contract
 * says, the group `/cmd` routing, bot-post edits, BotFather's rules, and the client wiring
 * with a fake platform.
 */
class BotsPhase1Test {

    // ------------------------------------------------------------------ identity

    @Test
    fun `public bot id is the server's sha256 of oshi-bot-id plus token, 16 hex`() {
        // python3: hashlib.sha256(b'oshi-bot-id:' + b'a'*64).hexdigest()[:16]
        assertEquals("67362b1f55379a10", BotIds.publicIdFor("a".repeat(64)))
        assertEquals(OwnedBot(name = "x", token = "a".repeat(64)).botId, "67362b1f55379a10")
    }

    @Test
    fun `usernames follow the contract rule`() {
        listOf("weatherbot", "ops_bot", "a12bot", "abcdefghij_klmnopqrstuvwxyz1bot".take(32).let { if (it.endsWith("bot")) it else "abcdebot" })
            .forEach { assertTrue(it, BotUsername.isValid(it)) }
        listOf("bot", "abot", "1weatherbot", "weather", "Weather-bot", "_xbot", "a".repeat(30) + "bot")
            .forEach { assertFalse(it, BotUsername.isValid(it) && BotUsername.normalize(it) == it) }
        assertTrue(BotUsername.isValid("@WeatherBot"))   // normalised: lowercase, no @
        assertEquals(BotUsername.Problem.MustEndWithBot, BotUsername.problemOf("weather"))
        assertEquals(BotUsername.Problem.MustStartWithLetter, BotUsername.problemOf("1abcbot"))
        assertEquals(BotUsername.Problem.BadCharacters, BotUsername.problemOf("we-ather_bot"))
        assertEquals(BotUsername.Problem.None, BotUsername.problemOf("@weather_bot"))
    }

    @Test
    fun `a username is proposed from the name, and alternatives are all valid`() {
        assertEquals("weather_paris_bot", BotUsername.fromName("Weather Paris"))
        assertEquals("ops_bot", BotUsername.fromName("Ops Bot"))
        assertEquals("", BotUsername.fromName("日本"))
        assertTrue(BotUsername.isValid(BotUsername.fromName("A very long name for a bot that never ends at all")))
        val alts = BotUsername.suggestions("weather_bot")
        assertEquals(3, alts.size)
        alts.forEach { assertTrue(it, BotUsername.isValid(it)); assertTrue(it != "weather_bot") }
    }

    @Test
    fun `bot links parse in every accepted form and nothing else`() {
        assertEquals(BotLink("weather_bot"), BotLink.parse("https://oshi-messenger.com/bot/weather_bot"))
        assertEquals(BotLink("weather_bot", "abc-1"), BotLink.parse(" https://www.oshi-messenger.com/bot/Weather_Bot?start=abc-1 "))
        assertEquals(BotLink("weather_bot"), BotLink.parse("oshi-messenger.com/bot/weather_bot"))
        assertEquals(BotLink("weather_bot", "x"), BotLink.parse("oshi://bot/weather_bot?start=x"))
        assertEquals(BotLink("weather_bot"), BotLink.parse("oshi://bot/weather_bot?start=bad%20payload"))
        assertNull(BotLink.parse("https://evil.example/bot/weather_bot"))
        assertNull(BotLink.parse("https://oshi-messenger.com/g/weather_bot"))
        assertNull(BotLink.parse("https://oshi-messenger.com/bot/weather"))       // not a bot username
        assertNull(BotLink.parse("https://oshi-messenger.com/bot/a/b"))
        assertNull(BotLink.parse("oshi://add?key=abc"))
        assertEquals("https://oshi-messenger.com/bot/weather_bot?start=x", BotLink("weather_bot", "x").https)
        val found = BotLink.findIn("try oshi://bot/quiz_bot or https://oshi-messenger.com/bot/weather_bot!")
        assertEquals(listOf("quiz_bot", "weather_bot"), found.map { it.second.username })
        assertEquals("oshi://bot/quiz_bot", "try oshi://bot/quiz_bot or".substring(found[0].first))
    }

    @Test
    fun `external bot links open the profile sheet without a confirmation step`() {
        val p = com.oshi.desktop.pairing.ExternalLinkGate.classify("oshi://bot/weather_bot")
        assertTrue(p is com.oshi.desktop.pairing.ExternalLinkGate.Pending.Bot)
    }

    // ------------------------------------------------------------------ profile + keyboard

    @Test
    fun `a public profile parses leniently`() {
        val p = PublicBotProfile.fromJson(JSONObject("""{"botId":"b1","username":"Weather_Bot","name":"Weather","about":"${"a".repeat(200)}",
            "avatarEmoji":"","commands":[{"command":"/Now","description":"Now"},{"command":"bad name"}],"hosting":"local","public":true}"""))!!
        assertEquals("weather_bot", p.username)
        assertEquals(120, p.about.length)
        assertEquals("🤖", p.avatarEmoji)
        assertEquals(listOf(BotCommand("now", "Now")), p.commands)
        assertFalse(p.remote)
        assertNull(PublicBotProfile.fromJson(JSONObject("""{"username":"x_bot"}""")))
    }

    @Test
    fun `inline keyboards keep callback, https and bot-link buttons only`() {
        val kb = InlineKeyboard.parse(JSONObject("""{"inline_keyboard":[
            [{"text":"Yes","callback_data":"v:yes"},{"text":"No","callback_data":"v:no"}],
            [{"text":"Site","url":"https://example.org"},{"text":"Evil","url":"javascript:alert(1)"}],
            [{"text":"Too big","callback_data":"${"x".repeat(65)}"}],
            [{"text":"App","web_app":{"url":"https://app.example"}},{"text":"Bot","url":"oshi://bot/quiz_bot"}]]}"""))!!
        assertEquals(3, kb.rows.size)
        assertEquals(listOf("Yes", "No"), kb.rows[0].map { it.text })
        assertEquals(listOf("Site"), kb.rows[1].map { it.text })
        assertTrue(kb.rows[2][0] is InlineKeyboard.Button.WebApp)
        assertTrue(kb.rows[2][1] is InlineKeyboard.Button.Url)
        assertNull(InlineKeyboard.parse("""{"inline_keyboard":[]}"""))
        assertNull(InlineKeyboard.parse("not json"))
    }

    // ------------------------------------------------------------------ picker rule (§4.4)

    @Test
    fun `click sends, tab or arrow inserts, arg commands always insert`() {
        val help = SlashCommands.Command("/help", "What I can do", "Ops")
        val poll = SlashCommands.Command("/poll", "Start a vote: /poll <question>", "Ops")
        assertEquals(SlashCommands.Pick.SEND, SlashCommands.pickAction(help, insertGesture = false))
        assertEquals(SlashCommands.Pick.INSERT, SlashCommands.pickAction(help, insertGesture = true))
        assertEquals(SlashCommands.Pick.INSERT, SlashCommands.pickAction(poll, insertGesture = false))
        val pay = SlashCommands.builtIns().first()
        assertEquals(SlashCommands.Pick.INSERT, SlashCommands.pickAction(pay, insertGesture = false))
        assertEquals("/poll ", SlashCommands.completion(poll))
    }

    // ------------------------------------------------------------------ group /cmd routing

    @Test
    fun `group commands go to the bots that offer them, or to the one named`() {
        val weather = GroupBot("g", "w1", "weather_bot", commands = listOf(BotCommand("now", ""), BotCommand("help", "")))
        val quiz = GroupBot("g", "q1", "quiz_bot", commands = listOf(BotCommand("help", ""), BotCommand("quiz", "")))
        val unknown = GroupBot("g", "u1", "")   // learned from a post, commands never fetched
        val all = listOf(weather, quiz, unknown)
        assertEquals(listOf(weather, quiz), GroupCommandRouter.targets("/help", all))
        assertEquals(listOf(quiz), GroupCommandRouter.targets("/help@Quiz_Bot extra", all))
        assertEquals(listOf(weather), GroupCommandRouter.targets("/now Paris\nsecond line", all))
        assertTrue(GroupCommandRouter.targets("/nobody", all).isEmpty())
        assertTrue(GroupCommandRouter.targets("hello /help", all).isEmpty())
        assertTrue(GroupCommandRouter.targets("/", all).isEmpty())
        assertEquals("/now Paris", GroupCommandRouter.parse("  /now Paris\nsecond line")!!.line)
    }

    // ------------------------------------------------------------------ bot post ops

    @Test
    fun `edits and deletes apply only to the same bot's own post`() {
        val post = BotPostExtra("m1", "g", "botA", "A", null)
        val edit = BotPostOp.of(JSONObject("""{"action":"edit","targetMessageId":"m1","content":"Votes: 3"}"""), "botA")!!
        assertEquals(BotPostOp.Edit("m1", "botA", "Votes: 3", BotPostOp.Markup.Unchanged), edit)
        assertTrue(BotPostOp.mayApply(edit, post))
        assertFalse(BotPostOp.mayApply(BotPostOp.Delete("m1", "botB"), post))
        assertFalse(BotPostOp.mayApply(edit, null))
        // S5: replyMarkup key present = new keyboard, null = remove, absent = unchanged.
        assertEquals(BotPostOp.Markup.Remove, (BotPostOp.of(JSONObject("""{"action":"edit","targetMessageId":"m1","replyMarkup":null}"""), "b") as BotPostOp.Edit).markup)
        assertTrue((BotPostOp.of(JSONObject("""{"action":"edit","targetMessageId":"m1","replyMarkup":{"inline_keyboard":[]}}"""), "b") as BotPostOp.Edit).markup is BotPostOp.Markup.Set)
        assertEquals(BotPostOp.Pin("m1", "b", true), BotPostOp.of(JSONObject("""{"action":"pin","targetMessageId":"m1"}"""), "b"))
        assertNull(BotPostOp.of(JSONObject("""{"content":"new post"}"""), "botA"))
        assertNull(BotPostOp.of(JSONObject("""{"action":"edit"}"""), "botA"))
        assertNull(BotPostOp.of(JSONObject("""{"action":"edit","targetMessageId":"m1"}"""), "botA"))   // nothing to change
    }

    @Test
    fun `a bot_message_action queue item decodes to an Action matched on the outer botId`() {
        val outer = JSONObject().put("type", "bot_message_action").put("messageId", "env9").put("groupId", "G1").put("botId", "botA")
            .put("action", "delete").put("targetMessageId", "m1")
        val entry = "bot:env9:" + java.util.Base64.getEncoder().encodeToString(outer.toString().toByteArray())
        val d = BotEnvelope.decode(entry, ByteArray(32), ByteArray(32))
        assertEquals(BotEnvelope.Decoded.Action("env9", "G1", BotPostOp.Delete("m1", "botA"), BotEnvelope.Sealing.NONE), d)
        val post = JSONObject().put("type", "bot_message").put("messageId", "p1").put("groupId", "G1").put("botId", "botA")
            .put("botName", "A").put("content", "hi").put("replyMarkup", JSONObject("""{"inline_keyboard":[[{"text":"Go","callback_data":"go"}]]}"""))
        val m = (BotEnvelope.decode("bot:p1:" + java.util.Base64.getEncoder().encodeToString(post.toString().toByteArray()), ByteArray(32), ByteArray(32))
            as BotEnvelope.Decoded.Message).message
        assertEquals("botA", m.botId)
        assertEquals("Go", InlineKeyboard.parse(m.replyMarkup)!!.rows[0][0].text)
    }

    @Test
    fun `roster and post stores round-trip sealed`() = withTempDir { dir ->
        val key = ByteArray(32) { (it * 7).toByte() }
        val roster = GroupBotRoster(java.io.File(dir, GroupBotRoster.FILE_NAME), key)
        val b = GroupBot("G1", "b1", "weather_bot", "Weather", "🌦️", listOf(BotCommand("now", "")), BotPermissions(canPin = true))
        roster.put(b)
        roster.noteSeen("G1", "b2", "Quiz")
        roster.noteSeen("G1", "b1", "")             // keeps what is known
        val again = GroupBotRoster(java.io.File(dir, GroupBotRoster.FILE_NAME), key)
        assertEquals(b.copy(addedAtMillis = b.addedAtMillis), again.find("g1", "b1"))
        assertEquals(2, again.forGroup("g1").size)
        assertFalse(java.io.File(dir, GroupBotRoster.FILE_NAME).readText().contains("weather_bot"))
        val posts = BotPostStore(java.io.File(dir, BotPostStore.FILE_NAME), key)
        posts.put(BotPostExtra("m1", "G1", "b1", "Weather", """{"inline_keyboard":[[{"text":"Go","callback_data":"go"}]]}"""))
        assertEquals("Go", BotPostStore(java.io.File(dir, BotPostStore.FILE_NAME), key).get("m1")!!.keyboard!!.rows[0][0].text)
    }

    // ------------------------------------------------------------------ BotFather

    @Test
    fun `BotFather walks six steps with its rules`() {
        var d = BotFather.Draft()
        assertFalse(d.canLeave())
        d = d.copy(name = "Weather Paris").next()
        assertEquals(BotFather.Step.USERNAME, d.step)
        assertEquals("weather_paris_bot", d.shownUsername)
        assertFalse("not checked yet is fine, taken is not", d.copy(availability = BotFather.Availability.Taken(emptyList())).canLeave())
        d = d.copy(availability = BotFather.Availability.LooksFree).next()
        assertEquals(BotFather.Step.PROFILE, d.step)
        d = d.copy(about = "x".repeat(121))
        assertFalse(d.canLeave())
        d = d.copy(about = "Forecasts for Paris").next()
        d = d.toggle(BotFather.SUGGESTED[0]).toggle(BotFather.SUGGESTED[1]).withTemplate(BotFather.Template.POLL)
        assertEquals(listOf("start", "help", "poll", "results"), d.commands.map { it.name })
        assertEquals("🗳️", d.emoji)
        assertEquals(listOf("help", "poll", "results"), d.toggle(BotFather.SUGGESTED[0]).commands.map { it.name })
        val bot = d.toBot()
        assertEquals("weather_paris_bot", bot.username)
        assertTrue(bot.isPublic)
        assertEquals(bot.token, d.copy(bot = bot).toBot().token)   // a retry keeps the token
        val own = setOf("me")
        val found = { id: String -> BotPlatform.Lookup.Found(PublicBotProfile(id, "weather_paris_bot", "W")) }
        assertTrue(BotFather.availabilityOf("weather_paris_bot", found("other"), own) is BotFather.Availability.Taken)
        assertEquals(BotFather.Availability.Mine, BotFather.availabilityOf("weather_paris_bot", found("me"), own))
        assertEquals(BotFather.Availability.LooksFree, BotFather.availabilityOf("weather_paris_bot", BotPlatform.Lookup.NotFound, own))
        assertEquals(BotFather.Availability.LooksFree, BotFather.availabilityOf("weather_paris_bot", BotPlatform.UsernameCheck.Available))
        assertTrue(BotFather.availabilityOf("weather_paris_bot", BotPlatform.UsernameCheck.Taken) is BotFather.Availability.Taken)
        assertEquals(BotFather.Availability.Unknown, BotFather.availabilityOf("weather_paris_bot", BotPlatform.UsernameCheck.Failed("x")))
        assertTrue(BotFather.availabilityOf("weather", BotPlatform.Lookup.NotFound, own) is BotFather.Availability.Invalid)
        assertTrue(BotFather.curlSnippet("https://oshi-messenger.com", "TKN").contains("https://oshi-messenger.com/api/bot/send"))
        assertTrue(BotFather.pythonSnippet("https://oshi-messenger.com", "TKN").contains("getUpdates?token={TOKEN}"))
    }

    // ------------------------------------------------------------------ HTTP client vs a local server

    @Test
    fun `the HTTP platform speaks the contract routes and maps refusals`() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/bot/") { ex ->
            val path = ex.requestURI.path
            val q = ex.requestURI.rawQuery ?: ""
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += "${ex.requestMethod} $path ${ex.requestHeaders.getFirst("x-oshi-signature") ?: "-"} $q $body"
            val (status, out) = when (path) {
                "/api/bot/public" -> if (q.contains("weather_bot")) 200 to """{"success":true,"bot":{"botId":"w1","username":"weather_bot","name":"Weather","public":true}}"""
                    else 404 to """{"success":false,"error":"not_found"}"""
                "/api/bot/search" -> 200 to """{"success":true,"bots":[{"botId":"w1","username":"weather_bot","name":"Weather","public":true},{"botId":"p","username":"priv_bot","public":false}]}"""
                "/api/bot/commands" -> 200 to """{"success":true,"commands":[{"command":"now","description":"Now"}]}"""
                "/api/bot/setMyProfile" -> when {
                    body.contains("taken_bot") -> 409 to """{"success":false,"error":"username_taken"}"""
                    else -> 200 to """{"success":true,"bot":{"botId":"w1","username":"weather_bot","name":"Weather"}}"""
                }
                "/api/bot/dispatch" -> when {
                    body.contains("\"groupId\":\"nope\"") -> 403 to """{"success":false}"""
                    body.contains("@other_bot") -> 400 to """{"success":false,"error":"wrong_bot"}"""
                    else -> 200 to """{"success":true,"update_id":1,"callback_query_id":"cq1"}"""
                }
                "/api/bot/revokeToken" -> 200 to """{"success":true,"token":"fresh","botId":"w1"}"""
                "/api/bot/usernameAvailable" -> 200 to (if (q.contains("taken_bot")) """{"success":true,"available":false,"reason":"taken"}""" else """{"success":true,"available":true}""")
                "/api/bot/addToGroup" -> if (ex.requestHeaders.getFirst("x-oshi-signature") == null) 401 to """{"success":false,"error":"signature_required"}"""
                    else 200 to """{"success":true}"""
                else -> 404 to "{}"
            }
            val bytes = out.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = HttpBotPlatform("http://127.0.0.1:${server.address.port}", sign = { m, p, _ -> mapOf("x-oshi-signature" to "$m:$p") })
            assertEquals("weather_bot", (api.byUsername("@Weather_Bot") as BotPlatform.Lookup.Found).bot.username)
            assertEquals(BotPlatform.Lookup.NotFound, api.byUsername("ghost_bot"))
            assertEquals(listOf("w1"), api.search("@wea").getOrThrow().map { it.botId })   // non-public dropped
            assertTrue(seen.last().contains("q=wea"))
            assertEquals(listOf(BotCommand("now", "Now")), api.commands("w1", "KEY+/=", "G1").getOrThrow())
            assertTrue(seen.last().startsWith("GET /api/bot/commands GET:/api/bot/commands"))
            assertTrue(seen.last().contains("userPublicKey=KEY%2B%2F%3D"))
            assertEquals(BotPlatform.ProfileSave.UsernameTaken, api.setMyProfile("t", BotPlatform.ProfileEdit(username = "taken_bot")))
            assertTrue(api.setMyProfile("t", BotPlatform.ProfileEdit(about = "a", public = true)) is BotPlatform.ProfileSave.Saved)
            assertEquals(BotPlatform.Dispatch.Sent("cq1"), api.dispatchCallback("w1", "G1", "K", "v:yes", "m1"))
            assertTrue(seen.last().startsWith("POST /api/bot/dispatch POST:/api/bot/dispatch"))
            assertTrue(seen.last().contains("\"kind\":\"callback_query\""))
            assertEquals(BotPlatform.Dispatch.NotAMember, api.dispatchCommand("w1", "nope", "K", "/now", "m"))
            assertEquals(BotPlatform.Dispatch.WrongBot, api.dispatchCommand("w1", "G1", "K", "/now@other_bot", "m"))
            assertEquals(BotPlatform.Revoked("fresh", "w1"), api.revokeToken("old").getOrThrow())
            assertEquals(BotPlatform.UsernameCheck.Taken, api.usernameAvailable("taken_bot"))
            assertEquals(BotPlatform.UsernameCheck.Available, api.usernameAvailable("free_bot", token = "t"))
            assertTrue(seen.last().contains("token=t"))
            assertTrue(api.addToGroup("w1", "G1", "channel", listOf("K"), BotPermissions(canPin = true), "K").isSuccess)
            assertTrue(seen.last().startsWith("POST /api/bot/addToGroup POST:/api/bot/addToGroup"))
            assertTrue(seen.last().contains("\"kind\":\"channel\"") && seen.last().contains("\"canPin\":true") && !seen.last().contains("\"name\""))
            assertTrue(HttpBotPlatform("http://127.0.0.1:${server.address.port}").addToGroup("w1", "G1", "group", listOf("K"), BotPermissions(), "K").isFailure)
            assertTrue(HttpBotPlatform("http://127.0.0.1:1").byUsername("weather_bot") is BotPlatform.Lookup.Failed)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `dispatch is signed, a 401 signature_required is retried once with a fresh signature, a 403 forbidden is not`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val stamps = Collections.synchronizedList(mutableListOf<String>())
        var mode = "401-then-ok"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/bot/dispatch") { ex ->
            ex.requestBody.readBytes()
            stamps += ex.requestHeaders.getFirst("x-oshi-timestamp") ?: "-"
            val n = calls.incrementAndGet()
            val (status, out) = when {
                mode == "forbidden" -> 403 to """{"success":false,"error":"forbidden"}"""
                n == 1 -> 401 to """{"success":false,"error":"signature_required"}"""
                else -> 200 to """{"success":true}"""
            }
            val bytes = out.toByteArray(); ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val seq = java.util.concurrent.atomic.AtomicInteger()
            val api = HttpBotPlatform("http://127.0.0.1:${server.address.port}", sign = { _, _, _ -> mapOf("x-oshi-timestamp" to "t" + seq.incrementAndGet()) })
            assertEquals(BotPlatform.Dispatch.Sent(null), api.dispatchCommand("b", "g", "K", "/x", "m"))
            assertEquals(2, calls.get())
            assertEquals(listOf("t1", "t2"), stamps.toList())
            mode = "forbidden"; calls.set(0)
            assertEquals(BotPlatform.Dispatch.Failed("forbidden"), api.dispatchCallback("b", "g", "K", "d", "m"))
            assertEquals(1, calls.get())
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------------------ client wiring with a fake

    private class FakePlatform : BotPlatform {
        val dispatched = mutableListOf<String>()
        val added = mutableListOf<Pair<String, BotPermissions>>()
        override fun byUsername(username: String) = BotPlatform.Lookup.NotFound
        override fun byId(botId: String) = BotPlatform.Lookup.NotFound
        override fun search(query: String) = Result.success(emptyList<PublicBotProfile>())
        override fun commands(botId: String, userPublicKey: String, groupId: String?) = Result.success(listOf(BotCommand("quiz", "")))
        override fun setMyProfile(token: String, edit: BotPlatform.ProfileEdit) = BotPlatform.ProfileSave.Saved(null)
        override fun dispatchCommand(botId: String, groupId: String, fromPublicKey: String, text: String, messageId: String): BotPlatform.Dispatch {
            dispatched += "$botId $groupId $text"; return BotPlatform.Dispatch.Sent()
        }
        override fun dispatchCallback(botId: String, groupId: String, fromPublicKey: String, callbackData: String, messageId: String): BotPlatform.Dispatch {
            dispatched += "cb $botId $callbackData $messageId"; return BotPlatform.Dispatch.Sent("cq9")
        }
        override fun addToGroup(botId: String, groupId: String, kind: String, memberKeys: List<String>, permissions: BotPermissions, adderPublicKey: String): Result<Unit> {
            added += botId to permissions; kinds += kind; return Result.success(Unit)
        }
        val kinds = mutableListOf<String>()
        override fun removeFromGroup(botId: String, groupId: String, adderPublicKey: String) = Result.success(Unit)
        override fun revokeToken(token: String) = Result.success(BotPlatform.Revoked("new-token", ""))
        override fun usernameAvailable(username: String, token: String?) = BotPlatform.UsernameCheck.Available
    }

    @Test
    fun `the client adds bots, dispatches group commands and applies a bot's own edits`() = withTempDir { dir ->
        val c = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = "t", log = {})
        try {
            val fake = FakePlatform()
            c.botPlatform = fake
            val now = System.currentTimeMillis()
            val gid = "11111111-2222-3333-4444-555555555555"
            c.groups.put(GroupDefinition(gid, "Channel", GroupType.ADMIN_ONLY, c.address,
                listOf(GroupMember(c.address, joinedAtUnixMillis = now, isAdmin = true), GroupMember("bWVtYmVyLWtleQ==", joinedAtUnixMillis = now)), now, now))

            // Add someone else's bot as admin with chosen permissions; the roster learns it.
            val quiz = PublicBotProfile("q1", "quiz_bot", "Quiz", commands = listOf(BotCommand("quiz", "")))
            val perms = BotPermissions(canPost = true, canEdit = true, canDelete = true, canPin = false)
            c.addBotToGroup(quiz, gid, perms).getOrThrow()
            assertEquals(listOf("q1" to perms), fake.added)
            assertEquals(listOf("channel"), fake.kinds)
            assertEquals(perms, c.groupBots.find(gid, "q1")!!.permissions)
            assertTrue(c.addBotToGroup(quiz, "unknown-group", perms).isFailure)

            // /quiz goes to the quiz bot only, first line only; ordinary text goes nowhere.
            assertEquals(listOf("q1"), c.dispatchGroupCommand(gid, "/quiz now\nrest", "m1").map { it.botId })
            assertEquals("q1 $gid /quiz now", fake.dispatched.last())
            assertTrue(c.dispatchGroupCommand(gid, "hello", "m2").isEmpty())
            // The remote bot's commands feed the `/` menu.
            assertEquals(listOf("/quiz"), c.groupBotCommands.commandsFor(gid).map { it.second.slash })

            // A bot post, then that bot's edit and another bot's attempted delete.
            val convo = "bot!$gid"
            c.messages.append(Message(id = "p1", conversationId = convo, senderAddress = convo, recipientAddress = c.address,
                fromMe = false, content = "Vote!", sentAtMs = now, sentAtSource = com.oshi.desktop.store.TimestampSource.LOCAL_CLOCK))
            c.botPosts.put(BotPostExtra("p1", gid, "q1", "Quiz", """{"inline_keyboard":[[{"text":"A","callback_data":"a"}]]}"""))
            assertEquals(BotPlatform.Dispatch.Sent("cq9"), c.tapBotButton(c.botPosts.get("p1")!!, "a"))
            assertEquals("cb q1 a p1", fake.dispatched.last())
            c.applyBotPostOp(convo, BotPostOp.Delete("p1", "intruder"))
            assertNotNull(c.botPosts.get("p1"))
            c.applyBotPostOp(convo, BotPostOp.Edit("p1", "q1", "Votes: A 1", BotPostOp.Markup.Set("""{"inline_keyboard":[[{"text":"A (1)","callback_data":"a"}]]}""")))
            assertEquals("Votes: A 1", c.messages.message(convo, "p1")!!.content)
            assertEquals("A (1)", c.botPosts.get("p1")!!.keyboard!!.rows[0][0].text)
            c.applyBotPostOp(convo, BotPostOp.Edit("p1", "q1", null, BotPostOp.Markup.Remove))
            assertNull(c.botPosts.get("p1")!!.keyboard)
            assertEquals("Votes: A 1", c.messages.message(convo, "p1")!!.content)
            c.applyBotPostOp(convo, BotPostOp.Pin("p1", "q1", true))
            assertTrue(c.botPosts.get("p1")!!.pinned)
            c.applyBotPostOp(convo, BotPostOp.Delete("p1", "q1"))
            assertNull(c.botPosts.get("p1"))

            // BotFather /revoke on our own bot.
            val mine = OwnedBot(name = "Mine", username = "mine_bot")
            c.ownedBots.save(mine)
            val oldId = mine.botId
            assertEquals("new-token", c.revokeOwnedBotToken(mine).getOrThrow().token)
            assertEquals("new-token", c.ownedBots.get(mine.id)!!.token)
            // S3: the botId survives the revoke (never re-derived from the new token).
            assertEquals(oldId, c.ownedBots.get(mine.id)!!.botId)
        } finally {
            runCatching { c.close() }
        }
    }

    private fun withTempDir(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("bots-phase1").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Suppress("unused")
    private fun json(vararg rows: Pair<String, Any>) = JSONObject().apply { rows.forEach { put(it.first, it.second) } }.also { JSONArray() }
}
