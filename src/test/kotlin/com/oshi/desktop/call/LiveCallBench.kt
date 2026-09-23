package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.ui.state.CallScreenModel
import org.junit.Assume
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A REAL call, not a test double: two desktop identities, the PRODUCTION call server
 * (`https://oshi-messenger.com/api/call`), real UDP sockets, real STUN, and this machine's real
 * microphone and speaker (`CallMedia.real()`). Skipped unless `OSHI_LIVE_CALL=1`:
 *
 *   OSHI_LIVE_CALL=1 ./gradlew test --tests com.oshi.desktop.call.LiveCallBench -i
 *
 * B is driven through `CallScreenModel` — the object the window's buttons call — so "B
 * answers in the window" is the model's `answer()`, minus only the pixel it would be clicked on.
 * Only the call lane is used: no message-relay account is registered for these identities.
 *
 * What it proves and what it does not: frames leaving and authenticating on BOTH sides over a
 * path ICE selected. Both endpoints are on this one Mac, so the path is host-to-host on one
 * machine — it says nothing about NAT traversal between two networks (no TURN in this stack).
 * If macOS has not granted the JVM microphone access, capture delivers silence: frames still
 * flow and are counted, but carry zeros.
 */
class LiveCallBench {

    @Test
    fun twoDesktopsCallEachOtherThroughTheRealServer() {
        Assume.assumeTrue("set OSHI_LIVE_CALL=1 to place a real call", System.getenv("OSHI_LIVE_CALL") == "1")
        val server = V2Http.defaultBaseUrl()

        fun lane(id: DesktopIdentity, device: String) = CallLane(
            myAddress = id.userKey,
            myPrivateKey = id.identity.priv,
            transport = CallSignalClient(server, DesktopV2Signer(id)),
            deviceId = device,
            machine = CallStateMachine(id.userKey, null, device),
            mediaOpener = CallMedia.real(),
            log = { println("[live] $device: $it") },
        )
        val aId = DesktopIdentity.generate(); val bId = DesktopIdentity.generate()
        val a = lane(aId, "desk-A"); val b = lane(bId, "desk-B")
        val aEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bModel = CallScreenModel(b, labelFor = { "A" })
        val aModel = CallScreenModel(a, labelFor = { "B" })
        a.onEvent = { aEvents += it.toString(); aModel.onEvent(it) }
        b.onEvent = { bModel.onEvent(it) }

        // The same 1 s poll + tick the window's client runs.
        val pollers = Executors.newScheduledThreadPool(2)
        for (l in listOf(a, b)) pollers.scheduleWithFixedDelay({
            runCatching { l.pollOnce(); l.tick() }.onFailure { println("[live] poll failed: $it") }
        }, 0, CallLane.POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)

        fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean): Long {
            val t0 = System.currentTimeMillis()
            while (!cond()) {
                check(System.currentTimeMillis() - t0 < timeoutMs) { "timed out waiting for $what" }
                Thread.sleep(50)
            }
            return System.currentTimeMillis() - t0
        }
        try {
            val dial0 = System.currentTimeMillis()
            aModel.call(bId.userKey)
            val ringMs = waitFor("B to ring", 15_000) { bModel.screen?.phase == CallScreenModel.Phase.INCOMING }
            println("[live] B rang ${ringMs} ms after A dialled (server round trip + ≤1 s poll)")

            bModel.answer()
            val connB = waitFor("B connected", 15_000) { bModel.screen?.phase == CallScreenModel.Phase.CONNECTED }
            val connA = waitFor("A connected", 15_000) { aModel.screen?.phase == CallScreenModel.Phase.CONNECTED }
            println("[live] connected: B ${connB} ms after answer, A ${connA} ms later; total ${System.currentTimeMillis() - dial0} ms from dial")

            val pathMs = waitFor("an ICE path on both sides", 20_000) {
                a.mediaDiagnostics().pathSelected && b.mediaDiagnostics().pathSelected
            }
            println("[live] ICE path selected on both sides after $pathMs ms")

            val before = a.mediaDiagnostics() to b.mediaDiagnostics()
            Thread.sleep(10_000)
            val after = a.mediaDiagnostics() to b.mediaDiagnostics()
            fun rate(x: Long, y: Long) = "%.1f/s".format((y - x) / 10.0)
            println("[live] over 10 s: A sent ${after.first.framesSent - before.first.framesSent} (${rate(before.first.framesSent, after.first.framesSent)}), " +
                "A accepted ${after.first.framesAccepted - before.first.framesAccepted} (${rate(before.first.framesAccepted, after.first.framesAccepted)}), " +
                "refused ${after.first.framesRefused}")
            println("[live] over 10 s: B sent ${after.second.framesSent - before.second.framesSent} (${rate(before.second.framesSent, after.second.framesSent)}), " +
                "B accepted ${after.second.framesAccepted - before.second.framesAccepted} (${rate(before.second.framesAccepted, after.second.framesAccepted)}), " +
                "refused ${after.second.framesRefused}")
            println("[live] media status A=${after.first.status} B=${after.second.status}; candidates A sent/recv ${after.first.candidatesSent}/${after.first.candidatesReceived}, B ${after.second.candidatesSent}/${after.second.candidatesReceived}")

            // Mute on B: frames keep flowing (as silence).
            bModel.toggleMute()
            val m0 = b.mediaDiagnostics().framesSent
            Thread.sleep(2_000)
            println("[live] B muted: still sent ${b.mediaDiagnostics().framesSent - m0} frames in 2 s (silence)")

            aModel.hangUp()
            val endA = waitFor("A ended", 10_000) { aModel.screen?.phase == CallScreenModel.Phase.ENDED }
            val endB = waitFor("B ended", 15_000) { bModel.screen?.phase == CallScreenModel.Phase.ENDED }
            println("[live] hang-up: A ended in $endA ms, B saw it ${endB} ms later; B reason=${bModel.screen?.endedKey}")
            println("[live] microphones released: A leg=${a.media == null}, B leg=${b.media == null}")
        } finally {
            pollers.shutdownNow()
            a.closeMedia(); b.closeMedia()
        }
    }
}
