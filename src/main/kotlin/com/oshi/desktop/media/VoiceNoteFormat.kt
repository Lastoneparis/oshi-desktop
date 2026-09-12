package com.oshi.desktop.media

import javax.sound.sampled.AudioFormat

/**
 * What a voice note IS on this network — established from the two shipped clients, not
 * chosen here.
 *
 * ============================================================ WHAT THE PHONES ACTUALLY SEND
 *
 * Both phones record **AAC-LC in an MPEG-4 container (`.m4a`), 44 100 Hz, MONO**:
 *
 *  - iOS `AudioRecorderManager.startRecording` (`OSHI/AudioRecorderManager.swift:82-92`)
 *    writes `recording_<uuid>.m4a` with `AVFormatIDKey: kAudioFormatMPEG4AAC`,
 *    `AVSampleRateKey: 44100`, `AVNumberOfChannelsKey: 1`, quality `.high`.
 *  - Android `AudioRecorderManager.startRecording`
 *    (`OSHI-Android/…/service/AudioRecorderManager.kt:132-149`) writes `voice_<uuid>.m4a`
 *    with `OutputFormat.MPEG_4`, `AudioEncoder.AAC`, `setAudioEncodingBitRate(128000)`,
 *    `setAudioSamplingRate(44100)` (`SAMPLE_RATE`, line 38).
 *
 * On the v2 wire the audio message announces itself as:
 *
 *  - `mime = "audio/m4a"` — iOS `MessageManager.v2Mime(for:)`
 *    (`OSHI/MessageManager+V2.swift:1833-1841`, the `.audio` arm).
 *  - `filename = "audio.m4a"` — iOS `MessageManager.v2FileName(for:)`
 *    (`OSHI/MessageManager+V2.swift:1843-1852`).
 *  - `mediaType = "audio"` — the FIELD, which both receivers read BEFORE the MIME
 *    (Android `MessageRepository.mediaTypeFromV2:4172-4188`, iOS
 *    `MediaManager.MediaType(rawValue:)`). See [com.oshi.desktop.store.MediaType.forMime].
 *
 * Neither receiver parses the MIME beyond its `audio/` prefix
 * (`MessageManager+V2.swift:1857` `hasPrefix("audio/")`; Android
 * `MessageRepository.kt:4184` `startsWith("audio/")`), so `audio/x-m4a` — which is what
 * `Files.probeContentType` returns for a `.m4a` on this desktop — classifies identically.
 *
 * ============================================================ NO DURATION, NO WAVEFORM ON THE WIRE
 *
 * This was worth checking and the answer is counter-intuitive. iOS has an
 * `AudioAttachment` envelope carrying `{data, duration, voiceEffect, waveformData}`
 * (`OSHI/AudioRecorderManager.swift:226-238`) — but the SEND path never uses it:
 * `ChatView.sendAudioMessage` (`OSHI/ChatView.swift:2226-2242`) passes
 * `attachment.data`, the **raw m4a bytes**, as `mediaAttachment`. The envelope is a
 * local-storage shape only. Its own reader admits this: `AudioMessageView`
 * (`OSHI/AudioMessageView.swift:165-178`) tries `JSONDecoder` first and falls back to
 * "raw bytes, duration 0" — which is the branch every inbound v2 note takes.
 *
 * Android confirms it from the other side: `VoiceNoteAudio`'s doc
 * (`OSHI-Android/…/ui/media/VoiceNoteAudio.kt:11-40`) states that an iPhone voice note
 * has an EMPTY body and no duration field, so Android reads the duration out of the
 * container itself (`probeDurationSeconds`, `MediaMetadataRetriever`, line 191-205) and
 * generates the waveform from the raw bytes (`generateWaveform`, line 60-63; the whole file is a port of iOS's).
 *
 * **Therefore: the bytes are the whole message.** A voice note needs no side-channel
 * duration and no side-channel waveform — but the container it ships in MUST carry a
 * readable duration, because that is where both receivers go looking for it.
 *
 * ============================================================ WHAT THE JVM CAN AND CANNOT DO
 *
 * `javax.sound.sampled` is a device and PCM-container API. It has **no AAC encoder and
 * no MPEG-4 muxer**, and it cannot even READ an m4a: `AudioSystem.getAudioInputStream`
 * on an afconvert-produced `.m4a` throws `UnsupportedAudioFileException` (measured on
 * this machine, JDK 25). Its writers are WAVE, AU and AIFF only.
 *
 * So this package records **WAV/PCM 16-bit 44 100 Hz mono** — the same rate and channel
 * count the phones use, in the only container the JDK can write — and then hands it to
 * [AudioTranscoder], which shells out to a platform AAC encoder to produce the real
 * `.m4a`. On macOS that is `/usr/bin/afconvert`, which is part of the OS and always
 * present; elsewhere it is `ffmpeg` if the user has one.
 *
 * **When no encoder is available the result is a WAV, and [VoiceNote.phoneCompatible] is
 * false.** That is not a silent downgrade: the caller is told, in a typed field and a
 * human sentence, that a phone may not play it. Do not paper over that.
 */
object VoiceNoteFormat {

    /** 44 100 Hz — iOS `AVSampleRateKey: 44100`, Android `SAMPLE_RATE = 44100`. */
    const val SAMPLE_RATE = 44_100

