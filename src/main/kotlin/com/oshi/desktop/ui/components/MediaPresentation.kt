package com.oshi.desktop.ui.components

import com.oshi.desktop.store.MediaType
import java.io.File

/**
 * What a media row is allowed to DRAW — decided in plain Kotlin, never in a composable.
 *
 * ============================================================ WHY THIS IS NOT IN THE BUBBLE
 *
 * "Render the image" and "do not render the image" are the two most consequential branches
 * in this window, and one of them is a view-once message. A branch that only exists inside
 * a `@Composable` can only be watched failing on a machine with a screen, which is not where
 * this project's Windows and Linux evidence comes from (see `ChatShellModel`'s class note).
 * So the decision is a pure function with an injectable filesystem probe, `MessageBubble`
 * merely obeys it, and `MediaPresentationTest` holds it to account headlessly.
 *
 * ============================================================ IT PARSES, IT DOES NOT RE-READ
 *
 * `ChatShellModel` already read the store and produced `MessageRow.attachment` in a
 * documented shape — `"[<mediaType.wire>] <mediaRef>"`, built at `ChatShellModel.row`. This
 * parses THAT string rather than opening `MessageStore` a second time, so the window cannot
 * end up disagreeing with the model about what a row carries. The one fact the model does
 * not carry is `Message.isViewOnce`; where that comes from, and what it costs, is written on
 * [ViewOnceFacts].
 *
 * ============================================================ IT FAILS CLOSED, ALWAYS
 *
 * [inlineImage] is true only when every one of these holds:
 *
 *   1. the attachment string parsed into a type and a path at all;
 *   2. the type is [MediaType.IMAGE] — a `document` whose name ends `.png` does NOT inline,
 *      because a receiver reads the FIELD and not the extension (PARITY.md row 0.12, and the
 *      live-phone defect recorded there is exactly this confusion in the other direction);
 *   3. the file is present on THIS disk — the bytes are decrypted to a path by row 0.15 and
 *      a path that no longer resolves must draw a stated absence, not a broken-image box;
 *   4. the file is within [MAX_INLINE_BYTES]. The shipped `MediaChatBubble` refuses payloads
 *      at 50 MB and decodes at `maxDimension: 800`; this window has no downsampling decoder,
 *      so a full-resolution decode is what an inline image costs and the cap is lower;
 *   5. the row is not a sealed view-once.
 *
 * Everything that is not an inline image is a named file card. A card is a true statement
 * about a file on disk; a thumbnail this window could not actually produce would be a
 * promise, which is the failure mode `DesktopLimits` exists to prevent.
 */
