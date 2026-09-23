package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.net.V2Http
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * A REAL call through the PRODUCTION call server where the SIGNALLED hang-up never reaches
 * the callee — its signalling poll is switched off just before the caller hangs up — so the
 * only thing that can end B's call is the in-band `0x0D` burst on the media path
 * ([com.oshi.desktop.call.media.InBandCallEnd]). Skipped unless `OSHI_LIVE_CALL=1`:
 *
 *   OSHI_LIVE_CALL=1 ./gradlew test --tests com.oshi.desktop.call.LiveInBandHangUpBench -i
 *
 * Both ends are on this one Mac (no NAT crossed), with real UDP, STUN, microphones and
 * speakers (`CallMedia.real()`). Before the in-band hang-up existed, B stayed "connected"
 * here until someone hung up by hand.
 */
class LiveInBandHangUpBench {

    @Test
    fun theInBandHangUpAloneEndsTheOtherSide() {
        Assume.assumeTrue("set OSHI_LIVE_CALL=1 to place a real call", System.getenv("OSHI_LIVE_CALL") == "1")
        val server = V2Http.defaultBaseUrl()

        fun lane(id: DesktopIdentity, device: String) = CallLane(
            myAddress = id.userKey,
            myPrivateKey = id.identity.priv,
            transport = CallSignalClient(server, DesktopV2Signer(id)),
            deviceId = device,
            machine = CallStateMachine(id.userKey, null, device),
            mediaOpener = CallMedia.real(),
            log = { println("[inband] $device: $it") },
        )
        val aId = DesktopIdentity.generate(); val bId = DesktopIdentity.generate()
        val a = lane(aId, "desk-A"); val b = lane(bId, "desk-B")
        val bEnded = java.util.concurrent.CopyOnWriteArrayList<CallLane.CallEvent.Ended>()
        b.onEvent = { if (it is CallLane.CallEvent.Ended) bEnded += it }

        val bPolls = AtomicBoolean(true)
        val pollers = Executors.newScheduledThreadPool(2)
        pollers.scheduleWithFixedDelay({ runCatching { a.pollOnce(); a.tick() } }, 0, CallLane.POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        pollers.scheduleWithFixedDelay({ runCatching { if (bPolls.get()) b.pollOnce(); b.tick() } }, 0, CallLane.POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)

        fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean): Long {
            val t0 = System.currentTimeMillis()
            while (!cond()) {
                check(System.currentTimeMillis() - t0 < timeoutMs) { "timed out waiting for $what" }
                Thread.sleep(50)
            }
            return System.currentTimeMillis() - t0
        }
        try {
            assertTrue(a.call(bId.userKey) is CallLane.Dialled.Ringing)
            waitFor("B to ring", 15_000) { b.state == CallState.RINGING }
            assertEquals(CallRefusal.NONE, b.answer())
            waitFor("A connected", 15_000) { a.state == CallState.IN_CALL }
            val pathMs = waitFor("a media path on both sides", 25_000) {
                a.mediaDiagnostics().pathSelected && b.mediaDiagnostics().pathSelected
            }
            println("[inband] media path on both sides after $pathMs ms")
            // Past iOS's 3 s post-connect grace, which applies to the in-band copy too.
            Thread.sleep(4_000)
            val accepted = b.mediaDiagnostics().framesAccepted
            println("[inband] B accepted $accepted audio frames so far")

            // Cut B off from the signalling server, THEN hang up on A.
            bPolls.set(false)
            Thread.sleep(1_200) // let any in-flight poll finish
            val t = System.currentTimeMillis()
            assertEquals(CallRefusal.NONE, a.hangUp())
            println("[inband] A sent ${a.inBandEndsSent} in-band copies")
            val endMs = waitFor("B to end from the in-band hang-up alone", 10_000) {
                b.state == CallState.ENDED && bEnded.isNotEmpty()
            }
            println("[inband] B ended ${System.currentTimeMillis() - t} ms after A hung up (waited $endMs ms) — reason=${bEnded.first().reason}, applied=${b.inBandEndsApplied}")
            assertEquals(3, a.inBandEndsSent)
            assertEquals(1, b.inBandEndsApplied)
            assertEquals(CallEndReason.HUNG_UP, bEnded.first().reason)
            assertTrue(bEnded.first().wasConnected)
            assertEquals(1, b.calls().size)
            assertEquals("B must not echo an in-band hang-up back", 0, b.inBandEndsSent)
        } finally {
            pollers.shutdownNow()
            a.closeMedia(); b.closeMedia()
        }
    }
}
