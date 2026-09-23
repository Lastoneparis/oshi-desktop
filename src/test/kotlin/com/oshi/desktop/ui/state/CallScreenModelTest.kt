package com.oshi.desktop.ui.state

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.call.CallLane
import com.oshi.desktop.call.CallSignalClient
import com.oshi.desktop.call.CallState
import com.oshi.desktop.call.CallStateMachine
import com.oshi.desktop.call.CallTimeouts
import com.oshi.desktop.call.FakeCallServer
import com.oshi.desktop.store.ContactStore
import com.oshi.desktop.ui.state.CallScreenModel.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/**
 * The window's call screen, driven by REAL lanes over a real socket (`FakeCallServer`, the
 * shipped call server's routes) — nothing mocked but the loudspeaker. Every test starts with
 * the event that would reach the window and asserts what it would draw and ring.
 */
class CallScreenModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeCallServer()
    private var now = System.currentTimeMillis()
    private val direct = Executor { it.run() }

    @After
    fun tearDown() = server.close()

    private class FakeRinger : CallRinger {
        val log = ArrayList<String>()
        var ringing: String? = null
        override fun startIncoming() { log += "incoming"; ringing = "incoming" }
        override fun startRingback() { log += "ringback"; ringing = "ringback" }
        override fun stop() { ringing = null }
    }

    private inner class Party(contacts: ContactStore? = null) {
        val identity: DesktopIdentity = DesktopIdentity.generate()
        val address: String get() = identity.userKey
        val deviceId = "dev-" + UUID.randomUUID().toString().take(8)
        val lane = CallLane(
            myAddress = identity.userKey,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(server.baseUrl, DesktopV2Signer(identity)),
            deviceId = deviceId,
            machine = CallStateMachine(identity.userKey, contacts, deviceId),
        ).also { it.siblingPushUrl = null } // answer/decline must not POST to production push
        val ringer = FakeRinger()
        val summaries = ArrayList<Triple<String, String, Boolean>>()
        val rated = ArrayList<String>()
        val model = CallScreenModel(
            lane = lane,
            labelFor = { if (it == address) "me" else "Alice Martin" },
            ringer = ringer,
            recordSummary = { peer, content, outgoing -> summaries += Triple(peer, content, outgoing) },
            afterCall = { callId, _, _, _ -> rated += callId },
            worker = direct,
            clock = { now },
        ).also { m -> lane.onEvent = m::onEvent }

        fun poll() = lane.pollOnce(now)
        val screen get() = model.screen
    }

    @Test
    fun incomingAnswerConnectedHangUp() {
        val a = Party(); val b = Party()
        a.lane.call(b.address, now)

        now += 10; b.poll()
        assertEquals(Phase.INCOMING, b.screen!!.phase)
        assertEquals("Alice Martin", b.screen!!.label)
        assertEquals("AM", b.screen!!.initials)
        assertEquals("the phone must ring", "incoming", b.ringer.ringing)

        now += 10; b.model.answer()
        assertNull("answering stops the ring", b.ringer.ringing)
        assertEquals(Phase.CONNECTED, b.screen!!.phase)
        assertEquals(CallState.IN_CALL, b.lane.state)
        now += 10; a.poll()
        assertEquals(CallState.IN_CALL, a.lane.state)

        now += 65_000 // past the 3 s post-connect grace; a 65 s call
        a.lane.hangUp(nowMs = now)
        now += 10; b.poll()
        assertEquals(Phase.ENDED, b.screen!!.phase)
        assertEquals("call.ended.hungup", b.screen!!.endedKey)
        assertEquals("📱CALL_SUMMARY📱📞↙️call.incoming|1:05", b.summaries.single().second)
        assertEquals("a connected call reaches the rating policy", 1, b.rated.size)

        now += CallScreenModel.ENDED_SHOW_MS; b.model.tick(now)
        assertNull("the ended screen retires by itself", b.screen)
    }

    @Test
    fun incomingDecline() {
        val a = Party(); val b = Party()
        a.lane.call(b.address, now)
        now += 10; b.poll()
        b.model.decline()
        assertNull(b.ringer.ringing)
        assertEquals(Phase.ENDED, b.screen!!.phase)
        assertEquals("call.ended.declined", b.screen!!.endedKey)
        assertEquals("📱CALL_SUMMARY📱📞↙️call.declined", b.summaries.single().second)
        assertTrue("a declined call is not rated", b.rated.isEmpty())

        now += 10; a.poll()
        assertEquals(CallState.ENDED, a.lane.state)
        assertEquals("call.ended.declined", a.screen!!.endedKey)
    }

    @Test
    fun remoteCancelStopsTheRingAndIsMissed() {
        val a = Party(); val b = Party()
        a.lane.call(b.address, now)
        now += 10; b.poll()
        assertEquals("incoming", b.ringer.ringing)

        now += CallTimeouts.END_GRACE_AFTER_DIAL_MS + 1_000
        a.lane.hangUp(nowMs = now)
        now += 10; b.poll()
        assertNull("the caller cancelled — the ring must stop", b.ringer.ringing)
        assertEquals("call.missed", b.screen!!.endedKey)
        assertEquals("📱MISSED_CALL📱❌call.missed", b.summaries.single().second)
    }

    @Test
    fun unansweredRingTimesOutAsMissed() {
        val a = Party(); val b = Party()
        a.lane.call(b.address, now)
        now += 10; b.poll()
        now += CallTimeouts.CALLEE_RING_MS + 1
        b.lane.tick(now)
        assertNull(b.ringer.ringing)
        assertEquals(Phase.ENDED, b.screen!!.phase)
        assertEquals("call.missed", b.screen!!.endedKey)
        assertEquals("📱MISSED_CALL📱❌call.missed", b.summaries.single().second)
    }

    @Test
    fun blockedCallerNeverRings() {
        val contacts = ContactStore(File(tmp.newFolder(), "contacts.json"))
        val a = Party()
        contacts.seen(a.address, now)
        contacts.block(a.address)
        val b = Party(contacts)

        a.lane.call(b.address, now)
        now += 10; b.poll()
        assertNull("no screen for a blocked caller", b.screen)
        assertTrue("and no ring, ever", b.ringer.log.isEmpty())
        assertTrue("and no history row", b.summaries.isEmpty())
        assertEquals(CallState.IDLE, b.lane.state)
    }

    @Test
    fun outgoingCancel() {
        val a = Party(); val b = Party()
        a.model.call(b.address)
        assertEquals(Phase.OUTGOING, a.screen!!.phase)
        assertTrue("ringback started", a.screen!!.ringingBack)
        assertEquals("ringback", a.ringer.ringing)
        assertNotNull(a.screen!!.callId)

        now += 1_000; a.model.hangUp()
        assertNull(a.ringer.ringing)
        assertEquals(Phase.ENDED, a.screen!!.phase)
        assertEquals("📱CALL_SUMMARY📱📞↗️call.no.answer", a.summaries.single().second)
        assertTrue(a.summaries.single().third)
    }

    @Test
    fun aSecondCallWhileInACallIsDeclinedAndNeverRings() {
        val a = Party(); val b = Party(); val c = Party()
        a.lane.call(b.address, now)
        now += 10; b.poll(); b.model.answer()
        now += 10; a.poll()
        assertEquals(Phase.CONNECTED, b.screen!!.phase)
        val ringsBefore = b.ringer.log.size

        now += 10; c.lane.call(b.address, now)
        now += 10; b.poll()
        assertEquals("the call on screen is still A's", Phase.CONNECTED, b.screen!!.phase)
        assertEquals(a.address, b.screen!!.peer)
        assertEquals("no second ring over a live call", ringsBefore, b.ringer.log.size)

        // iOS declines a caller it cannot take (RACE 4): C's call ends as declined.
        now += 10; c.poll()
        assertEquals(CallState.ENDED, c.lane.state)
        assertEquals("call.ended.declined", c.screen!!.endedKey)
    }

    @Test
    fun muteIsRememberedAcrossTheMediaLeg() {
        val a = Party(); val b = Party()
        a.lane.call(b.address, now)
        now += 10; b.poll(); b.model.answer()
        b.model.toggleMute()
        assertTrue(b.screen!!.muted)
        assertTrue(b.lane.muted)
        b.model.toggleMute()
        assertTrue(!b.lane.muted)
    }
}
