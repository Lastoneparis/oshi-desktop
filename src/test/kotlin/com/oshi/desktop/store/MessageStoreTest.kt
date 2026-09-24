package com.oshi.desktop.store

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 0.13 — durable local message storage.
 *
 * Every test here proves ONE claim made in [MessageStore]'s doc comment, by name, so a
 * regression in that file points straight back at the sentence it broke.
 */
class MessageStoreTest {

    private val dir: File = Files.createTempDirectory("oshi-messages-test").toFile()
    private fun store(): MessageStore = MessageStore(dir)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // A real-looking epoch-millis base (2026-ish). The tests below only ever care about
    // RELATIVE order, but Message.init enforces PLAN.md §4.2's magnitude guard, so a
    // bare "1_000L" (1970) would trip the very check this store is supposed to have.
    private val baseMs = 1_770_000_000_000L

    private fun msg(
        id: String,
        conv: String = "peer-a",
        sentAtMs: Long,
        content: String = "hello",
        fromMe: Boolean = false,
        transport: String = "relay",
    ) = Message(
        id = id,
        conversationId = conv,
        senderAddress = if (fromMe) "me" else conv,
        recipientAddress = if (fromMe) conv else "me",
        fromMe = fromMe,
        content = content,
        sentAtMs = baseMs + sentAtMs,
        sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
        transport = transport,
    )

    // ------------------------------------------------------------------------ durability

    @Test
    fun `history survives a reopen of the store`() {
        store().append(msg("m1", sentAtMs = 1_000L, content = "first"))
        store().append(msg("m2", sentAtMs = 2_000L, content = "second"))

        // A brand-new MessageStore instance over the SAME directory is what "the app
        // restarted" looks like — nothing in this class may live only in memory.
        val reopened = store().messages("peer-a")

        assertEquals(2, reopened.size)
        assertEquals("first", reopened[0].content)
        assertEquals("second", reopened[1].content)
    }

    @Test
    fun `encrypted history never writes message plaintext and survives a reopen`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val encrypted = MessageStore(dir, key)
        encrypted.append(msg("m1", sentAtMs = 1_000L, content = "never on disk in clear"))

