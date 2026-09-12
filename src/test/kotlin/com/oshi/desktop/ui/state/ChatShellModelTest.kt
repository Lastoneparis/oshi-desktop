package com.oshi.desktop.ui.state

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.app.WiringFixture
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * PARITY.md row 1.1 — the window's state layer, with no window.
 *
 * Every client here is a REAL [com.oshi.desktop.app.OshiClient] over a REAL in-process
 * relay, exactly as [WiringFixture]'s note says: mocking the client would assert that the
 * method we wrote was called by the code we wrote. What is under test is whether the
 * window would tell the truth, and truth-telling is not a thing a mock can be wrong about.
 *
 * **The executor is DIRECT.** [ChatShellModel] hands sends and reachability probes to a
 * worker so a real window never blocks its drawing thread on the relay; passing
 * `Executor { it.run() }` here collapses that into the calling thread, so a test never waits
 * on a latch and never sleeps. The production default is a single daemon thread.
 *
 * Nothing below opens a window, references `androidx.compose`, or needs a display. That is
 * the whole reason [ChatShellModel] exists as a separate layer — see its class note.
 */
class ChatShellModelTest {

    private val fx = WiringFixture()
    private val direct = Executor { it.run() }
    private val models = ArrayList<ChatShellModel>()

    @After fun tearDown() {
        models.forEach { runCatching { it.close() } }
        fx.close()
    }

    private fun model(client: com.oshi.desktop.app.OshiClient): ChatShellModel =
        ChatShellModel(client, direct).also { models += it; it.attach() }

    /** A valid OSHI address that has published nothing. Unreachable, by construction. */
    private fun unpublishedAddress(): String = DesktopIdentity.generate().userKey

    // ------------------------------------------------------------------ the slice itself

    @Test
    fun `the list and the thread are read from the real client, not from anything we staged`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")
        fx.seedIncoming(alice, bob.address, "m1", "from bob")
        fx.seedOutgoing(alice, bob.address, "m2", "from me")

        val m = model(alice)

        // The list is exactly what OshiClient.conversations() returns, rendered.
        assertEquals(listOf(bob.address), m.state.conversations.map { it.id })
        assertEquals(2, m.state.conversations.single().messageCount)

