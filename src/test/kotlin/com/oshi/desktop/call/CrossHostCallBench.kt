package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.transport.StunBinding
import com.oshi.desktop.call.video.CallVideoSession
import com.oshi.desktop.call.video.SyntheticCamera
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.ui.state.CallScreenModel
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assume
import org.junit.Test
import java.net.DatagramSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

/**
 * A REAL video call through the production call server with no camera, microphone or
 * speaker — built for the only Windows this project can test on: GitHub's `windows-latest`.
 *
 * Everything between the devices is the shipped code: `CallLane` signalling against the
 * live server, the sealed offer/answer, STUN, the hole punch, `MediaSocket`, audio sealing
 * and the replay window, the H.264 encoder and decoder from THIS host's FFmpeg natives,
 * fragmentation and the video seal. Only the three ends are substituted:
 *
 * - microphone → a 440 Hz tone generated in real time ([ToneAudioSession]);
 * - speaker    → a counter of non-silent frames that reached it (the tone, decoded);
 * - camera     → [SyntheticCamera] moving bars, 30 fps (DirectShow itself is NOT exercised).
 *
 * Modes, from `OSHI_CROSS_ROLE`:
 * - `both`   — two lanes in this one process (same host, host candidates suffice);
 * - `caller` / `callee` — ONE side, on two different machines (two CI runners), so the
 *   call crosses two real NATs. Both sides derive both identities from `OSHI_CROSS_SEED`,
 *   which is how they find each other without any other channel.
 *
 * Each side asserts what IT received — so in the two-machine mode both directions are
 * checked, one by each job.
 */
class CrossHostCallBench {

    private val role = System.getenv("OSHI_CROSS_ROLE")
    private val seed = System.getenv("OSHI_CROSS_SEED") ?: "local-${System.nanoTime()}"
    private val holdMs = (System.getenv("OSHI_CROSS_HOLD_S")?.toLongOrNull() ?: 20L) * 1000

    /**
     * `OSHI_CROSS_RELAY`: `production` (default — what the app does: direct first, TURN and
     * the `:8089` relay as fallbacks), `turn-only` (media MUST go through coturn — the only
     * way to prove the TURN leg across two real NATs), `relay-only` (TURN or `:8089`, never
     * direct), `off` (STUN + hole punch only, as before the relay work).
     */
    private val relayMode = System.getenv("OSHI_CROSS_RELAY") ?: "production"
    private val relayConfig: CallRelayConfig? = when (relayMode) {
        "production" -> CallRelayConfig.production()
        "relay-only" -> CallRelayConfig.production(relayOnly = true)
        "turn-only" -> CallRelayConfig(com.oshi.desktop.call.transport.TurnCredentials(), udpRelay = null, relayOnly = true)
        // __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ media MUST ride the token-authenticated :8089 relay.
        "udp-relay-only" -> CallRelayConfig(turn = null, udpRelay = com.oshi.desktop.call.transport.UdpRelayClient.DEFAULT_SERVER, relayOnly = true)
        "off" -> null
        else -> error("OSHI_CROSS_RELAY=$relayMode: use production|relay-only|udp-relay-only|turn-only|off")
    }