    /** 16-bit signed. The JDK's only universally supported capture width. */
    const val SAMPLE_BITS = 16

    /** Mono — iOS `AVNumberOfChannelsKey: 1`, Android's recorder is single-channel. */
    const val CHANNELS = 1

    /** Bytes per PCM frame: 2. */
    const val FRAME_BYTES = (SAMPLE_BITS / 8) * CHANNELS

    /** 88 200 B/s. Duration is derived from byte counts through this, never from a clock. */
    const val BYTES_PER_SECOND = SAMPLE_RATE * FRAME_BYTES

    /** Android `setAudioEncodingBitRate(128000)` (`AudioRecorderManager.kt:144`). */
    const val AAC_BITRATE = 128_000

    /**
     * **The recording bound: 5 minutes.**
     *
     * Taken from Android's `MAX_RECORDING_DURATION_MS = 300_000L`
     * (`OSHI-Android/…/service/AudioRecorderManager.kt:39`), which is the only cap either
     * phone ships — iOS's recorder has no limit at all. The stricter shipped number wins,
     * because a note this client records must be playable on both.
     *
     * At 88 200 B/s that is 26.5 MB of WAV, well under
     * `V2BlobClient.MAX_PLAINTEXT_BYTES` (200 MB), so the bound is about the recording
     * and not about the uplink.
     *
     * It is enforced in BYTES CAPTURED, not in elapsed wall-clock: a scheduling stall
     * that starves the line must shorten the recording, not silently pad it.
     */
    const val MAX_DURATION_MS = 300_000L

    /** [MAX_DURATION_MS] expressed as the byte ceiling the capture loop actually uses. */
    const val MAX_CAPTURE_BYTES = MAX_DURATION_MS * BYTES_PER_SECOND / 1000L

    /**
     * The floor below which a "recording" is a mis-tap, not a message: 200 ms.
     *
     * **This one is ours, not parity** — neither phone has a minimum. It exists so that a
     * press-and-release with no audio in it fails LOUDLY here instead of shipping a
     * quarter-kilobyte blob that draws an empty player on someone's phone.
     */
    const val MIN_DURATION_MS = 200L

    /**
     * The capture format. **Little-endian, deliberately, and this differs from the call
     * path on purpose.**
     *
     * `CallAudio.FORMAT` is big-endian because packet type `0x15` is defined that way by
     * the peer. Nothing here is defined by a peer: these samples go into a RIFF/WAVE
     * file, whose sample data is little-endian by specification, and then into an encoder.
     * Copying the call path's `bigEndian = true` would produce a WAV whose bytes are
     * swapped inside every sample — which does not fail, it just sounds like white noise.
     */
    val CAPTURE_FORMAT: AudioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        SAMPLE_RATE.toFloat(),
        SAMPLE_BITS,
        CHANNELS,
        FRAME_BYTES,
        SAMPLE_RATE.toFloat(),
        false,
    )

    /** 100 ms of audio: the read granularity of the capture loop. 8 820 bytes. */
    const val CAPTURE_CHUNK_BYTES = BYTES_PER_SECOND / 10

    /** Milliseconds of PCM in [bytes] at [CAPTURE_FORMAT]. Integer division, floor. */
    fun pcmDurationMs(bytes: Long): Long = bytes * 1000L / BYTES_PER_SECOND
}

/**
 * The two containers this client can put a voice note in, and the honest status of each.
 *
 * @property mime what to announce on the wire. Both receivers only look at the `audio/`
 *   prefix, but a receiver's file-manager view shows the string.
 * @property extension the file extension, which matters more than it looks: Android
 *   writes an inbound note to `voice_<id>.m4a` regardless
 *   (`AudioRecorderManager.kt:621`, `voice_<id>.m4a`) and iOS to `playback_<uuid>.m4a`
 *   (`AudioMessageView.swift:195`), and both then let the OS sniff the CONTENT. So the
 *   extension we send does not decide whether it plays — the bytes do.
 * @property matchesPhoneRecorder true when this is byte-for-byte the kind of file both
 *   phones produce themselves. **This is the only container for which "it will play on a
 *   phone" is a defensible claim**, and even then see the honesty note on
 *   [VoiceNote.phoneCompatible].
 */
enum class VoiceNoteContainer(
    val mime: String,
    val extension: String,
    val matchesPhoneRecorder: Boolean,
) {
    /** AAC-LC in MPEG-4. What iOS and Android both record. */
    M4A_AAC("audio/m4a", "m4a", true),

    /**
     * Linear PCM in RIFF/WAVE. What the JDK alone can produce.
     *
     * Both phones' players are content-sniffing OS decoders that DO handle PCM WAV
     * (`AVAudioPlayer` via AudioToolbox; Android `MediaPlayer` via `MediaExtractor`), and
     * on this machine `afinfo` correctly identified WAV bytes carrying a `.m4a` name — so
     * there is reason to expect it to work. **No phone has been shown to play one.** Ship
     * it only with the warning attached.
     */
    WAV_PCM("audio/wav", "wav", false);

    /** `audio.m4a` / `audio.wav` — the shape iOS's `v2FileName` uses for `.audio`. */
    val defaultFilename: String get() = "audio.$extension"
}
