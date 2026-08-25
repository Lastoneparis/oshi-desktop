package com.oshi.desktop.bot

import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The pending queue as the BOT transport, driven against a behaving in-process server —
 * PARITY.md row 0.26.
 *
 * These are transport properties, so they are checked by DRIVING the protocol rather than
 * by inspecting a URL string: what the server actually stores, what a second device still
 * sees after the first acks, and what a failed poll is distinguishable from.
 */
class BotQueueClientTest {

    private val server = BotQueueServer()

    /**
     * A key with `+` and `/` in it, on purpose. A base64 X25519 public key is 44 characters
     * and roughly half of them contain one — this is the case the path encoding exists for.
     */
    private val myKey = "ab+cd/efGHIJKLMNOPQRSTUVWXYZ0123456789+/xyz="
    private val deviceId = "device-A"

    private fun client(device: String = deviceId) =
        BotQueueClient(myKey, device, baseUrl = server.baseUrl)

    @After
    fun tearDown() = server.close()

    /**
     * The path key is base64url-unpadded and the server normalises it back to the SAME slot
     * the raw key names — while a percent-encoded key would name a different one.
     *
     * PARITY.md row 0.24's warning applied to the queue: there is no error and no 404 for
     * getting it wrong, both spellings "work", and the two devices simply never see each
     * other's messages. The test asserts the bytes on the wire, then asserts that the entry
     * seeded under the RAW key is what came back — i.e. that the two met in the server's
     * normaliser.
     */
    @Test
    fun `the path key is base64url and lands in the same slot as the raw key`() {
        server.seedRaw(myKey, botEnvelope("11111111-2222-3333-4444-555555555555"))

        val got = client().pending().getOrThrow()
        assertEquals(1, got.size)

        val path = server.requestedPaths.last()
        assertTrue("must be base64url: $path", path.contains("ab-cd_ef"))
        assertFalse("must NOT be percent-encoded: $path", path.contains("%2B") || path.contains("%2F"))
        assertFalse("padding is stripped: $path", path.substringBefore("?").contains("="))
    }

    /**
     * `/api/pending` is one queue carrying different kinds of thing, and the desktop has to
     * tell them apart.
     *
     * This is the property row 0.26 rides on: a bot message is not a content identifier, it
     * is the whole message sitting in the identifier's slot. A client that assumed every
     * entry was a CID would never render one.
     */
    @Test
    fun `a queue mixes CIDs and bot envelopes and both are classified`() {
        server.seedCid(myKey, "QmAAA")
        server.seedRaw(myKey, botEnvelope("11111111-2222-3333-4444-555555555555"))
        server.seedCid(myKey, "bafyBBB")
        server.seedRaw(myKey, "something-else")

        val got = client().pending().getOrThrow()
        assertEquals(4, got.size)
        assertTrue(got[0] is BotQueueClient.QueueEntry.Cid)
        assertTrue(got[1] is BotQueueClient.QueueEntry.Bot)
        assertTrue(got[2] is BotQueueClient.QueueEntry.Cid)
        assertTrue(
            "an unclassifiable entry is KEPT — dropping it would poll it forever",
            got[3] is BotQueueClient.QueueEntry.Unknown,
        )
    }

    /**
     * A failed poll is a `failure`, never an empty list.
     *
     * Row 0.10's rule, and it bites harder here: the caller's next action after an empty
     * queue is to do nothing at all, so an error rendered as "nothing waiting" never
     * surfaces anywhere.
     */
    @Test
    fun `a server error is a failure and not an empty queue`() {
        server.seedCid(myKey, "QmX")
        server.failPendingRemaining = 1

        assertTrue("a 500 must be a failure", client().pending().isFailure)
        assertEquals(1, client().pending().getOrThrow().size)
    }

    /**
     * An ack is per-device: it does NOT remove the entry for the account's other devices.
     *
     * `msg.receivedBy.add(deviceId)` (`message_queue_server.js:378`) — the multi-device
     * rule, and the reason [BotQueueClient.ack] carries a device id at all.
     */
    @Test
    fun `an ack from one device leaves the entry for another`() {
        server.seedCid(myKey, "QmShared")

        client("device-A").ack(BotQueueClient.QueueEntry.Cid("QmShared")).getOrThrow()
        assertTrue("device A is done", client("device-A").pending().getOrThrow().isEmpty())
        assertEquals("device B has not seen it", 1, client("device-B").pending().getOrThrow().size)
    }

    /**
     * The bot ack goes by the SHORT message id, and it clears an envelope far too long to
     * put in a URL path.
     *
     * The envelope here is ~700 KB — a photo-carrying bot message, which is the exact case
     * `/api/bot-received` was added for. Acking it by URL path would exceed the request-line
     * limit and fail silently, and the envelope would come back on every single poll.
     */
    @Test
    fun `a large bot envelope is acked by its short message id`() {
        val messageId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val huge = "bot:$messageId:" + b64(ByteArray(512 * 1024) { 42 })
        assertTrue("fixture must be URL-hostile", huge.length > 600_000)
        server.seedRaw(myKey, huge)

        assertEquals(1, client().pending().getOrThrow().size)
        client().ackBot(messageId).getOrThrow()

        // Captured HERE, before the verification poll appends its own path — otherwise
        // `last()` is the `/api/pending` request and the assertion below tests nothing.
        val path = server.requestedPaths.last()
        assertTrue("the ack must be the short route, got: $path", path.startsWith("/api/bot-received/"))
        assertTrue("the envelope must not be in the URL (${path.length} chars)", path.length < 200)

        assertTrue("the short ack must clear it", client().pending().getOrThrow().isEmpty())
    }

    /**
     * And the URL-path ack REFUSES a bot envelope rather than silently failing on it.
     *
     * Android still acks bot envelopes this way (`VPSClient.kt:725-747`) and still has the
     * silent-failure bug. Typing the parameter is what stops a desktop caller reaching it.
     */
    @Test
    fun `the url-path ack refuses a bot envelope`() {
        val entry = BotQueueClient.QueueEntry.Bot(botEnvelope("11111111-2222-3333-4444-555555555555"))
        try {
            client().ack(entry)
            fail("a bot envelope must not be acked through the URL-path DELETE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ackBot"))
        }
    }

    private fun botEnvelope(messageId: String): String {
        val payload = """{"type":"bot_message","messageId":"$messageId","botToken":"a1b2c3d4...",""" +
            """"botName":"B","groupId":"G","groupName":"N","content":"hi",""" +
            """"timestamp":"2026-08-25T14:03:11.123Z"}"""
        return "bot:$messageId:" + b64(payload.toByteArray(Charsets.UTF_8))
    }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
}
