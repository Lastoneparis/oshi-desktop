package com.oshi.desktop.bot

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The bot control API and the end-to-end lane it feeds — PARITY.md row 0.26.
 *
 * Driven against [BotQueueServer], which reproduces `message_queue_server.js`'s actual
 * behaviour: unconditional registration, case-insensitive group matching, a 403 that
 * carries `registeredGroups`, and — the property this row exists for — a bot send landing
 * in every member's PENDING QUEUE as a `bot:…` string beside the CIDs.
 */
class BotApiTest {

    private val server = BotQueueServer()
    private val api get() = BotApi(baseUrl = server.baseUrl)

    private val token = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val groupId = "A4B2C1D3-0000-0000-0000-000000000001"
    private val alice = "alice+key/AAAA=="
    private val bob = "bobbb+key/BBBB=="
    private val owner = "owner+key/OOOO=="

    private fun group(id: String = groupId, members: List<String> = listOf(alice, bob)) =
        BotApi.BotGroup(id, "Ops", members)

    @After
    fun tearDown() = server.close()

    // ============================================================ THE ROUND TRIP THAT
    // MATTERS

    /**
     * Register, send, and watch the message arrive **in the pending queue** of every member
     * — as a `bot:` envelope, not as a CID, and decodable by [BotEnvelope].
     *
     * This is the whole of row 0.26 in one test: the bot lane is the legacy queue, and a
     * desktop that implements V2 perfectly still needs [BotQueueClient] to see any of it.
     */
    @Test
    fun `a bot send lands in every member's pending queue as a decodable envelope`() {
        api.register(token, "Telegram Bridge", owner, listOf(group())).getOrThrow()

        val result = api.send(token, groupId, content = "build finished").getOrThrow()
        assertEquals(2, result.delivered)
        assertEquals(2, result.totalMembers)
        assertEquals("Ops", result.groupName)

        for (member in listOf(alice, bob)) {
            val queue = BotQueueClient(member, "device-1", baseUrl = server.baseUrl)
                .pending().getOrThrow()
            assertEquals(1, queue.size)
            val entry = queue.single()
            assertTrue("a bot message occupies the CID slot", entry is BotQueueClient.QueueEntry.Bot)

            val msg = BotEnvelope.parse(entry.raw)
            assertEquals("build finished", msg.content)
            assertEquals("Telegram Bridge", msg.botName)
            assertEquals(groupId, msg.groupId)
            assertEquals("bot:${token.take(8)}", msg.senderAddress)
            assertNotNull("the server's ISO-8601 stamp parses", msg.unixMillis)

            // And it acks by the short id, clearing the queue.
            val client = BotQueueClient(member, "device-1", baseUrl = server.baseUrl)
            client.ackBot(msg.envelopeMessageId).getOrThrow()
            assertTrue(client.pending().getOrThrow().isEmpty())
        }
    }

    /**
     * **The content is readable off the wire by anyone with the recipient's public key.**
     *
     * Not a hypothetical and not softened: the entry is base64 of a JSON object, and
     * `/api/pending/{publicKey}` authenticates nothing. This test performs the attack — poll
     * a queue belonging to somebody else, using only their public key, and read the message
     * body — because a security property that important should be demonstrated rather than
     * asserted in a comment. The server's own source says the same thing at
     * `message_queue_server.js:754-767`.
     */
    @Test
    fun `bot content is readable by anyone holding the recipient's public key`() {
        api.register(token, "Bridge", owner, listOf(group())).getOrThrow()
        api.send(token, groupId, content = "the merger closes friday").getOrThrow()

        // A third party. No token, no private key, no credential of any kind — just Alice's
        // PUBLIC key, which a QR code hands out (PARITY.md row 0.22).
        val eavesdropper = BotQueueClient(alice, "not-alices-device", baseUrl = server.baseUrl)
        val raw = eavesdropper.pending().getOrThrow().single().raw
        val json = String(Base64.getDecoder().decode(raw.split(":", limit = 3)[2]), Charsets.UTF_8)

        assertTrue("plaintext, in a JSON object", json.contains("\"the merger closes friday\""))
        assertTrue("and the group NAME too", json.contains("\"groupName\":\"Ops\""))
    }

    // ============================================================ THE ERROR SHAPES

