package com.oshi.desktop.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The delivery lifecycle of PARITY.md row 0.25 — the status machine, the cancel race, and
 * the lateness a desktop client has to be honest about.
 */
class ScheduledMessageRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val PEER = "kr/6aAArDrl91TOXf/bjv2u1SiRf+H8gIi8BBgPYpi0="

    private fun store() = ScheduledMessageStore(File(tmp.newFolder(), "scheduled-messages.json"))

    /** Records what it was asked to send and answers whatever the test says. */
    private class RecordingSender(
        private val answer: (ScheduledMessage) -> ScheduledMessageRunner.Outcome,
    ) : ScheduledMessageRunner.Sender {
        val sent = mutableListOf<ScheduledMessage>()
        override fun send(message: ScheduledMessage): ScheduledMessageRunner.Outcome {
            sent.add(message)
            return answer(message)
        }
    }

    private fun runner(store: ScheduledMessageStore, sender: ScheduledMessageRunner.Sender) =
        ScheduledMessageRunner(store, sender)

    // ────────────────────────────────────── the happy path

    @Test
    fun `a due message is sent once and settles as SENT`() {
        val s = store()
        val sender = RecordingSender { ScheduledMessageRunner.Outcome.SENT }
        val r = runner(s, sender)
        val m = r.schedule(PEER, "hello", scheduledAtMs = 2_000L, nowMs = 1_000L)

        assertEquals(0, r.runDue(nowMs = 1_999L).attempted)
        assertTrue("nothing may be sent before it is due", sender.sent.isEmpty())

        val run = r.runDue(nowMs = 2_000L)
        assertEquals(1, run.attempted)
        assertEquals(1, run.sent)
        assertEquals(listOf("hello"), sender.sent.map { it.content })
        assertEquals(ScheduledMessage.Status.SENT, s.get(m.id)!!.status)

        // …and a second sweep sends nothing.
        assertEquals(0, r.runDue(nowMs = 3_000L).attempted)
        assertEquals(1, sender.sent.size)
    }

    /**
     * A backlog goes out in SCHEDULE order, not creation order — the only order that reads
     * as a conversation after a laptop has been shut for a weekend. Neither phone specifies
     * one (`swift:172` iterates `indices`, `.kt:306-308` filters a StateFlow).
     */
    @Test
    fun `a backlog is sent oldest scheduled first regardless of creation order`() {
        val s = store()
        val sender = RecordingSender { ScheduledMessageRunner.Outcome.SENT }
        val r = runner(s, sender)
        r.schedule(PEER, "third", scheduledAtMs = 3_000L, nowMs = 1L)
        r.schedule(PEER, "first", scheduledAtMs = 1_500L, nowMs = 1L)
        r.schedule(PEER, "second", scheduledAtMs = 2_000L, nowMs = 1L)

        r.runDue(nowMs = 9_000L)
        assertEquals(listOf("first", "second", "third"), sender.sent.map { it.content })
    }

    // ────────────────────────────────────── GUARD: only PENDING is ever sent

    /**
     * GUARD — a cancelled message is never sent, and a cancel that lands between the `due`
     * snapshot and the send still wins.
     *
     * The Android worker has exactly this hole: `getContentToSend(messageId)`
     * (`ScheduledMessageManager.kt:243-245`) returns the body whatever the status is, so a
     * cancel that loses the race against `WorkManager.cancelUniqueWork` (`:173`) still
     * sends the message the user cancelled.
     */
    @Test
    fun `a cancelled message is never sent even when cancelled mid-sweep`() {
        val s = store()
        val r0 = ScheduledMessageRunner(s, { ScheduledMessageRunner.Outcome.SENT })
        val alpha = r0.schedule(PEER, "alpha", scheduledAtMs = 2_000L, nowMs = 1_000L)
        val beta = r0.schedule(PEER, "beta", scheduledAtMs = 2_100L, nowMs = 1_000L)

        // The sender cancels beta while alpha is being delivered — the race.
        val sender = RecordingSender { m ->
            if (m.id == alpha.id) s.cancel(beta.id)
            ScheduledMessageRunner.Outcome.SENT
        }
        val run = ScheduledMessageRunner(s, sender).runDue(nowMs = 5_000L)

        assertEquals(listOf("alpha"), sender.sent.map { it.content })
        assertEquals(1, run.sent)
        assertEquals(ScheduledMessage.Status.CANCELLED, s.get(beta.id)!!.status)
    }

    @Test
    fun `an already cancelled message is not due`() {
        val s = store()
        val r = runner(s, RecordingSender { ScheduledMessageRunner.Outcome.SENT })
        val m = r.schedule(PEER, "x", scheduledAtMs = 2_000L, nowMs = 1_000L)
        assertTrue(r.cancel(m.id))
        assertTrue(s.due(9_000L).isEmpty())
        assertEquals(0, r.runDue(nowMs = 9_000L).attempted)
    }

    /**
     * GUARD — [ScheduledMessageStore.settle] refuses to move a non-PENDING row.
     *
     * Nothing may resurrect a CANCELLED or SENT message. `settle` also refuses PENDING as a
     * TARGET, because "settle it back to pending" is [ScheduledMessageStore.retryLater]'s
     * job and conflating the two loses the attempt count.
     */
    @Test
    fun `settle only moves a pending row and only out of pending`() {
        val s = store()
        val r = runner(s, RecordingSender { ScheduledMessageRunner.Outcome.SENT })
        val m = r.schedule(PEER, "x", scheduledAtMs = 2_000L, nowMs = 1_000L)

        assertTrue(s.settle(m.id, ScheduledMessage.Status.SENT))
        assertFalse("a SENT row cannot be re-settled", s.settle(m.id, ScheduledMessage.Status.FAILED))
        assertEquals(ScheduledMessage.Status.SENT, s.get(m.id)!!.status)
        assertFalse("nothing may resurrect it", s.retryLater(m.id))

        val e = runCatching { s.settle(m.id, ScheduledMessage.Status.PENDING) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
    }

    @Test
    fun `a sent message cannot be cancelled or edited`() {
        val s = store()
        val r = runner(s, RecordingSender { ScheduledMessageRunner.Outcome.SENT })
        val m = r.schedule(PEER, "x", scheduledAtMs = 2_000L, nowMs = 1_000L)
        r.runDue(nowMs = 2_000L)
        assertFalse(s.cancel(m.id))
        assertFalse(s.editContent(m.id, "too late"))
        assertEquals("x", s.get(m.id)!!.content)
    }

    // ────────────────────────────────────── the three-answer send seam

    /**
     * iOS: *"Marking a refused message `.sent` and dropping it is exactly the silent loss
     * this status field exists to prevent."* (`swift:200-203`). A rate limit leaves the row
     * PENDING so the next sweep retries it; Android instead marks it FAILED and then asks
     * WorkManager to retry a row it has already failed (`.kt:397-407`).
     */
    @Test
    fun `a deferred send leaves the message pending and counts the attempt`() {
        val s = store()
        var answer = ScheduledMessageRunner.Outcome.DEFERRED
        val sender = RecordingSender { answer }
        val r = runner(s, sender)
        val m = r.schedule(PEER, "x", scheduledAtMs = 2_000L, nowMs = 1_000L)

        val first = r.runDue(nowMs = 2_000L)
        assertEquals(1, first.deferred)
        assertEquals(0, first.sent)
        assertEquals(ScheduledMessage.Status.PENDING, s.get(m.id)!!.status)
        assertEquals(1, s.get(m.id)!!.attempts)

        r.runDue(nowMs = 2_030L)
        assertEquals(2, s.get(m.id)!!.attempts)

        answer = ScheduledMessageRunner.Outcome.SENT
        r.runDue(nowMs = 2_060L)
        assertEquals(ScheduledMessage.Status.SENT, s.get(m.id)!!.status)
        assertEquals(3, s.get(m.id)!!.attempts)
    }

    /** A permanent refusal is terminal — iOS's `.tooLong` → `.failed` (`swift:206-212`). */
    @Test
    fun `a failed send is terminal`() {
        val s = store()
        val sender = RecordingSender { ScheduledMessageRunner.Outcome.FAILED }
        val r = runner(s, sender)
        val m = r.schedule(PEER, "x", scheduledAtMs = 2_000L, nowMs = 1_000L)

        assertEquals(1, r.runDue(nowMs = 2_000L).failed)
        assertEquals(ScheduledMessage.Status.FAILED, s.get(m.id)!!.status)
        assertEquals(0, r.runDue(nowMs = 9_000L).attempted)
        assertEquals(1, sender.sent.size)
    }

    /**
     * A throwing sender is DEFERRED, not FAILED: an exception is an unclassified failure,
     * and marking a message failed forever on one transient stack trace is the silent loss
     * the status field exists to prevent.
     */
    @Test
    fun `a throwing sender defers rather than failing the message`() {
        val s = store()
        var boom = true
        val sender = object : ScheduledMessageRunner.Sender {
            var calls = 0
            override fun send(message: ScheduledMessage): ScheduledMessageRunner.Outcome {
                calls++
                if (boom) throw IllegalStateException("socket closed")
                return ScheduledMessageRunner.Outcome.SENT
            }
        }
        val r = runner(s, sender)
        val m = r.schedule(PEER, "x", scheduledAtMs = 2_000L, nowMs = 1_000L)

        assertEquals(1, r.runDue(nowMs = 2_000L).deferred)
        assertEquals(ScheduledMessage.Status.PENDING, s.get(m.id)!!.status)

        boom = false
        assertEquals(1, r.runDue(nowMs = 2_100L).sent)
        assertEquals(2, sender.calls)
    }

    // ────────────────────────────────────── idempotence within one sweep

    /**
     * At most one attempt per message per sweep — iOS's `sentDuringBGTask` (`swift:104-113`,
     * "FIX R22"), whose own bug shape is that it is cleared by the BGTask path and never by
     * the timer path, so it protects one of two entry points. Here there is one.
     */
    @Test
    fun `a message is attempted at most once per sweep`() {
        val s = store()
        val sender = RecordingSender { ScheduledMessageRunner.Outcome.DEFERRED }
        val r = runner(s, sender)
        r.schedule(PEER, "x", scheduledAtMs = 2_000L, nowMs = 1_000L)

        val run = r.runDue(nowMs = 9_000L)
        assertEquals(1, run.attempted)
        assertEquals(1, sender.sent.size)
    }

    // ────────────────────────────────────── lateness

    /**
     * A late message is still sent — always. The threshold only decides whether the caller
     * should say so. A desktop client sends on its next sweep, so "how late" is the number
     * a user is owed rather than a reason to drop their message.
     */
    @Test
    fun `a late send is still sent and reports how late it was`() {
        val s = store()
        val sender = RecordingSender { ScheduledMessageRunner.Outcome.SENT }
        val r = runner(s, sender)
        r.schedule(PEER, "birthday", scheduledAtMs = 1_000_000L, nowMs = 1L)

        val threeDaysLater = 1_000_000L + 3 * 24 * 3600 * 1000L
        val run = r.runDue(nowMs = threeDaysLater)

        assertEquals(1, run.sent)
        assertTrue("the laptop was shut; the message still goes", run.wasLate)
        assertEquals(3 * 24 * 3600 * 1000L, run.lateByMs)
        assertEquals(listOf("birthday"), sender.sent.map { it.content })
    }

    @Test
    fun `an on-time send is not flagged as late`() {
        val s = store()
        val r = runner(s, RecordingSender { ScheduledMessageRunner.Outcome.SENT })
        r.schedule(PEER, "x", scheduledAtMs = 2_000L, nowMs = 1_000L)
        val run = r.runDue(nowMs = 2_000L + 30_000L)   // one poll interval late
        assertFalse(run.wasLate)
        assertEquals(0L, run.lateByMs)
    }

    // ────────────────────────────────────── scheduling refusals

    /**
     * GUARD — a time in the past is refused, where both phones accept it and send at once
     * (`.kt:260-263`: *"scheduled in the past — delivering immediately"*).
     *
     * On this client the two cases are indistinguishable at the call site: a desktop that
     * was closed for a week has a correct clock and a schedule full of past times, while a
     * user picking yesterday has made a mistake. "Send now" should go through the ordinary
     * send path, not through a queue whose contract is deferral.
     */
    @Test
    fun `scheduling in the past is refused`() {
        val r = runner(store(), RecordingSender { ScheduledMessageRunner.Outcome.SENT })
        for (t in listOf(999L, 1_000L, 0L, -1L)) {
            val e = runCatching { r.schedule(PEER, "x", scheduledAtMs = t, nowMs = 1_000L) }.exceptionOrNull()
            assertTrue("t=$t must be refused, got $e", e is IllegalArgumentException)
        }
        r.schedule(PEER, "x", scheduledAtMs = 1_001L, nowMs = 1_000L)   // one ms ahead is fine
    }

    @Test
    fun `scheduling an empty body is refused`() {
        val r = runner(store(), RecordingSender { ScheduledMessageRunner.Outcome.SENT })
        val e = runCatching { r.schedule(PEER, "", scheduledAtMs = 2_000L, nowMs = 1_000L) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
    }

    // ────────────────────────────────────── persistence

    @Test
    fun `the schedule survives a restart and is sent by the next sweep`() {
        val file = File(tmp.newFolder(), "scheduled-messages.json")
        val m = ScheduledMessageRunner(ScheduledMessageStore(file), { ScheduledMessageRunner.Outcome.SENT })
            .schedule(PEER, "after reboot", scheduledAtMs = 2_000L, nowMs = 1_000L)

        // A brand-new process: new store object, same file.
        val reopened = ScheduledMessageStore(file)
        assertEquals(1, reopened.pending().size)
        assertEquals("after reboot", reopened.get(m.id)!!.content)

        val sender = RecordingSender { ScheduledMessageRunner.Outcome.SENT }
        ScheduledMessageRunner(reopened, sender).runDue(nowMs = 5_000L)
        assertEquals(listOf("after reboot"), sender.sent.map { it.content })
        assertEquals(ScheduledMessage.Status.SENT, ScheduledMessageStore(file).get(m.id)!!.status)
    }

    /**
     * A damaged schedule RAISES. Reading it as "no scheduled messages" would silently
     * cancel every one of them and the user finds out by the message never arriving — the
     * same call [com.oshi.desktop.store.ContactStore] makes, and the opposite of the one
     * [com.oshi.desktop.sync.SyncCursor] makes for a value that costs one replay.
     */
    @Test
    fun `a damaged schedule file raises rather than reading as empty`() {
        val file = File(tmp.newFolder(), "scheduled-messages.json")
        file.writeText("{ this is not json")
        val e = runCatching { ScheduledMessageStore(file).all() }.exceptionOrNull()
        assertTrue("expected ScheduledStoreException, got $e", e is ScheduledStoreException)
    }

    @Test
    fun `pending queries are scoped and exclude terminal rows`() {
        val s = store()
        val r = runner(s, RecordingSender { ScheduledMessageRunner.Outcome.SENT })
        val other = "Qm9iQm9iQm9iQm9iQm9iQm9iQm9iQm9iQm9iQm9iMTI="
        val a = r.schedule(PEER, "a", scheduledAtMs = 2_000L, nowMs = 1L)
        r.schedule(other, "b", scheduledAtMs = 3_000L, nowMs = 1L)
        val c = r.schedule(PEER, "c", scheduledAtMs = 4_000L, nowMs = 1L)
        s.cancel(c.id)

        assertEquals(listOf("a"), s.pendingFor(PEER).map { it.content })
        assertEquals(listOf("b"), s.pendingFor(other).map { it.content })
        assertEquals(2, s.pending().size)
        assertEquals(3, s.all().size)
        assertEquals(a.id, s.due(9_000L).first().id)
    }

    @Test
    fun `deleting is distinct from cancelling`() {
        val s = store()
        val r = runner(s, RecordingSender { ScheduledMessageRunner.Outcome.SENT })
        val m = r.schedule(PEER, "x", scheduledAtMs = 2_000L, nowMs = 1_000L)
        assertTrue(s.cancel(m.id))
        assertEquals("a cancel keeps the row so it cannot resurrect", 1, s.all().size)
        assertTrue(s.delete(m.id))
        assertNull(s.get(m.id))
        assertFalse(s.delete(m.id))
    }
}