    @Test
    fun realCallWithSyntheticDevices() {
        Assume.assumeTrue("set OSHI_CROSS_ROLE=both|caller|callee", role in setOf("both", "caller", "callee"))
        val callerId = identity("caller"); val calleeId = identity("callee")
        println("[cross] os=${System.getProperty("os.name")} ${System.getProperty("os.arch")} role=$role " +
            "relay=$relayMode caller=${callerId.userKey.take(12)}… callee=${calleeId.userKey.take(12)}…")
        if (System.getenv("OSHI_CROSS_PRINT_KEYS") == "1") println("[cross] keys caller=${callerId.userKey} callee=${calleeId.userKey}")
        when (role) {
            "both" -> {
                val a = Side("caller", callerId); val b = Side("callee", calleeId)
                try {
                    b.startPolling(); a.startPolling()
                    a.model.call(calleeId.userKey, video = true)
                    waitFor("callee to ring", 20_000) { b.model.screen?.phase == CallScreenModel.Phase.INCOMING }
                    b.model.answer()
                    a.measureAndAssert(); b.measureAndAssert()
                    a.model.hangUp()
                    waitFor("callee to see the hang-up", 20_000) { b.model.screen?.phase == CallScreenModel.Phase.ENDED }
                } finally { a.close(); b.close() }
            }
            "callee" -> Side("callee", calleeId).use { me ->
                me.startPolling()
                println("[cross] callee: waiting for the call")
                waitFor("an incoming call", 8 * 60_000) { me.model.screen?.phase == CallScreenModel.Phase.INCOMING }
                me.model.answer()
                // A failed check here must NOT close the call under the caller, which is still
                // measuring: CI 35868599749 lost the caller's whole result (every counter read
                // as null after teardown) to the callee's audio assertion. Hold the call until
                // the caller hangs up, then report our own verdict.
                val verdict = runCatching { me.measureAndAssert() }
                // The caller hangs up after its own measurement; do not cut it short.
                waitFor("the caller to hang up", holdMs + 60_000) { me.model.screen?.phase == CallScreenModel.Phase.ENDED }
                verdict.getOrThrow()
            }
            "caller" -> Side("caller", callerId).use { me ->
                me.startPolling()
                // The two jobs start at different times: re-dial until answered.
                val t0 = System.currentTimeMillis()
                while (true) {
                    check(System.currentTimeMillis() - t0 < 8 * 60_000) { "the callee never answered" }
                    if (me.model.screen == null || me.model.screen?.phase == CallScreenModel.Phase.ENDED) {
                        println("[cross] caller: dialling")
                        me.model.call(calleeId.userKey, video = true)
                    }
                    if (me.model.screen?.phase == CallScreenModel.Phase.CONNECTED) break
                    Thread.sleep(500)
                }
                val verdict = runCatching { me.measureAndAssert() }
                // The callee's window opens a little after ours (it waits for its own first
                // picture/path), so hanging up the moment ours closes cut the END of its window:
                // CI 35899430240 read a stopped camera and negative counters on the callee side.
                // Keep the call up long enough for it to finish measuring.
                Thread.sleep((System.getenv("OSHI_CROSS_CALLER_GRACE_MS") ?: "25000").toLong())
                me.model.hangUp()
                verdict.getOrThrow()
            }
        }
    }