    /**
     * A 403 carries the groups the bot IS registered in, and the client keeps that body.
     *
     * Discarding it would turn "wrong group, here are the right ones" into a bare 403 — the
     * only actionable part of the answer is in the body.
     */
    @Test
    fun `a send to an unregistered group is a 403 that names the registered ones`() {
        api.register(token, "Bridge", owner, listOf(group())).getOrThrow()

        val r = api.send(token, "FFFFFFFF-0000-0000-0000-000000000000", content = "hi")
        val e = r.exceptionOrNull() as BotApi.BotApiException
        assertEquals(403, e.status)
        assertTrue(e.serverError.contains("not assigned to this group"))
        val reg = JSONObject(e.body).getJSONArray("registeredGroups")
        assertEquals(groupId, reg.getJSONObject(0).getString("id"))
    }

    /** An unknown token is a 401, distinguishable from the 403 above. */
    @Test
    fun `an unregistered token is a 401`() {
        val e = api.send("deadbeef", groupId, content = "hi").exceptionOrNull() as BotApi.BotApiException
        assertEquals(401, e.status)
    }

    /**
     * The server matches `groupId` case-INsensitively (`message_queue_server.js:573`).
     *
     * Which matters because iOS stores group ids as uppercase `UUID.uuidString` and
     * `java.util.UUID.toString()` is lowercase — the same split that breaks the legacy group
     * KEY derivation (row 0.23). Here the server absorbs it; there, nothing does.
     */
    @Test
    fun `group id matching is case insensitive on the server`() {
        api.register(token, "Bridge", owner, listOf(group())).getOrThrow()
        assertTrue(api.send(token, groupId.lowercase(), content = "hi").isSuccess)
    }

    // ============================================================ THE CLIENT-SIDE GUARDS

    /**
     * `update-groups` REPLACES the roster; the method is named for what it does.
     *
     * The 6 300-403 incident (`BotManager.swift:916-920`,
     * `__BOT_PARTIAL_SYNC_2026_08_22__`) is a partial roster uploaded through a replacement
     * endpoint. This asserts the semantics so the name and the behaviour cannot drift apart.
     */
    @Test
    fun `replaceGroups replaces rather than merges`() {
        api.register(token, "Bridge", owner, listOf(group(), group("BBBB-2", listOf(alice)))).getOrThrow()
        assertEquals(2, server.registeredGroups(token)!!.length())

        api.replaceGroups(token, listOf(group())).getOrThrow()
        val after: JSONArray = server.registeredGroups(token)!!
        assertEquals("the second group is GONE, not merged", 1, after.length())
        assertEquals(groupId, after.getJSONObject(0).getString("id"))
    }

    /**
     * A group with no members, or an empty id, is refused before it reaches the wire.
     *
     * Both would register something that silently delivers to nobody: an empty-member group
     * returns `delivered: 0` with an HTTP 200, which reads as success.
     */
    @Test
    fun `a group with no members or no id is refused client-side`() {
        expectIllegal("members") { api.register(token, "B", owner, listOf(group(members = emptyList()))) }
        expectIllegal("empty id") { api.register(token, "B", owner, listOf(group(id = ""))) }
    }

    /**
     * Outbound media validation mirrors the server's allow-list — and `image` is NOT on it,
     * even though [BotEnvelope.normaliseMediaType] accepts it on RECEIVE.
     *
     * Lenient inbound, strict outbound. Sending `image` is a guaranteed 400
     * (`message_queue_server.js:536-542`), so failing fast here saves a round trip and says
     * why.
     */
    @Test
    fun `image is accepted inbound and refused outbound`() {
        assertEquals("photo", BotEnvelope.normaliseMediaType("image"))
        expectIllegal("mediaType") {
            api.send(token, groupId, mediaType = "image",
                mediaDataBase64 = Base64.getEncoder().encodeToString(ByteArray(8)),
                mediaFileName = "cat.jpg")
        }
    }

    /** `mediaFileName` is required with media (`:544-549`). */
    @Test
    fun `media without a filename is refused`() {
        expectIllegal("mediaFileName") {
            api.send(token, groupId, mediaType = "photo",
                mediaDataBase64 = Base64.getEncoder().encodeToString(ByteArray(8)))
        }
    }

