package com.oshi.desktop.msg

import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.MessageStore
import com.oshi.desktop.store.TimestampSource
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 0.18 — where a parsed payload meets local state.
 *
 * [ControlPayloadWireFormatTest] proves the bytes; this proves the three rules that need a
 * store to state at all: the C-MSG-4 ownership check, the monotonic receipt rule, and
 * "nothing silent is ever stored."
 */
class ControlPayloadRouterTest {

    private val dir: File = Files.createTempDirectory("oshi-msg-router-test").toFile()
    private val store = MessageStore(dir)
    private val router = ControlPayloadRouter(store)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val me = "ME/desktop+key/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa="
    private val peer = "PEER/phone+key/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb="
    private val conv = peer
    private val baseMs = 1_770_000_000_000L

    private fun put(
        id: String,
        fromMe: Boolean,
        content: String? = "hello",
        status: DeliveryStatus = DeliveryStatus.SENT,
    ): Message {
        val m = Message(
            id = id,
            conversationId = conv,
            senderAddress = if (fromMe) me else peer,
            recipientAddress = if (fromMe) peer else me,
            fromMe = fromMe,
            content = content,
            sentAtMs = baseMs,
            sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            deliveryStatus = status,
        )
        store.append(m)
        return m
    }

    private fun apply(plaintext: String, from: String = peer) = router.apply(conv, from, plaintext).second

    // ================================================================== classification

    /**
     * PLAN.md §4.5. `🔧ACTION🔧` carries four operations behind ONE sentinel, so a router
     * that branched on the prefix would apply a delete for every pin.
     */
    @Test
    fun `all four action types classify to their own operation behind one sentinel`() {
        for (t in MessageActionPayload.ActionType.entries) {
            val content = if (t == MessageActionPayload.ActionType.EDIT_MESSAGE) "x" else null
            val e = router.classify(MessageActionPayload.encode(t, "m", baseMs, content))
            assertTrue(e is ControlEvent.Action)
            assertEquals(t, (e as ControlEvent.Action).payload.actionType)
        }
    }

    /** "Another row's payload" and "the user typed this" are different answers. */
    @Test
    fun `a foreign sentinel is named, not mistaken for prose`() {
        val e = router.classify(ControlPrefix.LOCATION + "{\"isLive\":true}")
        assertTrue(e is ControlEvent.Foreign)
        assertEquals(ControlPrefix.LOCATION, (e as ControlEvent.Foreign).prefix)
        assertEquals(ControlPrefix.Kind.RENDERED, e.kind)

        assertTrue(router.classify("just a normal message") is ControlEvent.Prose)
        assertTrue(router.classify("we should add a 📍LOCATION📍 marker") is ControlEvent.Prose)
    }

    /**
     * A silent payload that fails to parse produces no error a user ever sees — that is how
     * Android's `{"isTyping":true}` ping looked fixed for a whole release. Naming the case is
     * what lets a caller count it.
     */
    @Test
    fun `one of our sentinels with an unusable body is reported, not swallowed as prose`() {
        val e = router.classify("⌨️TYPING⌨️{\"isTyping\":true}")
        assertTrue("expected Unparseable, got $e", e is ControlEvent.Unparseable)
        assertEquals(ControlPrefix.TYPING_IOS, (e as ControlEvent.Unparseable).prefix)
        assertEquals(ControlPayloadRouter.Outcome.UNPARSEABLE, apply("⌨️TYPING⌨️{\"isTyping\":true}"))
    }

    // ================================================================ THE OWNERSHIP RULE

    /**
     * C-MSG-4, the security guard of this row. iOS refuses a delete-for-everyone whose sender
     * does not own the target: "without this ownership check an attacker can remotely delete
     * ANY message." Android: "a crafted 🔧ACTION🔧 could rewrite OUR OWN sent messages in our
     * thread."
     */
    @Test
    fun `a peer cannot delete a message we sent`() {
        put("mine-1", fromMe = true, content = "my words")
        val payload = MessageActionPayload.encode(
            MessageActionPayload.ActionType.DELETE_FOR_EVERYONE, "mine-1", baseMs,
        )
        assertEquals(ControlPayloadRouter.Outcome.REJECTED_NOT_OWNER, apply(payload, from = peer))
        val after = store.message(conv, "mine-1")!!
        assertFalse("C-MSG-4: the message must survive", after.isDeletedForEveryone)
        assertEquals("my words", after.content)
    }

