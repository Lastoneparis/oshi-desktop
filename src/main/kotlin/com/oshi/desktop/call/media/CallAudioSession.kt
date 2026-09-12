package com.oshi.desktop.call.media

import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * The audio device half of a call — capture, render, and a teardown that actually
 * releases the hardware.
 *
 * ============================================================ THE FORMAT IS NOT A CHOICE
 *
 * 48 000 Hz, mono, signed 16-bit, **big-endian**, 20 ms frames = 960 samples = 1 920
 * bytes. Every one of those is fixed by the peer, not by us:
 *
 *  - 48 kHz mono signed-16 is what packet type `0x15` means
 *    (`VoiceCallManager.swift:3077`, `RAW_PCM_AUDIO_TYPE`).
 *  - 20 ms is the frame the whole pipeline is built around — `CALL_V2_PLAN.md:31`,
 *    "20 ms frames @ 48 kHz → 960 samples per frame" — and it is what makes iOS's
 *    measured 50 fps and 1 957 bytes per datagram add up.
 *  - **Big-endian is the one that will surprise a JVM developer.** Every multi-byte
 *    field in this protocol is big-endian (the packet header, the media sequence, the
 *    ICE TLV), and `javax.sound.sampled` defaults to little-endian on a PCM line. Get
 *    it wrong and the call is not silent — it is loud white noise, because every sample
 *    has its bytes swapped. That is why [FORMAT] passes `bigEndian = true` explicitly
 *    rather than letting a default decide.
 *
 * ============================================================ WHAT THE JDK DOES NOT GIVE YOU
 *
 * `javax.sound.sampled` is a device API and nothing more. It has **no acoustic echo
 * canceller, no noise suppression, no automatic gain control, no jitter buffer and no
 * packet-loss concealment.** All five exist in libwebrtc and none of them can be borrowed
 * without taking the whole stack (see `build.gradle.kts`'s WEBRTC block).
 *
 * The practical consequence, stated plainly because a user will hear it: **on speakers,
 * without headphones, the far end hears themselves.** That is not a bug in this file, it
 * is the absence of an AEC, and no amount of buffer tuning fixes it. Headphones make it
 * go away. [PlaybackBuffer] below is a fixed 60 ms de-jitter queue, which is what the
 * shipped clients started from too (`CALL_V2_PLAN.md` calls the current phone buffer "a
 * fixed 60 ms that drops everything late" and specifies an adaptive replacement that has
 * not shipped). This client is therefore no worse than the phones on jitter and worse
 * than them on echo, and both halves of that sentence are the honest status.
 *
 * ============================================================ TEARDOWN
 *
 * A microphone that is not released stays lit. On Windows and on GNOME the OS shows a
 * recording indicator, and on Linux a held ALSA/PulseAudio device can block the next
 * call from opening it at all. So [stop] is idempotent, runs even if the call ended by
 * exception, and does `stop()` → `flush()` → `close()` on both lines in that order —
 * `close()` alone can block on a line with queued data, and `flush()` before `stop()`
 * races the mixer thread.
 *
 * ============================================================ WHAT IS VERIFIED HERE
 *
 * The frame arithmetic, the buffer and the format constants are unit-tested. **Opening a
 * real microphone is NOT tested and cannot be** — CI has no audio hardware, and this
 * machine's device is shared with other sessions. [isAvailable] exists so a caller can
 * ask before it rings, and it is the only honest thing this file can say about hardware
 * without touching it. In the spirit of PARITY.md row 0.27's radio link: the codec and
 * the framing are exercised; the device has never moved a sample on any machine that ran
 * these tests.
 */
object CallAudio {

    const val SAMPLE_RATE = 48_000f
    const val SAMPLE_BITS = 16
    const val CHANNELS = 1

    /** 20 ms. See the class doc — this is the peer's frame size, not a preference. */
    const val FRAME_MS = 20

    /** 960 samples at 48 kHz. */
    const val SAMPLES_PER_FRAME = (SAMPLE_RATE.toInt() / 1000) * FRAME_MS

    /** 1 920 bytes: 960 samples × 2 bytes, mono. */
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * (SAMPLE_BITS / 8) * CHANNELS

    /** Frames per second. 50. Used by the bandwidth note in [CallMediaFrame]. */
    const val FRAMES_PER_SECOND = 1000 / FRAME_MS

    /**
     * The one PCM format this client speaks. **Big-endian on purpose** — see the class doc.
     */
    val FORMAT: AudioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        SAMPLE_RATE,
        SAMPLE_BITS,
        CHANNELS,
        (SAMPLE_BITS / 8) * CHANNELS,
        SAMPLE_RATE,
        true,
    )

    /**
     * Can this machine both capture and render [FORMAT]?
     *
     * Asks the mixer rather than opening a line, so it is safe to call while another
     * call is up. A false here is a real answer — a headless server, a container with no
     * `/dev/snd`, a machine whose only device is exclusive-mode — and a caller should
     * refuse the call with a reason rather than ringing and then failing silently.
     */
    fun isAvailable(): Boolean =
        AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, FORMAT)) &&
            AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, FORMAT))

    /** Names of the mixers that can capture [FORMAT]. Diagnostics for a CLI. */
    fun captureDevices(): List<String> =
        AudioSystem.getMixerInfo().filter { info ->
            runCatching {
                AudioSystem.getMixer(info).isLineSupported(DataLine.Info(TargetDataLine::class.java, FORMAT))
            }.getOrDefault(false)
        }.map { "${it.name} — ${it.description}" }
}

