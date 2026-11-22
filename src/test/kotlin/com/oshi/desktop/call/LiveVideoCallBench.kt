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
 * PARITY.md row 2.1-v, LIVE: a VIDEO call between two desktop identities through the
 * production call server, real UDP, real STUN, this Mac's real camera and microphone.
 * Same gate and same caveats as [LiveCallBench]: `OSHI_LIVE_CALL=1`, and both ends are one
 * machine, so no NAT is traversed.
 */
class LiveVideoCallBench {

    @Test
    fun twoDesktopsVideoCallThroughTheRealServer() {
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
            aModel.call(bId.userKey, video = true)
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

            // Video: A's camera → B's decoder, over the real UDP path. Both sides open a
            // camera on connect (as the phones do); on one Mac both would share one device,
            // so B turns its camera off and the measured direction is A → B.
            b.video()!!.setCameraEnabled(false)
            val va = a.video()!!; val vb = b.video()!!
            val firstPic = waitFor("B to decode A's camera", 20_000) { vb.remoteFrameCount > 0 || va.cameraProblem != null }
            println("[live-video] A camera=${va.cameraRunning} encoder=${va.encoderName} problem=${va.cameraProblem}; first picture on B after $firstPic ms")
            val f0 = vb.remoteFrameCount; val s0 = va.sender.framesSent
            Thread.sleep(10_000)
            println("[live-video] over 10 s: A encoded+sent ${va.sender.framesSent - s0} frames (${va.sender.fragmentsSent} datagrams total, ${va.sender.bytesSent / 1024} KiB), " +
                "B decoded ${vb.remoteFrameCount - f0} pictures ${vb.remoteFrame?.width}x${vb.remoteFrame?.height}, B saw A camera off=${vb.remoteCameraOff}, A saw B camera off=${va.remoteCameraOff}")
            val d = a.mediaDiagnostics() to b.mediaDiagnostics()
            println("[live-video] audio alongside: A sent ${d.first.framesSent} accepted ${d.first.framesAccepted}; B sent ${d.second.framesSent} accepted ${d.second.framesAccepted}")
            check(vb.remoteFrameCount - f0 > 100) { "fewer than 10 fps reached B" }
            vb.remoteFrame?.let { img ->
                val bi = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, img.width, img.height, img.argb, 0, img.width)
                javax.imageio.ImageIO.write(bi, "png", java.io.File("build/live-video-remote.png"))
            }

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