    @Test
    fun `a peer cannot edit a message we sent`() {
        put("mine-2", fromMe = true, content = "my words")
        val payload = MessageActionPayload.encode(
            MessageActionPayload.ActionType.EDIT_MESSAGE, "mine-2", baseMs, "words the attacker prefers",
        )
        assertEquals(ControlPayloadRouter.Outcome.REJECTED_NOT_OWNER, apply(payload, from = peer))
        assertEquals("my words", store.message(conv, "mine-2")!!.content)
    }

    /** The same payload, from the message's actual sender, must go through. */
    @Test
    fun `the owner can delete and edit their own message`() {
        put("theirs-1", fromMe = false, content = "oops")
        put("theirs-2", fromMe = false, content = "teh")

        assertEquals(
            ControlPayloadRouter.Outcome.APPLIED,
            apply(MessageActionPayload.encode(
                MessageActionPayload.ActionType.DELETE_FOR_EVERYONE, "theirs-1", baseMs), from = peer),
        )
        assertTrue(store.message(conv, "theirs-1")!!.isDeletedForEveryone)
        assertNull("the bytes go, the row stays", store.message(conv, "theirs-1")!!.content)

        assertEquals(
            ControlPayloadRouter.Outcome.APPLIED,
            apply(MessageActionPayload.encode(
                MessageActionPayload.ActionType.EDIT_MESSAGE, "theirs-2", baseMs, "the"), from = peer),
        )
        assertEquals("the", store.message(conv, "theirs-2")!!.content)
        assertEquals(baseMs, store.message(conv, "theirs-2")!!.editedAtMs)
    }

    /**
     * The comparison is byte equality on the exact stored spelling — deliberately NOT run
     * through `canonicalIdentity` / `normalizeKey` (PLAN.md §4.9), which is also what iOS's
     * `handleMessageAction` does with its bare `==`. Failing closed on a padding difference
     * costs a delete; matching loosely costs a message.
     */
    @Test
    fun `the ownership check is byte equality, not a normalised identity`() {
        put("theirs-3", fromMe = false)
        val unpadded = peer.trimEnd('=')
        assertNotEquals(peer, unpadded)
        assertEquals(
            "a loosened identity check is exactly what §4.9 forbids — fail closed here",
            ControlPayloadRouter.Outcome.REJECTED_NOT_OWNER,
            apply(MessageActionPayload.encode(
                MessageActionPayload.ActionType.DELETE_FOR_EVERYONE, "theirs-3", baseMs), from = unpadded),
        )
    }

    @Test
    fun `an action naming a message we never received changes nothing`() {
        assertEquals(
            ControlPayloadRouter.Outcome.TARGET_UNKNOWN,
            apply(MessageActionPayload.encode(
                MessageActionPayload.ActionType.DELETE_FOR_EVERYONE, "never-seen", baseMs)),
        )
    }

    /** iOS keeps a pinned map we have nowhere to write. Reported, not silently dropped. */
    @Test
    fun `a pin is understood and reported as not applied`() {
        put("theirs-4", fromMe = false)
        assertEquals(
            ControlPayloadRouter.Outcome.PIN_NOT_APPLIED,
            apply(MessageActionPayload.encode(MessageActionPayload.ActionType.PIN_MESSAGE, "theirs-4", baseMs)),
        )
    }

