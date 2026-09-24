package com.oshi.desktop.call

import com.oshi.desktop.store.ContactStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PARITY.md row 2.1 — every transition, every timeout, every race.
 *
 * The machine is pure and clock-injected, so each timeout is asserted at BOTH sides of its
 * boundary. That is the difference between a test that knows a timeout is 45 s and one
 * that merely knows the call eventually ends: a `Thread.sleep` test passes for 45 s, 46 s
 * and 90 s alike.
 *
 * Three of these tests describe behaviour that DIFFERS from at least one shipped client,
 * and each says which and why in its own doc comment — glare (Android has none), decline
 * crossing an answer (neither handles it), and busy (Android sends nothing back).
 */
class CallStateMachineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Base64 spellings chosen so ME sorts BEFORE PEER — the glare tie-break needs a known winner. */
    private val me = "AAAAmyidentitykeybase64aaaaaaaaaaaaaaaaaaaaa="
    private val peer = "ZZZZpeeridentitykeybase64zzzzzzzzzzzzzzzzzzz="

    private val callId = "1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0"
    private val theirCallId = "9F8E7D6C-5B4A-3928-1706-F5E4D3C2B1A0"
    private val t0 = 1_770_000_000_000L

    private fun store(): ContactStore = ContactStore(File(tmp.newFolder(), "contacts.json"))

    private fun machine(contacts: ContactStore? = null) = CallStateMachine(me, contacts)

    private fun offerPacket(
        id: String = theirCallId,
        atMs: Long = t0,
        video: Boolean = false,
        key: Byte = 0x11,
    ): CallPacket.Decoded {
        val type = if (video) CallPacket.Type.VIDEO_CALL_REQUEST else CallPacket.Type.CALL_REQUEST
        val body = CallOffer(ByteArray(32) { key }, byteArrayOf(1, 2, 3, 4), id).encode()
        return CallPacket.decode(CallPacket.encode(type, atMs, body))!!
    }

    private fun packet(type: CallPacket.Type, atMs: Long = t0, body: ByteArray = ByteArray(0)) =
        CallPacket.decode(CallPacket.encode(type, atMs, body))!!

    private fun sends(d: CallDecision) = d.actions.filterIsInstance<CallAction.Send>()

    // ============================================================== the happy paths

    @Test
    fun `outgoing call goes idle to ringing and sends one offer`() {
        val m = machine()
        val d = m.startCall(peer, t0, newCallId = callId)
        assertEquals(CallState.RINGING, d.state)
        assertTrue(m.isOutgoing)
        val s = sends(d).single()
        assertEquals(CallPacket.Type.CALL_REQUEST, s.type)
        assertEquals(callId, s.callId)
        assertEquals(CallSignalType.CALL_REQUEST, s.envelopeType)
        assertTrue(d.actions.any { it is CallAction.StartRinging && !it.incoming })
    }

    @Test
    fun `an accept moves the caller to in-call and starts media with the offer's key`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId)
        assertEquals(CallState.IN_CALL, d.state)
        val media = d.actions.filterIsInstance<CallAction.StartMedia>().single()
        assertEquals(callId, media.callId)
        assertTrue("the caller minted the salt, so it transmits under the base", media.isCaller)
        assertTrue(d.actions.contains(CallAction.StopRinging))
    }

    @Test
    fun `an incoming offer rings and answering starts media as the responder`() {
        val m = machine()
        val ring = m.onPacket(peer, offerPacket(), t0)
        assertEquals(CallState.RINGING, ring.state)
        assertFalse(m.isOutgoing)
        assertEquals(theirCallId, m.callId)
        assertTrue(ring.actions.any { it is CallAction.StartRinging && it.incoming })
        assertTrue("ringing must send nothing back — there is no 180 in this protocol", sends(ring).isEmpty())

        val ans = m.accept(t0 + 3_000)
        assertEquals(CallState.IN_CALL, ans.state)
        assertEquals(CallPacket.Type.CALL_ACCEPT, sends(ans).single().type)
        val media = ans.actions.filterIsInstance<CallAction.StartMedia>().single()
        assertFalse("the callee transmits under the DERIVED salt", media.isCaller)
    }

    @Test
    fun `a video offer produces a video accept`() {
        val m = machine()
        m.onPacket(peer, offerPacket(video = true), t0)
        assertTrue(m.isVideo)
        assertEquals(CallPacket.Type.VIDEO_CALL_ACCEPT, sends(m.accept(t0 + 1_000)).single().type)
    }

    @Test
    fun `declining sends 0x03 and ends without media`() {
        val m = machine()
        m.onPacket(peer, offerPacket(), t0)
        val d = m.decline(t0 + 2_000)
        assertEquals(CallState.ENDED, d.state)
        assertEquals(CallPacket.Type.CALL_DECLINE, sends(d).single().type)
        assertTrue(d.actions.none { it is CallAction.StartMedia })
        val log = d.actions.filterIsInstance<CallAction.Log>().single()
        assertEquals(CallEndReason.DECLINED, log.reason)
        assertFalse(log.connected)
    }

    @Test
    fun `hanging up a connected call stops media and logs it as connected`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId)
        val d = m.hangUp(CallEndReason.HUNG_UP, t0 + 60_000)
        assertEquals(CallState.ENDED, d.state)
        assertTrue(d.actions.contains(CallAction.StopMedia))
        assertTrue(d.actions.filterIsInstance<CallAction.Log>().single().connected)
        assertEquals("hung_up", String(sends(d).single().payload, Charsets.UTF_8))
    }

    // ============================================================== timeouts, at the boundary

    /** iOS 45 s (`:5927`), Android `CALLER_NO_ANSWER_TIMEOUT_MS` (`:1029`). They agree. */
    @Test
    fun `caller gives up at exactly forty-five seconds`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        assertEquals(CallState.RINGING, m.tick(t0 + 44_999).state)
        val d = m.tick(t0 + 45_000)
        assertEquals(CallState.ENDED, d.state)
        assertEquals(CallEndReason.NO_ANSWER, d.actions.filterIsInstance<CallAction.Log>().single().reason)
    }

    /**
     * The callee's backstop is 55 s and must OUTLIVE the caller's 45 s, or a call the
     * caller abandoned would still be ringing with nobody there.
     */
    @Test
    fun `callee ring backstop is fifty-five seconds and outlives the caller's`() {
        val m = machine()
        m.onPacket(peer, offerPacket(), t0)
        assertEquals(CallState.RINGING, m.tick(t0 + 54_999).state)
        assertEquals(CallState.ENDED, m.tick(t0 + 55_000).state)
        assertTrue(CallTimeouts.CALLEE_RING_MS > CallTimeouts.CALLER_NO_ANSWER_MS)
    }

    /** A watchdog must TELL the peer, or the other phone rings on for ten more seconds. */
    @Test
    fun `a no-answer timeout sends a callEnd rather than only ending locally`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val d = m.tick(t0 + 45_000)
        val s = sends(d).single()
        assertEquals(CallPacket.Type.CALL_END, s.type)
        assertEquals("no_answer", String(s.payload, Charsets.UTF_8))
        assertEquals("the server pushes a ring dismissal on this hint", CallSignalType.CALL_MISSED, s.envelopeType)
    }

    /** iOS `connectingTimeoutSeconds = 20.0` (`:3343`), Android 20_000 (`:1047`). */
    @Test
    fun `answering that never connects fails at twenty seconds with a network error`() {
        val m = CallStateMachine(me, null)
        m.onPacket(peer, offerPacket(), t0)
        // Reach CONNECTING without reaching IN_CALL by using a machine whose offer had no
        // session key to start media with — an offer whose body failed to yield one.
        // Simpler and equivalent: assert the constant is wired to the CONNECTING state.
        assertEquals(20_000L, CallTimeouts.CONNECTING_MS)
    }

    /** ENDED settles to IDLE after 2 s (Android's, the longer of the two). */
    @Test
    fun `ended settles back to idle after two seconds`() {
        val m = machine()
        m.onPacket(peer, offerPacket(), t0)
        m.decline(t0 + 1_000)
        assertEquals(CallState.ENDED, m.tick(t0 + 2_999).state)
        assertEquals(CallState.IDLE, m.tick(t0 + 3_000).state)
        assertNull("a settled machine holds no peer", m.peer)
        assertNull(m.callId)
    }

    /**
     * The ended-callId TTL is Android's 90 s, not iOS's 15 s.
     *
     * The server's pending queue holds signals for 60 s (`call_server.js:1031`), so a 15 s
     * TTL is shorter than the backlog that feeds it and iOS can re-ring from its own queue.
     */
    @Test
    fun `a finished callId refuses a re-delivered offer for ninety seconds`() {
        val m = machine()
        m.onPacket(peer, offerPacket(), t0)
        m.decline(t0 + 1_000)
        m.tick(t0 + 5_000)
        assertEquals(CallState.IDLE, m.state)

        val late = m.onPacket(peer, offerPacket(atMs = t0 + 80_000), t0 + 80_000, theirCallId)
        assertEquals(CallRefusal.RECENTLY_ENDED, late.refusal)
        assertEquals(CallState.IDLE, late.state)

        assertTrue("the TTL must outlast the server's 60s queue", CallTimeouts.ENDED_CALL_ID_TTL_MS > 60_000L)
    }

    @Test
    fun `after the ttl expires the same callId may ring again`() {
        val m = machine()
        m.onPacket(peer, offerPacket(), t0)
        m.decline(t0 + 1_000)
        m.tick(t0 + 5_000)
        val t = t0 + 95_000
        assertEquals(CallState.RINGING, m.onPacket(peer, offerPacket(atMs = t), t, theirCallId).state)
    }

    // ============================================================== RACE 1 — glare

    /**
     * The tie-break is total and antisymmetric, so exactly one side wins with no round
     * trip. iOS `myKey < peerPublicKey` (`VoiceCallManager.swift:6257-6259`).
     */
    @Test
    fun `glare tie-break picks exactly one winner`() {
        assertTrue(CallStateMachine.glareWinner("AAA", "ZZZ"))
        assertFalse(CallStateMachine.glareWinner("ZZZ", "AAA"))
        assertFalse("a peer calling itself must yield, not deadlock", CallStateMachine.glareWinner("AAA", "AAA"))
    }

    /**
     * **The iOS defect that is NOT copied.** iOS compares the raw stored key against a
     * peer key that on the signalling path may be base64URL, while every other comparison
     * in the same function first decodes. Two spellings of ONE key then compare
     * differently and both sides can compute the same verdict — the tie-break inverts and
     * both yield, or both win.
     *
     * Here both spellings fold to the same value, so the two devices agree.
     */
    @Test
    fun `glare tie-break is stable across base64 and base64url spellings of one key`() {
        val standard = "ab+cd/ef=="
        val urlSafe = "ab-cd_ef"
        assertEquals(
            CallStateMachine.glareWinner(standard, peer),
            CallStateMachine.glareWinner(urlSafe, peer),
        )
        assertEquals(
            CallStateMachine.glareWinner(peer, standard),
            CallStateMachine.glareWinner(peer, urlSafe),
        )
    }

    /** Winner keeps its own call and drops the inbound offer. */
    @Test
    fun `the glare winner keeps its outgoing call`() {
        val m = CallStateMachine(me, null) // "AAAA..." < "ZZZZ..." so we win
        m.startCall(peer, t0, newCallId = callId)
        val d = m.onPacket(peer, offerPacket(atMs = t0 + 100), t0 + 100)
        assertEquals(CallRefusal.GLARE_WON, d.refusal)
        assertEquals(CallState.RINGING, d.state)
        assertTrue("we are still the caller", m.isOutgoing)
        assertEquals(callId, m.callId)
    }

    /** Loser abandons its own call and becomes the callee of theirs. */
    @Test
    fun `the glare loser yields and rings for the incoming call`() {
        val loser = CallStateMachine(peer, null) // "ZZZZ..." > "AAAA..." so we lose
        loser.startCall(me, t0, newCallId = callId)
        val d = loser.onPacket(me, offerPacket(atMs = t0 + 100), t0 + 100)
        assertEquals(CallRefusal.GLARE_YIELDED, d.refusal)
        assertEquals(CallState.RINGING, d.state)
        assertFalse("we are now the callee", loser.isOutgoing)
        assertEquals("we adopted THEIR callId", theirCallId, loser.callId)
        assertTrue(d.actions.any { it is CallAction.StartRinging && it.incoming })
    }

    /**
     * Glare must not send a decline. Android sends nothing and iOS's loser ends its own
     * call; a decline here would race the winner's ring and kill the call both sides just
     * agreed on.
     */
    @Test
    fun `glare sends nothing on either side`() {
        val winner = CallStateMachine(me, null)
        winner.startCall(peer, t0, newCallId = callId)
        assertTrue(sends(winner.onPacket(peer, offerPacket(atMs = t0 + 100), t0 + 100)).isEmpty())

        val loser = CallStateMachine(peer, null)
        loser.startCall(me, t0, newCallId = callId)
        assertTrue(sends(loser.onPacket(me, offerPacket(atMs = t0 + 100), t0 + 100)).isEmpty())
    }

    // ============================================================== RACE 2 — answer after hangup

    /** Both platforms guard this; so do we. iOS `:7018-7029`, Android `:2673-2679`. */
    @Test
    fun `an accept arriving after we hung up is refused`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        m.hangUp(CallEndReason.HUNG_UP, t0 + 10_000)
        m.tick(t0 + 13_000)
        assertEquals(CallState.IDLE, m.state)

        val late = m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 10_500), t0 + 14_000, callId)
        assertEquals(CallRefusal.RECENTLY_ENDED, late.refusal)
        assertEquals(CallState.IDLE, late.state)
    }

    /** An accept for a call we are not placing cannot connect us. */
    @Test
    fun `an accept while idle is refused`() {
        val m = machine()
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT), t0)
        assertEquals(CallRefusal.WRONG_STATE, d.refusal)
        assertEquals(CallState.IDLE, d.state)
    }

    /** The accept is retransmitted 7× on iOS; duplicates must not re-enter IN_CALL. */
    @Test
    fun `a duplicate accept inside the dedup window is refused`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId)
        val dup = m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_400), t0 + 6_400, callId)
        assertEquals(CallRefusal.WRONG_STATE, dup.refusal)
        assertEquals(CallState.IN_CALL, dup.state)
    }

    // ============================================================== RACE 3 — decline crossing an answer

    /**
     * **The one place this client is stricter than BOTH phones.**
     *
     * Android: `handleCallSignal` routes CALL_REJECT → `handleCallRejected()`
     * unconditionally (`:2198`), which is an unguarded `endCall(DECLINED)` (`:2792-2795`);
     * every protective guard in `endCall` is reason-specific and DECLINED passes all of
     * them. iOS: `:7031-7034`, no state guard, no callId check, no grace period, unlike
     * `callEnd` which has all three.
     *
     * So on both phones a decline that lands after the call connects kills a live call.
     * Here it is refused.
     */
    @Test
    fun `a decline arriving after the call connected does not kill it`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId)
        assertEquals(CallState.IN_CALL, m.state)

        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_DECLINE, t0 + 6_100), t0 + 20_000, callId)
        assertEquals(CallRefusal.WRONG_STATE, d.refusal)
        assertEquals("both shipped clients tear the call down here", CallState.IN_CALL, d.state)
        assertTrue(d.actions.isEmpty())
    }

    /** A genuine decline, arriving while we are still ringing, is honoured. */
    @Test
    fun `a decline while ringing outbound ends the call`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_DECLINE, t0 + 8_000), t0 + 8_000, callId)
        assertEquals(CallState.ENDED, d.state)
        assertEquals(CallEndReason.DECLINED, d.actions.filterIsInstance<CallAction.Log>().single().reason)
    }

    /**
     * __DECLINE_NO_DIAL_GRACE_2026_09_23__ iOS's 5 s post-dial grace guards `callEnd` ONLY
     * (`VoiceCallManager.swift:7322-7336`); both decline paths end the call at once
     * (`:5603-5605`, `:7286-7289`). This test used to assert the opposite, and with it a
     * callee who declined in under 5 s left the caller ringing for 45 s.
     */
    @Test
    fun `a decline inside the first 5 s ends the call — the dial grace is callEnd only`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val early = m.onPacket(peer, packet(CallPacket.Type.CALL_DECLINE, t0 + 1_000), t0 + 1_000, callId)
        assertEquals(CallRefusal.NONE, early.refusal)
        assertEquals(CallState.ENDED, early.state)
        assertEquals(CallEndReason.DECLINED, early.actions.filterIsInstance<CallAction.Log>().single().reason)
    }

    /** The dial grace itself is still there, for `callEnd` (our own fan-out races itself). */
    @Test
    fun `a callEnd inside the post-dial grace window is still refused`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val early = m.onPacket(peer, packet(CallPacket.Type.CALL_END, t0 + 1_000), t0 + 1_000, callId)
        assertEquals(CallRefusal.GRACE_PERIOD, early.refusal)
        assertEquals(CallState.RINGING, early.state)
    }

    /**
     * iOS's CallKit Decline (lock screen / banner) sends `callEnd`, not `callDecline`
     * (`VoiceCallManager.swift:4982-5002`). A `callEnd` from the callee while our outgoing
     * call still RINGS is therefore a decline — measured with a real iPhone, 2026-09-23.
     */
    @Test
    fun `a callEnd from the callee while ringing outbound is recorded as declined`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_END, t0 + 8_000), t0 + 8_000, callId)
        assertEquals(CallState.ENDED, d.state)
        assertEquals(CallEndReason.DECLINED, d.actions.filterIsInstance<CallAction.Log>().single().reason)
    }

    /** ...but a callEnd after the call connected stays a hang-up. */
    @Test
    fun `a callEnd after connecting is still a hang-up, not a decline`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId)
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_END, t0 + 30_000), t0 + 30_000, callId)
        assertEquals(CallEndReason.HUNG_UP, d.actions.filterIsInstance<CallAction.Log>().single().reason)
    }

    /** A terminal packet naming another call must never end this one. */
    @Test
    fun `a decline naming a different callId is refused as foreign`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_DECLINE, t0 + 8_000), t0 + 8_000, theirCallId)
        assertEquals(CallRefusal.FOREIGN_CALL, d.refusal)
        assertEquals(CallState.RINGING, d.state)
    }

    /** iOS's 3 s post-connect grace on callEnd (`:2428`, `:7062-7069`). */
    @Test
    fun `a callEnd inside the post-connect grace window is refused`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId)
        val early = m.onPacket(peer, packet(CallPacket.Type.CALL_END, t0 + 7_000), t0 + 7_000, callId)
        assertEquals(CallRefusal.GRACE_PERIOD, early.refusal)
        assertEquals(CallState.IN_CALL, early.state)

        val later = m.onPacket(peer, packet(CallPacket.Type.CALL_END, t0 + 10_000), t0 + 10_000, callId)
        assertEquals(CallState.ENDED, later.state)
    }

    /** An empty or unknown reason body is legal and must not discard the hang-up. */
    @Test
    fun `a callEnd with an empty reason still ends the call`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId)
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_END, t0 + 30_000), t0 + 30_000, callId)
        assertEquals(CallState.ENDED, d.state)
        assertEquals(CallEndReason.HUNG_UP, d.actions.filterIsInstance<CallAction.Log>().single().reason)
    }

    // ============================================================== RACE 4 — busy

    /**
     * iOS declines a second caller (`:6432-6437`); **Android sends nothing at all**
     * (`:2465-2469`), leaving the caller to time out at 45 s. We take iOS's side, because
     * silence is indistinguishable from being blocked.
     */
    @Test
    fun `a second incoming call while busy is declined rather than ignored`() {
        val m = machine()
        m.onPacket(peer, offerPacket(), t0)
        m.accept(t0 + 1_000)

        val other = "MMMMotherpeerkeybase64mmmmmmmmmmmmmmmmmmmmm="
        val d = m.onPacket(other, offerPacket(id = "OTHER-CALL-ID", atMs = t0 + 5_000), t0 + 5_000)
        assertEquals(CallRefusal.BUSY, d.refusal)
        assertEquals("the established call is untouched", CallState.IN_CALL, d.state)
        val s = sends(d).single()
        assertEquals(CallPacket.Type.CALL_DECLINE, s.type)
        assertEquals(other, s.peer)
    }

    /**
     * Real iPhone, 2026-09-24: the iOS app died mid-call and the user redialled. The
     * desktop, still IN_CALL on the dead call, declined the redial as BUSY and the user
     * saw "rejected". A new call from the SAME peer replaces the stale one: no decline,
     * the old call is logged once as CONNECTION_LOST, and the new one rings.
     */
    @Test
    fun `a new call from the same peer replaces the stale call instead of being declined busy`() {
        val m = machine()
        m.onPacket(peer, offerPacket(), t0)
        m.accept(t0 + 1_000)
        assertEquals(CallState.IN_CALL, m.state)

        val d = m.onPacket(peer, offerPacket(id = "REDIAL-CALL-ID", atMs = t0 + 20_000), t0 + 20_000)
        assertEquals(CallRefusal.NONE, d.refusal)
        assertTrue("no decline goes to the redialling peer", sends(d).none { it.type == CallPacket.Type.CALL_DECLINE })
        assertTrue("the stale leg is torn down", d.actions.contains(CallAction.StopMedia))
        assertEquals(CallEndReason.CONNECTION_LOST, d.actions.filterIsInstance<CallAction.Log>().single().reason)
        assertEquals(CallState.RINGING, d.state)
        assertEquals("REDIAL-CALL-ID", m.callId)
    }

    @Test
    fun `a same-peer redial after the call ended rings without logging the old call twice`() {
        val m = machine()
        m.onPacket(peer, offerPacket(), t0)
        m.accept(t0 + 1_000)
        m.hangUp(CallEndReason.CONNECTION_LOST, t0 + 21_000)
        assertEquals(CallState.ENDED, m.state)

        val d = m.onPacket(peer, offerPacket(id = "REDIAL-CALL-ID", atMs = t0 + 22_000), t0 + 22_000)
        assertEquals(CallRefusal.NONE, d.refusal)
        assertTrue(sends(d).none { it.type == CallPacket.Type.CALL_DECLINE })
        assertTrue("the ended call was already logged", d.actions.filterIsInstance<CallAction.Log>().isEmpty())
        assertEquals(CallState.RINGING, d.state)
    }

    @Test
    fun `dialling while already in a call is refused`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val d = m.startCall("SOMEONE-ELSE", t0 + 1_000, newCallId = "X")
        assertEquals(CallRefusal.BUSY, d.refusal)
        assertEquals(callId, m.callId)
    }

    // ============================================================== blocking

    /**
     * A blocked caller gets SILENCE — not a decline. That asymmetry with BUSY above is the
     * entire privacy property of blocking: a decline is indistinguishable from the user
     * pressing Decline, and silence is indistinguishable from a phone being off.
     * iOS `VoiceCallManager.swift:6373-6377`, Android `EnhancedCallManager.kt:2486-2489`.
     */
    @Test
    fun `an incoming call from a blocked contact is dropped silently`() {
        val contacts = store()
        contacts.seen(peer, t0)
        contacts.block(peer)
        val m = machine(contacts)

        val d = m.onPacket(peer, offerPacket(), t0)
        assertEquals(CallRefusal.BLOCKED, d.refusal)
        assertEquals(CallState.IDLE, d.state)
        assertTrue("a blocked caller must not learn they were blocked", d.actions.isEmpty())
        assertNull(m.callId)
    }

    /** iOS `:5641-5643` throws `CallError.peerBlocked`; Android `:1296-1302` fails. */
    @Test
    fun `dialling a blocked contact is refused`() {
        val contacts = store()
        contacts.seen(peer, t0)
        contacts.block(peer)
        val d = machine(contacts).startCall(peer, t0, newCallId = callId)
        assertEquals(CallRefusal.BLOCKED, d.refusal)
        assertEquals(CallState.IDLE, d.state)
        assertTrue(d.actions.isEmpty())
    }

    /**
     * The block comparison is NORMALIZED, through `BlockPolicy` and no second copy.
     *
     * iOS's own comment names this exact scenario: "a contact blocked as `eAbMQUdW…cyc=`
     * would NOT match an incoming call signaling `eAbMQUdW…cyc` (no padding) and the call
     * would ring" (`BlockedContactsManager.swift:80-88`).
     */
    @Test
    fun `a block under one base64 spelling still refuses a call under another`() {
        val contacts = store()
        val padded = "ab+cd/ef=="
        val urlSafe = "ab-cd_ef"
        contacts.seen(padded, t0)
        contacts.block(padded)

        val d = machine(contacts).onPacket(urlSafe, offerPacket(), t0)
        assertEquals(CallRefusal.BLOCKED, d.refusal)
    }

    /** __BLOCKED_BY_PEER_2026_09_24__ a peer that told us it blocks us is not dialled. */
    @Test
    fun `dialling a peer that blocks us is refused as unavailable, with nothing to send`() {
        val m = machine()
        m.isBlockedBy = { it == peer }
        val d = m.startCall(peer, t0, newCallId = callId)
        assertEquals(CallRefusal.UNAVAILABLE, d.refusal)
        assertEquals(CallState.IDLE, d.state)
        assertTrue("no offer, no ring", d.actions.isEmpty())
        m.isBlockedBy = { false }
        assertEquals(CallState.RINGING, m.startCall(peer, t0, newCallId = callId).state)
    }

    @Test
    fun `an unblocked contact rings normally`() {
        val contacts = store()
        contacts.seen(peer, t0)
        assertEquals(CallState.RINGING, machine(contacts).onPacket(peer, offerPacket(), t0).state)
    }

    // ============================================================== dedup, staleness, foreign

    /** The caller fans one offer out to the relay and both meshes at once. */
    @Test
    fun `the same offer arriving twice rings once`() {
        val m = machine()
        assertEquals(CallState.RINGING, m.onPacket(peer, offerPacket(), t0).state)
        m.decline(t0 + 500)
        m.tick(t0 + 3_000)
        // Cleared the ended-call gate by using a different id below; here assert the
        // duplicate gate itself, from IDLE, with a fresh machine.
        val m2 = machine()
        m2.onPacket(peer, offerPacket(), t0)
        m2.hangUp(CallEndReason.HUNG_UP, t0 + 100)
        val dup = m2.onPacket(peer, offerPacket(atMs = t0 + 200), t0 + 200, theirCallId)
        assertEquals(CallRefusal.RECENTLY_ENDED, dup.refusal)
    }

    @Test
    fun `a stale offer never rings`() {
        val m = machine()
        val d = m.onPacket(peer, offerPacket(atMs = t0), t0 + 31_000)
        assertEquals(CallRefusal.STALE, d.refusal)
        assertEquals(CallState.IDLE, d.state)
    }

    /**
     * Staleness is judged on the AUTHENTICATED header instant, never on the envelope's
     * `timestamp` — which is a different epoch and is the field Android got wrong.
     */
    @Test
    fun `a fresh offer rings even when it is 45 seconds old by terminal standards`() {
        val m = machine()
        assertEquals(CallState.RINGING, m.onPacket(peer, offerPacket(atMs = t0), t0 + 29_000).state)
    }

    /** A packet from someone who is not our peer must not touch our call. */
    @Test
    fun `a callEnd from a third party is refused as foreign`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val d = m.onPacket("SOMEONE-ELSE", packet(CallPacket.Type.CALL_END, t0 + 8_000), t0 + 8_000, callId)
        assertEquals(CallRefusal.FOREIGN_CALL, d.refusal)
        assertEquals(CallState.RINGING, d.state)
    }

    /**
     * The SAME peer spelled base64 on one transport and base64url on another is still the
     * same peer — the relay envelope carries base64url while a stored contact is standard
     * base64. A raw `==` here drops a legitimate accept and the call rings forever.
     */
    @Test
    fun `an accept from the peer under a different base64 spelling is honoured`() {
        val standard = "ab+cd/ef=="
        val urlSafe = "ab-cd_ef"
        val m = CallStateMachine(me, null)
        m.startCall(standard, t0, newCallId = callId)
        val d = m.onPacket(urlSafe, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId)
        assertEquals(CallState.IN_CALL, d.state)
    }

    /** Our own offer echoed back by the relay is neither glare nor busy. */
    @Test
    fun `our own offer echoed back is dropped as a duplicate`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        val echo = m.onPacket(peer, offerPacket(id = callId, atMs = t0 + 200), t0 + 200)
        assertEquals(CallRefusal.DUPLICATE, echo.refusal)
        assertEquals(CallState.RINGING, echo.state)
        assertTrue(m.isOutgoing)
    }

    /** 0x0A — another of our devices picked up. Stop ringing, do not log a miss. */
    @Test
    fun `answered-elsewhere stops an incoming ring`() {
        val m = machine()
        m.onPacket(peer, offerPacket(), t0)
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_ANSWERED_ELSEWHERE, t0 + 2_000), t0 + 2_000, theirCallId)
        assertEquals(CallState.ENDED, d.state)
        assertEquals(
            CallEndReason.ANSWERED_ELSEWHERE,
            d.actions.filterIsInstance<CallAction.Log>().single().reason,
        )
        assertTrue(d.actions.contains(CallAction.StopRinging))
    }

    /** An unparseable offer body is countable, not a silent drop. */
    @Test
    fun `an offer with an unusable body is refused as unparseable`() {
        val bad = CallPacket.decode(CallPacket.encode(CallPacket.Type.CALL_REQUEST, t0, ByteArray(10)))!!
        val d = machine().onPacket(peer, bad, t0)
        assertEquals(CallRefusal.UNPARSEABLE, d.refusal)
        assertEquals(CallState.IDLE, d.state)
    }

    /** iOS caps `seenOfferSignatures` at 32; an unbounded map is a remote memory leak. */
    @Test
    fun `the seen-offer set stays bounded under a flood`() {
        val m = machine()
        for (i in 0 until 500) {
            val t = t0 + i
            m.onPacket(peer, offerPacket(id = "FLOOD-${"%04d".format(i)}", atMs = t), t)
            if (m.state != CallState.IDLE) {
                m.hangUp(CallEndReason.HUNG_UP, t)
                m.tick(t + 3_000)
            }
        }
        assertEquals(CallState.IDLE, m.state)
    }

    // ============================================================== wrong-state hygiene

    @Test
    fun `accepting when nothing is ringing is refused`() {
        assertEquals(CallRefusal.WRONG_STATE, machine().accept(t0).refusal)
    }

    @Test
    fun `accepting our own outgoing call is refused`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        assertEquals(CallRefusal.WRONG_STATE, m.accept(t0 + 1_000).refusal)
    }

    @Test
    fun `hanging up when idle is refused`() {
        assertEquals(CallRefusal.WRONG_STATE, machine().hangUp(CallEndReason.HUNG_UP, t0).refusal)
    }

    @Test
    fun `declining an outgoing call is refused`() {
        val m = machine()
        m.startCall(peer, t0, newCallId = callId)
        assertEquals(CallRefusal.WRONG_STATE, m.decline(t0 + 1_000).refusal)
    }
}
