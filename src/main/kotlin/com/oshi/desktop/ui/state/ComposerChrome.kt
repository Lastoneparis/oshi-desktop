package com.oshi.desktop.ui.state

/**
 * __VIDEO_NOTE_2026_09_24__ What the composer row shows around the text field — decided in
 * plain Kotlin so it can be held by a headless test (the composables are not).
 *
 * The owner's rule, shared with iOS and Android: **the moment the user starts typing, the
 * field widens (animated) and the capture buttons — GIF, voice note, video note — step aside;
 * they come back when the text is cleared.** The paperclip stays: it is how a typed message
 * gets a file attached, so hiding it while typing would take away the one action that goes
 * WITH text. A recording in progress keeps its own button visible whatever the draft says,
 * because hiding the only Stop control of a running microphone is a trap.
 */
data class ComposerChrome(
    /** GIF / voice / video-note buttons are on screen. */
    val captureButtonsVisible: Boolean,
    /** The text field has taken the freed width (drives the widen animation). */
    val fieldExpanded: Boolean,
) {
    companion object {
        fun of(draft: String, voiceRecording: Boolean = false): ComposerChrome {
            val typing = draft.isNotEmpty()
            return ComposerChrome(
                captureButtonsVisible = !typing || voiceRecording,
                fieldExpanded = typing && !voiceRecording,
            )
        }
    }
}

/** The recorder's progress ring: elapsed / cap, clamped. Pure for the test. */
object VideoNoteRing {
    fun fraction(elapsedMs: Long, capMs: Long = com.oshi.desktop.media.VideoNoteFormat.MAX_DURATION_MS): Float =
        if (capMs <= 0) 0f else (elapsedMs.toFloat() / capMs).coerceIn(0f, 1f)
}
