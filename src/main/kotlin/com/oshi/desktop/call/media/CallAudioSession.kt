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
 * 48 000 Hz, mono, signed 16-bit, **little-endian**, 20 ms frames = 960 samples = 1 920
 * bytes. Every one of those is fixed by the peer, not by us:
 *
 *  - 48 kHz mono signed-16 is what packet type `0x15` means
 *    (`VoiceCallManager.swift:3077`, `RAW_PCM_AUDIO_TYPE`).
 *  - 20 ms is the frame the whole pipeline is built around — `CALL_V2_PLAN.md:31`,
 *    "20 ms frames @ 48 kHz → 960 samples per frame" — and it is what makes iOS's
 *    measured 50 fps and 1 957 bytes per datagram add up.
 *  - **Little-endian is the one that will surprise a reader of this protocol.** Every
 *    multi-byte HEADER field is big-endian (the packet header, the media sequence, the
 *    ICE TLV) — but the PCM samples are not. iOS assembles each `0x15` sample as
 *    `(hi << 8) | lo` from bytes `[lo, hi]` (`VoiceCallManager.swift` `int16ToFloat32`,
 *    "LE assemble … same byte order as Android emits") and emits native ARM64 Int16;
 *    Android writes `AudioRecord` bytes and plays them into `AudioTrack` untouched, both
 *    native little-endian. Get it wrong and the call is not silent — it is loud white
 *    noise, because every sample has its bytes swapped. **This file shipped with
 *    `bigEndian = true` until 2026-09-22**, which would have been exactly that noise in
 *    both directions against every phone; no test could see it because every test was
 *    desktop ↔ desktop, where the two swaps cancel. [FORMAT] passes `bigEndian = false`
 *    explicitly rather than letting a default decide.
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

    /** 2 ms at 48 kHz: the fade-in after a discontinuity (iOS `min(frameLength, 96)`). */
    const val FADE_IN_SAMPLES = 96

    /**
     * How long a received PCM frame plays, never below [FRAME_MS]: the current iPhone
     * build sends 20 ms, the App Store build ~32 ms. Capped so a malformed frame cannot
     * stall the render loop.
     */
    fun frameMsFor(pcmBytes: Int): Int =
        (pcmBytes / (BYTES_PER_FRAME / FRAME_MS)).coerceIn(FRAME_MS, 120)

    /**
     * A copy of [pcm] (Int16 little-endian, [FORMAT]) whose first [FADE_IN_SAMPLES]
     * samples ramp linearly from 0 to unity. The input is never modified.
     */
    fun fadeIn(pcm: ByteArray): ByteArray {
        val out = pcm.copyOf()
        val n = minOf(pcm.size / 2, FADE_IN_SAMPLES)
        for (i in 0 until n) {
            val s = ((out[2 * i + 1].toInt() shl 8) or (out[2 * i].toInt() and 0xFF)).toShort().toInt()
            val scaled = s * i / n
            out[2 * i] = (scaled and 0xFF).toByte()
            out[2 * i + 1] = ((scaled shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * The one PCM format this client speaks. **Little-endian, as the phones** — see the
     * class doc.
     */
    val FORMAT: AudioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        SAMPLE_RATE,
        SAMPLE_BITS,
        CHANNELS,
        (SAMPLE_BITS / 8) * CHANNELS,
        SAMPLE_RATE,
        false,
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

    private var devices: AudioDevices? = null

    /**
     * The two PCM ends of a call — microphone and speaker — reduced to the three calls this
     * class makes on them. The seam exists so a machine with NO audio hardware (a CI runner,
     * which is the only Windows this project can test on) can still run a real call through
     * the real socket, crypto and jitter path with a generated tone in place of a
     * microphone. The application only ever uses [openDevices]'s default: real lines.
     */
    class AudioDevices(
        /** Blocking read of captured PCM ([CallAudio.FORMAT]); ≤0 = nothing this time. */
        val read: (ByteArray, Int, Int) -> Int,
        /** Blocking write of PCM to be played. */
        val write: (ByteArray, Int, Int) -> Unit,
        val close: () -> Unit,
    )

    /**
     * Open the microphone and speaker. Throws [LineUnavailableException] when either will
     * not open — see [start].
     */
    @Throws(LineUnavailableException::class)
    protected open fun openDevices(): AudioDevices {
        val inLine = AudioSystem.getLine(
            DataLine.Info(TargetDataLine::class.java, CallAudio.FORMAT),
        ) as TargetDataLine
        // Four frames of device buffer. Smaller starves on a scheduling hiccup;
        // larger adds latency the user hears as delay before the jitter buffer ever
        // sees the audio.
        inLine.open(CallAudio.FORMAT, CallAudio.BYTES_PER_FRAME * 4)
        val outLine = try {
            (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, CallAudio.FORMAT)) as SourceDataLine)
                .also { it.open(CallAudio.FORMAT, CallAudio.BYTES_PER_FRAME * 4) }
        } catch (t: Throwable) {
            runCatching { inLine.close() }
            throw t
        }
        inLine.start()
        outLine.start()
        return AudioDevices(
            read = { b, off, len -> inLine.read(b, off, len) },
            write = { b, off, len -> outLine.write(b, off, len) },
            close = {
                runCatching { inLine.stop() }; runCatching { inLine.flush() }; runCatching { inLine.close() }
                runCatching { outLine.stop() }; runCatching { outLine.flush() }; runCatching { outLine.close() }
            },
        )
    }
    private var captureThread: Thread? = null
    private var renderThread: Thread? = null

    /** True between a successful [start] and [stop]. */
    val isRunning: Boolean get() = running.get()

    /**
     * __DESKTOP_CALL_UI_2026_09_23__ Mute. The capture keeps running and frames keep
     * leaving — as SILENCE — rather than stopping: a sender that goes quiet on the wire is
     * indistinguishable from a dead path to the peer's watchdogs, and the NAT mapping the
     * hole punch opened would age out under a long mute.
     */
    @Volatile var muted: Boolean = false

    /** Sealed frames handed to the socket. The outbound half of "audio is flowing". */
    val framesSent = java.util.concurrent.atomic.AtomicLong()

    /**
     * Consecutive captured frames that were EXACT digital zero while not muted.
     *
     * A real microphone never delivers a run of bit-exact zeros — even a quiet room has
     * noise in the low bits. Windows does exactly that when Settings → Privacy & security →
     * Microphone is OFF for desktop apps: the line opens, `read` returns full buffers, and
     * every sample is 0. The call looks healthy on both ends and the peer hears nothing.
     * macOS does the same for a process that was refused microphone access. This counter
     * is how the call screen can say so instead of letting both people wait.
     */
    val consecutiveSilentFrames = java.util.concurrent.atomic.AtomicLong()

    /** ~3 s of digital zero from an unmuted microphone. See [consecutiveSilentFrames]. */
    val micLooksBlocked: Boolean get() = consecutiveSilentFrames.get() >= MIC_BLOCKED_FRAMES

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
            val d = openDevices()
            devices = d
            captureThread = Thread({ pumpCapture(d) }, "oshi-call-capture").apply {
                isDaemon = true
                start()
            }
            renderThread = Thread({ pumpRender(d) }, "oshi-call-render").apply {
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
    /**
     * Draw the next value of THIS call's media counter for a non-audio sealed control
     * (`0x0E`/`0x0F` video upgrade). Shared on purpose: the control is sealed under the
     * audio key and salt, so a private counter would repeat an audio nonce.
     */
    fun nextSequence(): Long = sequence.next()

    fun onFrame(frame: ByteArray): Boolean {
        val decoded = CallMediaFrame.decode(sessionKey, frame) ?: return false
        if (!replay.accept(decoded.seq)) return false
        // 0x05 AAC-ELD and 0x16 OshiCodec authenticate fine and are not decodable here.
        // Dropping them is correct; playing the compressed bytes as PCM is white noise.
        if (decoded.audioType != CallMediaFrame.TYPE_PCM_48K) return false
        playback.offer(decoded.pcm)
        return true
    }

    private fun pumpCapture(line: AudioDevices) {
        val buf = ByteArray(CallAudio.BYTES_PER_FRAME)
        while (running.get()) {
            var filled = 0
            while (filled < buf.size && running.get()) {
                val n = line.read(buf, filled, buf.size - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled != buf.size || !running.get()) continue
            if (!muted) {
                if (buf.all { it == 0.toByte() }) consecutiveSilentFrames.incrementAndGet()
                else consecutiveSilentFrames.set(0)
            }
            val sealed = CallMediaFrame.encode(
                sessionKey, baseSalt, isCaller, sequence.next(),
                CallMediaFrame.TYPE_PCM_48K, if (muted) ByteArray(buf.size) else buf.copyOf(),
            )
            if (runCatching { send(sealed) }.isSuccess) framesSent.incrementAndGet()
        }
    }

    private fun pumpRender(line: AudioDevices) {
        // Duration of the last real frame. The iPhone App Store build sends ~32 ms frames
        // at ~30 pkt/s: waiting only 20 ms for one wrote 20 ms of silence between every
        // two of them — audio that was never sent, and a click either side of it.
        var waitMs = CallAudio.FRAME_MS.toLong()
        var fadeInNext = false
        while (running.get()) {
            val frame = playback.take(waitMs)
            // A missing frame is written as silence rather than skipped. Skipping shortens
            // the stream and every later frame plays early, which compounds — the drift a
            // PLC would otherwise hide.
            val pcm = if (frame == null) {
                fadeInNext = true
                ByteArray(CallAudio.BYTES_PER_FRAME)
            } else {
                waitMs = CallAudio.frameMsFor(frame.size).toLong()
                // __DROP_CLICK_FADE_2026_09_22__ (iOS): after an overflow drop or a
                // silence, this frame does not continue the last one's waveform — the
                // step is a click. 2 ms of fade-in removes it and cannot be heard.
                if (playback.consumeDiscontinuity() || fadeInNext) {
                    fadeInNext = false
                    CallAudio.fadeIn(frame)
                } else frame
            }
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
        devices?.let { runCatching { it.close() } }
        devices = null
        playback.clear()
    }

    companion object {
        /** 150 × 20 ms = 3 s. Long enough that a PTT-quiet start never trips it. */
        const val MIC_BLOCKED_FRAMES = 150L
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
    private val discontinuity = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Frames discarded because the queue was full. Diagnostic. */
    val overflowDrops = java.util.concurrent.atomic.AtomicLong()

    fun offer(pcm: ByteArray) {
        // Drop the OLDEST on overflow, never the newest: in a live call the freshest
        // audio is the only audio worth hearing, and discarding it to preserve a backlog
        // makes the call lag further behind with every overrun.
        while (queue.size >= maxFrames) {
            if (queue.poll() != null) {
                overflowDrops.incrementAndGet()
                discontinuity.set(true)
            }
        }
        queue.offer(pcm)
    }

    /**
     * True once after an overflow drop: the next frame taken does not continue the one
     * before it. The render loop fades it in (__DROP_CLICK_FADE_2026_09_22__).
     */
    fun consumeDiscontinuity(): Boolean = discontinuity.getAndSet(false)

    /** The next frame, or null after [timeoutMs]. Null means "play silence". */
    fun take(timeoutMs: Long): ByteArray? =
        runCatching {
            queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()

    fun size(): Int = queue.size

    fun clear() {
        queue.clear()
        discontinuity.set(false)
    }

    companion object {
        /** 3 × 20 ms = 60 ms, the shipped depth. */
        const val MAX_FRAMES = 3
    }
}
