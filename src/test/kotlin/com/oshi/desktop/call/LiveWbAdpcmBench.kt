package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.transport.UdpRelayClient
import com.oshi.desktop.call.transport.WsRelayClient
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.ui.state.CallScreenModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * __WB_ADPCM_CODEC_2026_09_23__ LIVE, against production: two Desktop lanes place a real call
 * through the production call server (signed signalling, relay tokens) with the new 0x18
 * codec, media forced onto ONE relay carrier — the `:8089` UDP relay, or the WebSocket relay.
 * The microphone is a synthetic 440 Hz tone paced at 20 ms (this Mac has one microphone and
 * two capture sessions starve each other — see LiveRelayBench), the speaker discards.
 *
 * Gate: `OSHI_LIVE_CALL=1`.
 */
class LiveWbAdpcmBench {

    private fun live() = Assume.assumeTrue("set OSHI_LIVE_CALL=1", System.getenv("OSHI_LIVE_CALL") == "1")

    private class ToneSession(spec: CallMediaSpec, send: (ByteArray) -> Unit) :
        CallAudioSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send) {
        override fun openDevices(): AudioDevices {
            var n = 0L
            var next = System.nanoTime()
            return AudioDevices(
                read = { b, off, len ->
                    next += 20_000_000L
                    val wait = next - System.nanoTime()
                    if (wait > 0) {
                        try { Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt()) }
                        catch (_: InterruptedException) { return@AudioDevices 0 }
                    }
                    var i = off
                    while (i + 1 < off + len) {
                        // Big-endian signed 16 — CallAudio.FORMAT.
                        val s = (6000 * Math.sin(2 * Math.PI * 440 * n++ / 48000.0)).toInt()
                        b[i] = (s shr 8).toByte(); b[i + 1] = s.toByte(); i += 2
                    }
                    len
                },
                write = { _, _, _ -> try { Thread.sleep(CallAudio.FRAME_MS.toLong()) } catch (_: InterruptedException) {} },
                close = {},
            )
        }
    }

    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean): Long {
        val t0 = System.currentTimeMillis()
        while (!cond()) {
            check(System.currentTimeMillis() - t0 < timeoutMs) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
        return System.currentTimeMillis() - t0
    }

    /** Per-leg wire observations: sizes and types of every sealed media frame received. */
    private class Wire {
        val sizes = ConcurrentHashMap<Int, AtomicLong>()
        val types = ConcurrentHashMap<Int, AtomicLong>()
        fun note(b: ByteArray) {
            if (b.isEmpty()) return
            types.computeIfAbsent(b[0].toInt() and 0xFF) { AtomicLong() }.incrementAndGet()
            sizes.computeIfAbsent(b.size) { AtomicLong() }.incrementAndGet()
        }
    }

    private fun runCall(tag: String, relay: CallRelayConfig) {
        val server = V2Http.defaultBaseUrl()
        val sessions = ConcurrentHashMap<String, ToneSession>()
        val wires = ConcurrentHashMap<String, Wire>()
        val specs = ConcurrentHashMap<String, CallMediaSpec>()
        fun opener(device: String) = CallMediaOpener { spec, log ->
            specs[device] = spec
            val leg = CallMediaLeg(
                spec = spec,
                datagram = DatagramSocket(0),
                audioFor = { send ->
                    ToneSession(spec, send).also {
                        it.useWbAdpcm = spec.wbAdpcm   // what CallMedia.real does
                        sessions[device] = it
                    }
                },
                stunServer = null,
                log = log,
                hostCandidates = { emptyList() },
                relayConfig = relay,
            )
            val w = Wire(); wires[device] = w
            leg.tap = { w.note(it) }
            leg
        }
        fun lane(id: DesktopIdentity, device: String) = CallLane(
            myAddress = id.userKey,
            myPrivateKey = id.identity.priv,
            transport = CallSignalClient(server, DesktopV2Signer(id)),
            deviceId = device,
            machine = CallStateMachine(id.userKey, null, device),
            mediaOpener = opener(device),
            log = { println("[$tag] $device: $it") },
        )
        val aId = DesktopIdentity.generate(); val bId = DesktopIdentity.generate()
        val a = lane(aId, "desk-A"); val b = lane(bId, "desk-B")
        val aModel = CallScreenModel(a, labelFor = { "B" })
        val bModel = CallScreenModel(b, labelFor = { "A" })
        a.onEvent = { aModel.onEvent(it) }
        b.onEvent = { bModel.onEvent(it) }
        val pollers = Executors.newScheduledThreadPool(2)
        for (l in listOf(a, b)) pollers.scheduleWithFixedDelay({
            runCatching { l.pollOnce(); l.tick() }.onFailure { println("[$tag] poll failed: $it") }
        }, 0, CallLane.POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        try {
            aModel.call(bId.userKey, video = false)
            waitFor("B to ring", 15_000) { bModel.screen?.phase == CallScreenModel.Phase.INCOMING }
            bModel.answer()
            waitFor("both connected", 15_000) {
                aModel.screen?.phase == CallScreenModel.Phase.CONNECTED && bModel.screen?.phase == CallScreenModel.Phase.CONNECTED
            }
            println("[$tag] NEGOTIATION A: spec.wbAdpcm=${specs["desk-A"]?.wbAdpcm} session.useWbAdpcm=${sessions["desk-A"]?.useWbAdpcm}")
            println("[$tag] NEGOTIATION B: spec.wbAdpcm=${specs["desk-B"]?.wbAdpcm} session.useWbAdpcm=${sessions["desk-B"]?.useWbAdpcm}")
            val regMs = waitFor("carrier usable on both legs", 15_000) {
                val am = a.media; val bm = b.media
                if (relay.udpRelay != null) am?.udpRelayClient?.usable() == true && bm?.udpRelayClient?.usable() == true
                else am?.wsRelayClient?.usable() == true && bm?.wsRelayClient?.usable() == true
            }
            Thread.sleep(2_000)   // settle
            val sa = sessions["desk-A"]!!; val sb = sessions["desk-B"]!!
            val am = a.media!!; val bm = b.media!!
            val aSent0 = sa.framesSent.get(); val bSent0 = sb.framesSent.get()
            val aAcc0 = am.framesAccepted.get(); val bAcc0 = bm.framesAccepted.get()
            val aRef0 = am.framesRefused.get(); val bRef0 = bm.framesRefused.get()
            val wa = wires["desk-A"]!!; val wb = wires["desk-B"]!!
            wa.sizes.clear(); wa.types.clear(); wb.sizes.clear(); wb.types.clear()
            Thread.sleep(15_000)
            val aSent = sa.framesSent.get() - aSent0; val bSent = sb.framesSent.get() - bSent0
            val bAcc = bm.framesAccepted.get() - bAcc0; val aAcc = am.framesAccepted.get() - aAcc0
            val lossAB = if (aSent > 0) 100.0 * (aSent - bAcc) / aSent else -1.0
            val lossBA = if (bSent > 0) 100.0 * (bSent - aAcc) / bSent else -1.0
            println("[$tag] carrier registered $regMs ms after connect")
            println("[$tag] RESULT A→B over 15 s: sent=$aSent  B received(decoded)=$bAcc  refused=${bm.framesRefused.get() - bRef0}  loss=${"%.2f".format(lossAB)}%  B wire types=${wb.types} sizes=${wb.sizes}")
            println("[$tag] RESULT B→A over 15 s: sent=$bSent  A received(decoded)=$aAcc  refused=${am.framesRefused.get() - aRef0}  loss=${"%.2f".format(lossBA)}%  A wire types=${wa.types} sizes=${wa.sizes}")
            println("[$tag] relay counters: A relaySent=${am.relaySent.get()} relayRecv=${am.relayReceived.get()} wsSent=${am.wsSent.get()} | B relaySent=${bm.relaySent.get()} relayRecv=${bm.relayReceived.get()} wsSent=${bm.wsSent.get()}")
            println("[$tag] udp auth: A lastNack=${am.udpRelayClient?.lastNack} authRegs=${am.udpRelayClient?.authRegistersSent} legacyRegs=${am.udpRelayClient?.legacyRegistersSent} authFailed=${am.udpRelayClient?.authFailed} | B lastNack=${bm.udpRelayClient?.lastNack} authRegs=${bm.udpRelayClient?.authRegistersSent} legacyRegs=${bm.udpRelayClient?.legacyRegistersSent} authFailed=${bm.udpRelayClient?.authFailed}")
            assertEquals(true, sa.useWbAdpcm); assertEquals(true, sb.useWbAdpcm)
            assertTrue("only 0x18 audio on the wire to B", wb.types.keys.all { it == CallMediaFrame.TYPE_WB_ADPCM })
            assertTrue("A→B delivered", bAcc >= aSent * 9 / 10)
            assertTrue("B→A delivered", aAcc >= bSent * 9 / 10)
            aModel.hangUp()
            waitFor("B ended", 15_000) { bModel.screen?.phase == CallScreenModel.Phase.ENDED }
        } finally {
            pollers.shutdownNow()
            a.closeMedia(); b.closeMedia()
        }
    }

    @Test
    fun wbAdpcmCallOverProductionUdpRelayOnly() {
        live()
        runCall("wb-udp", CallRelayConfig(turn = null, udpRelay = UdpRelayClient.DEFAULT_SERVER, relayOnly = true))
    }

    @Test
    fun wbAdpcmCallOverProductionWebSocketRelayOnly() {
        live()
        runCall("wb-ws", CallRelayConfig(turn = null, udpRelay = null, relayOnly = true,
            webSocketUrl = WsRelayClient.urlFor(V2Http.defaultBaseUrl())))
    }
}