data class MediaPresentation(
    /** The declared type. [MediaType.UNKNOWN] when the reference could not be parsed. */
    val kind: MediaType,
    /** Absolute path as the model reported it, or the raw reference when unparseable. */
    val path: String,
    /** Last path segment, for the card's title. Never empty — falls back to [path]. */
    val fileName: String,
    /** True only when all five conditions in the class note hold. */
    val inlineImage: Boolean,
    /** Sealed view-once: the bytes exist and this window is deliberately not drawing them. */
    val sealedViewOnce: Boolean,
    /**
     * The row was marked view-once by its SENDER, whether or not it has been revealed.
     *
     * [sealedViewOnce] answers "hide it now?" and goes false the moment the user reveals it;
     * this one never does. `MediaViewer` needs the difference: a revealed view-once row is
     * viewable (the user asked) and still must not get a "Show in folder" button, because
     * handing a file manager to a view-once file is a different act from looking at it once.
     */
    val viewOnceRow: Boolean,
    /** Short, user-facing reason the image is not inline. Null when there is nothing to say. */
    val note: String?,
    /** The reference did not have the shape `ChatShellModel.row` produces; it is shown raw. */
    val unparseable: Boolean,
) {
    companion object {

        /**
         * The shape `ChatShellModel.row` writes: `"[" + wire + "] " + mediaRef`.
         *
         * Anchored at both ends and non-greedy on the type so a path containing `] ` cannot
         * be mistaken for the delimiter. A reference that does not match is not guessed at.
         */
        private val REF = Regex("""^\[([^\]]+)] (.+)$""", RegexOption.DOT_MATCHES_ALL)

        const val MISSING_FILE_NOTE =
            "The decrypted file is not at that path any more. Nothing was lost on the wire — " +
                "the bytes were written to this disk when the message arrived and something " +
                "has removed them since."

        const val SEALED_NOTE =
            "View once, as the sender marked it. Reveal is a LOCAL action: this client does " +
                "not destroy the file afterwards and the sender is never told you opened it."

        /**
         * The ceiling on a decoded-in-full inline image, in bytes.
         *
         * `org.jetbrains.skia.Image.makeFromEncoded` decodes at native resolution — there is
         * no `maxDimension` here — so a 40-megapixel JPEG is ~160 MB of ARGB in the heap of a
         * process whose default max heap is a fraction of RAM. 16 MiB of ENCODED bytes is
         * generous for a photograph and bounded enough that a hostile or careless attachment
         * cannot take the window down. Over it, the row is a file card that says why.
         */
        const val MAX_INLINE_BYTES = 16L * 1024 * 1024

        const val OVERSIZE_NOTE =
            "This image is too large for this window to decode in one piece (over 16 MiB). " +
                "The file is on disk and intact; open it in an image viewer."

        const val UNPARSEABLE_NOTE =
            "This attachment reference is not in the form this window knows how to read, so " +
                "it is shown verbatim rather than interpreted."

        /**
         * @param attachment `MessageRow.attachment` — null or blank means "no media".
         * @param viewOnce whether the underlying `Message.isViewOnce` was set.
         * @param revealed whether the user has deliberately opened this sealed row.
         * @param probe returns the file's size in bytes, or null when it is not there.
         *   Injectable so the guard runs with no filesystem in a test.
         */
        fun of(
            attachment: String?,
            viewOnce: Boolean = false,
            revealed: Boolean = false,
            // __LOCAL_DATA_AT_REST_2026_09_22__ the PLAINTEXT size, from the header of a
            // sealed attachment, so the inline cap is the same cap it was before sealing.
            probe: (String) -> Long? = { com.oshi.desktop.store.MediaVault.lengthOf(File(it)) },
        ): MediaPresentation? {
            val raw = attachment?.trim().orEmpty()
            if (raw.isEmpty()) return null

            val m = REF.matchEntire(raw)
                ?: return MediaPresentation(
                    kind = MediaType.UNKNOWN,
                    path = raw,
                    fileName = raw,
                    inlineImage = false,
                    sealedViewOnce = viewOnce && !revealed,
                    viewOnceRow = viewOnce,
                    note = UNPARSEABLE_NOTE,
                    unparseable = true,
                )

            val kind = MediaType.fromWire(m.groupValues[1])
            val path = m.groupValues[2]
            val size = probe(path)
            val sealed = viewOnce && !revealed
            val oversize = size != null && size > MAX_INLINE_BYTES

            return MediaPresentation(
                kind = kind,
                path = path,
                fileName = nameOf(path),
                inlineImage = kind == MediaType.IMAGE && size != null && !oversize && !sealed,
                sealedViewOnce = sealed,
                viewOnceRow = viewOnce,
                note = when {
                    sealed -> SEALED_NOTE
                    size == null -> MISSING_FILE_NOTE
                    oversize && kind == MediaType.IMAGE -> OVERSIZE_NOTE
                    else -> null
                },
                unparseable = false,
            )
        }

        /** Last segment under either separator — a Windows path can reach a Unix build's log. */
        fun nameOf(path: String): String =
            path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

        /** The word a file card puts above the filename. Never "Photo" for a non-image. */
        fun cardLabel(kind: MediaType): String = when (kind) {
            MediaType.IMAGE -> "Image"
            MediaType.VIDEO -> "Video — no player in this window"
            MediaType.AUDIO -> "Voice note — click to play"
            MediaType.DOCUMENT -> "File"
            MediaType.CONTACT -> "Contact card"
            MediaType.LOCATION -> "Location"
            MediaType.UNKNOWN -> "Attachment"
        }
    }
}

/**
 * Where `Message.isViewOnce` comes from, and the honest cost of getting it here.
 *
 * `ChatShellModel.MessageRow` does not carry it. That model is not this agent's file to
 * change, and the alternative — inventing the flag, or defaulting every media row to "not
 * view once" — would mean this window draws a picture its sender asked to be shown once.
 * That is precisely the class of lie the whole project is organised against, so the window
 * asks the same store the model read.
 *
 * **What it costs.** `MessageStore.messages(cid)` is served from `logFor(cid)`, an in-memory
 * cache, so this is a map build over an already-loaded list — NOT the directory scan plus N
 * file opens that `ChatShellModel.build()` performs. It is computed once per (conversation,
 * message-count) change and never per frame and never per keystroke.
 *
 * **What it still is:** a second reader of the store from the drawing layer, which the
 * window otherwise does not have. If `MessageRow` ever grows an `isViewOnce`, delete this
 * and read the field — the bubble only ever sees [ViewOnceFacts].
 */
fun interface ViewOnceFacts {
    /** True iff that message id is a view-once row. Unknown ids are NOT view-once. */
    fun isViewOnce(messageId: String): Boolean

    companion object {
        /** No facts available — every row renders as an ordinary attachment. */
        val NONE = ViewOnceFacts { false }

        /** Build a lookup over a known id set. */
        fun of(ids: Set<String>) = ViewOnceFacts { it in ids }
    }
}
