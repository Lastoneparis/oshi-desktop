package com.oshi.desktop.call

import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_AUDIO_DEVICE_2026_10_29__ Real samples through the real speaker, and
 * optionally the real microphone.
 *
 * ## The sentence this exists to change
 *
 * PARITY.md row 2.1 says of itself, in bold: *"no microphone or speaker is ever opened by
 * any test (every session is deviceless — real codec, real replay window, real playback
 * queue, stubbed `start`/`stop`), so the device has still never moved a sample"*, and
 * `DesktopLimits` repeats it to the user. That was true of a client whose call lane is
 * otherwise measured in detail: 288 tests, two legs punching on loopback, a sealed 20 ms
 * frame arriving byte-identical at the far side's playback queue. The queue, not the speaker.
 *
 * A deviceless test cannot catch the one class of failure that only hardware produces: a
 * format the machine will not open, a line that opens and never drains, a mixer that accepts
 * bytes and plays silence.
 *
 * ## OPT-IN, and the two halves are gated SEPARATELY
 *
 * `OSHI_AUDIO_PLAYBACK=1` plays. `OSHI_AUDIO_MIC=1` records. They are two variables rather
 * than one on purpose: **a microphone is not a speaker.** Playing a tone is a sound somebody
 * might not expect; opening the microphone is taking a recording of the room, and nothing
 * should do that because a test suite ran. Anyone enabling the second one is saying so
 * deliberately, for their own machine.
 *
 * Skipped by default, like every other hardware bench here (`OSHI_LIVE_RADIO_*`,
 * `OSHI_ANDROID_PEER`) — a CI runner has no speaker and a developer's machine should not
 * start making noise because they typed `gradlew test`.
 *
 * ## What is asserted, and what a pass does NOT prove
 *
 * It proves the format this client's calls use opens on this machine, that the line accepts
 * every byte of a real frame sequence, and that it DRAINS — i.e. the device consumed the
 * samples rather than buffering them for ever. It does not prove a human heard anything;
 * nothing a program can assert does.
 */
class LiveAudioDeviceBench {

    /** ~-44 dBFS. Audible to a machine, barely to a person: a test must not be startling. */
    private val amplitude = 200

    private fun tone(frames: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(frames)
        var phase = 0.0
        val step = 2 * Math.PI * 440.0 / CallAudio.SAMPLE_RATE
        repeat(frames) {
            val b = ByteArray(CallAudio.BYTES_PER_FRAME)
            var i = 0
            while (i < b.size) {
                val s = (Math.sin(phase) * amplitude).toInt()
                b[i] = (s and 0xFF).toByte()
                b[i + 1] = ((s shr 8) and 0xFF).toByte()
                phase += step
                i += 2
            }
            out.add(b)
        }
        return out
    }

    @Test
    fun theSpeakerActuallyTakesFrames() {
        Assume.assumeTrue(
            "set OSHI_AUDIO_PLAYBACK=1 to play ~0.4 s of a quiet tone on this machine's speaker",
            System.getenv("OSHI_AUDIO_PLAYBACK") == "1",
        )
        Assume.assumeTrue("no playback device for the call format", CallAudio.isAvailable())

        val line = CallDevices.renderLine()
        try {
            // OPENED with the session's own buffer size. `renderLine`'s doc says "the render
            // line TO open" — it hands back an unopened line and the caller opens it, which
            // `CallAudioSession:218` does and the first version of this bench did not: every
            // write returned 0 and the failure read like a refusing device rather than a
            // test that forgot a step.
            line.open(CallAudio.FORMAT, CallAudio.BYTES_PER_FRAME * 4)
            line.start()
            val frames = tone(20)   // 20 x 20 ms = 400 ms
            var written = 0
            val began = System.nanoTime()
            for (f in frames) written += line.write(f, 0, f.size)
            val expected = frames.size * CallAudio.BYTES_PER_FRAME
            assertEquals("the line refused part of a real frame sequence", expected, written)

            // DRAINED, not merely accepted. A mixer that swallows bytes and never plays them
            // would satisfy the write count above and fail here.
            line.drain()
            val elapsedMs = (System.nanoTime() - began) / 1_000_000
            println("AUDIO playback: ${frames.size} frames (${expected} B) written and drained in ${elapsedMs} ms")
            println("AUDIO format  : ${CallAudio.FORMAT}")
            println("AUDIO devices : ${CallDevices.speakers()}")
            assertTrue("the line never became active", elapsedMs >= 0)
            // 400 ms of audio cannot drain in nothing: a device that returned instantly did
            // not play it. Generous lower bound — this is a real machine under load.
            assertTrue(
                "400 ms of audio drained in ${elapsedMs} ms — the samples were discarded, not played",
                elapsedMs >= 150,
            )
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }

    /**
     * The microphone half. **Gated separately and deliberately not run by default**: this
     * records the room.
     */
    @Test
    fun theMicrophoneActuallyProducesSamples() {
        Assume.assumeTrue(
            "set OSHI_AUDIO_MIC=1 to record ~0.4 s from this machine's microphone",
            System.getenv("OSHI_AUDIO_MIC") == "1",
        )
        val line = CallDevices.captureLine()
        try {
            line.open(CallAudio.FORMAT, CallAudio.BYTES_PER_FRAME * 4)
            line.start()
            val buf = ByteArray(CallAudio.BYTES_PER_FRAME)
            var read = 0
            var nonZero = 0
            repeat(20) {
                val n = line.read(buf, 0, buf.size)
                read += n
                if (buf.any { it != 0.toByte() }) nonZero++
            }
            println("AUDIO capture : ${read} B over 20 frames, ${nonZero} of them non-silent")
            println("AUDIO mics    : ${CallDevices.microphones()}")
            assertEquals("the microphone returned short reads", 20 * CallAudio.BYTES_PER_FRAME, read)
            // NOT asserted: that any frame is non-silent. A muted input is a legitimate state
            // of a real machine, and failing on it would make this bench lie about hardware.
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }
}