/**
 * A running audio session: microphone in, speaker out, both released on [stop].
 *
 * Deliberately NOT a state machine and deliberately not aware of a call. It is handed a
 * session key and a direction by [com.oshi.desktop.call.CallAction.StartMedia] and it
 * moves bytes; every decision about whether a call should be running belongs to
 * [com.oshi.desktop.call.CallStateMachine]. That split is what lets the state machine be
 * tested with no audio hardware at all.
 *
 * @param send hands each SEALED frame to the transport. The transport is not this class's
 *   business — P2P UDP, a TURN channel, the :8089 relay and the WebSocket all take the
 *   same bytes.
 */
open class CallAudioSession(
    private val sessionKey: ByteArray,
    private val baseSalt: ByteArray,
    private val isCaller: Boolean,
    private val send: (ByteArray) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val sequence = MediaSequence()
    private val replay = ReplayWindow()

    /**
     * The speaker queue.
     *
     * `internal` rather than private so a test can assert on WHAT REACHED THE SPEAKER
     * without a speaker: [onFrame] is the whole inbound path and its only observable
     * effect is this queue, so a private one would make "the peer's audio arrived and
     * decoded to the right bytes" unassertable on a machine with no audio hardware —
     * which is every machine that runs these tests.
     */
    internal val playback = PlaybackBuffer()

    private var mic: TargetDataLine? = null
    private var speaker: SourceDataLine? = null
    private var captureThread: Thread? = null
    private var renderThread: Thread? = null

    /** True between a successful [start] and [stop]. */
    val isRunning: Boolean get() = running.get()

    /**
     * Open both lines and start pumping.
     *
     * Throws [LineUnavailableException] rather than returning false, and the distinction
     * is deliberate: a call that cannot open the microphone must be ENDED with a reason
     * the user sees, not connected silently. A silent connected call is the single most
     * expensive failure mode in a messenger, because both people wait.
     *
     * If the second line fails to open, the first is released before the exception
     * propagates — otherwise a failed call leaves the microphone lit.
     *
     * `open` for exactly one reason: **CI cannot produce a device that refuses to open,
     * and that is the branch the whole "never silently connected" rule hangs off.** A test
     * substitutes a session whose `start` throws, so
     * [com.oshi.desktop.call.CallMediaLeg.start]'s release-then-rethrow and
     * [com.oshi.desktop.call.CallLane]'s end-the-call path can both be watched failing.
     * Nothing in the application subclasses this.
     */
    @Throws(LineUnavailableException::class)
    open fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val inLine = AudioSystem.getLine(
                DataLine.Info(TargetDataLine::class.java, CallAudio.FORMAT),
            ) as TargetDataLine
            // Four frames of device buffer. Smaller starves on a scheduling hiccup;
            // larger adds latency the user hears as delay before the jitter buffer ever
            // sees the audio.
            inLine.open(CallAudio.FORMAT, CallAudio.BYTES_PER_FRAME * 4)
            mic = inLine

            val outLine = AudioSystem.getLine(
                DataLine.Info(SourceDataLine::class.java, CallAudio.FORMAT),
            ) as SourceDataLine
            outLine.open(CallAudio.FORMAT, CallAudio.BYTES_PER_FRAME * 4)
            speaker = outLine

            inLine.start()
            outLine.start()

            captureThread = Thread({ pumpCapture(inLine) }, "oshi-call-capture").apply {
                isDaemon = true
                start()
            }
            renderThread = Thread({ pumpRender(outLine) }, "oshi-call-render").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            stop()
            throw t
        }
    }

    /**
     * Feed one frame that arrived from the peer.
     *
     * Returns false when the frame did not authenticate, replays, or is a codec this
     * client cannot decode — three different reasons a frame is not played, all of which
     * a caller may want to count separately from silence.
     */
    fun onFrame(frame: ByteArray): Boolean {
        val decoded = CallMediaFrame.decode(sessionKey, frame) ?: return false
        if (!replay.accept(decoded.seq)) return false
        // 0x05 AAC-ELD and 0x16 OshiCodec authenticate fine and are not decodable here.
        // Dropping them is correct; playing the compressed bytes as PCM is white noise.
        if (decoded.audioType != CallMediaFrame.TYPE_PCM_48K) return false
        playback.offer(decoded.pcm)
        return true
    }

    private fun pumpCapture(line: TargetDataLine) {
        val buf = ByteArray(CallAudio.BYTES_PER_FRAME)
        while (running.get()) {
            var filled = 0
            while (filled < buf.size && running.get()) {
                val n = line.read(buf, filled, buf.size - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled != buf.size || !running.get()) continue
            val sealed = CallMediaFrame.encode(
                sessionKey, baseSalt, isCaller, sequence.next(),
                CallMediaFrame.TYPE_PCM_48K, buf.copyOf(),
            )
            runCatching { send(sealed) }
        }
    }

    private fun pumpRender(line: SourceDataLine) {
        while (running.get()) {
            val frame = playback.take(CallAudio.FRAME_MS.toLong())
            // A missing frame is written as silence rather than skipped. Skipping shortens
            // the stream and every later frame plays early, which compounds — the drift a
            // PLC would otherwise hide.
            val pcm = frame ?: ByteArray(CallAudio.BYTES_PER_FRAME)
            runCatching { line.write(pcm, 0, pcm.size) }
        }
    }

    /**
     * Release both devices. Idempotent, and safe to call from any thread including from
     * inside [start]'s own failure path.
     *
     * `stop()` → `flush()` → `close()`, in that order, on both lines. See the class doc
     * of [CallAudio] for why the order is not arbitrary.
     *
     * `open` for the same reason [start] is: a test that substitutes a failing session has
     * to be able to observe that this was called anyway.
     */
    open fun stop() {
        running.set(false)
        captureThread?.interrupt()
        renderThread?.interrupt()
        captureThread = null
        renderThread = null
        mic?.let { runCatching { it.stop() }; runCatching { it.flush() }; runCatching { it.close() } }
        speaker?.let { runCatching { it.stop() }; runCatching { it.flush() }; runCatching { it.close() } }
        mic = null
        speaker = null
        playback.clear()
    }
}