    private inner class Side(val name: String, val id: DesktopIdentity) : AutoCloseable {
        val heard = AtomicLong()
        /**
         * Media and poll threads outlive the test method by a few hundred ms. Output they
         * print after JUnit has closed the test corrupts Gradle's result store ("Could not
         * write XML test results … Buffer underflow"), failing a run that passed. So a
         * closed side is silent.
         */
        @Volatile var quiet = false
        fun say(msg: String) { if (!quiet) println(msg) }
        val lane = CallLane(
            myAddress = id.userKey,
            myPrivateKey = id.identity.priv,
            transport = CallSignalClient(V2Http.defaultBaseUrl(), DesktopV2Signer(id)),
            deviceId = "bench-$name",
            machine = CallStateMachine(id.userKey, null, "bench-$name"),
            mediaOpener = CallMediaOpener { spec, log ->
                val datagram = DatagramSocket(0)
                try {
                    CallMediaLeg(
                        spec = spec,
                        datagram = datagram,
                        audioFor = { send -> ToneAudioSession(spec, send, heard).also { it.useWbAdpcm = spec.wbAdpcm; it.useOpus = spec.opus } },
                        stunServer = StunBinding.DEFAULT_SERVER,
                        log = log,
                        relayConfig = relayConfig,
                        videoFor = { send, nextSeq ->
                            CallVideoSession(
                                spec.sessionKey, spec.nonceSalt, spec.isCaller, send, nextSeq,
                                cameraFactory = { SyntheticCamera() }, log = log,
                            )
                        },
                    )
                } catch (t: Throwable) { datagram.close(); throw t }
            },
            log = { say("[cross] $name: $it") },
        )
        val model = CallScreenModel(lane, labelFor = { "peer" })
        private val poller = Executors.newSingleThreadScheduledExecutor()
        private var ticks = 0L

        init { lane.onEvent = { model.onEvent(it) } }

        fun startPolling() {
            poller.scheduleWithFixedDelay({
                runCatching { lane.pollOnce(); lane.tick(); model.tick() }
                    .onFailure { say("[cross] $name poll failed: $it") }
                // Every 2 s: what the socket and the relays actually saw, so a one-way path
                // on CI is diagnosable from the log alone.
                if (++ticks % 2 == 0L) lane.media?.let { m ->
                    val s = m.socket
                    say("[cross-stats] $name t=${ticks}s sel=${m.selected?.type}:${m.selected?.ip}:${m.selected?.port} " +
                        "sock rx=${s.packetsReceived.get()} rxErr=${s.receiveErrors.get()} pingsAns=${s.pingsAnswered.get()} " +
                        "pongs=${s.pongsCorrelated.get()} dropped=${s.packetsDropped.get()} | accepted=${m.framesAccepted.get()} " +
                        "refused=${m.framesRefused.get()} | 8089 rx=${m.relayReceived.get()} tx=${m.relaySent.get()} ws tx=${m.wsSent.get()} " +
                        "| audio sent=${m.audio?.framesSent?.get()} video sent=${m.video?.sender?.framesSent} | video rx ${m.video?.rxStats()}")
                }
            }, 0, CallLane.POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

        fun measureAndAssert() {
            waitFor("$name connected", 30_000) { model.screen?.phase == CallScreenModel.Phase.CONNECTED }
            // relay-only may legitimately carry everything on :8089 with NO pair selected.
            val pathMs = if (relayMode == "relay-only" || relayMode == "udp-relay-only") 0L
            else waitFor("$name media path", 30_000) { lane.mediaDiagnostics().pathSelected }
            val sel = lane.media?.selected
            println("[cross] $name: media path selected after $pathMs ms — relay mode $relayMode, " +
                "our candidate ${sel?.type} ${sel?.let { "${it.ip}:${it.port}" }}")
            if (relayMode == "turn-only") {
                check(sel?.type == com.oshi.desktop.call.transport.IceCandidateType.RELAY) {
                    "$name: relay-only call selected a ${sel?.type} path — the relay was not what carried it"
                }
            }
            val v = lane.video() ?: error("$name: a video call opened no video session")
            // Not fatal here: a peer with no working encoder is diagnosed by the checks below,
            // which print every counter first. Failing now hid the audio half of the result.
            runCatching { waitFor("$name first remote picture", 20_000) { v.remoteFrameCount > 0 } }
                .onFailure { println("[cross] $name: no remote picture after 20 s — measuring anyway") }
            val r0 = lane.mediaDiagnostics().framesRefused
            val f0 = v.remoteFrameCount; val a0 = lane.mediaDiagnostics().framesAccepted; val h0 = heard.get()
            Thread.sleep(holdMs)
            val d = lane.mediaDiagnostics()
            val finalSel = lane.media?.selected
            println("[cross] $name: carried by ${finalSel?.type ?: "no ICE pair"}; :8089 relay datagrams sent=${lane.media?.relaySent?.get()}")
            lane.media?.udpRelayClient?.let { r ->
                println("[cross] $name: :8089 registered=${r.registered} authRegisters=${r.authRegistersSent} " +
                    "legacyRegisters=${r.legacyRegistersSent} lastNack=${r.lastNack} relayReceived=${lane.media?.relayReceived?.get()}")
                if (relayMode == "udp-relay-only") {
                    check(r.authRegistersSent > 0 && r.legacyRegistersSent == 0L) { "$name: :8089 registers were not token-authenticated" }
                    check((lane.media?.relayReceived?.get() ?: 0L) > 0) { "$name: nothing arrived over :8089" }
                }
            }
            if (relayMode == "turn-only") {
                check(finalSel?.type == com.oshi.desktop.call.transport.IceCandidateType.RELAY) { "$name: TURN pair lost during the call (now ${finalSel?.type})" }
                check((lane.media?.relaySent?.get() ?: 0L) == 0L) { "$name: turn-only call leaked onto the :8089 relay" }
            }
            val pics = v.remoteFrameCount - f0; val audio = d.framesAccepted - a0; val tone = heard.get() - h0
            val secs = holdMs / 1000.0
            println(
                "[cross-result] $name os=${System.getProperty("os.name")} encoder=${v.encoderName} " +
                    "audioCodec=${if (lane.media?.audio?.useOpus == true) "opus(0x19)" else if (lane.media?.audio?.useWbAdpcm == true) "wb-adpcm(0x18)" else "pcm(0x15)"} " +
                    "camera=${v.cameraRunning} problem=${v.cameraProblem} | received over ${secs}s: " +
                    "$pics pictures (${"%.1f".format(pics / secs)} fps, ${v.remoteFrame?.width}x${v.remoteFrame?.height}), " +
                    "$audio audio frames accepted, $tone non-silent frames played | sent ${d.framesSent} audio, " +
                    "${v.sender.framesSent} video frames | refused ${d.framesRefused - r0} frames, " +
                    "via :8089 ${lane.media?.relayReceived?.get()} rx / ${lane.media?.relaySent?.get()} tx, ws tx ${lane.media?.wsSent?.get()}",
            )
            // Frames actually encoded, not the live flag: the peer may already be hanging up.
            check(v.cameraRunning || v.sender.framesSent > 0) { "$name: our synthetic camera/encoder did not run: ${v.cameraProblem}" }
            check(pics >= secs * 10) { "$name: fewer than 10 fps of the peer's video decoded ($pics in ${secs}s)" }
            check(audio >= secs * 25) { "$name: fewer than half the peer's audio frames arrived ($audio in ${secs}s)" }
            check(tone >= audio / 2) { "$name: audio arrived but decoded to silence ($tone of $audio)" }
        }

        override fun close() {
            quiet = true
            poller.shutdownNow()
            runCatching { poller.awaitTermination(3, TimeUnit.SECONDS) }
            runCatching { lane.closeMedia() }
            Thread.sleep(1500) // let capture/render/decode threads wind down before JUnit moves on
        }
    }

    /** The microphone is a 440 Hz tone; the speaker counts frames that are not silence. */
    private class ToneAudioSession(spec: CallMediaSpec, send: (ByteArray) -> Unit, val heard: AtomicLong) :
        CallAudioSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send) {
        override fun openDevices(): AudioDevices {
            var phase = 0.0
            var nextAt = System.nanoTime()
            val frameNs = CallAudio.FRAME_MS * 1_000_000L
            return AudioDevices(
                read = { b, off, len ->
                    val wait = nextAt - System.nanoTime()
                    if (wait > 0) try {
                        Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    } catch (_: InterruptedException) { return@AudioDevices 0 } // stop() interrupts
                    nextAt += frameNs * len / CallAudio.BYTES_PER_FRAME
                    var i = off
                    while (i + 1 < off + len) {
                        val v = (sin(phase) * 8000).toInt()
                        phase += 2 * PI * 440 / CallAudio.SAMPLE_RATE
                        b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte() // little-endian, as CallAudio.FORMAT
                        i += 2
                    }
                    len
                },
                write = { b, off, len ->
                    if ((off until off + len).any { b[it] != 0.toByte() }) heard.incrementAndGet()
                    try { Thread.sleep(len.toLong() * 1000 / (CallAudio.SAMPLE_RATE.toLong() * 2)) } catch (_: InterruptedException) {}
                },
                close = {},
            )
        }
    }

    private fun identity(who: String): DesktopIdentity {
        fun bytes(label: String) = MessageDigest.getInstance("SHA-256").digest("oshi-cross-bench|$seed|$who|$label".toByteArray())
        val x = X25519PrivateKeyParameters(bytes("x25519"), 0)
        val e = Ed25519PrivateKeyParameters(bytes("ed25519"), 0)
        return DesktopIdentity(OSHICryptoV2.X25519Pair(x.encoded, x.generatePublicKey().encoded), e.encoded, e.generatePublicKey().encoded)
    }

    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean): Long {
        val t0 = System.currentTimeMillis()
        while (!cond()) {
            check(System.currentTimeMillis() - t0 < timeoutMs) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
        return System.currentTimeMillis() - t0
    }
}
