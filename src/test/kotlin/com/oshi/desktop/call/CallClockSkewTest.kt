package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.net.ServerClock
import org.json.JSONObject
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * __CALL_CLOCK_SKEW_DESKTOP_2026_11_21__ / __CROSS_CALL_GUARD_DESKTOP_2026_11_21__
 *
 * A PC whose clock runs fast dropped every incoming offer as stale (the header instant is
 * stamped by a peer with a correct clock). Freshness is now judged on the server's clock
 * when this PC is >= 5 s off, with the SAME windows — so a replay stays stale.
 */
class CallClockSkewTest {

    private val me = "AAAAmyidentitykeybase64aaaaaaaaaaaaaaaaaaaaa="
    private val peer = "ZZZZpeeridentitykeybase64zzzzzzzzzzzzzzzzzzz="
    private val callId = "1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0"
    private val theirCallId = "9F8E7D6C-5B4A-3928-1706-F5E4D3C2B1A0"
    private val t0 = 1_770_000_000_000L

    @Before fun reset() = ServerClock.resetForTest()
    @After fun resetAfter() = ServerClock.resetForTest()

    private fun offer(atMs: Long, id: String = theirCallId): CallPacket.Decoded {
        val body = CallOffer(ByteArray(32) { 0x11 }, byteArrayOf(1, 2, 3, 4), id).encode()
        return CallPacket.decode(CallPacket.encode(CallPacket.Type.CALL_REQUEST, atMs, body))!!
    }

    private fun packet(type: CallPacket.Type, atMs: Long) =
        CallPacket.decode(CallPacket.encode(type, atMs, ByteArray(0)))!!

    @Test fun `a PC one minute fast drops a fresh offer when no offset is known`() {
        // The regression this fixes: the peer stamped t0, our wrong clock reads t0 + 60 s.
        val d = CallStateMachine(me, null).onPacket(peer, offer(t0), t0 + 60_000)
        assertEquals(CallRefusal.STALE, d.refusal)
    }

    @Test fun `a PC one minute fast rings once the server offset is learned`() {
        ServerClock.setOffsetForTest(-60_000)
        val d = CallStateMachine(me, null).onPacket(peer, offer(t0), t0 + 60_000)
        assertEquals(CallState.RINGING, d.state)
    }

    @Test fun `a PC one minute slow still refuses a replayed 31 s old offer`() {
        ServerClock.setOffsetForTest(60_000)
        // Server time is t0 + 31 s while our wrong clock reads t0 - 29 s: by the local clock
        // the packet looks FRESH (it is in our future); on server time it is a replay.
        val d = CallStateMachine(me, null).onPacket(peer, offer(t0), t0 - 29_000)
        assertEquals(CallRefusal.STALE, d.refusal)
    }

    @Test fun `the replay window is unchanged under a correction`() {
        ServerClock.setOffsetForTest(-60_000)
        val m = CallStateMachine(me, null)
        // 31 s old on server time → stale, exactly as on a healthy clock.
        assertEquals(CallRefusal.STALE, m.onPacket(peer, offer(t0), t0 + 60_000 + 31_000).refusal)
    }

    @Test fun `a skew under 5 s changes nothing`() {
        ServerClock.setOffsetForTest(-4_000)
        // Healthy-clock behaviour: 31 s old by the local clock is stale, 29 s is not.
        assertEquals(CallRefusal.STALE, CallStateMachine(me, null).onPacket(peer, offer(t0), t0 + 31_000).refusal)
        assertEquals(CallState.RINGING, CallStateMachine(me, null).onPacket(peer, offer(t0), t0 + 29_000).state)
    }

    // ------------------------------------------------------------ cross-call guard

    @Test fun `callIds compare case-insensitively and NUL-trimmed`() {
        assertTrue(CallStateMachine.sameCallId(callId, callId.lowercase()))
        assertTrue(CallStateMachine.sameCallId(callId, callId + "\u0000\u0000"))
        assertFalse(CallStateMachine.sameCallId(callId, theirCallId))
        assertFalse(CallStateMachine.sameCallId(null, callId))
    }

    @Test fun `an accept echoing our callId in another case is honoured`() {
        val m = CallStateMachine(me, null)
        m.startCall(peer, t0, newCallId = callId)
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId.lowercase())
        assertEquals(CallState.IN_CALL, d.state)
    }

    @Test fun `an end for another call never ends this one, whatever the case`() {
        val m = CallStateMachine(me, null)
        m.startCall(peer, t0, newCallId = callId)
        m.onPacket(peer, packet(CallPacket.Type.CALL_ACCEPT, t0 + 6_000), t0 + 6_000, callId)
        val d = m.onPacket(peer, packet(CallPacket.Type.CALL_END, t0 + 20_000), t0 + 20_000, theirCallId.lowercase())
        assertEquals(CallRefusal.FOREIGN_CALL, d.refusal)
        assertEquals(CallState.IN_CALL, d.state)
    }

    @Test fun `a recently ended call stays ended under another spelling of its id`() {
        val m = CallStateMachine(me, null)
        m.onPacket(peer, offer(t0, id = theirCallId), t0)
        m.hangUp(CallEndReason.HUNG_UP, t0 + 100)
        val again = m.onPacket(peer, offer(t0 + 200, id = theirCallId.lowercase()), t0 + 200, theirCallId.lowercase())
        assertEquals(CallRefusal.RECENTLY_ENDED, again.refusal)
    }

    // ------------------------------------------------------------ outgoing stamp

    /**
     * The header instant of an offer is judged by the PEER (iOS drops > 5 s in the future),
     * so a PC one minute fast stamps it on the server's clock once the offset is known.
     */
    @Test fun `a fast PC stamps its offer on server time`() {
        FakeCallServer(requireSignature = false).use { server ->
            val a = DesktopIdentity.generate()
            val b = DesktopIdentity.generate()
            val lane = CallLane(
                myAddress = a.userKey,
                myPrivateKey = a.identity.priv,
                // Unsigned: the loopback server judges x-oshi-timestamp on THIS machine's (correct) clock.
                transport = CallSignalClient(server.baseUrl, signer = null),
                deviceId = "dev-skew",
                machine = CallStateMachine(a.userKey, null, "dev-skew"),
            )
            val realNow = System.currentTimeMillis()
            ServerClock.setOffsetForTest(-60_000)
            ServerClock.learning = false   // the loopback server's Date is OUR clock
            lane.call(b.userKey, realNow + 60_000)   // our wrong clock reads +60 s
            val env = JSONObject(server.postedBodies.first())
            val sealed = Base64.getDecoder().decode(env.getString("signal"))
            val plain = CallSignalCrypto.open(b.identity.priv, Base64.getDecoder().decode(a.userKey), sealed)!!
            val packet = CallPacket.decode(plain)!!
            assertEquals(realNow, packet.timestampMs)
        }
    }
}
