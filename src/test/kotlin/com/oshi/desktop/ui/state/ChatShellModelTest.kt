package com.oshi.desktop.ui.state

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.app.WiringFixture
import com.oshi.desktop.crypto.SafetyNumber
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.TypingPayload
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
import java.util.concurrent.atomic.AtomicInteger

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

    /** A local toast is useful only when it does not duplicate the thread being read. */
    @Test
    fun `desktop notification is private and only fires for another incoming thread`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")
        val notifications = AtomicInteger()
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String?>>()
        val m = ChatShellModel(alice, direct, { sender, preview ->
            seen += sender to preview
            notifications.incrementAndGet()
        }).also { models += it; it.attach() }

        // __DESKTOP_PREVIEW_PARITY_2026_10_27__ The default is OFF, and with it off the
        // notifier is handed a bare signal: no sender, no plaintext. This is the
        // guarantee the class comments make, so it is asserted rather than assumed.
        m.onInbound(inbound(alice.address, bob.address, "n1", "not a notification preview"))
        assertEquals(1, notifications.get())
        assertEquals(listOf<Pair<String?, String?>>(null to null), seen.toList())

        // The conversation now being read gets neither unread badge nor redundant toast.
        m.select(bob.address)
        m.onInbound(inbound(alice.address, bob.address, "n2", "already visible"))
        assertEquals(1, notifications.get())
    }

    /**
     * __HIDDEN_WINDOW_NOTIFY_2026_10_29__ The window hidden to the tray is a state no
     * phone has, and "the conversation is selected, therefore the user has seen it" is
     * false in it. The close button hides rather than quits, so this is the ordinary way
     * to put OSHI away — and it used to mean the ONE chat the user was most likely waiting
     * on was the one chat that could neither notify nor grow a badge.
     */
    @Test
    fun `a message in the open conversation still notifies while the window is hidden`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")
        val notifications = AtomicInteger()
        var visible = true
        // Seed one message so the conversation ROW exists; the badge assertions below read it.
        fx.seedIncoming(alice, bob.address, "m0", "earlier")
        val m = ChatShellModel(
            alice, direct,
            { _, _ -> notifications.incrementAndGet() },
            windowVisible = { visible },
        ).also { models += it; it.attach() }

        m.refresh()
        m.select(bob.address)

        // On screen, the open conversation is genuinely seen: no toast, no badge.
        m.onInbound(inbound(alice.address, bob.address, "v1", "while watching"))
        assertEquals(0, notifications.get())
        assertEquals(0, m.state.conversations.first { it.id == bob.address }.unread)

        // Hidden to the tray with the same conversation still selected.
        visible = false
        m.onInbound(inbound(alice.address, bob.address, "h1", "while away"))
        assertEquals("a hidden window must still be told", 1, notifications.get())
        assertEquals(1, m.state.conversations.first { it.id == bob.address }.unread)

        m.onInbound(inbound(alice.address, bob.address, "h2", "and again"))
        assertEquals(2, notifications.get())
        assertEquals(2, m.state.conversations.first { it.id == bob.address }.unread)

        // Back on screen: what arrived while away has now been seen.
        visible = true
        m.onWindowShown()
        assertEquals(0, m.state.conversations.first { it.id == bob.address }.unread)
        assertEquals("showing the window is not a notification", 2, notifications.get())
    }

    /** Reopening the window must not eat a half-typed message — [ChatShellModel.select] would. */
    @Test
    fun `showing the window keeps the draft`() {
        val alice = fx.client("alice")
        val bob = fx.client("bob")
        val m = model(alice)
        m.select(bob.address)
        m.draft("half a sentence")
        m.onWindowShown()
        assertEquals("half a sentence", m.state.draft)
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
        // __DESKTOP_CALLS_ARE_NOT_MISSING_2026_10_29__ This used to read:
        //
        //     assertTrue(MISSING.any { it.what.contains("calls") })
        //     assertFalse(NOT_IN_THIS_WINDOW.any { it.what.contains("call") })
        //
        // with the reason that "a UI implying a working call is the worst thing this slice
        // could ship". That was right when the window had no call controls. The window now
        // has all of them and row 2.1 is "voice + video both ways, desktop<->desktop
        // measured live" — so the assertion had stopped protecting the user and started
        // PINNING A FALSE CLAIM: the limits list could not be corrected while this test
        // demanded the stale entry, which is the one way a stale doc outlives everything
        // else in this repository. A test is a claim too, and it goes stale the same way.
        //
        // The intent is kept and re-aimed at what is actually still unproven. Calls must
        // still be named in MISSING — not because the feature is absent, but because two
        // things about it are unverified (no phone has ever been called, and no test has
        // opened a real microphone or speaker), and a window that implied either would be
        // making the same promise the original assertion existed to prevent.
        val callLimit = DesktopLimits.MISSING.singleOrNull { it.what.contains("call", ignoreCase = true) }
        assertNotNull("calls must stay named in MISSING while a phone has never been called", callLimit)
        assertTrue(
            "the call limit must name what is unproven, not deny the feature: ${callLimit!!.what}",
            callLimit.why.contains("PHONE", ignoreCase = true) ||
                callLimit.why.contains("microphone", ignoreCase = true),
        )
        // And it must NOT deny the controls, which this window draws.
        assertFalse(
            "the call limit claims this window renders no call controls; it renders all of them",
            callLimit.why.contains("does not yet render call controls"),
        )
        assertFalse(DesktopLimits.NOT_IN_THIS_WINDOW.any { it.what.contains("call", ignoreCase = true) })
    }

    /**
     * __DESKTOP_RADIO_LIMIT_IS_THE_CABLE_2026_10_29__ A declared limit must not contradict a
     * capability this client demonstrably has.
     *
     * `every declared limit names a reason and a ledger row` passed for months beside an
     * entry that told the user, in the window, that nothing a LoRa radio or the LAN mesh
     * carries can be read — on a build that reads both. It passed because it only ever
     * checked SHAPE: a reason longer than 40 characters, citing a ledger file. A false claim
     * satisfies all of that.
     *
     * This checks the claim against the behaviour instead. The capability half is measured
     * here and not assumed: an inbound radio row is filed and classified as
     * `ConversationKind.LORA`, which is only reachable through the `lora!` conversation key
     * the client writes. If that holds, no limit may say radio traffic is unreadable.
     *
     * Deliberately narrow. It does not try to parse English — it pins the ONE sentence that
     * was wrong, in the one place it was drawn, so that re-introducing it fails here instead
     * of in front of a user.
     */
    @Test
    fun `no declared limit may deny reading what the radio lane demonstrably stores`() {
        val me = fx.client("me")
        // The capability, measured: a row under a `lora!` key is a LORA conversation.
        val radioKey = com.oshi.desktop.lora.LoRaNodeBindings.fallbackConversationKey(0x4b88a86du)
        fx.seedIncoming(me, radioKey, "r1", "INBOUND-TEST-4412")
        val m = model(me)
        val row = m.state.conversations.single { it.id == radioKey }
        assertEquals(
            "a lora! conversation must classify as LORA, or this guard is measuring nothing",
            ConversationKind.LORA, row.kind,
        )
        m.select(radioKey)
        assertEquals("INBOUND-TEST-4412", m.state.thread!!.messages.single().body)

        // Therefore no limit may deny it. Both buckets: moving the sentence from MISSING to
        // NOT_IN_THIS_WINDOW would have been just as untrue.
        val all = DesktopLimits.MISSING + DesktopLimits.NOT_IN_THIS_WINDOW
        all.forEach { limit ->
            val claim = (limit.what + " " + limit.why).lowercase()
            val deniesReading = limit.what.lowercase().let {
                it.contains("reading") && (it.contains("lora") || it.contains("mesh") || it.contains("radio"))
            }
            assertFalse(
                "a declared limit denies reading radio/mesh traffic that this client stores " +
                    "and shows as a ${ConversationKind.LORA} thread: '${limit.what}'",
                deniesReading,
            )
            // __LORA_IS_NOT_REPL_ONLY_2026_10_29__ The check above reads the TITLE and only
            // rejects the word "reading", so an entry titled "LoRa attach, account deletion
            // and sync checkpointing" whose body ended "The others are REPL-only: /sync
            // checkpoint, /lora, /deleteaccount." passed it for days while telling the user
            // something stronger than what it rejected. A guard that inspects the shape of a
            // sentence does not constrain what the sentence SAYS.
            //
            // More → Radio attaches, detaches, broadcasts, links a node to a contact, sets a
            // fixed position, beacons it, names the radio, and writes the region and channel.
            // So no limit may send the user to the console FOR THE RADIO, in either bucket
            // and under any wording.
            assertFalse(
                "'${limit.what}' sends the user to the REPL for the radio, which has had its " +
                    "own pane under More since 2026-10-28 (attach, send, link, beacon, owner) " +
                    "and its region and channel since 2026-10-29",
                Regex("(repl|console|slash command)[^.]*/lora|/lora[^.]*(repl|console|only)")
                    .containsMatchIn(claim),
            )
            // __DESKTOP_DELETE_ACCOUNT_UI_2026_10_29__ Same rule, same reason: deleting the
            // account has its own screen under More, so no limit may send the user to the
            // console for it either. Written the moment the screen landed rather than after
            // a twelfth stale line is found — the gap this list exists to describe closed,
            // and a list that keeps describing it would be wrong in the other direction.
            assertFalse(
                "'${limit.what}' sends the user to the REPL to delete the account, which has " +
                    "had its own screen under More since 2026-10-29",
                Regex("(repl|console|slash command)[^.]*/deleteaccount|/deleteaccount[^.]*(repl|console|only)")
                    .containsMatchIn(claim),
            )
            assertFalse(
                "'${limit.what}' claims the legacy ratchet is not implemented; " +
                    "LegacyRatchetEnvelope is compiled and live (PARITY row 0.16, UNBLOCKED)",
                claim.contains("legacy ratchet, which this client does " + "not implement") ||
                    claim.contains("never stored as conversations"),
            )
        }
    }

    /**
     * __DESKTOP_POLLS_2026_10_29__ A poll arriving from a peer becomes a CARD, not a body.
     *
     * Both halves are asserted because they fail independently: the card can be built while
     * the raw payload still prints underneath it (the model's `body` would fall through its
     * `when` to `content`, which is exactly how a poll used to render as JSON), and the body
     * can be blanked while nothing draws, leaving an empty bubble.
     */
    @Test
    fun `an inbound poll becomes a card and never prints its payload`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val poll = com.oshi.desktop.msg.PollPayload.Poll(
            id = "P-ui", question = "Pizza or sushi?", options = listOf("Pizza", "Sushi"),
            allowsMultipleAnswers = false, isAnonymous = false,
            createdAtUnix = 1_770_000_000.0, closesAtUnix = null, creatorPublicKey = peer.address,
        )
        fx.seedIncoming(me, peer.address, "p-row", poll.encode())
        val m = model(me)
        m.select(peer.address)

        val row = m.state.thread!!.messages.single()
        assertEquals("the payload must not print under the card", "", row.body)
        val card = row.poll
        assertNotNull("no card was built, so the bubble would be empty", card)
        assertEquals("Pizza or sushi?", card!!.question)
        assertEquals(listOf("Pizza", "Sushi"), card.options.map { it.label })
        assertEquals(0, card.voterCount)
        assertFalse(card.closed)
        // And the conversation list says the question, not the JSON.
        val listed = m.state.conversations.single { it.id == peer.address }
        assertTrue("got: ${listed.preview}", listed.preview.contains("Pizza or sushi?"))
        assertFalse(listed.preview.contains("opts"))
    }

    /**
     * Voting is a TOGGLE in a single-answer poll: a new pick replaces the old one, and
     * picking the current one again retracts it. Retraction is the half that is easy to drop,
     * and it must leave zero voters rather than a phantom one.
     */
    @Test
    fun `voting picks, moves and retracts`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val poll = com.oshi.desktop.msg.PollPayload.Poll(
            id = "P-vote", question = "Q", options = listOf("x", "y"),
            allowsMultipleAnswers = false, isAnonymous = false,
            createdAtUnix = 1.0, closesAtUnix = null, creatorPublicKey = peer.address,
        )
        fx.seedIncoming(me, peer.address, "p-row", poll.encode())
        val m = model(me)
        m.select(peer.address)

        m.votePoll("p-row", 0)
        var card = m.state.thread!!.messages.single().poll!!
        assertTrue("our pick is not marked", card.options[0].mine)
        assertEquals(1, card.voterCount)
        assertEquals(100, card.options[0].percent)

        m.votePoll("p-row", 1)
        card = m.state.thread!!.messages.single().poll!!
        assertFalse(card.options[0].mine)
        assertTrue(card.options[1].mine)
        assertEquals("one voter who moved, never two", 1, card.voterCount)

        m.votePoll("p-row", 1)
        card = m.state.thread!!.messages.single().poll!!
        assertFalse("picking our own choice again must retract it", card.options[1].mine)
        assertEquals(0, card.voterCount)
    }

    /** A closed poll shows its numbers and refuses a ballot, with a reason. */
    @Test
    fun `a closed poll refuses a vote and says so`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val poll = com.oshi.desktop.msg.PollPayload.Poll(
            id = "P-closed", question = "Q", options = listOf("x", "y"),
            allowsMultipleAnswers = false, isAnonymous = false,
            createdAtUnix = 1.0, closesAtUnix = 2.0, creatorPublicKey = peer.address,
        )
        fx.seedIncoming(me, peer.address, "p-row", poll.encode())
        val m = model(me)
        m.select(peer.address)
        assertTrue(m.state.thread!!.messages.single().poll!!.closed)

        m.votePoll("p-row", 0)
        assertEquals(Severity.ERROR, m.state.notice!!.severity)
        assertEquals(0, m.state.thread!!.messages.single().poll!!.voterCount)
        assertTrue(me.pollBallots.ballotsFor("P-closed").isEmpty())
    }

    /** A multiple-answer poll accumulates picks instead of replacing them. */
    @Test
    fun `a multiple answer poll adds picks and counts one voter`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val poll = com.oshi.desktop.msg.PollPayload.Poll(
            id = "P-multi", question = "Q", options = listOf("x", "y", "z"),
            allowsMultipleAnswers = true, isAnonymous = false,
            createdAtUnix = 1.0, closesAtUnix = null, creatorPublicKey = peer.address,
        )
        fx.seedIncoming(me, peer.address, "p-row", poll.encode())
        val m = model(me)
        m.select(peer.address)
        m.votePoll("p-row", 0)
        m.votePoll("p-row", 2)
        val card = m.state.thread!!.messages.single().poll!!
        assertTrue(card.options[0].mine)
        assertTrue(card.options[2].mine)
        assertEquals("one person with two picks", 1, card.voterCount)
        assertTrue("picks mark the label, not the person", card.allowsMultipleAnswers)
    }

    /**
     * __DESKTOP_POLL_COMPOSER_2026_10_29__ Creating a poll from the window, and the refusal
     * being told apart from a network failure.
     *
     * The second half is the point: `sendPoll` returns null for an unsendable poll and a
     * `SendOutcome` for everything else, so a composer that treated null as "failed" would
     * tell someone the network was down when their poll had one option.
     */
    @Test
    fun `creating a poll sends it and an unsendable one says why`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val m = model(me)
        m.select(peer.address)

        m.createPoll("Pizza or sushi?", listOf("Pizza", "Sushi", ""), multi = false, anon = false)
        assertEquals(Severity.OK, m.state.notice!!.severity)
        val row = m.state.thread!!.messages.single()
        val card = row.poll
        assertNotNull("our own poll must draw as a card", card)
        assertEquals("Pizza or sushi?", card!!.question)
        assertEquals("the trailing blank option must be dropped", listOf("Pizza", "Sushi"), card.options.map { it.label })
        assertEquals("", row.body)

        // It arrives at the peer as a poll, not as text.
        assertEquals(1, peer.router.poll())
        assertNotNull(com.oshi.desktop.msg.PollPayload.decodePoll(peer.messages.messages(me.address).last().content))

        // A refusal is a refusal, and says so.
        m.createPoll("Only one", listOf("a"), multi = false, anon = false)
        assertEquals(Severity.ERROR, m.state.notice!!.severity)
        assertTrue("got: ${m.state.notice!!.text}", m.state.notice!!.text.contains("2 and 12"))
        assertFalse("a refused poll must not mention the network", m.state.notice!!.text.contains("network"))
        assertEquals("nothing was stored for the refused poll", 1, m.state.thread!!.messages.size)
    }

    /** The anonymous flag survives the composer and reaches the card. */
    @Test
    fun `an anonymous multi poll keeps both flags through the wire`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val m = model(me)
        m.select(peer.address)
        m.createPoll("Q", listOf("x", "y"), multi = true, anon = true)
        peer.router.poll()
        val onPeer = com.oshi.desktop.msg.PollPayload.decodePoll(peer.messages.messages(me.address).last().content)!!
        assertTrue(onPeer.allowsMultipleAnswers)
        assertTrue(onPeer.isAnonymous)
        val card = m.state.thread!!.messages.single().poll!!
        assertTrue(card.isAnonymous)
        assertTrue(card.allowsMultipleAnswers)
    }

    /**
     * __DESKTOP_OFFLINE_ONLY_2026_10_29__ The switch, and the notice that explains the
     * asymmetry rather than letting it read as a failure.
     *
     * Turning it ON cannot announce itself over the relay it just banned, so over a
     * relay-only pair the send does not succeed — and the window must say that the
     * restriction HELD and the contact was not told, not that something broke.
     */
    @Test
    fun `the offline-only switch restricts at once and explains why the peer was not told`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val m = model(me)
        m.select(peer.address)
        assertFalse(m.state.thread!!.offlineOnly)
        assertNull(m.state.thread!!.offlineOnlyConfirmed)

        m.setOfflineOnly(true)
        assertTrue("the restriction must hold whatever the send did", m.state.thread!!.offlineOnly)
        assertEquals("nobody has confirmed", 0 to 1, m.state.thread!!.offlineOnlyConfirmed)
        // Not an error: the restriction worked, which is the half that protects anything.
        assertEquals(Severity.OK, m.state.notice!!.severity)
        assertTrue("got: ${m.state.notice!!.text}", m.state.notice!!.text.contains("radio or a nearby device"))

        // Lifting it travels, because the restriction is gone before the request is sent.
        m.setOfflineOnly(false)
        assertFalse(m.state.thread!!.offlineOnly)
        assertNull(m.state.thread!!.offlineOnlyConfirmed)
        assertEquals(Severity.OK, m.state.notice!!.severity)
    }

    /** An inbound request restricts the thread and shows as a readable row, never a payload. */
    @Test
    fun `a peer's request shows in the thread and flips the switch`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val m = model(me)
        m.select(peer.address)
        fx.inbound(
            me, peer.address,
            com.oshi.desktop.msg.OfflineOnlyPayload.Request(
                on = true, atUnix = System.currentTimeMillis() / 1000.0,
            ).encode(),
        )
        assertTrue(m.state.thread!!.offlineOnly)
        val row = m.state.thread!!.messages.single()
        assertTrue("got: ${row.body}", row.body.contains("offline only"))
        assertFalse("the payload must never print", row.body.contains("\"on\""))
    }

    /**
     * __DESKTOP_MEDIA_GALLERY_2026_10_29__ The gallery lists the conversation's attachments,
     * newest first, and is built from the same rows the thread draws.
     */
    @Test
    fun `the gallery lists attachments newest first`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val dir = java.nio.file.Files.createTempDirectory("oshi-gal").toFile()
        try {
            repeat(3) { i ->
                val f = java.io.File(dir, "pic$i.jpg").apply { writeBytes(ByteArray(256) { it.toByte() }) }
                assertEquals(
                    com.oshi.desktop.app.OshiClient.SendOutcome.SENT,
                    peer.sendFile(me.address, f, com.oshi.desktop.store.MediaType.IMAGE),
                )
                assertEquals(1, me.router.poll())
            }
            val m = model(me)
            m.select(peer.address)
            val gallery = m.state.thread!!.media
            assertEquals(3, gallery.size)
            assertEquals("newest first", listOf("pic2.jpg", "pic1.jpg", "pic0.jpg"), gallery.map { it.fileName })
            gallery.forEach {
                assertEquals(com.oshi.desktop.store.MediaType.IMAGE, it.kind)
                assertTrue(it.path.isNotEmpty())
                assertFalse(it.fromMe)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A text-only conversation offers no gallery, so no control is drawn for an empty grid. */
    @Test
    fun `a conversation with no attachments has an empty gallery`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        fx.seedIncoming(me, peer.address, "t1", "just talking")
        val m = model(me)
        m.select(peer.address)
        assertTrue(m.state.thread!!.media.isEmpty())
    }

    /**
     * A VIEW-ONCE ATTACHMENT IS NEVER IN THE GALLERY. "View once" means once, and a grid
     * that lists it afterwards is a second look by another route — the gallery must not be
     * the back door to what the bubble spent effort sealing.
     */
    @Test
    fun `a view-once attachment never appears in the gallery`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        // `sendFile` has no viewOnce parameter — the SENDER sets the flag on the file key —
        // so the key is built here, with a real uploaded blob, exactly as a phone would.
        val bytes = ByteArray(128) { it.toByte() }
        val enc = com.oshi.messenger.network.v2.OSHICryptoV2.encryptFile(bytes, "secret.jpg", "image/jpeg")
        val blobId = peer.blobs.uploadBlob(me.address, enc.chunks)
        assertNotNull("the fixture relay refused the blob", blobId)
        val key = com.oshi.messenger.network.v2.V2FileKeyMessage(
            blobId = blobId!!,
            fileKey = enc.fileKey,
            fileNonce = enc.fileNonce,
            chunkCount = enc.chunks.size,
            manifest = enc.manifest,
            filename = "secret.jpg",
            mime = "image/jpeg",
            mediaType = "image",
            isViewOnce = true,
        )
        fx.inbound(me, peer.address, String(key.toBytes(), Charsets.UTF_8))

        val m = model(me)
        m.select(peer.address)
        assertTrue("a view-once photo was browsable in the gallery", m.state.thread!!.media.isEmpty())
        // ...while the thread still has the row.
        assertEquals(1, m.state.thread!!.messages.size)
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
    fun `a group composer sends text and offers attachments (group media fan-out)`() {
        val me = fx.client("me")
        me.groups.put(group("g1", me.address, listOf(unpublishedAddress())))
        me.messages.append(inbound(me.address, "g1", "g-m1", "hello group"))

        val m = model(me)
        m.select("g1")
        val thread = m.state.thread!!

        assertEquals(ConversationKind.GROUP, thread.kind)
        assertTrue("a group composer must send text — sendGroupText is the same call /group send makes",
            thread.composer.enabled)
        // __GROUP_E2E_V2_2026_09_23__ OshiClient.sendGroupFile (GROUP_E2E_V2_SPEC §4).
        assertTrue("a group offers attachments now that group media fans out", thread.composer.attachEnabled)
        assertNull(thread.composer.attachDisabledReason)
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

    @Test
    fun `creating a group makes its definition visible before its first message`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        me.contacts.seen(peer.address, System.currentTimeMillis())
        val m = model(me)

        m.createGroup("Weekend plans", listOf(peer.address))

        val created = m.state.conversations.single { it.kind == ConversationKind.GROUP }
        assertEquals("Weekend plans", created.label)
        assertEquals(0, created.messageCount)
        assertEquals(created.id, m.state.selectedId)
        assertEquals(Severity.OK, m.state.notice!!.severity)
        assertTrue(me.groups.get(created.id)!!.isMember(peer.address))
        assertTrue("the creator alone may receive a rename control", m.state.thread!!.groupAdmin)
        assertEquals(setOf(me.address, peer.address), m.state.thread!!.groupMembers.map { it.address }.toSet())
        assertTrue(m.state.thread!!.groupMembers.single { it.self }.isAdmin)

        m.renameGroup(created.id, "Sunday plans")
        assertEquals("Sunday plans", m.state.thread!!.title)
        assertEquals("Sunday plans", me.groups.get(created.id)!!.name)
        assertEquals(Severity.OK, m.state.notice!!.severity)
    }

    @Test
    fun `a group admin can add a known contact and remove that other member from the roster`() {
        val me = fx.client("me")
        val initial = fx.client("initial")
        val candidate = fx.client("candidate")
        me.contacts.seen(initial.address, System.currentTimeMillis())
        me.contacts.seen(candidate.address, System.currentTimeMillis())
        val m = model(me)

        m.createGroup("Plans", listOf(initial.address))
        val groupId = m.state.thread!!.conversationId
        assertEquals(listOf(candidate.address), m.state.thread!!.groupCandidates.map { it.address })

        m.addGroupMember(groupId, candidate.address)
        assertTrue(me.groups.get(groupId)!!.isMember(candidate.address))
        assertFalse(m.state.thread!!.groupCandidates.any { it.address == candidate.address })
        assertEquals(Severity.OK, m.state.notice!!.severity)

        m.setGroupMemberAdmin(groupId, candidate.address, true)
        assertTrue(me.groups.get(groupId)!!.isAdmin(candidate.address))
        assertTrue(m.state.thread!!.groupMembers.single { it.address == candidate.address }.isAdmin)
        assertEquals(Severity.OK, m.state.notice!!.severity)

        m.setGroupMemberAdmin(groupId, candidate.address, false)
        assertFalse(me.groups.get(groupId)!!.isAdmin(candidate.address))

        m.removeGroupMember(groupId, candidate.address)
        assertFalse(me.groups.get(groupId)!!.isMember(candidate.address))
        assertTrue(m.state.thread!!.groupCandidates.any { it.address == candidate.address })
        assertEquals(Severity.OK, m.state.notice!!.severity)
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

    @Test
    fun `safety verification requires the peer QR payload and records a mismatch`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        fx.seedIncoming(me, peer.address, "m1", "hi")
        me.contacts.seen(peer.address, System.currentTimeMillis())
        val m = model(me)

        assertFalse(m.verifySafetyNumber(peer.address, "SAFETY:v1:not-the-keys"))
        assertEquals(Severity.ERROR, m.state.notice!!.severity)
        assertFalse(m.state.contacts.single { it.address == peer.address }.verified)

        val payload = SafetyNumber.qrPayload(me.address, peer.address)
        assertTrue(m.verifySafetyNumber(peer.address, "  $payload  "))
        assertEquals(Severity.OK, m.state.notice!!.severity)
        assertTrue(m.state.contacts.single { it.address == peer.address }.verified)
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

    @Test
    fun `the scheduled queue is surfaced as local delivery intent`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val m = model(me)
        val queued = me.scheduler.schedule(peer.address, "Later", System.currentTimeMillis() + 60_000)

        m.refresh()
        val row = m.state.scheduled.single()
        assertEquals(queued.id, row.id)
        assertEquals(peer.address, row.recipient)
        assertEquals("Later", row.content)
        assertEquals("pending", row.status)
        assertFalse("a direct schedule must not draw as a group", row.group)
    }

    @Test
    fun `cancelling a pending scheduled row retains it as cancelled`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val m = model(me)
        val queued = me.scheduler.schedule(peer.address, "Later", System.currentTimeMillis() + 60_000)

        m.cancelScheduled(queued.id)

        assertEquals("cancelled", m.state.scheduled.single().status)
        assertEquals(Severity.OK, m.state.notice!!.severity)
        m.cancelScheduled(queued.id)
        assertEquals(Severity.ERROR, m.state.notice!!.severity)
    }

    @Test
    fun `a known unblocked contact can receive a scheduled message from the window model`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        me.contacts.seen(peer.address, System.currentTimeMillis())
        val m = model(me)

        m.scheduleMessage(peer.address, "Later", 60_000)

        assertEquals("Later", m.state.scheduled.single().content)
        assertEquals(peer.address, m.state.scheduled.single().recipient)
        assertEquals(Severity.OK, m.state.notice!!.severity)
    }

    @Test
    fun `a group this account belongs to can receive a scheduled message from the window model`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        me.contacts.seen(peer.address, System.currentTimeMillis())
        val m = model(me)
        m.createGroup("Plans", listOf(peer.address))
        val groupId = m.state.thread!!.conversationId

        m.scheduleMessage(groupId, "Later together", 60_000, isGroup = true)

        assertEquals(groupId, m.state.scheduled.single().recipient)
        assertTrue(m.state.scheduled.single().group)
        assertEquals(Severity.OK, m.state.notice!!.severity)
    }

    @Test
    fun `editing a pending scheduled row changes only its pending local content`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val m = model(me)
        val queued = me.scheduler.schedule(peer.address, "Original", System.currentTimeMillis() + 60_000)

        m.editScheduled(queued.id, "Revised")

        assertEquals("Revised", m.state.scheduled.single().content)
        assertEquals(Severity.OK, m.state.notice!!.severity)
        m.cancelScheduled(queued.id)
        m.editScheduled(queued.id, "Too late")
        assertEquals("Revised", m.state.scheduled.single().content)
        assertEquals(Severity.ERROR, m.state.notice!!.severity)
    }

    @Test
    fun `a direct thread reaction uses the client local first reaction state`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        fx.seedIncoming(me, peer.address, "m1", "Hello")
        val m = model(me)
        m.select(peer.address)

        m.react("m1", "👍")

        assertTrue(m.state.thread!!.messages.single().reactions.contains("👍"))
        assertEquals(Severity.OK, m.state.notice!!.severity)
    }

    /**
     * __DESKTOP_DIRECT_PIN_2026_10_29__ Pin in a 1:1 — where the gap actually lived.
     *
     * `pinMessage` opened with `groupSelected() ?: return`, so this press was a no-op in a
     * direct thread. The pane was NOT lying about it (it offered the item only in groups,
     * correctly), which is why the window looked right and the feature was simply absent.
     * The view state is asserted as well as the store: `pinned` was `groupPinned` and read
     * a group definition, so even a stored pin had nowhere to show.
     */
    @Test
    fun `pinning in a direct thread writes the pin and shows it in the thread state`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        fx.seedIncoming(me, peer.address, "p1", "trailhead at 8")
        val m = model(me)
        m.select(peer.address)
        assertTrue("the pane must offer Pin in a 1:1", m.state.thread!!.pinCanToggle)
        assertNull(m.state.thread!!.pinned)

        m.pinMessage("p1")

        assertEquals("p1", me.pinnedMessages.get(peer.address))
        val shown = m.state.thread!!.pinned
        assertNotNull("the pinned bar had nothing to draw", shown)
        assertEquals("p1", shown!!.messageId)
        assertEquals("trailhead at 8", shown.text)
        assertEquals(Severity.OK, m.state.notice!!.severity)

        m.pinMessage(null)
        assertNull(me.pinnedMessages.get(peer.address))
        assertNull(m.state.thread!!.pinned)
    }

    /**
     * The notice is its own assertion because reusing `runDirectMessageAction` would have
     * produced "Message pined" — its wording is built by appending "ed" to the verb. A
     * feature nobody can read the confirmation of is half-shipped.
     */
    @Test
    fun `the pin notice is spelled correctly and names the right outcome`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        fx.seedIncoming(me, peer.address, "p2", "bring the map")
        val m = model(me)
        m.select(peer.address)

        m.pinMessage("p2")
        assertTrue("got: ${m.state.notice?.text}", m.state.notice!!.text.contains("pinned"))
        assertFalse(m.state.notice!!.text.contains("pined"))

        m.pinMessage(null)
        assertTrue("got: ${m.state.notice?.text}", m.state.notice!!.text.contains("unpinned"))

        // A message this device does not hold is refused, not announced as a pin.
        m.pinMessage("never-stored")
        assertEquals(Severity.ERROR, m.state.notice!!.severity)
        assertNull(me.pinnedMessages.get(peer.address))
    }

    @Test
    fun `typing state is an ephemeral direct-thread transition and never a message row`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val m = model(me)
        m.select(peer.address)

        m.typingChanged(peer.address, true)
        m.typingChanged(peer.address, true) // Debounced: no second send is attempted.
        m.typingChanged(peer.address, false)

        assertTrue(m.state.thread!!.messages.isEmpty())
        assertNull(m.state.notice)
    }

    @Test
    fun `receipt privacy switches are reflected in the window state and the real client`() {
        val me = fx.client("me")
        val m = model(me)

        m.setReceiptPrivacy(delivery = false, read = false)

        assertFalse(me.deliveryReceiptsEnabled)
        assertFalse(me.readReceiptsEnabled)
        assertFalse(m.state.deliveryReceiptsEnabled)
        assertFalse(m.state.readReceiptsEnabled)
    }

    @Test
    fun `device sync failures are shown in account state rather than disappearing`() {
        val me = fx.client("me")
        val m = model(me)

        // WiringFixture intentionally has no /v2/sync route. The important UI contract is
        // that a failed network operation has a visible, named outcome in Account.
        m.syncPushContacts()

        assertEquals(Severity.ERROR, m.state.syncNotice?.severity)
        assertTrue(m.state.syncNotice?.text?.contains("Could not archive contacts") == true)
        assertFalse(m.state.busy)
    }

    @Test
    fun `an inbound typing control appears in the selected direct thread and clears on stop`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val m = model(me)
        m.select(peer.address)

        fx.inbound(me, peer.address, TypingPayload.encode(peer.address, true, System.currentTimeMillis()))

        assertTrue(m.state.thread!!.peerTyping)
        assertTrue(m.state.thread!!.messages.isEmpty())

        fx.inbound(me, peer.address, TypingPayload.encode(peer.address, false, System.currentTimeMillis()))

        assertFalse(m.state.thread!!.peerTyping)
    }

    @Test
    fun `a direct thread can edit and delete its own message locally first`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        fx.seedOutgoing(me, peer.address, "m1", "Original")
        val m = model(me)
        m.select(peer.address)

        m.editMessage("m1", "Revised")

        assertEquals("Revised", m.state.thread!!.messages.single().body)
        assertTrue(m.state.thread!!.messages.single().edited)
        assertEquals(Severity.OK, m.state.notice!!.severity)

        m.deleteMessage("m1")

        assertTrue(m.state.thread!!.messages.single().deleted)
        assertEquals("(deleted)", m.state.thread!!.messages.single().body)
        assertEquals(Severity.OK, m.state.notice!!.severity)
    }

    @Test
    fun `a scheduled run refreshes the queue without replacing its existing callback`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val now = System.currentTimeMillis()
        var priorRuns = 0
        me.onScheduledRun = { priorRuns++ }
        val m = model(me)
        val queued = me.scheduler.schedule(peer.address, "Later", now + 60_000, nowMs = now)

        me.tick(now + 61_000)

        assertEquals("the REPL callback must remain connected", 1, priorRuns)
        assertEquals("sent", m.state.scheduled.single { it.content == "Later" }.status)
        assertEquals(queued.id, me.scheduled.all().single().id)
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
    fun `attaching into a group nobody can reach is not drawn as sent and names who must update`() {
        val me = fx.client("me")
        me.groups.put(group("g4", me.address, listOf(unpublishedAddress())))

        val m = model(me)
        m.select("g4")
        m.attach(java.io.File.createTempFile("oshi-attach", ".txt").apply { writeText("x"); deleteOnExit() })

        assertEquals(Severity.ERROR, m.state.notice!!.severity)
        // GROUP_E2E_V2_SPEC §6: no legacy fallback; the member is named via group.member_must_update.
        assertTrue(m.state.notice!!.text, m.state.notice!!.text.contains("must update OSHI"))
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