    /**
     * A peer that put Unix millis in the Apple-epoch field (three shipped Android emitters
     * did) still gets its edit applied — with a local stamp instead of a year-55 000 one.
     * Dropping the action over a decoration is the failure this row exists to avoid.
     */
    @Test
    fun `an edit with a mis-encoded timestamp still applies`() {
        put("theirs-5", fromMe = false, content = "before")
        val bad = "🔧ACTION🔧{\"actionType\":\"editMessage\",\"targetMessageId\":\"theirs-5\"," +
            "\"newContent\":\"after\",\"timestamp\":1770000000000}"
        assertEquals(ControlPayloadRouter.Outcome.APPLIED, apply(bad))
        val m = store.message(conv, "theirs-5")!!
        assertEquals("after", m.content)
        assertTrue(
            "the year-55000 value must not reach the store",
            m.editedAtMs!! < 4_102_444_800_000L,
        )
    }

    // ================================================================ receipts

    @Test
    fun `a delivery receipt advances exactly the message it names`() {
        put("a", fromMe = true, status = DeliveryStatus.SENT)
        put("b", fromMe = true, status = DeliveryStatus.SENT)
        assertEquals(ControlPayloadRouter.Outcome.APPLIED, apply(DeliveryReceipt.encode("b")))
        assertEquals(DeliveryStatus.SENT, store.message(conv, "a")!!.deliveryStatus)
        assertEquals(DeliveryStatus.DELIVERED, store.message(conv, "b")!!.deliveryStatus)
    }

    @Test
    fun `a repeated delivery receipt is a no-change, not a rewrite`() {
        put("a", fromMe = true)
        assertEquals(ControlPayloadRouter.Outcome.APPLIED, apply(DeliveryReceipt.encode("a")))
        assertEquals(ControlPayloadRouter.Outcome.NO_CHANGE, apply(DeliveryReceipt.encode("a")))
    }

    /** Neither platform sends an id with a read receipt — it is a whole-conversation event. */
    @Test
    fun `a read receipt marks every message we sent, and nothing the peer sent`() {
        put("mine-a", fromMe = true, status = DeliveryStatus.SENT)
        put("mine-b", fromMe = true, status = DeliveryStatus.DELIVERED)
        put("theirs", fromMe = false, status = DeliveryStatus.DELIVERED)

        assertEquals(ControlPayloadRouter.Outcome.APPLIED, apply(ReadReceipt.encode(peer, baseMs)))
        assertEquals(DeliveryStatus.READ, store.message(conv, "mine-a")!!.deliveryStatus)
        assertEquals(DeliveryStatus.READ, store.message(conv, "mine-b")!!.deliveryStatus)
        assertEquals(DeliveryStatus.DELIVERED, store.message(conv, "theirs")!!.deliveryStatus)
    }

    /**
     * The mesh and the relay race, so a `delivered` receipt can land after a `read` one for
     * the same message. Without the monotonic rule that un-reads a message the user opened.
     */
    @Test
    fun `a late delivery receipt cannot un-read a message`() {
        put("a", fromMe = true, status = DeliveryStatus.SENT)
        apply(ReadReceipt.encode(peer, baseMs))
        assertEquals(DeliveryStatus.READ, store.message(conv, "a")!!.deliveryStatus)
        assertEquals(ControlPayloadRouter.Outcome.NO_CHANGE, apply(DeliveryReceipt.encode("a")))
        assertEquals(DeliveryStatus.READ, store.message(conv, "a")!!.deliveryStatus)
    }

    @Test
    fun `a receipt cannot resurrect a failed send`() {
        put("dead", fromMe = true, status = DeliveryStatus.FAILED)
        assertEquals(ControlPayloadRouter.Outcome.NO_CHANGE, apply(DeliveryReceipt.encode("dead")))
        assertEquals(DeliveryStatus.FAILED, store.message(conv, "dead")!!.deliveryStatus)
    }

    /** iOS's bare read receipt is the shipped form — it must still do the work. */
    @Test
    fun `iOS's bare read receipt marks the conversation read`() {
        put("a", fromMe = true, status = DeliveryStatus.SENT)
        assertEquals(ControlPayloadRouter.Outcome.APPLIED, apply("📖READ_RECEIPT📖"))
        assertEquals(DeliveryStatus.READ, store.message(conv, "a")!!.deliveryStatus)
    }

