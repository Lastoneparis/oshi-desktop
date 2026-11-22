package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * __CALL_WAKE_2026_11_22__ The callee reports `callRinging` (recorded by the server, never
 * forwarded); the caller asks `GET /call-reach` while its call rings and the screen says
 * "Ringing…" only once the callee's phone has the call.
 */
class CallReachTest {

    private val server = FakeCallServer()

    @After fun tearDown() = server.close()

    private fun lane(identity: DesktopIdentity): CallLane {
        val deviceId = "dev-" + UUID.randomUUID().toString().take(8)
        return CallLane(
            myAddress = identity.userKey,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(server.baseUrl, DesktopV2Signer(identity)),
            deviceId = deviceId,
            machine = CallStateMachine(identity.userKey, null, deviceId),
        ).also { it.siblingPushUrl = null }
    }

    private fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return true; Thread.sleep(25) }
        return cond()
    }

    @Test fun calleeReportsRingingOnceAndTheReportIsNeverQueuedForTheCaller() {
        val a = DesktopIdentity.generate(); val b = DesktopIdentity.generate()
        val caller = lane(a); val callee = lane(b)
        val dialled = caller.call(b.userKey) as CallLane.Dialled.Ringing
        assertTrue(waitFor { callee.pollOnce(); callee.state == CallState.RINGING })
        assertTrue("callee must report ringing", waitFor { server.ringingReports.contains(dialled.callId) })
        callee.pollOnce(); Thread.sleep(200)
        assertEquals(1, server.ringingReports.count { it == dialled.callId })
        assertEquals("the ringing report must not reach the caller's queue", 0, server.queueDepth(a.userKey))
    }

    @Test fun callerPollsReachWhileRingingAndPublishesIt() {
        val a = DesktopIdentity.generate(); val b = DesktopIdentity.generate()
        val caller = lane(a)
        val events = java.util.Collections.synchronizedList(mutableListOf<CallLane.CallEvent>())
        caller.onEvent = { events += it }
        val dialled = caller.call(b.userKey) as CallLane.Dialled.Ringing
        server.reach[dialled.callId] = "delivered"
        val t0 = System.currentTimeMillis()
        caller.tick(t0 + 2_100)
        assertTrue(waitFor { events.any { it is CallLane.CallEvent.Reach } })
        val r = events.filterIsInstance<CallLane.CallEvent.Reach>().first()
        assertEquals(CallReach.DELIVERED, r.reach)
        assertEquals(dialled.callId, r.callId)
    }

    @Test fun unreachableIsHeldBackForTheGraceAndUnknownRouteIsSilent() {
        val a = DesktopIdentity.generate(); val b = DesktopIdentity.generate()
        val caller = lane(a)
        val events = java.util.Collections.synchronizedList(mutableListOf<CallLane.CallEvent>())
        caller.onEvent = { events += it }
        val dialled = caller.call(b.userKey) as CallLane.Dialled.Ringing
        // no reach entry: the server answers 404 -> nothing published
        val t0 = System.currentTimeMillis()
        caller.tick(t0 + 2_100)
        assertTrue(waitFor { server.reachQueries.isNotEmpty() })
        Thread.sleep(300)
        assertTrue(events.none { it is CallLane.CallEvent.Reach })
        server.reach[dialled.callId] = "unreachable"
        caller.tick(t0 + 4_300)   // inside the 6 s grace
        assertTrue(waitFor { server.reachQueries.size >= 2 })
        Thread.sleep(300)
        assertTrue(events.none { it is CallLane.CallEvent.Reach })
        caller.tick(t0 + 6_500)
        assertTrue(waitFor { events.any { it is CallLane.CallEvent.Reach } })
    }

    @Test fun labelsAndMerge() {
        assertEquals(CallReach.RINGING, CallReach.fromServer("ringing"))
        assertNull(CallReach.fromServer("online"))
        assertEquals(CallReach.RINGING, CallReach.merge(CallReach.RINGING, CallReach.CALLING))
        assertEquals(CallReach.DELIVERED, CallReach.merge(CallReach.UNREACHABLE, CallReach.DELIVERED))
        assertEquals(CallReach.PUSHED, CallReach.merge(CallReach.PUSHED, CallReach.UNREACHABLE))
        assertEquals(CallReach.Label.RINGING, CallReach.outgoingLabel(null, 0))
        assertEquals(CallReach.Label.CALLING, CallReach.outgoingLabel(CallReach.PUSHED, 30_000))
        assertEquals(CallReach.Label.CALLING, CallReach.outgoingLabel(CallReach.UNREACHABLE, 5_999))
        assertEquals(CallReach.Label.UNREACHABLE, CallReach.outgoingLabel(CallReach.UNREACHABLE, 6_000))
        assertEquals(CallReach.Label.RINGING, CallReach.outgoingLabel(CallReach.DELIVERED, 0))
    }
}
