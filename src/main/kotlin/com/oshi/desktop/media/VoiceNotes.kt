package com.oshi.desktop.media

import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.MediaType
import java.io.File
import java.util.UUID

/**
 * **The seam.** Everything a UI or an [com.oshi.desktop.app.OshiClient] caller needs from
 * this package, on one object, with no `javax.sound` type in the signature.
 *
 * ============================================================ HOW TO WIRE IT
 *
 * ```kotlin
 * val voice = VoiceNotes()                       // once, at startup
 *
 * // does this machine have a microphone, and will it produce a phone-playable file?
 * val cap = voice.capability()
 * if (!cap.canRecord) show(cap.recordingBlocker!!.message)
 * if (!cap.producesPhoneCompatibleAudio) warn(cap.formatWarning!!)
 *
 * // record
 * when (val s = voice.startRecording()) {
 *     is RecordingStart.Failure -> show(s.reason.message)
 *     RecordingStart.Started -> { /* draw the timer */ }
 * }
 *
 * // send
 * when (val r = voice.stopRecording()) {
 *     is VoiceNoteResult.Failure -> show(r.reason.message)
 *     is VoiceNoteResult.Ready -> {
 *         val note = r.note
 *         if (!note.phoneCompatible) warn(note.warning!!)   // do NOT swallow this
 *         client.sendFile(peer, note.file, note.mediaType)  // mediaType is MediaType.AUDIO
 *         voice.discard(note)                               // after the send returns
 *     }
 * }
 *
 * // inbound: the file OshiClient already decrypted to Message.mediaRef
 * val ms = voice.durationMs(File(message.mediaRef!!))       // header read, no decode
 * voice.play(File(message.mediaRef!!)) { end -> repaint(end) }
 * ```
 *
 * ============================================================ THE ONE THING NOT TO SWALLOW
 *
 * [VoiceNote.phoneCompatible] is false whenever no AAC encoder was usable and the note
 * shipped as WAV. On macOS it is always true (`/usr/bin/afconvert` is part of the OS); on
 * a Linux or Windows desktop without `ffmpeg` it is false. A UI that drops
 * [VoiceNote.warning] on the floor is shipping a file to a phone that may draw a
 * "Download" row instead of a player, with nothing anywhere saying why.
 *
 * [Capability.producesPhoneCompatibleAudio] answers the same question BEFORE the user
 * records, which is the better place to say it.
 */
class VoiceNotes(
    private val workDir: File = DesktopPaths.file("media"),
    private val devices: AudioDevices = AudioDevices.JavaSound,
    private val transcoder: AudioTranscoder = SystemAudioTranscoder(workDir = workDir),
    private val recorder: AudioRecorder = AudioRecorder(devices = devices, workDir = workDir),
    private val player: AudioPlayer = AudioPlayer(devices = devices, transcoder = transcoder, workDir = workDir),
) {

    // ------------------------------------------------------------------ capability

    /**
     * What this machine can do, asked without opening a device and without recording.
     *
     * [producesPhoneCompatibleAudio] costs one subprocess the first time (the transcoder
     * probe actually RUNS an encoder on a 100 ms tone — see [AudioTranscoder]'s doc for
     * why `which` is not enough) and is cached after that.
     */
    data class Capability(
        val canRecord: Boolean,
        val inputDevices: List<String>,
        val producesPhoneCompatibleAudio: Boolean,
        val encoder: String?,
        val container: VoiceNoteContainer,
    ) {
        val recordingBlocker: RecordingFailure?
            get() = if (canRecord) null else RecordingFailure.NoInputDevice

        /** Null when the note will be a real m4a. A sentence to show otherwise. */
        val formatWarning: String?
            get() = if (producesPhoneCompatibleAudio) {
                null
            } else {
                "No AAC encoder on this machine (no afconvert, no working ffmpeg). Voice " +
                    "notes will be sent as WAV/PCM, which is NOT the m4a both phones " +
                    "record, and a phone may not play them."
            }
    }

    fun capability(): Capability {
        val encoder = transcoder.probe()
        return Capability(
            canRecord = recorder.isAvailable(),
            inputDevices = recorder.deviceNames(),
            producesPhoneCompatibleAudio = encoder != null,
            encoder = encoder,
            container = if (encoder != null) VoiceNoteContainer.M4A_AAC else VoiceNoteContainer.WAV_PCM,
        )
    }

    // ------------------------------------------------------------------- recording

    val isRecording: Boolean get() = recorder.isRecording

    fun startRecording(): RecordingStart = recorder.start()

    /**
     * Stop, and produce the file to send.
     *
     * The WAV comes off the recorder; the encode to m4a is attempted here. **A failed
     * encode is not a failed recording** — the WAV is returned with
     * [VoiceNote.phoneCompatible] false and a warning, rather than throwing away audio the
     * user already spoke.
     */
    fun stopRecording(): VoiceNoteResult =
        when (val stopped = recorder.stop()) {
            is RecordingStop.Failure -> VoiceNoteResult.Failure(stopped.reason)
            is RecordingStop.Captured -> VoiceNoteResult.Ready(finish(stopped.recording))
        }

    fun cancelRecording() = recorder.cancel()

    private fun finish(recording: Recording): VoiceNote {
        val m4a = File(workDir, "voice-${UUID.randomUUID()}.${VoiceNoteContainer.M4A_AAC.extension}")
        val encoded = transcoder.toM4a(recording.file, m4a)
        return if (encoded is TranscodeResult.Produced) {
            recording.file.delete()
            VoiceNote(
                file = encoded.file,
                container = VoiceNoteContainer.M4A_AAC,
                // The container's own duration, not the PCM's: an AAC encode adds priming
                // and padding, and the number a phone will show for this file is the one
                // in its mvhd. Falls back to the PCM duration if the header is unreadable,
                // which would itself be a reason to distrust the file.
                durationMs = AudioDurationProbe.of(encoded.file)?.durationMs ?: recording.durationMs,
                truncated = recording.truncated,
                encoder = encoded.tool,
                encodeFailure = null,
            )
        } else {
            m4a.delete()
            VoiceNote(
                file = recording.file,
                container = VoiceNoteContainer.WAV_PCM,
                durationMs = recording.durationMs,
                truncated = recording.truncated,
                encoder = null,
                encodeFailure = when (encoded) {
                    is TranscodeResult.Unavailable -> encoded.message
                    is TranscodeResult.Failed -> "${encoded.tool}: ${encoded.reason}"
                    is TranscodeResult.Produced -> null // unreachable
                },
            )
        }
    }

    /** Delete a note's file once it has been sent (or abandoned). */
    fun discard(note: VoiceNote) { runCatching { note.file.delete() } }

    // -------------------------------------------------------------------- duration

    /**
     * Length of an audio file in milliseconds, from its header. Does not decode, does not
     * read the samples, does not load the file into RAM. Null when the container is not
     * one of the two this client understands, or its header does not state a duration.
     */
    fun durationMs(file: File): Long? = AudioDurationProbe.of(file)?.durationMs

    /** The full probe: container, duration, and rate/channels where the header says. */
    fun probe(file: File): AudioDurationProbe.Info? = AudioDurationProbe.of(file)

    /** `0:07`, the shape both phones print (`AudioMessageView.formatTime`). */
    fun formatDuration(millis: Long): String {
        val total = (millis / 1000L).coerceAtLeast(0L)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    // -------------------------------------------------------------------- playback

    val isPlaying: Boolean get() = player.isPlaying

    fun play(file: File, onEnd: (PlaybackEnd) -> Unit = {}): PlaybackStart = player.play(file, onEnd)

    fun stopPlayback() = player.stop()
}

/**
 * A recorded voice note, ready to hand to
 * [com.oshi.desktop.app.OshiClient.sendFile].
 *
 * @property mediaType **pass this explicitly** to `sendFile(peer, file, mediaType)`.
 *   Leaving it to be derived works on a desktop with a MIME database
 *   (`Files.probeContentType` returns `audio/x-m4a` for an `.m4a` and `audio/vnd.wave`
 *   for a `.wav` on this machine, both of which classify as AUDIO), but `OshiClient`'s
 *   fallback extension table has no `wav` entry — so on a machine where
 *   `probeContentType` returns null, a WAV note would be announced as
 *   `application/octet-stream` and arrive on a phone as a DOCUMENT: a file row with a
 *   Download button instead of a player. That is exactly the defect PARITY.md row 0.12
 *   records as having shipped once already.
 * @property phoneCompatible true only for [VoiceNoteContainer.M4A_AAC], the container
 *   both phones record. False means the note is WAV and a phone MAY not play it.
 * @property durationMs what a phone will read out of this file's own header.
 * @property truncated the 5-minute bound cut it short.
 * @property encodeFailure why there is no m4a, when there is not.
 */
data class VoiceNote(
    val file: File,
    val container: VoiceNoteContainer,
    val durationMs: Long,
    val truncated: Boolean,
    val encoder: String?,
    val encodeFailure: String?,
) {
    val mime: String get() = container.mime

    val suggestedFilename: String get() = container.defaultFilename

    val mediaType: MediaType get() = MediaType.AUDIO

    val phoneCompatible: Boolean get() = container.matchesPhoneRecorder

    /**
     * The sentence to put in front of the user before sending. Null when there is nothing
     * to warn about.
     */
    val warning: String?
        get() = if (phoneCompatible) {
            null
        } else {
            "This voice note is WAV/PCM, not the m4a/AAC both phones record — a phone may " +
                "not play it." + (encodeFailure?.let { " ($it)" } ?: "")
        }
}

sealed interface VoiceNoteResult {
    data class Ready(val note: VoiceNote) : VoiceNoteResult
    data class Failure(val reason: RecordingFailure) : VoiceNoteResult
}
