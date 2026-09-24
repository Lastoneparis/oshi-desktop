package com.oshi.desktop.media

import com.oshi.messenger.network.v2.V2FileKeyMessage
import com.oshi.messenger.service.media.VideoNote
import org.json.JSONObject

/**
 * __VIDEO_NOTE_2026_09_24__ The desktop's seam onto the round-video-note wire —
 * `docs/VIDEO_NOTE_SPEC.md`, shared by iOS, Android and this client.
 *
 * A video note IS an ordinary video attachment (`mediaType:"video"`, `mime:"video/mp4"`) plus
 * `"videoNote":true` and `"durationMs":<int>` at the top level of the `v2file` key-message
 * JSON (§2.A) and, in a group, ALSO at the top level of the embedded GroupMessage (§2.B).
 *
 * NOTHING HERE RE-DECIDES A RULE. The key names, the true-or-absent emit rule, the tolerant
 * duration read (§1 rule 2), the "a flag on a photo is ignored" rule (§1 rule 1) and the
 * group merge (§1 rule 5) are Android's `service/media/VideoNote.kt`, compiled into this
 * client from the Android tree exactly like the crypto files (build.gradle.kts), and the two
 * fields ride on the SHARED `V2FileKeyMessage`. This object only adapts them to the
 * desktop's types — a copy of a receiver rule that drifted would never fail to compile.
 */
object VideoNoteWire {
    const val KEY_VIDEO_NOTE = VideoNote.KEY_FLAG
    const val KEY_DURATION_MS = VideoNote.KEY_DURATION

    /** Spec §3: the recorder's hard cap, and the ceiling a received duration is clamped to. */
    const val MAX_DURATION_MS = VideoNote.MAX_DURATION_MS

    const val MIME = "video/mp4"

    /** What a note carries. [durationMs] null = unknown (take it from the decoded media). */
    data class Meta(val durationMs: Long?) {
        init { require(durationMs == null || durationMs in 0..MAX_DURATION_MS) { "durationMs out of range: $durationMs" } }

        /** The wire's integer, or null when unknown / zero (spec: omit when not known). */
        val wireDurationMs: Int? get() = durationMs?.let { VideoNote.wireDuration(it) }
    }

    /** §1 rules 1–2 on one JSON object (the GroupMessage), given the media type that applies to it. */
    fun read(o: JSONObject, mediaType: String?, mime: String?): Meta? {
        val flag = VideoNote.flagFrom(o)
        if (!VideoNote.isVideoNote(flag, mediaType, mime)) return null
        return Meta(VideoNote.durationFrom(o)?.toLong())
    }

    /**
     * The note carried by a parsed key message, or null. Groups (rule 5): a note when EITHER
     * the key message or its embedded GroupMessage says so; the duration comes from the key
     * message first. The video check is applied to each location with its own media type.
     */
    fun fromKey(key: V2FileKeyMessage): Meta? {
        val top = VideoNote.isVideoNote(key.videoNote, key.mediaType, key.mime)
        val inner = key.groupMessage?.let { b64 ->
            runCatching { JSONObject(String(java.util.Base64.getDecoder().decode(b64.trim()), Charsets.UTF_8)) }.getOrNull()
        }
        val innerNote = inner?.let {
            VideoNote.isVideoNote(VideoNote.flagFrom(it), (it.opt("mediaType") as? String) ?: key.mediaType, key.mime)
        } == true
        val (isNote, duration) = VideoNote.merge(
            fileKeyFlag = top, fileKeyDurationMs = if (top) key.durationMs else null,
            innerFlag = innerNote, innerDurationMs = if (innerNote) VideoNote.durationFrom(inner) else null,
        )
        if (!isNote) return null
        // The shared parser already clamped a float/over-range value; clamp again so a value
        // built in code (not parsed) cannot carry more than the contract either.
        return Meta(duration?.toLong()?.coerceIn(0L, MAX_DURATION_MS))
    }

    /** A raw ratchet plaintext → the note, or null for anything else (never a throw). */
    fun fromFileKeyJson(text: String): Meta? = V2FileKeyMessage.parse(text)?.let(::fromKey)

    /** Spec §3: `videonote_<unix-millis>.mp4`. Informative only; receivers never rely on it. */
    fun filename(nowMs: Long = System.currentTimeMillis()): String = VideoNote.fileName(nowMs)
}