        val file = File(dir, encrypted.fileNameFor("peer-a"))
        val raw = file.readText()
        assertTrue("the envelope, not a message object, must be written", raw.contains("\"nonce\""))
        assertTrue("the envelope, not a message object, must be written", raw.contains("\"ct\""))
        assertTrue("message content leaked to the journal", !raw.contains("never on disk in clear"))
        assertEquals(
            "never on disk in clear",
            MessageStore(dir, key).messages("peer-a").single().content,
        )
    }

    @Test
    fun `opening a legacy plaintext journal with a history key migrates it atomically`() {
        val plaintext = store()
        plaintext.append(msg("m1", sentAtMs = 1_000L, content = "old local history"))
        val file = File(dir, plaintext.fileNameFor("peer-a"))
        assertTrue(file.readText().contains("old local history"))

        val key = ByteArray(32) { (0x40 + it).toByte() }
        val migrated = MessageStore(dir, key)
        assertEquals("old local history", migrated.messages("peer-a").single().content)
        assertTrue("migration left plaintext behind", !file.readText().contains("old local history"))
        assertEquals("old local history", MessageStore(dir, key).messages("peer-a").single().content)
    }

    @Test
    fun `tampered encrypted row is rejected without being read as plaintext`() {
        val key = ByteArray(32) { (0x20 + it).toByte() }
        val encrypted = MessageStore(dir, key)
        encrypted.append(msg("m1", sentAtMs = 1_000L, content = "intact"))
        encrypted.append(msg("m2", sentAtMs = 2_000L, content = "must not appear"))
        val file = File(dir, encrypted.fileNameFor("peer-a"))
        val lines = file.readLines().toMutableList()
        val envelope = JSONObject(lines[1])
        val ct = envelope.getString("ct")
        envelope.put("ct", (if (ct[0] == 'A') "B" else "A") + ct.drop(1))
        lines[1] = envelope.toString()
        file.writeText(lines.joinToString("\n", postfix = "\n"))

        assertEquals(listOf("intact"), MessageStore(dir, key).messages("peer-a").map { it.content })
    }

    // ------------------------------------------------------------------------ dedup

    @Test
    fun `the same msgId arriving over two transports yields one message`() {
        val s = store()
        val viaMesh = msg("shared-id", sentAtMs = 5_000L, content = "hi", transport = "mesh")
        val viaRelay = viaMesh.copy(transport = "relay") // same logical message, different path

        val first = s.append(viaMesh)
        val second = s.append(viaRelay)

        assertEquals(MessageStore.AppendOutcome.INSERTED, first)
        assertEquals(MessageStore.AppendOutcome.DUPLICATE, second)

        val all = s.messages("peer-a")
        assertEquals("the message must appear exactly once regardless of how many transports delivered it",
            1, all.size)
        assertEquals("shared-id", all.single().id)
    }

    @Test
    fun `a genuine edit under the same id is not treated as a duplicate`() {
        val s = store()
        s.append(msg("m1", sentAtMs = 1_000L, content = "typo"))
        val outcome = s.append(msg("m1", sentAtMs = 1_000L, content = "fixed"))

        assertEquals(MessageStore.AppendOutcome.UPDATED, outcome)
        val all = s.messages("peer-a")
        assertEquals(1, all.size)
        assertEquals("fixed", all.single().content)
    }

    // ------------------------------------------------------------------------ ordering

    @Test
    fun `out-of-order arrival still reads back in chronological order`() {
        val s = store()
        // B was SENT after A (higher sentAtMs) but ARRIVES first at this node — the
        // exact mesh-vs-relay race MessageStore's doc comment describes.
        s.append(msg("B", sentAtMs = 2_000L, content = "second"))
        s.append(msg("A", sentAtMs = 1_000L, content = "first"))

        val ordered = s.messages("peer-a")
        assertEquals(listOf("first", "second"), ordered.map { it.content })
    }

    @Test
    fun `equal timestamps break ties by local arrival order, deterministically`() {
        val s = store()
        s.append(msg("x", sentAtMs = 1_000L, content = "x"))
        s.append(msg("y", sentAtMs = 1_000L, content = "y"))

        val ordered = s.messages("peer-a")
        assertEquals(listOf("x", "y"), ordered.map { it.content })
    }

    // ------------------------------------------------------------------------ crash safety

    @Test
    fun `a torn write on the last line does not lose earlier history`() {
        val s = store()
        repeat(20) { i -> s.append(msg("m$i", sentAtMs = 1_000L + i, content = "msg-$i")) }

        val file = File(dir, s.fileNameFor("peer-a"))
        assertTrue(file.isFile)

        // Simulate a crash mid-append: a write() that copied only PART of the next
        // line's bytes before the process died. This does NOT touch any byte that was
        // already durable — it only appends a fragment, exactly what a torn write does.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(file.length())
            raf.write("{\"id\":\"m20\",\"conversationId\":\"peer-a\",\"content\":\"trunc".toByteArray(Charsets.UTF_8))
            // deliberately no closing brace, no trailing newline
        }

        // A fresh store (fresh in-memory state) reading the damaged file must recover
        // all 20 complete records and must not throw.
        val recovered = store().messages("peer-a")
        assertEquals(20, recovered.size)
        assertEquals((0 until 20).map { "msg-$it" }.toSet(), recovered.map { it.content }.toSet())
    }

    @Test
    fun `a torn write in the middle of the file still yields every parseable record around it`() {
        // A stricter variant: corruption is not necessarily the LAST line (e.g. a
        // filesystem that reorders block writes). The reader's contract is "a line that
        // fails to parse costs that one record," not "costs everything downstream of it."
        val file = File(dir, MessageStore(dir).fileNameFor("peer-a"))
        file.parentFile.mkdirs()
        val good1 = msg("m1", sentAtMs = 1_000L, content = "one").copy(localSeq = 0)
        val bad = "{not json"
        val good2 = msg("m2", sentAtMs = 2_000L, content = "two").copy(localSeq = 1)
        file.writeText(good1.toJson().toString() + "\n" + bad + "\n" + good2.toJson().toString() + "\n")

        val recovered = MessageStore(dir).messages("peer-a")
        assertEquals(setOf("one", "two"), recovered.map { it.content }.toSet())
    }

    // ------------------------------------------------------------------------ cheap append

    @Test
    fun `appending to a large conversation never rewrites earlier history`() {
        val s = store()
        val conv = "big-peer"
        repeat(4_999) { i -> s.append(msg("m$i", conv = conv, sentAtMs = 1_000L + i, content = "x".repeat(20))) }

        val file = File(dir, s.fileNameFor(conv))
        val sizeBefore = file.length()
        val prefixBefore = file.readBytes().copyOfRange(0, sizeBefore.toInt())
        // The inode identity, not just the resulting bytes: a correct-but-wasteful
        // "rewrite everything, then verify the bytes match" implementation (e.g. routing
        // through AtomicFile's temp-file-then-rename) would still pass a pure byte-prefix
        // comparison, because the FINAL content is identical either way. A rename onto
        // the target necessarily replaces its inode, though — a true O_APPEND write never
        // does. This is the one measurement that actually distinguishes "appended" from
        // "correctly reconstructed from scratch," which is the property this test exists
        // to prove (verified by mutating appendLineDurable() to the rewrite-via-AtomicFile
        // shape during development: the byte-prefix assertions alone still passed, and
        // only this inode check caught it).
        val inodeBefore = runCatching { Files.getAttribute(file.toPath(), "unix:ino") }.getOrNull()

        s.append(msg("m4999", conv = conv, sentAtMs = 1_000L + 4_999, content = "x".repeat(20)))

        val sizeAfter = file.length()
        val prefixAfter = file.readBytes().copyOfRange(0, sizeBefore.toInt())
        val inodeAfter = runCatching { Files.getAttribute(file.toPath(), "unix:ino") }.getOrNull()

        assertTrue("appending one message must not shrink or truncate existing bytes",
            sizeAfter > sizeBefore)
        assertTrue(
            "every byte written before the append must be BYTE-IDENTICAL afterwards — " +
                "proof the append did not rewrite the file",
            prefixBefore.contentEquals(prefixAfter),
        )
        if (inodeBefore != null && inodeAfter != null) {
            assertEquals(
                "the append must write into the SAME file (same inode), never replace it " +
                    "via a temp-file-then-rename — that would be a rewrite wearing an append's clothes",
                inodeBefore, inodeAfter,
            )
        }
        val grew = sizeAfter - sizeBefore
        assertTrue(
            "one more message should cost roughly one line (~100s of bytes), not grow " +
                "with the size of the whole conversation so far (was $sizeBefore bytes); " +
                "got a $grew-byte write",
            grew < 2_000,
        )
    }

    @Test
    fun `a growing conversation stays exactly one file on disk`() {
        val s = store()
        val conv = "one-file-peer"
        repeat(500) { i -> s.append(msg("m$i", conv = conv, sentAtMs = 1_000L + i)) }

        val messageFiles = (dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: emptyArray())
        assertEquals("one conversation must be one file, regardless of message count",
            1, messageFiles.size)
    }

    // ------------------------------------------------------------------------ mutators

    @Test
    fun `delivery status only ever advances, and a failed message stays failed`() {
        val s = store()
        s.append(msg("m1", sentAtMs = 1_000L).copy(deliveryStatus = DeliveryStatus.SENT))

        s.advanceDeliveryStatus("peer-a", "m1", DeliveryStatus.READ)
        assertEquals(DeliveryStatus.READ, s.message("peer-a", "m1")!!.deliveryStatus)

        // A stale "delivered" receipt arriving after "read" must not roll it back.
        s.advanceDeliveryStatus("peer-a", "m1", DeliveryStatus.DELIVERED)
        assertEquals(DeliveryStatus.READ, s.message("peer-a", "m1")!!.deliveryStatus)

        s.append(msg("m2", sentAtMs = 2_000L).copy(deliveryStatus = DeliveryStatus.FAILED))
        s.advanceDeliveryStatus("peer-a", "m2", DeliveryStatus.SENT)
        assertEquals("FAILED is terminal", DeliveryStatus.FAILED, s.message("peer-a", "m2")!!.deliveryStatus)
    }

    @Test
    fun `delete for everyone clears content but keeps the row`() {
        val s = store()
        s.append(msg("m1", sentAtMs = 1_000L, content = "secret plan"))
        s.deleteForEveryone("peer-a", "m1", atMs = 1_500L)

        val m = s.message("peer-a", "m1")!!
        assertNull(m.content)
        assertTrue(m.isDeletedForEveryone)
        assertEquals(1, s.messages("peer-a").size)
    }

    @Test
    fun `an out-of-range timestamp is rejected rather than silently stored`() {
        var threw = false
        try {
            // Bypasses the msg() helper's baseMs offset deliberately: a raw 200ms is
            // ~1970, outside the plausible [2000, 2100) range Message.init enforces.
            Message(
                id = "bad", conversationId = "peer-a", senderAddress = "peer-a", recipientAddress = "me",
                fromMe = false, content = "x", sentAtMs = 200L, sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a magnitude-guard violation (PLAN.md §4.2) must throw, not corrupt ordering", threw)
    }

    @Test
    fun `conversations lists every conversation with its most recent activity`() {
        val s = store()
        s.append(msg("a1", conv = "peer-a", sentAtMs = 1_000L))
        s.append(msg("b1", conv = "peer-b", sentAtMs = 5_000L))
        s.append(msg("a2", conv = "peer-a", sentAtMs = 2_000L))

        val summaries = s.conversations()
        assertEquals(2, summaries.size)
        // peer-b's single message is more recent than peer-a's latest, so it sorts first.
        assertEquals("peer-b", summaries.first().conversationId)
        val peerA = summaries.first { it.conversationId == "peer-a" }
        assertEquals(2, peerA.messageCount)
        assertNotNull(peerA.lastMessage)
    }
}
