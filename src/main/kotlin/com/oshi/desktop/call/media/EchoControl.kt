package com.oshi.desktop.call.media

import dev.onvoid.webrtc.media.audio.AudioProcessing
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig
import dev.onvoid.webrtc.media.audio.AudioProcessingStats
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig

/**
 * Echo cancellation, noise suppression and automatic gain for the call microphone —
 * WebRTC's audio-processing module (AEC3, NS, AGC2) through `webrtc-java`'s
 * `AudioProcessing`, and nothing else from WebRTC (no transport, no SDP, no codec).
 *
 * WHY (measured with a real iPhone, 2026-09-23): the person on the phone heard their own
 * voice back from the Mac's speaker and a constant hiss from the Mac's microphone. The
 * iPhone runs Apple's voice processing (`VoiceCallManager.swift:9645`,
 * `setVoiceProcessingEnabled(true)`); the desktop had nothing between `TargetDataLine`
 * and the wire.
 *
 * HOW:
 *  - [render] is fed the EXACT PCM handed to the speaker, in the order it is played —
 *    AEC3 subtracts what it predicts that signal became after the room.
 *  - [capture] runs each 20 ms microphone frame through AEC → NS → AGC2 before it is
 *    sealed.
 *  - APM works in 10 ms blocks: 48 kHz mono s16le = 480 samples = 960 bytes. A 20 ms
 *    capture frame is two blocks; render frames of any length (the iPhone App Store build
 *    sends ~32 ms) are re-blocked through a small accumulator.
 *
 * FALLBACK: [create] returns null when the native library cannot load (windows-aarch64 has
 * no published binary) or when disabled with `-Doshi.call.apm=false` / `OSHI_CALL_APM=false`. The session then
 * keeps the raw path exactly as before, and says so once in the log.
 */
class EchoControl private constructor(private val apm: AudioProcessing) : AutoCloseable {

    private val stream = AudioProcessingStreamConfig(CallAudio.SAMPLE_RATE.toInt(), CallAudio.CHANNELS)
    private val lock = Any()
    private val renderAcc = ByteArray(BLOCK_BYTES * 16)
    private var renderFill = 0
    private val scratchIn = ByteArray(BLOCK_BYTES)
    private val scratchOut = ByteArray(BLOCK_BYTES)
    @Volatile private var closed = false

    /** Blocks processed on each side — diagnostics and tests. */
    @Volatile var captureBlocks = 0L
        private set
    @Volatile var renderBlocks = 0L
        private set

    /** Feed the far-end reference: exactly what is about to be written to the speaker. */
    fun render(pcm: ByteArray) {
        if (closed || pcm.isEmpty()) return
        synchronized(lock) {
            var off = 0
            while (off < pcm.size) {
                val n = minOf(pcm.size - off, renderAcc.size - renderFill)
                System.arraycopy(pcm, off, renderAcc, renderFill, n)
                renderFill += n; off += n
                var consumed = 0
                while (renderFill - consumed >= BLOCK_BYTES) {
                    System.arraycopy(renderAcc, consumed, scratchIn, 0, BLOCK_BYTES)
                    runCatching { apm.processReverseStream(scratchIn, stream, stream, scratchOut) }
                    renderBlocks++
                    consumed += BLOCK_BYTES
                }
                if (consumed > 0) {
                    System.arraycopy(renderAcc, consumed, renderAcc, 0, renderFill - consumed)
                    renderFill -= consumed
                }
            }
        }
    }

    /**
     * Process one captured frame (a whole number of 10 ms blocks — the 20 ms call frame is
     * two). Returns the cleaned frame; on any native error the INPUT is returned, so a
     * glitch never silences the microphone.
     */
    fun capture(frame: ByteArray): ByteArray {
        if (closed || frame.size % BLOCK_BYTES != 0) return frame
        val out = ByteArray(frame.size)
        synchronized(lock) {
            var off = 0
            while (off < frame.size) {
                System.arraycopy(frame, off, scratchIn, 0, BLOCK_BYTES)
                val rc = runCatching { apm.processStream(scratchIn, stream, stream, scratchOut) }.getOrDefault(-1)
                if (rc != 0) return frame
                System.arraycopy(scratchOut, 0, out, off, BLOCK_BYTES)
                captureBlocks++
                off += BLOCK_BYTES
            }
        }
        return out
    }

    /** Speaker → microphone delay hint; AEC3 estimates the rest itself. */
    fun setDelayMs(ms: Int) {
        synchronized(lock) { runCatching { apm.setStreamDelayMs(ms) } }
    }

    /** AEC3's own measurements (ERL, ERLE, estimated delay). */
    fun stats(): AudioProcessingStats? = synchronized(lock) { runCatching { apm.statistics }.getOrNull() }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { apm.dispose() }
        }
    }

    companion object {
        /** 10 ms at 48 kHz mono s16le. */
        const val BLOCK_BYTES = 960

        /**
         * Output buffer (4 frames) + input buffer (4 frames) is the device latency this
         * session configures; AEC3 refines it from there.
         */
        const val DEFAULT_DELAY_MS = 100

        /** See the AGC2 comment in [create]. */
        const val MAX_OUTPUT_NOISE_DBFS = -75f
        const val MAX_AGC_GAIN_DB = 12f

        @Volatile private var reported = false

        /** ON unless `-Doshi.call.apm=false` or the environment has `OSHI_CALL_APM=false`. */
        val enabled: Boolean get() =
            (System.getProperty("oshi.call.apm") ?: System.getenv("OSHI_CALL_APM"))?.lowercase() != "false"

        /**
         * A configured processor, or null when disabled or when the native library will not
         * load here — logged once per process, never thrown.
         */
        fun create(log: (String) -> Unit = {}, agc: Boolean = true, nsLevel: AudioProcessingConfig.NoiseSuppression.Level = AudioProcessingConfig.NoiseSuppression.Level.HIGH): EchoControl? {
            if (!enabled) return null
            return try {
                val apm = AudioProcessing()
                val cfg = AudioProcessingConfig()
                cfg.echoCanceller.enabled = true
                cfg.echoCanceller.enforceHighPassFiltering = true
                cfg.highPassFilter.enabled = true
                cfg.noiseSuppression.enabled = true
                cfg.noiseSuppression.level = nsLevel
                cfg.gainControllerDigital.enabled = agc
                cfg.gainControllerDigital.adaptiveDigital.enabled = agc
                // AGC2's default lets it raise a noise-only signal up to -50 dBFS, which
                // lifted the suppressed hiss straight back (measured: NS alone -18 dB, NS+AGC
                // -3 dB). Cap the noise it may produce and the gain it may add.
                cfg.gainControllerDigital.adaptiveDigital.maxOutputNoiseLevelDbfs = MAX_OUTPUT_NOISE_DBFS
                cfg.gainControllerDigital.adaptiveDigital.maxGainDb = MAX_AGC_GAIN_DB
                apm.applyConfig(cfg)
                apm.setStreamDelayMs(DEFAULT_DELAY_MS)
                if (!reported) { reported = true; log("call: echo cancellation + noise suppression ON (WebRTC APM)") }
                EchoControl(apm)
            } catch (t: Throwable) {
                if (!reported) {
                    reported = true
                    log("call: echo cancellation unavailable here (${t.javaClass.simpleName}: ${t.message}) — raw microphone path")
                }
                null
            }
        }
    }
}