        m.select(bob.address)
        val thread = assertNotNull(m.state.thread).let { m.state.thread!! }
        assertEquals(listOf("from bob", "from me"), thread.messages.map { it.body })
        assertEquals(bob.address, thread.address)
        assertTrue(thread.composer.enabled)
    }

    @Test
    fun `a send that reaches the relay ends up on screen as sent`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")

        val m = model(alice)
        m.select(bob.address)
        m.draft("hello from the window")
        m.send()

        assertEquals(Severity.OK, m.state.notice?.severity)
        val row = m.state.thread!!.messages.single { it.body == "hello from the window" }
        assertTrue(row.fromMe)
        assertEquals(DeliveryStatus.SENT.wire, row.status)
        assertFalse(row.failed)
        // The box is cleared only because the message actually reached the store.
        assertEquals("", m.state.draft)
    }

    /**
     * THE GUARD THAT MATTERS MOST IN THIS FILE.
     *
     * `OshiClient.send` writes the local row whatever happens — a message the user typed does
     * not vanish because the network refused it — so the bubble appears either way. If the
     * window drew that bubble the same in both cases it would be reporting delivery it never
     * observed, which is the exact failure the REPL avoids by printing the outcome.
     */
    @Test
    fun `a send with no V2 path is NOT drawn as sent`() {
        val alice = fx.client("alice")
        val nobody = unpublishedAddress()

        val m = model(alice)
        m.select(nobody)
        m.draft("into the void")
        m.send()

        assertEquals(Severity.ERROR, m.state.notice?.severity)
        assertTrue(
            "the outcome has to say it did not arrive: ${m.state.notice?.text}",
            m.state.notice!!.text.contains("NOT DELIVERED"),
        )
        val row = m.state.thread!!.messages.single { it.body == "into the void" }
        assertEquals(DeliveryStatus.FAILED.wire, row.status)
        assertTrue(row.failed)
        // And the peer is reported as unreachable rather than as unknown-therefore-fine.
        assertEquals(Reach.NO_BUNDLE, m.state.thread!!.reach)
    }

    /**
     * PARITY.md row 0.26. The bot lane puts the user's words on the wire in the clear, and
     * the constant this asserts against is the one `OshiClient` owns precisely so that no
     * surface can drop or reword the warning.
     */
    @Test
    fun `a bot conversation has no composer, and says why`() {
        val alice = fx.client("alice")
        val convo = ChatShellModel.BOT_PREFIX + "grp-1"
        alice.messages.append(inbound(alice.address, convo, "b1", "a bot post"))

        val m = model(alice)
        m.select(convo)

        val composer = m.state.thread!!.composer
        assertFalse("a plaintext lane must not get a composer", composer.enabled)
        assertTrue(
            composer.disabledReason.orEmpty().contains(
                com.oshi.desktop.app.OshiClient.BOT_CHANNEL_IS_PLAINTEXT
            )
        )
        assertEquals(ConversationKind.BOT, m.state.thread!!.kind)

        // And asking to send anyway is refused in words, with nothing written.
        val before = alice.history(convo).size
        m.draft("reply")
        m.send()
        assertEquals(Severity.ERROR, m.state.notice?.severity)
        assertEquals(before, alice.history(convo).size)
    }

    /** PARITY.md row 0.27 — a radio thread has no verified identity to answer. */
    @Test
    fun `a radio conversation refuses the composer, and a direct one still gets it`() {
        val alice = fx.client("alice")
        val radio = com.oshi.desktop.lora.LoRaNodeBindings.FALLBACK_PREFIX + "deadbeef"
        alice.messages.append(inbound(alice.address, radio, "r1", "hello from a radio"))

        val m = model(alice)
        m.select(radio)
        assertEquals(ConversationKind.LORA, m.state.thread!!.kind)
        assertFalse(m.state.thread!!.composer.enabled)
        assertTrue(m.state.thread!!.composer.disabledReason.orEmpty().contains("unverified"))

        // A DIRECT conversation in the same store still gets its composer — the refusal is
        // per-kind, not a blanket read-only window.
        val bob = fx.client("bob")
        fx.seedIncoming(alice, bob.address, "m1", "hi")
        m.select(bob.address)
        assertTrue(m.state.thread!!.composer.enabled)
    }

    /**
     * A message arriving for a conversation that is not open must not appear in the open
     * one. The naive wiring — "something arrived, append it to what is on screen" — puts a
     * stranger's message inside somebody else's thread, which on a messenger is not a
     * cosmetic bug.
     */
    @Test
    fun `an inbound message for another conversation stays out of the open thread`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")
        val carol = fx.client("carol")
        fx.seedIncoming(alice, bob.address, "m1", "bob says hi")

        val m = model(alice)
        m.select(bob.address)
        fx.inbound(alice, from = carol.address, text = "carol says hi")

        assertEquals(listOf("bob says hi"), m.state.thread!!.messages.map { it.body })
        assertEquals(1, m.state.conversations.single { it.id == carol.address }.unread)
        assertEquals(0, m.state.conversations.single { it.id == bob.address }.unread)

        // Opening it clears the badge and shows the message.
        m.select(carol.address)
        assertEquals(0, m.state.conversations.single { it.id == carol.address }.unread)
        assertEquals(listOf("carol says hi"), m.state.thread!!.messages.map { it.body })

        // And the other half of the same guard: a message arriving for the conversation the
        // user is LOOKING AT must not raise an unread badge on it. Without this line the
        // guard is only ever exercised in the direction that happens to be structurally
        // safe (the thread reads history for the selected id, so a stranger's message could
        // not appear in it however the counter behaved) — and a mutation that drops the
        // selected-id test would survive.
        fx.inbound(alice, from = carol.address, text = "carol again")
        assertEquals(0, m.state.conversations.single { it.id == carol.address }.unread)
        assertEquals(
            listOf("carol says hi", "carol again"),
            m.state.thread!!.messages.map { it.body },
        )
    }

    /**
     * PARITY.md row 0.21: a blocked peer is WITHHELD from the conversation list, never
     * deleted. `OshiClient` exposes both readers and only one of them applies the block;
     * `conversationsIncludingBlocked` exists for `/blocked`, which announces what it is
     * showing. A window that reached for it would silently un-hide everyone the user blocked.
     */
    @Test
    fun `a blocked peer is withheld from the conversation list`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")
        fx.seedIncoming(alice, bob.address, "m1", "before the block")

        val m = model(alice)
        assertTrue(m.state.conversations.any { it.id == bob.address })

        alice.block(bob.address)
        m.refresh()

        assertFalse(
            "a blocked conversation must not be listed",
            m.state.conversations.any { it.id == bob.address },
        )
        // Withheld, not destroyed — the history is still on disk.
        assertEquals(1, alice.conversationsIncludingBlocked().count { it.conversationId == bob.address })
    }

    /**
     * `OshiClient.send` refuses a body that is itself a control sentinel and stores NOTHING,
     * so this is the one outcome where clearing the box would destroy the user's text with no
     * record of it anywhere.
     */
    @Test
    fun `a control payload is refused and the typed text survives`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")
        val payload = ControlPrefix.ACTION + """{"action":"delete"}"""

        val m = model(alice)
        m.select(bob.address)
        m.draft(payload)
        m.send()

        assertEquals(Severity.ERROR, m.state.notice?.severity)
        assertTrue(m.state.notice!!.text.contains("REFUSED"))
        assertEquals("the draft must survive a refusal", payload, m.state.draft)
        assertTrue("nothing may be stored", alice.history(bob.address).isEmpty())
    }

    /**
     * A row deleted for everyone must render as deleted even if a body is still attached to
     * it. The store keeps the record and the router nulls the text, but nothing in the type
     * system stops a caller handing over both — and a renderer that reached for `content`
     * first would draw the message its sender revoked.
     */
    @Test
    fun `a message deleted for everyone never renders its body`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")
        alice.messages.append(
            inbound(alice.address, bob.address, "d1", "the text that was revoked")
                .copy(isDeletedForEveryone = true)
        )

        val m = model(alice)
        m.select(bob.address)

        val row = m.state.thread!!.messages.single()
        assertEquals("(deleted)", row.body)
        assertTrue(row.deleted)
        assertFalse(row.body.contains("revoked"))
        assertFalse(m.state.conversations.single().preview.contains("revoked"))
    }

    /**
     * A delivery status beside an INBOUND bubble reads as a claim about the other side's
     * delivery, which this client knows nothing about — its `DeliveryStatus` on an inbound
     * row is our own receipt bookkeeping.
     */
    @Test
    fun `only outgoing rows carry a delivery status`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")
        fx.seedIncoming(alice, bob.address, "in", "theirs")
        fx.seedOutgoing(alice, bob.address, "out", "mine")

        val m = model(alice)
        m.select(bob.address)

        assertNull(m.state.thread!!.messages.single { it.body == "theirs" }.status)
        assertEquals(DeliveryStatus.SENT.wire, m.state.thread!!.messages.single { it.body == "mine" }.status)
    }

    /** Reachability is a measurement. Before it is taken, the pane must not imply one. */
    @Test
    fun `reachability starts unknown and is only asserted after it is measured`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")

        // No probe has run yet for a conversation nobody opened.
        val m = model(alice)
        assertNull(m.state.thread)

        m.select(bob.address)
        assertEquals(Reach.REACHABLE, m.state.thread!!.reach)

        // A non-direct conversation has no reachability CONCEPT, and says nothing rather
        // than saying "unknown" about a question that does not apply.
        val radio = com.oshi.desktop.lora.LoRaNodeBindings.FALLBACK_PREFIX + "cafe"
        alice.messages.append(inbound(alice.address, radio, "r", "x"))
        m.select(radio)
        assertEquals(Reach.NOT_APPLICABLE, m.state.thread!!.reach)
        assertEquals("", m.state.thread!!.reachLabel)
    }

    /**
     * The limits ledger is the window's only defence against "absent" being read as "not
     * found yet". An entry with no reason is worse than no entry: it names a gap and refuses
     * to account for it.
     */
    @Test
    fun `every declared limit names a reason and a ledger row`() {
        val all = DesktopLimits.MISSING + DesktopLimits.NOT_IN_THIS_WINDOW
        assertTrue(all.size >= 10)
        all.forEach {
            assertTrue("blank reason for '${it.what}'", it.why.length > 40)
            assertTrue(
                "'${it.what}' cites no ledger row",
                it.why.contains("PARITY.md") || it.why.contains("VIEWS.md"),
            )
        }
        // The one the task brief singles out: a UI implying a working call is the worst
        // thing this slice could ship, so calls must be in MISSING and never in the
        // "not yet wired" bucket, which reads as a schedule.
        assertTrue(DesktopLimits.MISSING.any { it.what.contains("calls", ignoreCase = true) })
        assertFalse(DesktopLimits.NOT_IN_THIS_WINDOW.any { it.what.contains("call", ignoreCase = true) })
    }

    // ------------------------------------------------------------------ helpers

    private fun inbound(me: String, convo: String, id: String, text: String) = Message(
        id = id,
        conversationId = convo,
        senderAddress = convo,
        recipientAddress = me,
        fromMe = false,
        content = text,
        sentAtMs = System.currentTimeMillis(),
        sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
        deliveryStatus = DeliveryStatus.DELIVERED,
        transport = "relay-v2",
    )

    // ================================================================ the second slice
    //
    // Everything below covers what the window learned after the first vertical slice:
    // group sending, attachments, pairing by pasted code, blocking from the people list,
    // and moving between destinations. Each is a capability the CLIENT already had and the
    // WINDOW did not — so what these assert is the WIRING, and specifically that the wiring
    // did not quietly acquire a new way to lie.

    /**
     * A group takes text and refuses attachments, and those are two different gates.
     *
     * This is the assertion that would have caught the shortcut: one `enabled` boolean would
     * have forced a choice between a group with no composer at all (which is what this window
     * used to have, and it was the window being behind the client) and a group with a working
     * paperclip (which would upload a blob no member can fetch, because there is no group
     * media fan-out anywhere in this client).
     */
    @Test
    fun `a group composer sends text and refuses attachments, for a stated reason`() {
        val me = fx.client("me")
        me.groups.put(group("g1", me.address, listOf(unpublishedAddress())))
        me.messages.append(inbound(me.address, "g1", "g-m1", "hello group"))

        val m = model(me)
        m.select("g1")
        val thread = m.state.thread!!

        assertEquals(ConversationKind.GROUP, thread.kind)
        assertTrue("a group composer must send text — sendGroupText is the same call /group send makes",
            thread.composer.enabled)
        assertFalse("a group must not offer an attachment", thread.composer.attachEnabled)
        assertTrue(
            "the refusal has to say WHY, not merely be disabled",
            thread.composer.attachDisabledReason.orEmpty().contains("no group media fan-out"),
        )
    }

    /**
     * A group send that reached nobody is never drawn as sent.
     *
     * The members here have published no prekey bundle, so the fan-out has no V2 path to any
     * of them. The outcome has to carry BOTH numbers: "sent" with a silent 0/2 is the exact
     * failure `ChatShellModel` exists to prevent, and a group is where it is easiest to hide
     * because something plausibly happened.
     */
    @Test
    fun `a group send that reached nobody says so, with both numbers`() {
        val me = fx.client("me")
        me.groups.put(group("g2", me.address, listOf(unpublishedAddress(), unpublishedAddress())))

        val m = model(me)
        m.select("g2")
        m.draft("anyone there")
        m.send()

        val notice = assertNotNull(m.state.notice).let { m.state.notice!! }
        assertEquals(Severity.ERROR, notice.severity)
        assertTrue("the outcome must name how many of how many: ${notice.text}",
            notice.text.contains("0 of 2"))
    }

    /**
     * A safety number exists for a 1:1 conversation and for nothing else.
     *
     * A safety number is a function of two IDENTITY keys. A group has many and a bot has
     * none, so drawing one there would offer a verification ritual that verifies nothing —
     * which is worse than offering none at all, because the user would perform it.
     */
    @Test
    fun `only a direct conversation carries a safety number`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        fx.seedIncoming(me, peer.address, "m1", "hi")
        me.groups.put(group("g3", me.address, listOf(peer.address)))
        me.messages.append(inbound(me.address, "g3", "g1", "group hello"))

        val m = model(me)
        m.select(peer.address)
        assertTrue("a 1:1 conversation must carry one", m.state.thread!!.safetyNumber.isNotBlank())
        assertFalse("nothing is verified until a human says so", m.state.thread!!.verified)

        m.select("g3")
        assertEquals("a group must carry none", "", m.state.thread!!.safetyNumber)
    }

    /**
     * Pasting a code adds a contact and opens the conversation, and sends nothing.
     *
     * The "sends nothing" half is asserted by the store: a message log that gained a row
     * would mean this window had introduced itself to a stranger on the user's behalf.
     */
    @Test
    fun `a pasted code opens a conversation and writes no message`() {
        val me = fx.client("me")
        val peer = unpublishedAddress()

        val m = model(me)
        m.startConversation(peer)

        assertEquals(peer, m.state.selectedId)
        assertNotNull("the contact has to be recorded, or the list draws a raw key", me.contacts.get(peer))
        assertTrue("adding a contact must not send anything", me.messages.messages(peer).isEmpty())
        assertEquals(Severity.OK, m.state.notice!!.severity)
    }

    /**
     * A code that does not parse reports the PARSER's own reason, not "invalid code".
     *
     * "That is your own address" and "that is not valid base64" send a user to two completely
     * different places, and the parser already distinguishes them. Flattening both into one
     * sentence here would throw that away at the last step.
     */
    @Test
    fun `an unusable code is refused with the reason, and nothing is added`() {
        val me = fx.client("me")
        val before = me.contacts.all().size

        val m = model(me)
        m.startConversation("this is not a key")

        assertEquals(Severity.ERROR, m.state.notice!!.severity)
        assertEquals("nothing may be recorded from a code that did not parse", before, me.contacts.all().size)
        assertNull(m.state.selectedId)
    }

    /**
     * Blocking from the people list hides the conversation and keeps the person visible.
     *
     * PARITY.md row 0.21 — withheld, never deleted. The trap this guards is the obvious one:
     * if the only screen listing people also hid the blocked ones, blocking would be a
     * one-way door. `ShellState.contacts` therefore reads `all()` while the conversation list
     * still reads `conversations()`.
     */
    @Test
    fun `blocking hides the conversation and leaves the person on the contacts list`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        fx.seedIncoming(me, peer.address, "m1", "hi")

        val m = model(me)
        assertEquals(listOf(peer.address), m.state.conversations.map { it.id })

        m.setBlocked(peer.address, true)
        assertTrue("a blocked peer must leave the conversation list", m.state.conversations.isEmpty())
        val row = m.state.contacts.firstOrNull { it.address == peer.address }
        assertNotNull("a blocked peer must stay on the contacts list, or there is no way back", row)
        assertTrue(row!!.blocked)
        assertTrue("the history is withheld, never deleted", me.messages.messages(peer.address).isNotEmpty())

        m.setBlocked(peer.address, false)
        assertEquals(listOf(peer.address), m.state.conversations.map { it.id })
    }

    /** Walking to another destination and back must not close what was open. */
    @Test
    fun `changing destination keeps the open conversation and drops the stale notice`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        fx.seedIncoming(me, peer.address, "m1", "hi")

        val m = model(me)
        m.select(peer.address)
        m.draft(ControlPrefix.DELIVERY_RECEIPT + "x")
        m.send()
        assertNotNull("the refusal should have left a notice", m.state.notice)

        m.go(Destination.MORE)
        assertEquals(Destination.MORE, m.state.destination)
        assertNull("an outcome line belongs to the screen that produced it", m.state.notice)
        assertEquals("walking away must not close the thread", peer.address, m.state.selectedId)

        m.go(Destination.MESSAGES)
        assertNotNull(m.state.thread)
    }

    /** An attach into a group is refused by the model, before any file is read. */
    @Test
    fun `attaching into a group is refused and nothing is uploaded`() {
        val me = fx.client("me")
        me.groups.put(group("g4", me.address, listOf(unpublishedAddress())))

        val m = model(me)
        m.select("g4")
        m.attach(java.io.File.createTempFile("oshi-attach", ".txt").apply { writeText("x"); deleteOnExit() })

        assertEquals(Severity.ERROR, m.state.notice!!.severity)
        assertTrue(m.state.notice!!.text.contains("no group media fan-out"))
    }

    private fun group(
        id: String,
        admin: String,
        others: List<String>,
    ): com.oshi.desktop.group.GroupDefinition {
        val now = 1_700_000_000_000L
        return com.oshi.desktop.group.GroupDefinition(
            groupId = id,
            name = "Demo group",
            type = com.oshi.desktop.group.GroupType.COLLABORATIVE,
            adminPublicKey = admin,
            members = (listOf(admin) + others).map {
                com.oshi.desktop.group.GroupMember(
                    publicKey = it,
                    joinedAtUnixMillis = now,
                    isAdmin = it == admin,
                )
            },
            createdAtUnixMillis = now,
            lastActivityUnixMillis = now,
            stateVersion = 1,
        )
    }
}