    // ================================================================ reactions

    @Test
    fun `a reaction is stored against the transport sender, not the payload's claim`() {
        put("theirs", fromMe = false)
        val forged = "🔥REACTION🔥{\"messageId\":\"theirs\",\"emoji\":\"👍\"," +
            "\"senderPublicKey\":\"SOMEONE-ELSE\",\"senderName\":\"\",\"timestamp\":791692800,\"action\":\"add\"}"
        assertEquals(ControlPayloadRouter.Outcome.APPLIED, apply(forged, from = peer))
        assertEquals(
            "the reactor must be the authenticated envelope sender — a payload field is " +
                "attacker-chosen, and setReaction() clears that address's previous reaction",
            mapOf("👍" to setOf(peer)),
            store.message(conv, "theirs")!!.reactions,
        )
    }

    /** Reacting to someone else's message IS the feature — no ownership check here. */
    @Test
    fun `a peer may react to a message we sent`() {
        put("mine", fromMe = true)
        assertEquals(
            ControlPayloadRouter.Outcome.APPLIED,
            apply(ReactionPayload.encode("mine", "🔥", peer, isAdding = true, atUnixMillis = baseMs)),
        )
        assertEquals(mapOf("🔥" to setOf(peer)), store.message(conv, "mine")!!.reactions)
    }

    @Test
    fun `one reaction per person - a second emoji replaces the first`() {
        put("m", fromMe = false)
        apply(ReactionPayload.encode("m", "👍", peer, true, baseMs))
        apply(ReactionPayload.encode("m", "❤️", peer, true, baseMs))
        assertEquals(mapOf("❤️" to setOf(peer)), store.message(conv, "m")!!.reactions)
    }

    @Test
    fun `a removal clears it`() {
        put("m", fromMe = false)
        apply(ReactionPayload.encode("m", "👍", peer, true, baseMs))
        assertEquals(
            ControlPayloadRouter.Outcome.APPLIED,
            apply(ReactionPayload.encode("m", "👍", peer, isAdding = false, atUnixMillis = baseMs)),
        )
        assertEquals(emptyMap<String, Set<String>>(), store.message(conv, "m")!!.reactions)
    }

    // ================================================================ nothing silent is stored

    /**
     * A receipt, a typing ping and an action are `Kind.SILENT`. If any of them ever became a
     * row, the desktop would show protocol JSON in a bubble — the exact defect
     * `OSHIControlPayload` exists to prevent on both phones.
     */
    @Test
    fun `no control payload ever becomes a message row`() {
        put("m", fromMe = false, content = "real message")
        val before = store.messages(conv).size

        apply(DeliveryReceipt.encode("m"))
        apply(ReadReceipt.encode(peer, baseMs))
        apply(TypingPayload.encode(peer, true, baseMs))
        apply(ReactionPayload.encode("m", "👍", peer, true, baseMs))
        apply(MessageActionPayload.encode(MessageActionPayload.ActionType.EDIT_MESSAGE, "m", baseMs, "edited"))

        assertEquals("control traffic must never add a row", before, store.messages(conv).size)
        assertEquals("edited", store.message(conv, "m")!!.content)
    }

    @Test
    fun `a typing ping is parsed and deliberately not stored`() {
        val (event, outcome) = router.apply(conv, peer, TypingPayload.encode(peer, true, baseMs))
        assertTrue(event is ControlEvent.Typing)
        assertTrue((event as ControlEvent.Typing).payload.isTyping)
        assertEquals(ControlPayloadRouter.Outcome.TYPING_NOT_STORED, outcome)
        assertEquals(0, store.messages(conv).size)
    }

    @Test
    fun `ordinary prose is left entirely alone`() {
        val (event, outcome) = router.apply(conv, peer, "hello there")
        assertTrue(event is ControlEvent.Prose)
        assertEquals(ControlPayloadRouter.Outcome.NOT_MINE, outcome)
        assertEquals(0, store.messages(conv).size)
    }
}