/**
 * A fixed-depth de-jitter queue.
 *
 * 60 ms — three 20 ms frames — which is what the shipped clients use today.
 * `CALL_V2_PLAN.md` §B describes it accurately as "a fixed 60 ms that drops everything
 * late" and specifies an adaptive replacement modelled on NetEQ; that replacement has not
 * shipped on either phone, so matching the current behaviour is parity and improving on
 * it unilaterally would make the desktop's timing differ from both peers'.
 *
 * The cap is the important part. An unbounded queue on a path anyone can write to grows
 * without limit under a flood, and audio that arrives faster than 50 fps is either an
 * attack or a broken sender — neither is worth buffering.
 */
class PlaybackBuffer(private val maxFrames: Int = MAX_FRAMES) {
    private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    fun offer(pcm: ByteArray) {
        // Drop the OLDEST on overflow, never the newest: in a live call the freshest
        // audio is the only audio worth hearing, and discarding it to preserve a backlog
        // makes the call lag further behind with every overrun.
        while (queue.size >= maxFrames) queue.poll()
        queue.offer(pcm)
    }

    /** The next frame, or null after [timeoutMs]. Null means "play silence". */
    fun take(timeoutMs: Long): ByteArray? =
        runCatching {
            queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()

    fun size(): Int = queue.size

    fun clear() = queue.clear()

    companion object {
        /** 3 × 20 ms = 60 ms, the shipped depth. */
        const val MAX_FRAMES = 3
    }
}
