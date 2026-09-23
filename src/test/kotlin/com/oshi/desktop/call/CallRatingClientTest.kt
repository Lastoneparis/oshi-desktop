package com.oshi.desktop.call

import com.oshi.messenger.service.CallRatingPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * __CALL_RATING_2026_09_22__ — the two seams this client owns.
 *
 * WHETHER to ask is [CallRatingPolicy], compiled out of the Android tree and tested
 * there (`CallRatingPolicyTest`, 17 cases). Re-testing the rules here would test the
 * same object twice and say nothing about whether it is SHARED — which is the property
 * that matters, and which the `sourceSets` include list in `build.gradle.kts` enforces
 * by breaking this build if the file stops compiling from the Android tree.
 *
 * What is tested here is what the desktop adds: state that survives a restart in a file
 * rather than SharedPreferences, and a request body identical to the phones'.
 */
class CallRatingClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun client(
        at: Long = now,
        sent: MutableList<String> = mutableListOf(),
        key: String = "MY_PUBLIC_KEY",
        file: File = File(tmp.root, "call-rating.json"),
    ) = CallRatingClient(
        myPublicKey = key,
        stateFile = file,
        clock = { at },
        poster = { sent += it },
    )

    // ---------------------------------------------------------------- the ask

    @Test
    fun `a dropped call is offered for rating`() {
        val c = client()
        val p = c.callDidEnd("CALL-1", "peer", durationSeconds = 30, endedNormally = false)
        assertNotNull("a call that dropped is exactly the one worth asking about", p)
        assertEquals("CALL-1", p!!.callId)
        assertTrue(p.endedAbnormally)
        assertEquals(p, c.pending())
    }

    @Test
    fun `a call too short to judge is not offered`() {
        assertNull(client().callDidEnd("C", "peer", durationSeconds = 3, endedNormally = false))
    }

    @Test
    fun `a blank callId is refused`() {
        assertNull(client().callDidEnd("", "peer", durationSeconds = 60, endedNormally = false))
    }

    @Test
    fun `a second call is not offered while one is still unanswered`() {
        val c = client()
        assertNotNull(c.callDidEnd("CALL-1", "peer", 30, false))
        assertNull(
            "two prompts at once is indistinguishable from a bug",
            c.callDidEnd("CALL-2", "peer", 30, false)
        )
    }

    // ---------------------------------------------------------------- persistence

    @Test
    fun `the cooldown survives a restart`() {
        // The whole reason this state is a file: a desktop client restarts far more
        // often than a phone app, and a cooldown held only in memory is no cooldown.
        val first = client()
        assertNotNull(first.callDidEnd("CALL-1", "peer", 30, false))
        first.submit(2)

        assertNull(
            "one hour later is still inside the 3h abnormal cooldown",
            client(at = now + hour).callDidEnd("CALL-2", "peer", 30, false)
        )
        assertNotNull(client(at = now + 4 * hour).callDidEnd("CALL-3", "peer", 30, false))
    }

    @Test
    fun `the same call is never asked about twice, across a restart`() {
        val first = client()
        assertNotNull(first.callDidEnd("CALL-1", "peer", 30, false))
        first.dismiss()
        // Far past every cooldown, so only the already-rated rule can block it.
        assertNull(client(at = now + 30 * 24 * hour).callDidEnd("CALL-1", "peer", 30, false))
    }

    @Test
    fun `a corrupt state file costs one extra ask, not a crash`() {
        val f = File(tmp.root, "corrupt.json")
        f.writeText("{ not json at all", Charsets.UTF_8)
        assertNotNull(
            "a hang-up must never be able to throw",
            client(file = f).callDidEnd("CALL-1", "peer", 30, false)
        )
    }

    // ---------------------------------------------------------------- the body

    @Test
    fun `the posted body matches the phones' shape`() {
        val sent = mutableListOf<String>()
        val c = client(sent = sent)
        c.callDidEnd("CALL-9", "peer", 42, false)
        assertTrue(
            c.submit(2, setOf(CallRatingClient.Reason.CHOPPY, CallRatingClient.Reason.ECHO), " robot voice ")
        )

        val body = waitFor(sent)
        val o = JSONObject(body)
        assertEquals("CALL-9", o.getString("callId"))
        assertEquals("MY_PUBLIC_KEY", o.getString("sender"))
        assertEquals(2, o.getInt("rating"))
        assertEquals("the note is trimmed", "robot voice", o.getString("comment"))
        assertEquals(
            "reasons travel sorted, as the wire contract",
            listOf("choppy", "echo"),
            o.getJSONArray("reasons").let { a -> (0 until a.length()).map { a.getString(it) } }
        )
        assertTrue(o.getString("platform") in setOf("Windows", "Linux", "macOS-desktop"))
        assertTrue(o.has("appVersion"))
        // Nothing about the peer ever leaves. This is the assertion that fails if someone
        // "helpfully" adds the callee to make the channel easier to read.
        assertFalse("the peer's identity is not ours to send", body.contains("peer"))
    }

    @Test
    fun `a rating out of range is clamped rather than refused`() {
        val sent = mutableListOf<String>()
        val c = client(sent = sent)
        c.callDidEnd("CALL-1", "peer", 30, false)
        c.submit(99)
        assertEquals(5, JSONObject(waitFor(sent)).getInt("rating"))
    }

    @Test
    fun `an empty note and no reasons are omitted, not sent blank`() {
        val sent = mutableListOf<String>()
        val c = client(sent = sent)
        c.callDidEnd("CALL-1", "peer", 30, false)
        c.submit(5)
        val o = JSONObject(waitFor(sent))
        assertFalse(o.has("comment"))
        assertFalse(o.has("reasons"))
    }

    @Test
    fun `submitting with nothing pending sends nothing`() {
        val sent = mutableListOf<String>()
        assertFalse(client(sent = sent).submit(1))
        Thread.sleep(50)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an identity-less client never posts`() {
        val sent = mutableListOf<String>()
        val c = client(sent = sent, key = "")
        c.callDidEnd("CALL-1", "peer", 30, false)
        assertFalse(c.submit(1))
        Thread.sleep(50)
        assertTrue(sent.isEmpty())
    }

    // ---------------------------------------------------------------- wire contract

    @Test
    fun `reason wire values match the other two clients`() {
        // Source of truth: OSHI/CallRatingManager.swift `CallRatingReason`, asserted
        // against Kotlin on Android in `CallRatingPolicyTest`. Three clients, one
        // vocabulary, or the monitoring channel silently splits into three.
        assertEquals(
            listOf("choppy", "couldNotHearThem", "delay", "dropped", "echo", "theyCouldNotHearMe"),
            CallRatingClient.Reason.values().map { it.wire }.sorted()
        )
    }

    @Test
    fun `reasons are numbered from one, as the prompt shows them`() {
        assertEquals(CallRatingClient.Reason.COULD_NOT_HEAR_THEM, CallRatingClient.Reason.byIndex(1))
        assertEquals(CallRatingClient.Reason.DROPPED, CallRatingClient.Reason.byIndex(6))
        assertNull(CallRatingClient.Reason.byIndex(0))
        assertNull(CallRatingClient.Reason.byIndex(7))
    }

    /** The policy really is the shared one, not a desktop copy that drifted. */
    @Test
    fun `the policy in use is the one compiled from the Android tree`() {
        assertEquals(
            "com.oshi.messenger.service.CallRatingPolicy",
            CallRatingPolicy::class.java.name
        )
        assertEquals(8L, CallRatingPolicy.MIN_SECONDS)
        assertEquals(5, CallRatingPolicy.MAX_ASKS_PER_WEEK)
    }

    private fun waitFor(sent: MutableList<String>): String {
        val deadline = System.currentTimeMillis() + 2000
        while (sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("nothing was posted within 2s", sent.isNotEmpty())
        return sent.first()
    }
}