    /**
     * The 10 MB cap is measured on the DECODED length, without decoding.
     *
     * `Buffer.byteLength(mediaData, 'base64')` (`:545`) counts decoded bytes, so a base64
     * string is over the cap at ~13.3 MB of text. Computing it arithmetically is what stops
     * a 13 MB refusal from first materialising 10 MB of array — which is the point of a cap.
     */
    @Test
    fun `the media cap is measured on decoded bytes`() {
        val a = BotApi()
        assertEquals(0L, a.decodedLengthOfBase64(""))
        assertEquals(3L, a.decodedLengthOfBase64("AAAA"))
        assertEquals(1L, a.decodedLengthOfBase64("AA=="))
        assertEquals(2L, a.decodedLengthOfBase64("AAA="))
        assertEquals(BotApi.MAX_MEDIA_BYTES, a.decodedLengthOfBase64("A".repeat(4 * 1024 * 1024 * 10 / 3 + 1)))

        // 12 MB decoded → refused, and the argument never allocates 12 MB of bytes.
        val tooBig = "A".repeat(16 * 1024 * 1024)
        expectIllegal("over the") {
            api.send(token, groupId, mediaType = "photo", mediaDataBase64 = tooBig, mediaFileName = "big.jpg")
        }
    }

    /** Neither content nor media is refused (`:526`). */
    @Test
    fun `a send with neither content nor media is refused`() {
        expectIllegal("content and/or mediaData") { api.send(token, groupId) }
    }

    // ============================================================ DISCOVERY: THERE IS NONE

    /**
     * `list` returns exactly one bot — your own.
     *
     * Row 0.26's "how is a bot discovered" has an answer and it is "it is not". The endpoint
     * used to require no token and returned every bot on the platform; the fix comment
     * measures the leak at 43 bots (`message_queue_server.js:854-866`). A tokenless call is
     * now a 401, which is what the second half of this test asserts.
     */
    @Test
    fun `list returns only your own bot and a tokenless call is refused`() {
        api.register(token, "Bridge", owner, listOf(group())).getOrThrow()
        api.register("second-token", "Other Bot", "someone-else", listOf(group("XXXX-1", listOf(alice))))
            .getOrThrow()

        val mine = api.list(token).getOrThrow()
        assertEquals("a list of one, always", 1, mine.size)
        assertEquals("Bridge", mine.single().botName)
        assertEquals("${token.take(8)}...", mine.single().tokenPrefix)

        expectIllegal("bot token is required") { api.list("") }
        val e = api.list("not-a-real-token").exceptionOrNull() as BotApi.BotApiException
        assertEquals(401, e.status)
    }

    /**
     * Registration overwrites unconditionally — there is no ownership check.
     *
     * `botRegistry.set(token, record)` (`:497`). Anyone who learns a token can replace that
     * bot's name and its entire roster. Demonstrated rather than asserted in prose, for the
     * same reason as the eavesdropping test above.
     */
    @Test
    fun `anyone holding a token can overwrite the registration`() {
        api.register(token, "Bridge", owner, listOf(group())).getOrThrow()

        // A different party, same token, a roster of their choosing.
        api.register(token, "Not The Bridge", "attacker-key", listOf(group("ZZZZ-9", listOf(bob))))
            .getOrThrow()

        val after = api.list(token).getOrThrow().single()
        assertEquals("Not The Bridge", after.botName)
        assertEquals(1, after.groups)
        assertFalse("the original group is gone", api.send(token, groupId, content = "x").isSuccess)
    }

    @Test
    fun `unregister removes the bot and a second call is a 404`() {
        api.register(token, "Bridge", owner, listOf(group())).getOrThrow()
        assertTrue(api.unregister(token).isSuccess)
        assertFalse(server.isRegistered(token))
        val e = api.unregister(token).exceptionOrNull() as BotApi.BotApiException
        assertEquals(404, e.status)
    }

    private fun expectIllegal(expectInMessage: String, block: () -> Unit) {
        try {
            block()
            fail("must be refused; expected a message mentioning \"$expectInMessage\"")
        } catch (e: IllegalArgumentException) {
            assertTrue("message should name the problem, got: ${e.message}",
                e.message!!.contains(expectInMessage))
        }
    }
}
