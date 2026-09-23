package com.oshi.desktop.app

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.MessageActionPayload
import com.oshi.desktop.msg.ReactionPayload
import com.oshi.desktop.place.CheckInPayload
import com.oshi.desktop.place.CheckInType
import com.oshi.desktop.place.LocationPayload
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.MediaType
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SEAM, not the codecs.
 *
 * Every package under `com.oshi.desktop` was unit-tested against shipped bytes and almost
 * none of them was reachable from [OshiClient] or [ClientCommands]. These tests assert the
 * thing those unit tests structurally cannot: that a payload arriving at the ROUTER's
 * callback reaches the package that owns it, and that a person at the REPL can get to it
 * from the other side.
 *
 * So there is deliberately not one assertion here about a byte layout. If a test in this
 * file would still pass with the wiring removed, it does not belong here.
 */
class ClientWiringTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    private val emoji = ReactionPayload.IOS_EMOJI.first()

    // ============================================================ row 0.18 inbound

    @Test
    fun `an inbound reaction lands as a reaction, never as a text message`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val target = fx.seedOutgoing(me, peer, "msg-1", "hello")

        fx.inbound(
            me, peer,
            ReactionPayload.encode(
                messageId = target.id, emoji = emoji, senderPublicKey = peer,
                isAdding = true, atUnixMillis = System.currentTimeMillis(),
            ),
        )

        val after = me.messages.message(peer, target.id)!!
        assertEquals("the reaction never reached MessageStore.setReaction", setOf(peer), after.reactions[emoji])
        assertEquals(
            "a control payload became a chat row — it was routed as prose",
            1, me.messages.messages(peer).size,
        )
    }

    @Test
    fun `an inbound delivery receipt advances the row it names`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val target = fx.seedOutgoing(me, peer, "msg-2", "hello")
        assertEquals(DeliveryStatus.SENT, target.deliveryStatus)

        fx.inbound(me, peer, com.oshi.desktop.msg.DeliveryReceipt.encode(target.id))

        assertEquals(
            "the receipt did not reach ControlPayloadRouter",
            DeliveryStatus.DELIVERED, me.messages.message(peer, target.id)!!.deliveryStatus,
        )
        assertEquals(1, me.messages.messages(peer).size)
    }

    @Test
    fun `an inbound delete for everyone from the message's owner is applied`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val theirs = fx.seedIncoming(me, peer, "msg-3", "regrettable")

        fx.inbound(
            me, peer,
            MessageActionPayload.encode(
                actionType = MessageActionPayload.ActionType.DELETE_FOR_EVERYONE,
                targetMessageId = theirs.id,
                atUnixMillis = System.currentTimeMillis(),
            ),
        )

        assertTrue(
            "the action did not reach ControlPayloadRouter",
            me.messages.message(peer, theirs.id)!!.isDeletedForEveryone,
        )
    }

    /**
     * The gate, and the reason it has to cover control payloads and not merely prose: below
     * it a reaction, an edit and a delete all WRITE to our message log.
     */
    @Test
    fun `a blocked peer's control payload is dropped before the store is touched`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val target = fx.seedOutgoing(me, peer, "msg-4", "hello")
        me.block(peer)

        fx.inbound(
            me, peer,
            ReactionPayload.encode(
                messageId = target.id, emoji = emoji, senderPublicKey = peer,
                isAdding = true, atUnixMillis = System.currentTimeMillis(),
            ),
        )
        fx.inbound(
            me, peer,
            MessageActionPayload.encode(
                actionType = MessageActionPayload.ActionType.DELETE_FOR_EVERYONE,
                targetMessageId = target.id,
                atUnixMillis = System.currentTimeMillis(),
            ),
        )

        val after = me.messages.message(peer, target.id)!!
        assertTrue("a blocked peer reacted to our message", after.reactions.isEmpty())
        assertFalse("a blocked peer deleted our message", after.isDeletedForEveryone)
    }

    // ============================================================ row 0.19 inbound

    @Test
    fun `an inbound location share is rendered to one line, not stored as JSON`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val now = System.currentTimeMillis()

        var seen: com.oshi.desktop.place.PlaceEvent? = null
        me.onPlace = { _, e -> seen = e }

        val payload = LocationPayload.encode(
            latitude = 48.8584, longitude = 2.2945, atUnixMillis = now,
            isLive = true, expiresAtUnixMillis = now + 3_600_000, sessionId = "s-1",
        )
        fx.inbound(me, peer, payload, ts = now)

        assertTrue("PlaceRouter was never reached", seen is com.oshi.desktop.place.PlaceEvent.Location)
        val row = me.messages.messages(peer).single()
        assertFalse(
            "the raw sentinel was stored — a user would see a wall of JSON",
            row.content!!.startsWith(ControlPrefix.LOCATION),
        )
        assertTrue("nothing was rendered", row.content!!.contains("📍"))
        assertNotNull(
            "the live share was not folded into the tracker",
            me.places.tracker().session("s-1"),
        )
    }

    @Test
    fun `an inbound check-in is rendered through the same dispatch`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val now = System.currentTimeMillis()

        fx.inbound(
            me, peer,
            CheckInPayload.encode(
                type = CheckInType.ARRIVED,
                sessionId = "c-1",
                atUnixMillis = now,
                currentLatitude = 48.8584,
                currentLongitude = 2.2945,
            ),
            ts = now,
        )

        val row = me.messages.messages(peer).single()
        assertFalse(row.content!!.startsWith(ControlPrefix.CHECK_IN))
        assertTrue("the check-in was not rendered", row.content!!.isNotBlank())
    }

    @Test
    fun `a sentinel belonging to a row this client has not built is never stored`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address

        fx.inbound(me, peer, ControlPrefix.CALL_SIGNAL + """{"type":"offer"}""")

        assertTrue(
            "a foreign control payload was filed as a chat message",
            me.messages.messages(peer).isEmpty(),
        )
    }

    @Test
    fun `prose still reaches the store`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        fx.inbound(me, peer, "an ordinary message")
        assertEquals("an ordinary message", me.messages.messages(peer).single().content)
    }

    // ============================================================ end to end, two clients

    /**
     * The whole row-0.18 loop over a real relay: text out, stored at the far end, a
     * delivery receipt back without anybody asking, then a reaction that finds its target
     * in the OTHER client's store.
     */
    @Test
    fun `two clients exchange a message, a receipt and a reaction over the relay`() {
        val a = fx.client("a")
        val b = fx.client("b")

        assertEquals(OshiClient.SendOutcome.SENT, a.send(b.address, "hello b"))
        val sent = a.messages.messages(b.address).single()

        assertEquals(1, b.router.poll())
        assertEquals("hello b", b.messages.messages(a.address).single().content)

        // B's dispatch answered with a delivery receipt on its own — no API call was made.
        a.router.poll()
        assertEquals(
            "no delivery receipt came back: the receive path never emits one",
            DeliveryStatus.DELIVERED, a.messages.message(b.address, sent.id)!!.deliveryStatus,
        )

        assertEquals(OshiClient.SendOutcome.SENT, a.sendReaction(b.address, sent.id, emoji))
        b.router.poll()
        assertEquals(
            "the reaction did not reach B's store",
            setOf(a.address), b.messages.message(a.address, sent.id)!!.reactions[emoji],
        )
        assertEquals("the reaction became a message row at B", 1, b.messages.messages(a.address).size)
    }

    /** A blocked contact must not learn they are blocked from a tick coming back. */
    @Test
    fun `a blocked sender gets no delivery receipt`() {
        val a = fx.client("a")
        val b = fx.client("b")
        b.block(a.address)

        a.send(b.address, "hello")
        b.router.poll()
        val before = a.messages.messages(b.address).single().deliveryStatus
        a.router.poll()

        assertEquals(
            "blocking became observable to the person blocked (PARITY.md 0.21 defect 1)",
            before, a.messages.messages(b.address).single().deliveryStatus,
        )
    }

    // ============================================================ row 0.15 media

    @Test
    fun `a file is encrypted, uploaded and decrypted back at the far end`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val dir = Files.createTempDirectory("oshi-media").toFile()
        val source = File(dir, "note.txt").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) }

        try {
            assertEquals(OshiClient.SendOutcome.SENT, a.sendFile(b.address, source))
            assertEquals(1, b.router.poll())

            val row = b.messages.messages(a.address).single()
            assertEquals("note.txt", row.content)
            assertNotNull("the key message was stored without ever fetching the blob", row.mediaRef)
            // __LOCAL_DATA_AT_REST_2026_09_22__ sealed on arrival: the plaintext never lands.
            assertTrue(
                "an inbound attachment was written to disk in plaintext",
                com.oshi.desktop.store.MediaVault.isSealed(File(row.mediaRef!!)),
            )
            assertTrue(
                "the decrypted bytes differ from what was sent",
                b.mediaVault.readBytes(File(row.mediaRef!!), Long.MAX_VALUE).contentEquals(source.readBytes()),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The bug this guards was found by sending a JPEG to a real Android phone and reading
     * its log: `📥 Received DOCUMENT via V2 blob (13464B)`. The bytes were perfect and the
     * MIME was `image/jpeg`; the `mediaType` field said `document`, and a receiver reads
     * that field FIRST, so the phone drew a file row instead of a photo.
     */
    @Test
    fun `a photo announces itself as a photo, not as a document`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val dir = Files.createTempDirectory("oshi-media-type").toFile()
        try {
            for ((name, expected) in listOf(
                "shot.jpg" to MediaType.IMAGE,
                "clip.mp4" to MediaType.VIDEO,
                "note.m4a" to MediaType.AUDIO,
                "contract.pdf" to MediaType.DOCUMENT,
            )) {
                val f = File(dir, name).apply { writeBytes(ByteArray(64) { it.toByte() }) }
                assertEquals(OshiClient.SendOutcome.SENT, a.sendFile(b.address, f))
                assertEquals(1, b.router.poll())
                assertEquals(
                    "$name went out labelled as something a receiver will not render as $expected",
                    expected, b.messages.messages(a.address).last().mediaType,
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The receive half of the same rule, and the asymmetry it fixes: both phones fall back
     * to the MIME when the `mediaType` field is absent, and this client did not — it
     * defaulted the missing field to DOCUMENT, so a peer that omitted it got an
     * attachment here and a photo everywhere else.
     */
    @Test
    fun `an inbound file with no mediaType field is classified from its MIME`() {
        assertEquals(MediaType.IMAGE, MediaType.forMime("image/png"))
        assertEquals(MediaType.VIDEO, MediaType.forMime("video/mp4"))
        assertEquals(MediaType.AUDIO, MediaType.forMime("audio/mp4"))
        assertEquals(
            "a contact card that falls through to DOCUMENT is never decoded as a contact",
            MediaType.CONTACT, MediaType.forMime("text/vcard"),
        )
        assertEquals(MediaType.DOCUMENT, MediaType.forMime("application/pdf"))
        assertEquals(MediaType.DOCUMENT, MediaType.forMime(null))
        assertEquals("Android accepts this alias on the wire", MediaType.IMAGE, MediaType.fromWire("photo"))
    }

    /**
     * Row 0.19's third payload. It has no sentinel — it is attachment bytes with
     * `mediaType: contact` — so it could not be reachable until the media path was.
     */
    @Test
    fun `a contact card arrives as a contact, not as an opaque attachment`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val c = fx.client("c", publish = false)
        a.contacts.seen(c.address, System.currentTimeMillis(), "carol")

        assertEquals(OshiClient.SendOutcome.SENT, a.sendContactCard(b.address, c.address))
        assertEquals(1, b.router.poll())

        assertNotNull(
            "the card was stored as bytes and never decoded — ContactCardPayload was not reached",
            b.contacts.get(c.address),
        )
        assertEquals("carol", b.contacts.get(c.address)!!.displayName)
        assertTrue(b.messages.messages(a.address).single().content!!.startsWith("Contact: carol"))
    }

    // ============================================================ row 0.25 scheduled

    /**
     * The driver. `tick` is what the poll loop calls; nothing else in this test touches the
     * scheduler, so a `tick` that stopped calling `runDue` would fail here.
     */
    @Test
    fun `a scheduled message is sent by the poll tick, and says how late it was`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val now = System.currentTimeMillis()

        val queued = a.scheduler.schedule(b.address, "happy birthday", now + 60_000, nowMs = now)
        var reported: com.oshi.desktop.scheduled.ScheduledMessageRunner.DueRun? = null
        a.onScheduledRun = { reported = it }

        // Nothing due yet: the tick must not send early.
        a.tick(now)
        assertEquals(0, b.router.poll())

        // Now simulate a process that was DOWN when it came due and started an hour later.
        a.tick(now + 60_000 + 3_600_000)

        assertEquals(1, b.router.poll())
        assertEquals("happy birthday", b.messages.messages(a.address).single().content)
        assertEquals(
            com.oshi.desktop.scheduled.ScheduledMessage.Status.SENT,
            a.scheduled.get(queued.id)!!.status,
        )
        assertNotNull("the tick reported nothing", reported)
        assertTrue(
            "an overdue send was reported as punctual — the lateness is the whole point",
            reported!!.wasLate,
        )
        assertTrue(reported!!.lateByMs >= 3_600_000)
    }

    @Test
    fun `a cancelled schedule is not sent by a later tick`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val now = System.currentTimeMillis()
        val queued = a.scheduler.schedule(b.address, "never mind", now + 60_000, nowMs = now)

        assertTrue(a.scheduler.cancel(queued.id))
        a.tick(now + 120_000)

        assertEquals("a cancelled message went out anyway", 0, b.router.poll())
    }

    // ============================================================ outbound refusals

    @Test
    fun `a typed body that is itself a control payload is refused`() {
        val a = fx.client("a")
        val b = fx.client("b")
        assertEquals(
            OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD,
            a.send(b.address, MessageActionPayload.encode(
                actionType = MessageActionPayload.ActionType.DELETE_FOR_EVERYONE,
                targetMessageId = "anything",
                atUnixMillis = System.currentTimeMillis(),
            )),
        )
        assertTrue("a refused send still wrote a row", a.messages.messages(b.address).isEmpty())
    }

    @Test
    fun `editing a message we did not send is refused before anything goes on the wire`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val theirs = fx.seedIncoming(me, peer, "msg-9", "their words")

        assertEquals(
            OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD,
            me.editMessage(peer, theirs.id, "my words"),
        )
        assertEquals("their words", me.messages.message(peer, theirs.id)!!.content)
    }

    // ============================================================ the REPL

    @Test
    fun `the REPL reaches the safety-number package`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        me.contacts.seen(peer, System.currentTimeMillis())

        val out = fx.repl(me, "/safety $peer")
        val digits = out.first().trim()
        assertEquals("not iOS's 12 groups of 5", 12, digits.split(" ").size)
        assertTrue(digits.all { it.isDigit() || it == ' ' })
        assertEquals("the CLI and the package disagree", me.safetyNumber(peer), digits)
    }

    @Test
    fun `the REPL reaches the pairing package`() {
        val me = fx.client("me")
        val out = fx.repl(me, "/qr").joinToString("\n")
        assertTrue("the share text is missing", out.contains(me.address))
        assertTrue("no QR was rendered", out.contains("██"))

        val other = fx.client("other", publish = false)
        val scanned = fx.repl(me, "/scan ${other.address}")
        assertTrue(scanned.first().contains("added"))
        assertNotNull("the scan never reached ContactStore", me.contacts.get(other.address))

        assertTrue(
            "scanning our own code must be refused",
            fx.repl(me, "/scan ${me.address}").first().contains("OWN_KEY"),
        )
    }

    @Test
    fun `the REPL reaches the scheduled package`() {
        val a = fx.client("a")
        val b = fx.client("b")
        assertTrue(fx.repl(a, "/schedule ${b.address} +2h see you").first().contains("queued"))
        val queued = a.scheduled.all().single()
        assertEquals("see you", queued.content)

        assertTrue(fx.repl(a, "/scheduled").any { it.contains(queued.id.take(8)) })
        assertTrue(fx.repl(a, "/cancel ${queued.id.take(8)}").first().contains("cancelled"))
        assertEquals(
            com.oshi.desktop.scheduled.ScheduledMessage.Status.CANCELLED,
            a.scheduled.get(queued.id)!!.status,
        )
    }

    @Test
    fun `the REPL reaches the group package`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val out = fx.repl(a, "/group new standup ${b.address}")
        assertTrue(out.first().contains("created"))

        val g = a.groups.all().single()
        assertEquals("standup", g.name)
        assertTrue("the creator must be an admin", g.isAdmin(a.address))
        assertTrue(fx.repl(a, "/groups").any { it.contains("standup") })

        // B receives the definition and the `created` twin the broadcast also sent.
        b.router.poll()
        assertEquals("the definition never reached B's ingest path", 1, b.groups.all().size)
        assertEquals("standup", b.groups.all().single().name)
    }

    @Test
    fun `the REPL reaches the sync package`() {
        val a = fx.client("a")
        a.contacts.seen("AAAA", System.currentTimeMillis(), "someone")
        // No /v2/sync routes on this relay, so the call FAILS — and that failure is the
        // proof the command reached the network layer rather than stopping at a stub.
        val out = fx.repl(a, "/sync push").joinToString(" ")
        assertTrue("the command never reached SyncEngine: $out", out.contains("push failed"))
    }

    @Test
    fun `blocked conversations are withheld from chats and listed by blocked`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        fx.seedIncoming(me, peer, "msg-b", "hello")
        me.block(peer)

        // The empty state is LOCALISED (PARITY.md 1.5), so this compares against the
        // catalog rather than against the English words it used to hardcode. A test that
        // asserted "none yet" would pass or fail on the machine's default locale, which
        // is the exact vacuous-assertion trap the i18n audit exists to prevent — and on a
        // French-defaulted JVM the old assertion fails while the code is perfectly right.
        assertTrue(
            "a blocked peer's thread is still shown in /chats",
            fx.repl(me, "/chats").any { it.contains(com.oshi.desktop.i18n.Strings.get("messages.empty")) },
        )
        assertTrue(
            "conversationsIncludingBlocked has no reader",
            fx.repl(me, "/blocked").any { it.contains("[blocked]") },
        )
    }

    /**
     * A block set under one base64 spelling has to be cleared by an unblock under another.
     * iOS compares raw strings on unblock and normalised ones on `isBlocked`, so a contact
     * can stay blocked forever (PARITY.md 0.21 defect 4).
     */
    @Test
    fun `unblock clears every spelling of the key`() {
        val me = fx.client("me")
        val padded = "0123456789012345678901234567890123456789012="
        val unpadded = padded.trimEnd('=')
        me.block(padded)
        me.block(unpadded)

        fx.repl(me, "/unblock $padded")

        assertFalse("the other spelling stayed blocked", me.contacts.get(unpadded)!!.blocked)
        assertFalse(me.contacts.get(padded)!!.blocked)
    }

    // ============================================================ row 0.27 — LoRa

    /**
     * The whole receive chain over a REAL socket: `/lora attach` → `LoRaLink` → stream
     * framing → `FromRadio` → `MeshPacket` → `LoRaInbound` → the store.
     *
     * Against `FakeMeshtasticNode`, never a radio (PARITY.md row 0.27).
     */
    @Test
    fun `interop text from a node lands in its own quarantined conversation`() {
        val me = fx.client("me")
        com.oshi.desktop.lora.FakeMeshtasticNode().use { node ->
            val heard = java.util.concurrent.CountDownLatch(1)
            val published = java.util.concurrent.CountDownLatch(1)
            me.onLoRa = { if (it.contains("interop text")) heard.countDown() }
            me.onMessage = { if (it.transport == "lora") published.countDown() }

            fx.repl(me, "/lora attach ${node.host} ${node.port}")
            assertTrue("the link never handshaked", node.handshake.await(10, java.util.concurrent.TimeUnit.SECONDS))

            node.push(interopTextFrom(nodeNum = 0x1234u, text = "hello from a stock radio"))

            assertTrue("the interop text never reached the client", heard.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(
                "a stored radio row never reached the UI/notification callback",
                published.await(10, java.util.concurrent.TimeUnit.SECONDS),
            )
            val convos = me.conversationsIncludingBlocked().map { it.conversationId }
            assertTrue(
                "interop text must be quarantined under its own key, never a peer thread: $convos",
                convos.any { it.startsWith("lora!") },
            )
            me.loraDetach()
        }
    }

    /**
     * The one that matters for what a UI may claim: an OSHI envelope over LoRa is sealed
     * with the LEGACY ratchet this client does not implement, so it must be REPORTED and
     * never stored as a message. A row here would be indistinguishable from one the
     * ratchet vouched for.
     */
    @Test
    fun `an OSHI envelope over LoRa is reported unreadable and never stored`() {
        val me = fx.client("me")
        com.oshi.desktop.lora.FakeMeshtasticNode().use { node ->
            val reported = java.util.concurrent.CountDownLatch(1)
            me.onLoRa = { if (it.contains("UNREADABLE")) reported.countDown() }

            fx.repl(me, "/lora attach ${node.host} ${node.port}")
            assertTrue(node.handshake.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val before = me.conversationsIncludingBlocked().size

            // A complete "OM" envelope addressed to this client, built the way
            // LoRaInboundTest builds one, and chunked into real frames.
            val env = com.oshi.desktop.lora.LoRaSecureMessage(
                id = "11111111-2222-3333-4444-555555555555",
                senderAddress = "them",
                recipientAddress = me.address,
                encryptedContent = com.oshi.desktop.lora.LoRaEncryptedContent("Y2lwaGVy", "", "them", LORA_T0),
                unixMillis = LORA_T0,
                isRead = false,
                deliveryStatus = com.oshi.desktop.lora.LoRaSecureMessage.STATUS_SENT,
                senderPublicKey = "them",
                recipientPublicKey = me.address,
            )
            com.oshi.desktop.lora.LoRaFrame.frames(0xCAFEBABEu, env.toWireJson())!!
                .forEach { node.push(fromRadioWith(oshiPacket(0x99u, it))) }

            assertTrue("an unreadable envelope was not reported", reported.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(
                "an envelope this client cannot open was stored as if it were a message",
                before, me.conversationsIncludingBlocked().size,
            )
            me.loraDetach()
        }
    }

    /**
     * A plausible Unix-millis stamp. NOT zero: [com.oshi.desktop.msg.WireClock] refuses an
     * epoch outside [2000, 2100] rather than converting it, which is the guard that catches
     * an un-converted timestamp — and it caught this test passing 0 on the first run.
     */
    private val LORA_T0 = 1_787_000_000_000L

    private fun interopTextFrom(nodeNum: UInt, text: String): ByteArray {
        val data = com.oshi.desktop.lora.ProtoWriter()
            .varint(1, com.oshi.desktop.lora.LoRaProto.PORT_TEXT)
            .bytes(2, text.toByteArray(Charsets.UTF_8)).data
        return fromRadioWith(
            com.oshi.desktop.lora.ProtoWriter().fixed32(1, nodeNum).bytes(4, data).data
        )
    }

    private fun oshiPacket(nodeNum: UInt, payload: ByteArray): ByteArray {
        val data = com.oshi.desktop.lora.ProtoWriter()
            .varint(1, com.oshi.desktop.lora.LoRaProto.PORT_OSHI)
            .bytes(2, payload).data
        return com.oshi.desktop.lora.ProtoWriter().fixed32(1, nodeNum).bytes(4, data).data
    }

    /** `FromRadio.packet` is field 2 — the variant this client reads. */
    private fun fromRadioWith(meshPacket: ByteArray): ByteArray =
        com.oshi.desktop.lora.ProtoWriter().bytes(2, meshPacket).data

    // ============================================================ row 0.11 — account deletion

    @Test
    fun `deleteaccount without the word confirm reaches nothing`() {
        val me = fx.client("me")
        val addressBefore = me.address

        val out = fx.repl(me, "/deleteaccount")

        assertTrue("the usage line is what an unconfirmed call must produce", out.first().contains("confirm"))
        assertEquals("an unconfirmed /deleteaccount wiped the identity", addressBefore, me.address)
        assertNotNull("the vault lost the account on a command that never confirmed", com.oshi.desktop.store.IdentityStore.load(me.vault))
    }

    @Test
    fun `deleteaccount confirm erases the server and then this machine`() {
        val me = fx.client("me")
        val address = me.address
        assertTrue("the fixture publishes a bundle, so the relay must hold one", fx.relay.hasBundle(address))

        val out = fx.repl(me, "/deleteaccount confirm")

        assertTrue("the receipt never reached the REPL: $out", out.any { it.contains("account erased") })
        assertFalse("the server still holds the bundle — the DELETE never left", fx.relay.hasBundle(address))
        assertNull("the local half did not run", com.oshi.desktop.store.IdentityStore.load(me.vault))
    }

    @Test
    fun `a server that refuses leaves the account usable, because the key IS the credential`() {
        val me = fx.client("me")
        val address = me.address
        fx.relay.failAccountDelete = true

        val out = fx.repl(me, "/deleteaccount confirm")

        assertTrue("a refusal must be reported as one: $out", out.any { it.contains("delete failed") })
        assertNotNull(
            "the vault was wiped after the server refused — the signing key that authorises " +
                "the erase is gone, so the server copy can never be deleted by anyone",
            com.oshi.desktop.store.IdentityStore.load(me.vault),
        )
        assertTrue("the server copy is still there and now unreachable", fx.relay.hasBundle(address))
    }

    @Test
    fun `an unknown command does not reach anything`() {
        val me = fx.client("me")
        assertEquals(listOf("   unknown command"), fx.repl(me, "/nope"))
        assertFalse("quit must stop the loop", ClientCommands.execute(me, "/quit") {})
    }

    @Test
    fun `parsing a schedule time accepts the four suffixes and nothing else`() {
        val now = 1_000_000L
        assertEquals(now + 30_000, ClientCommands.parseWhen("+30s", now))
        assertEquals(now + 900_000, ClientCommands.parseWhen("+15m", now))
        assertEquals(now + 7_200_000, ClientCommands.parseWhen("+2h", now))
        assertEquals(now + 86_400_000, ClientCommands.parseWhen("+1d", now))
        assertEquals(5L, ClientCommands.parseWhen("5", now))
        assertNull(ClientCommands.parseWhen("+3y", now))
        assertNull(ClientCommands.parseWhen("soon", now))
    }
}
