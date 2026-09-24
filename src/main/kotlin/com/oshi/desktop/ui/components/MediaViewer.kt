package com.oshi.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.media.AudioPlayer
import com.oshi.desktop.media.PlaybackEnd
import com.oshi.desktop.media.PlaybackStart
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.ui.OshiTheme
import java.awt.EventQueue
import java.io.File
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage

/**
 * The viewer: a decrypted attachment, opened at full size, or a stated refusal.
 *
 * ============================================================ WHAT IT CLOSES
 *
 * PARITY.md rows 0.15 and 1.4. The blob path has been decrypting inbound media straight to
 * `OshiClient.mediaDir` since row 0.15 went green — since 2026-09-22 SEALED at rest
 * (`MediaVault`, "OSHIMED1"), so every read below goes through the vault and decrypts in
 * memory — and until this file existed, nothing in the window opened one.
 * `MessageBubble` draws a 250pt thumbnail inside the bubble; a thumbnail is not a viewer.
 * This is the full-size look, and it is the ONLY place in the window that reads media bytes
 * for a size the user chose rather than a size the layout chose.
 *
 * ============================================================ EVERY BYTE IS HOSTILE
 *
 * A messenger opens files a stranger sent. Nothing below trusts the extension, the
 * `mediaType` field, the file's own header, or its length; each is checked and each is
 * allowed to say no, in this order, because each step bounds the cost of the next:
 *
 *   1. **It is a file and it is not empty.** A directory, a dangling path or a zero-byte
 *      write is refused before anything is read.
 *   2. **It is within [MediaViewerLimits.MAX_FILE_BYTES]** — 64 MiB. This bounds the
 *      `ByteArray` the JVM is about to allocate, and it is the ONLY thing standing between
 *      this window and a 4 GB "photo": `File.readBytes` is not incremental and will happily
 *      try. Deliberately four times the inline cap (`MediaPresentation.MAX_INLINE_BYTES`),
 *      because opening the viewer is a deliberate act on one file while the inline cap
 *      applies to every row that scrolls past.
 *   3. **Its first bytes say what it is** ([ByteSignature]). A `.jpg` that is really an MP4
 *      is named as an MP4 and refused, rather than handed to a decoder to find out.
 *   4. **Its header parses and its pixel count is within [MediaViewerLimits.MAX_PIXELS]** —
 *      32 megapixels. `org.jetbrains.skia.Codec` reads the dimensions WITHOUT decoding, so
 *      a 50000×50000 PNG (about 45 KB compressed, 10 GB decoded — the classic decompression
 *      bomb) is refused for 45 KB of work and never allocates a pixel.
 *   5. **Only then** are the pixels produced, inside a `try`.
 *
 * The order is the guarantee. Reversing 4 and 5 would mean the ceiling is enforced by the
 * allocator, i.e. by an `OutOfMemoryError`, which is not a refusal — it is a crash with a
 * stack trace.
 *
 * ============================================================ IT NEVER PLAYS ANYTHING
 *
 * Video playback is not implemented. A video opens [NoPlayerPanel] — the file, where it is,
 * and the sentence "this window does not play it" — because a user who can see the file and
 * read why is better served than one staring at a dead triangle. Voice notes use the existing
 * [AudioPlayer] backend: it opens JDK-readable audio directly and asks the configured
 * transcoder for a temporary WAV when a phone-recorded m4a needs it.
 *
 * `AudioPlayer` has unit coverage with a fake render device, but no test has opened a real
 * speaker. This panel reports the backend's named failures instead of promising a sound that
 * this machine cannot produce. Nothing in this file decodes a video file, and this viewer is
 * not the place to start: it would be a codec, a clock and a device, none of which a still
 * viewer has any business owning.
 *
 * ============================================================ VIEW-ONCE
 *
 * [MediaViewerTarget.of] returns null for a sealed view-once row, so there is no way to
 * reach these pixels around `ThreadPane`'s `revealed` set: a viewer that opened a sealed row
 * would undo the one privacy promise the media path makes. A row the user HAS revealed opens
 * normally and carries [MediaViewerTarget.viewOnce], which suppresses the reveal-in-folder
 * control — handing a file manager to a view-once file is a different act from looking at it
 * once, and this window does not do the first on the strength of the second.
 *
 * ============================================================ NOT COVERED BY ANY TEST
 *
 * The composables. `MediaViewerLimits`, [ByteSignature], [MediaViewerLoader] and
 * [FolderReveal.choose] are pure and are held by `MediaViewerTest`; `MediaViewerRenderTest`
 * rasterises the overlay headlessly and asserts the decoded picture's own colour reaches the
 * output, which proves it draws but not that it is beautiful. What NOTHING covers: the
 * pointer, the Escape key (there is none — see [MediaViewerOverlay]), and
 * `java.awt.Desktop.browseFileDirectory`, which cannot be exercised without a desktop
 * session and is therefore wrapped rather than trusted.
 */
object MediaViewerLimits {

    /**
     * The most encoded bytes this window will read into the heap for one attachment.
     *
     * 64 MiB. A `ByteArray` of that size is survivable on any machine that can run a Compose
     * desktop window, and it comfortably holds any photograph a phone produces (a 48 MP HEIC
     * is 3–8 MB; a full-quality 24 MP JPEG is under 15 MB). Above it the file is named and
     * left alone — the bytes are intact on disk and the user's own image viewer, which can
     * memory-map and downsample, is the right tool.
     */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /**
     * The most PIXELS this window will decode: 32 megapixels, about 128 MiB at 4 bytes each.
     *
     * This is the ceiling that matters. Encoded size says almost nothing about decoded size —
     * a few kilobytes of PNG can claim 50000×50000 — so the byte cap above cannot bound
     * memory on its own, and the dimensions are read from the header before any pixel exists.
     *
     * 32 MP is above every mainstream camera's default output (12–24 MP) and above what this
     * window can show: the overlay is at most ~1180×780dp, so nothing beyond about 3 MP is
     * even visible. The ceiling is therefore generous by a factor of ten and still bounded.
     */
    const val MAX_PIXELS = 32_000_000L

    const val NOT_A_FILE =
        "There is no file at that path any more. The bytes were decrypted to this disk when " +
            "the message arrived; something has removed or moved them since. Nothing was lost " +
            "on the wire and the sender does not need to resend for this window's sake."

    const val EMPTY_FILE =
        "The file on disk is zero bytes. That is a failed or interrupted write here, not a " +
            "damaged message — the download deletes a partial file, so an empty one means the " +
            "path was created by something else."

    /** Called with the file's size; the number is in the message because a cap without one is folklore. */
    fun oversizeNote(bytes: Long): String =
        "This file is ${humanBytes(bytes)}, over this window's ${humanBytes(MAX_FILE_BYTES)} " +
            "ceiling for reading an image into memory in one piece. The file is on disk and " +
            "intact; open it in an image viewer, which can decode it without holding all of it."

    fun tooManyPixelsNote(w: Int, h: Int): String =
        "This image declares ${w}×$h — ${decimal(w.toLong() * h / 1_000_000.0)} " +
            "megapixels, over this window's ${MAX_PIXELS / 1_000_000} MP decoding ceiling. " +
            "Nothing was decoded: the dimensions come from the file's header. A small file " +
            "that claims enormous dimensions is the shape of a deliberate attack as well as " +
            "the shape of a panorama, and this window declines both the same way."

    /**
     * The header did not parse at all — `Codec` could not even say how big the picture is.
     *
     * Kept separate from [UNDECODABLE] because they are different failures and a test must be
     * able to tell them apart: this one means the file was refused BEFORE any pixel budget
     * was spent, which is the property the ordering in the class note promises.
     */
    const val NO_IMAGE_HEADER =
        "This file does not begin with an image header this window can read — truncated at " +
            "the very start, or not a picture at all whatever it is called. Nothing was " +
            "decoded, because there were no dimensions to decode into."

    const val UNDECODABLE =
        "The bytes are on disk but this window's image library could not turn them into a " +
            "picture. Truncated, or a format this build of skia does not carry. That is a " +
            "statement about THIS decoder and not about the file: another OSHI client, and " +
            "your own image viewer, may well open it."

    /** What a non-image signature is told, naming what the bytes actually look like. */
    fun notAnImageNote(signature: ByteSignature): String =
        "These bytes are ${signature.label}, which is not a still image, whatever the file is " +
            "called — the name came from the sender and this is what the file itself says. " +
            "This window draws still pictures and does not play anything, so there is nothing " +
            "honest for it to show. The file is on disk and unchanged."

    /**
     * MEASURED, 2026-09-11, and the reason this constant exists at all.
     *
     * A PNG truncated to half its length does NOT fail to decode here: skia returns an image
     * at the declared dimensions with the rows it managed and blank for the rest, and
     * `toComposeImageBitmap` accepts it without a murmur. So "the decoder will tell us" is
     * false, and a half-written file would otherwise be drawn as a picture — silently, with
     * no way for the person looking at it to know the bottom of the photograph is missing
     * rather than empty.
     *
     * [looksTruncated] is the answer: the last bytes of a PNG, JPEG or GIF are fixed by the
     * format, so a file that does not end the way its own format requires is incomplete and
     * can be said so. It is a CAVEAT and not a refusal — a partial picture is still worth
     * showing, and saying "some of this is missing" is the whole difference between a viewer
     * and a liar.
     */
    const val TRUNCATED_CAVEAT =
        "Incomplete: this file does not end the way its format requires, so part of the " +
            "picture is missing and what fills that space was never sent. A download that " +
            "was interrupted, or a copy that was. Ask the sender to send it again."

    const val ANIMATION_CAVEAT =
        "Animated — the first frame is shown. This window has no animation loop; playing it " +
            "would need a frame clock this viewer deliberately does not have."

    const val VIEW_ONCE_CAVEAT =
        "View once: you have opened this. The file stays on this disk — this client does not " +
            "destroy it and the sender is never told — and it is not revealed in your file " +
            "manager from here."

    /** KB/MB with one decimal, so a ceiling and a file size can be compared by eye. */
    fun humanBytes(n: Long): String = when {
        n >= 1024L * 1024 -> "${decimal(n / (1024.0 * 1024.0))} MB"
        n >= 1024 -> "${decimal(n / 1024.0)} KB"
        else -> "$n bytes"
    }

    /**
     * One decimal, in `Locale.ROOT`, and the locale is not an oversight.
     *
     * `"%.1f".format(x)` uses the DEFAULT locale, so on a French machine this window would
     * print "1,0 MB" and "2,3 megapixels" inside sentences that may otherwise be English —
     * the media viewer's own prose is desktop-only English even though other window strings
     * use the shared catalogs (PARITY.md row 1.5). Half-localising a number inside an English sentence is worse than
     * not localising it, and it made a ceiling this file states in its own message
     * unsearchable. Caught by `MediaViewerTest` on a machine whose default locale is fr_FR.
     */
    private fun decimal(v: Double): String = String.format(java.util.Locale.ROOT, "%.1f", v)
}

/**
 * What a file's first bytes say it is — read from the file, never from its name.
 *
 * The `mediaType` field on the wire is the SENDER's claim and the filename is the sender's
 * text; both have travelled across a network from a stranger. This is the only statement
 * about the content that comes from the content. It is used for one decision — "is it worth
 * handing to the image decoder at all" — and for one sentence of user-facing text, so an
 * unrecognised signature is not fatal: [UNKNOWN] is still offered to the decoder, because a
 * signature table is a convenience and skia's format support is the authority.
 *
 * NOT COVERED: containers whose signature lies about their contents (an MP4 renamed .png is
 * caught here; a JPEG with a PNG header prepended is not — the decoder catches that instead).
 */
enum class ByteSignature(val label: String, val stillImage: Boolean) {
    PNG("a PNG image", true),
    JPEG("a JPEG image", true),
    GIF("a GIF image", true),
    WEBP("a WebP image", true),
    BMP("a BMP image", true),
    ICO("a Windows icon", true),

    /**
     * HEIF/AVIF: a STILL image, and one this build of skia most likely cannot decode —
     * `EncodedImageFormat.HEIF` exists in the enum without a decoder behind it on every
     * platform. Marked as a still image on purpose so it is offered to the decoder and
     * refused by [MediaViewerLimits.UNDECODABLE], which is the truthful message, rather than
     * refused here as "not an image", which would not be.
     */
    HEIF("a HEIF/AVIF still image", true),

    MP4("an MP4/QuickTime video container", false),
    MATROSKA("a Matroska/WebM container", false),
    AVI("an AVI video container", false),
    OGG("an Ogg container", false),
    MPEG_AUDIO("MPEG audio", false),
    FLAC("FLAC audio", false),
    WAVE("WAV audio", false),
    PDF("a PDF document", false),
    ZIP("a ZIP-based file", false),

    /** Nothing matched. Offered to the decoder anyway — see the class note. */
    UNKNOWN("not in a form this window recognises", true);

    companion object {
        /** The ISO base-media brands that are still pictures rather than movies. */
        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "mif1", "msf1", "avif", "avis")

        fun of(bytes: ByteArray): ByteSignature {
            fun at(offset: Int, vararg magic: Int): Boolean =
                bytes.size >= offset + magic.size &&
                    magic.indices.all { (bytes[offset + it].toInt() and 0xFF) == magic[it] }

            fun ascii(offset: Int, text: String): Boolean =
                bytes.size >= offset + text.length &&
                    text.indices.all { bytes[offset + it].toInt().toChar() == text[it] }

            return when {
                at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                ascii(0, "GIF87a") || ascii(0, "GIF89a") -> GIF
                ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
                ascii(0, "RIFF") && ascii(8, "WAVE") -> WAVE
                ascii(0, "RIFF") && ascii(8, "AVI ") -> AVI
                ascii(0, "BM") -> BMP
                at(0, 0x00, 0x00, 0x01, 0x00) -> ICO
                ascii(4, "ftyp") -> if (brand(bytes) in HEIF_BRANDS) HEIF else MP4
                at(0, 0x1A, 0x45, 0xDF, 0xA3) -> MATROSKA
                ascii(0, "OggS") -> OGG
                ascii(0, "ID3") -> MPEG_AUDIO
                // An MPEG frame sync: eleven set bits. Last, because it is only 11 bits and
                // would otherwise shadow signatures that are far more specific.
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF &&
                    (bytes[1].toInt() and 0xE0) == 0xE0 -> MPEG_AUDIO
                ascii(0, "fLaC") -> FLAC
                ascii(0, "%PDF") -> PDF
                at(0, 0x50, 0x4B, 0x03, 0x04) -> ZIP
                else -> UNKNOWN
            }
        }

        private fun brand(bytes: ByteArray): String =
            if (bytes.size < 12) "" else String(bytes, 8, 4, Charsets.ISO_8859_1).lowercase()

        /**
         * Does the file END the way its own format says it must?
         *
         * Three formats fix their last bytes: PNG closes with the `IEND` chunk and its
         * constant CRC, JPEG with the `FFD9` end-of-image marker, GIF with the `3B`
         * trailer. Nothing else here makes a claim, and that is the contract — **false
         * means "no complaint", never "verified complete"**. A WebP or a BMP always returns
         * false, and so does a PNG that was truncated at a chunk boundary and happens to end
         * plausibly.
         *
         * This exists because the decoder does not report it: skia decodes a half-written
         * PNG into a half-filled picture and returns success (measured — see
         * [MediaViewerLimits.TRUNCATED_CAVEAT]). It is a cheap, one-sided check whose false
         * answer costs nothing and whose true answer is certain.
         */
        fun looksTruncated(bytes: ByteArray, signature: ByteSignature): Boolean {
            fun endsWith(vararg magic: Int): Boolean {
                if (bytes.size < magic.size) return true
                val from = bytes.size - magic.size
                return !magic.indices.all { (bytes[from + it].toInt() and 0xFF) == magic[it] }
            }
            return when (signature) {
                PNG -> endsWith(0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82)
                JPEG -> endsWith(0xFF, 0xD9)
                GIF -> endsWith(0x3B)
                else -> false
            }
        }
    }
}

/** The outcome of trying to turn a path into something to put on screen. */
sealed interface MediaLoad {

    /** Pixels, and the facts about them a header line can state without guessing. */
    data class Ready(
        val image: ImageBitmap,
        val width: Int,
        val height: Int,
        /** skia's own name for the encoded format — `PNG`, `JPEG`, `GIF`, `WEBP`. */
        val format: String,
        val fileBytes: Long,
        /** Frames the container declares. Greater than one adds a caveat. */
        val frames: Int,
        /**
         * True statements about this file that are not refusals — it IS being shown.
         *
         * Empty for an ordinary complete photograph. Every entry is drawn under the picture:
         * a caveat nobody sees is the same as no caveat.
         */
        val caveats: List<String>,
    ) : MediaLoad

    /** Nothing is drawn. Both strings are shown; neither is ever empty. */
    data class Refused(val headline: String, val detail: String) : MediaLoad
}

/**
 * Path in, [MediaLoad] out. The whole decision, with the filesystem injected.
 *
 * Not a composable and not `remember`ed here: the caller decides when this runs (the overlay
 * does it once per path) so that the cost — a read of up to 64 MiB and a full decode — is
 * never repeated by a recomposition.
 */
object MediaViewerLoader {

    /**
     * @param path absolute path as `MediaPresentation` reported it.
     * @param probe size in bytes, or null when there is no readable file there.
     * @param read the bytes. Called only after [probe] has passed both size gates.
     */
    fun load(
        path: String,
        // __LOCAL_DATA_AT_REST_2026_09_22__ plaintext size and bytes through the media vault:
        // a sealed attachment is decrypted in memory (after the size gates), never to disk.
        probe: (String) -> Long? = { com.oshi.desktop.store.MediaVault.lengthOf(File(it)) },
        read: (String) -> ByteArray = {
            com.oshi.desktop.store.MediaVault.readAny(File(it), MediaViewerLimits.MAX_FILE_BYTES)
        },
    ): MediaLoad {
        val size = probe(path)
            ?: return MediaLoad.Refused("Not on this disk", MediaViewerLimits.NOT_A_FILE)
        if (size == 0L) return MediaLoad.Refused("Empty file", MediaViewerLimits.EMPTY_FILE)
        if (size > MediaViewerLimits.MAX_FILE_BYTES) {
            return MediaLoad.Refused("Too large to open here", MediaViewerLimits.oversizeNote(size))
        }

        // Everything from here can throw on hostile input, and an I/O error mid-read is
        // ordinary rather than exceptional on a file another process may be touching.
        val bytes = try {
            read(path)
        } catch (t: Throwable) {
            return MediaLoad.Refused("Could not be read", "${MediaViewerLimits.NOT_A_FILE}\n\n${t.javaClass.simpleName}")
        }

        val signature = ByteSignature.of(bytes)
        if (!signature.stillImage) {
            return MediaLoad.Refused("Not a still image", MediaViewerLimits.notAnImageNote(signature))
        }

        val header = header(bytes)
            ?: return MediaLoad.Refused("No image header", MediaViewerLimits.NO_IMAGE_HEADER)
        if (header.width.toLong() * header.height.toLong() > MediaViewerLimits.MAX_PIXELS) {
            return MediaLoad.Refused(
                "Too many pixels to decode",
                MediaViewerLimits.tooManyPixelsNote(header.width, header.height),
            )
        }

        // `toComposeImageBitmap` rasterises here and now (it calls `Bitmap.makeFromImage`),
        // which is exactly what is wanted: a failure lands in this catch, where it becomes a
        // sentence, instead of in the render pass, where it would take the frame down.
        //
        // Throwable and not Exception, deliberately: the two things a hostile image can do
        // are throw from native code and exhaust the heap, and the second is an
        // `OutOfMemoryError`. Catching it is defensible in exactly this shape — the
        // allocation that failed was this decode, it is discarded on the way out, and the
        // alternative is a window that dies because somebody sent a picture.
        //
        // HONESTLY: no input this project has found reaches this catch. Truncated and
        // corrupt bodies were MEASURED (2026-09-11) to decode successfully into a partial
        // picture — which is why `TRUNCATED_CAVEAT` exists and why this is not the guard
        // that protects the user from a half-written file. It stays because "the native
        // decoder never throws" is not a claim anybody can make about every future file on
        // every platform, and the cost of being wrong without it is the whole window.
        val image = try {
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (t: Throwable) {
            return MediaLoad.Refused("Could not be decoded", MediaViewerLimits.UNDECODABLE)
        }

        return MediaLoad.Ready(
            image = image,
            width = header.width,
            height = header.height,
            format = header.format,
            fileBytes = size,
            frames = header.frames,
            caveats = buildList {
                if (ByteSignature.looksTruncated(bytes, signature)) add(MediaViewerLimits.TRUNCATED_CAVEAT)
                if (header.frames > 1) add(MediaViewerLimits.ANIMATION_CAVEAT)
            },
        )
    }

    private data class Header(val width: Int, val height: Int, val format: String, val frames: Int)

    /**
     * Dimensions, format and frame count WITHOUT decoding — the guard in step 4.
     *
     * `Codec` parses the header only. That is the entire point: it is how a 45 KB file
     * claiming 50000×50000 is refused before anything allocates. Both handles are closed in
     * reverse order of creation; `Data` copies the bytes into native memory, so the copy is
     * what would leak if this were sloppy, once per opened file.
     */
    private fun header(bytes: ByteArray): Header? = try {
        val data = Data.makeFromBytes(bytes)
        try {
            val codec = Codec.makeFromData(data)
            try {
                Header(codec.width, codec.height, codec.encodedImageFormat.name, codec.frameCount)
            } finally {
                codec.close()
            }
        } finally {
            data.close()
        }
    } catch (t: Throwable) {
        null
    }
}

/**
 * What the overlay was asked to open. Built ONLY through [of], which is where view-once is
 * enforced — there is no other constructor call anywhere in this package.
 */
data class MediaViewerTarget(
    val kind: MediaType,
    val path: String,
    val fileName: String,
    /** The row was marked view once and the user revealed it. Suppresses reveal-in-folder. */
    val viewOnce: Boolean,
) {
    companion object {
        /**
         * Null when there is nothing openable: no media, or a SEALED view-once row.
         *
         * A sealed row returning null is the guard — see the class note on
         * `MediaViewerLimits`. An unparseable reference also returns null: this window will
         * not open a path it could not read out of the model in the first place.
         */
        fun of(media: MediaPresentation?): MediaViewerTarget? {
            if (media == null || media.sealedViewOnce || media.unparseable) return null
            return MediaViewerTarget(
                kind = media.kind,
                path = media.path,
                fileName = media.fileName,
                viewOnce = media.viewOnceRow,
            )
        }
    }
}

/**
 * How a bubble asks for the viewer, without every composable between here and there growing
 * a parameter for it.
 *
 * A `CompositionLocal` rather than a callback threaded through `MessageBubbleRow`: the bubble
 * is somebody else's file and the smaller the change there, the better. [NONE] is the
 * default, and it is not a disabled viewer — it is NO viewer, so a screen that renders a
 * bubble without providing an opener (the renderer's contact sheets, for one) behaves exactly
 * as it did before this file existed.
 */
fun interface MediaOpener {
    fun open(target: MediaViewerTarget)

    companion object {
        val NONE = MediaOpener { }
    }
}

val LocalMediaOpener: ProvidableCompositionLocal<MediaOpener> = staticCompositionLocalOf { MediaOpener.NONE }

/** Provide an opener to everything drawn inside [content]. */
@Composable
fun WithMediaViewer(content: @Composable () -> Unit) {
    var open by remember { mutableStateOf<MediaViewerTarget?>(null) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalMediaOpener provides MediaOpener { open = it }) {
            content()
        }
        open?.let { MediaViewerOverlay(it) { open = null } }
    }
}

// ---------------------------------------------------------------------------- the overlay

/**
 * The full-size look at one attachment.
 *
 * A `Box` over the pane rather than a `DialogWindow`: an AWT dialog is a second window with
 * its own icon, its own taskbar entry and its own focus rules, and this is a detail view of
 * something in the pane behind it, not a separate document.
 *
 * **There is no Escape binding.** Wiring one needs a focus requester owned by the pane that
 * hosts this, which is not this file, and a key that silently does nothing is worse than a
 * close button that visibly works. The scrim is clickable and the button is labelled; if the
 * pane ever grows a focus owner, an Escape can be added here without changing anything else.
 *
 * The decode runs in `remember(path)` — once per opened file, never per frame — and the
 * bitmap is dropped when this leaves the composition, which is what "revealed for now"
 * means for a view-once row.
 */
@Composable
fun MediaViewerOverlay(target: MediaViewerTarget, onClose: () -> Unit) {
    // One overlay means one player. Creating it here rather than in every bubble preserves
    // the phones' one-note-at-a-time rule, and disposal stops a note before navigation can
    // leave an invisible speaker running.
    val audio = remember(target.path) { AudioPlayer() }
    DisposableEffect(audio) { onDispose { audio.stop() } }
    // A scrim that swallows the click that dismisses it. `indication = null` because a
    // ripple on a full-screen scrim reads as the surface itself being a control.
    val scrim = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(interactionSource = scrim, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        // A second, non-dismissing click target: without it, a click anywhere on the panel
        // falls through to the scrim and closes the thing the user just aimed at.
        val panel = remember { MutableInteractionSource() }
        Column(
            Modifier
                .padding(OshiTheme.xxl)
                .widthIn(max = 760.dp)
                .clip(OshiTheme.radiusMd)
                .background(OshiTheme.surfaceElevated)
                .border(Metrics.hairline, OshiTheme.separator, OshiTheme.radiusMd)
                .clickable(interactionSource = panel, indication = null, onClick = {})
                .padding(OshiTheme.lg),
            verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
        ) {
            // Video and audio do not go through the still-image loader. In particular, an
            // unopened voice note must not be read merely because its viewer is visible.
            val isStillImage = target.kind == MediaType.IMAGE
            val load = remember(target.path, isStillImage) {
                if (isStillImage) MediaViewerLoader.load(target.path) else null
            }

            ViewerHeader(target, load, onClose)
            Hairline()

            when {
                target.kind == MediaType.VIDEO -> NoPlayerPanel(target)
                target.kind == MediaType.AUDIO -> AudioPlayerPanel(target, audio)
                load is MediaLoad.Ready -> ReadyPanel(load)
                load is MediaLoad.Refused -> RefusedPanel(load)
                else -> RefusedPanel(MediaLoad.Refused("Nothing to open", MediaViewerLimits.NOT_A_FILE))
            }

            if (target.viewOnce) {
                Text(MediaViewerLimits.VIEW_ONCE_CAVEAT, fontSize = 11.sp, color = Ink.soft)
            }
            ViewerFooter(target)
        }
    }
}

@Composable
private fun ViewerHeader(target: MediaViewerTarget, load: MediaLoad?, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OshiTheme.xxs)) {
            Text(target.fileName, style = OshiTheme.typography.titleMedium, color = Ink.strong)
            Text(subtitle(target, load), fontSize = 11.sp, color = Ink.soft)
        }
        Box(
            Modifier.size(Metrics.actionButton).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.CLOSE, Ink.soft, 14.dp) }
    }
}

/**
 * The one line of facts under the filename.
 *
 * Every element is measured rather than asserted: the dimensions and the format come from
 * the file's own header via `Codec`, the size from the filesystem. The declared `mediaType`
 * is shown as the LABEL only — it is the sender's claim and is never presented as a fact
 * about the bytes.
 */
private fun subtitle(target: MediaViewerTarget, load: MediaLoad?): String = when (load) {
    is MediaLoad.Ready ->
        "${load.format} · ${load.width}×${load.height} · ${MediaViewerLimits.humanBytes(load.fileBytes)}" +
            if (load.frames > 1) " · ${load.frames} frames" else ""
    else -> MediaPresentation.cardLabel(target.kind)
}

@Composable
private fun ReadyPanel(load: MediaLoad.Ready) {
    Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
        // `Fit`, never `Crop`: this is the view where the whole picture is the point, and a
        // crop here would hide part of what a person opened it to see. The bubble crops; the
        // viewer does not.
        Image(
            bitmap = load.image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp)
                .clip(OshiTheme.radiusSm)
                .background(OshiTheme.surface),
        )
        // The glyph carries the warning hue and the words carry the contrast. `OshiTheme.warning`
        // is 2.2:1 on this surface — fine for an icon, not for a sentence (see `Ink`'s note),
        // and colour is never the only signal anyway.
        load.caveats.forEach { caveat ->
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.Top) {
                GlyphIcon(Glyph.ALERT, OshiTheme.warning, 14.dp)
                Text(caveat, fontSize = 11.sp, color = Ink.strong)
            }
        }
    }
}

/** A refusal, drawn as a refusal: an icon, the headline, and the whole reason. */
@Composable
private fun RefusedPanel(load: MediaLoad.Refused) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusSm)
            .background(OshiTheme.surface)
            .padding(OshiTheme.md),
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(Glyph.ALERT, OshiTheme.warning, 22.dp)
        Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            Text(
                load.headline,
                style = OshiTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink.strong,
            )
            Text(load.detail, fontSize = 11.sp, color = Ink.soft)
        }
    }
}

/**
 * A video: named, located, and explicitly not played.
 *
 * The file is NOT read here at all — not a byte. There is nothing this window could do with
 * the contents, and reading a stranger's MP4 into memory to display its size would be work
 * done for no reason on bytes chosen by somebody else.
 */
@Composable
private fun NoPlayerPanel(target: MediaViewerTarget) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusSm)
            .background(OshiTheme.surface)
            .padding(OshiTheme.lg),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(OshiTheme.sm))
        GlyphIcon(Glyph.FILE, Ink.soft, 34.dp)
        // NOT `cardLabel(kind)`: the header two lines above already says "Video — no player
        // in this window", and the first render of this panel printed that sentence twice on
        // one screen. Caught by looking at `build/screens/viewer-04-video.png`, which is the
        // only thing that catches it — no assertion in this repository reads words.
        Text(
            "Not played here",
            style = OshiTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Ink.strong,
        )
        Text(
            "Nothing in this window plays video, and it will not pretend otherwise " +
                "with a play button that produces silence. The file is stored encrypted on " +
                "this disk and is not touched by this panel; use \"Open in another app\" " +
                "below for a player that has the codecs. PARITY.md rows 1.4 and 2.1.",
            fontSize = 11.sp,
            color = Ink.soft,
        )
        Spacer(Modifier.height(OshiTheme.xxs))
    }
}

/**
 * The one media player this window can truthfully offer: a decrypted voice note. The player
 * owns decoding and its worker thread; this composable only translates its named outcomes
 * back onto the AWT event queue before changing Compose state.
 */
@Composable
private fun AudioPlayerPanel(target: MediaViewerTarget, player: AudioPlayer) {
    var playing by remember(target.path) { mutableStateOf(false) }
    var note by remember(target.path) { mutableStateOf<String?>(null) }
    val file = remember(target.path) { File(target.path) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusSm)
            .background(OshiTheme.surface)
            .padding(OshiTheme.lg),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(Glyph.FILE, Ink.soft, 34.dp)
        Text(
            "Voice note",
            style = OshiTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Ink.strong,
        )
        Text(
            "Playback decrypts this note to a temporary file that is deleted afterwards. " +
                "A phone-format m4a may be decoded to a temporary WAV first; if this machine has no usable audio device or " +
                "decoder, the reason is shown here.",
            fontSize = 11.sp,
            color = Ink.soft,
        )
        TextAction(if (playing) "Stop playback" else "Play voice note") {
            if (playing) {
                player.stop()
                playing = false
                note = "Stopped."
            } else {
                when (val start = player.play(file) { end ->
                    EventQueue.invokeLater {
                        playing = false
                        note = when (end) {
                            PlaybackEnd.Completed -> "Finished."
                            PlaybackEnd.Stopped -> "Stopped."
                            is PlaybackEnd.Failed -> "Playback stopped: ${end.detail}"
                        }
                    }
                }) {
                    is PlaybackStart.Started -> {
                        playing = true
                        note = null
                    }
                    is PlaybackStart.Failure -> {
                        playing = false
                        note = start.reason.message
                    }
                }
            }
        }
        note?.let { Text(it, fontSize = 11.sp, color = Ink.soft) }
    }
}

/** The path, and the reveal control when — and only when — the platform really has one. */
@Composable
private fun ViewerFooter(target: MediaViewerTarget) {
    var problem by remember(target.path) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
        Text(target.path, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Ink.soft)

        // The control EXISTS only where it works. `FolderReveal.action` probes AWT once; on
        // a platform that cannot do it — a bare Linux session with no file manager, a
        // headless JVM — there is no button at all, rather than one that fails on click.
        // And never for view-once: see the class note.
        //
        // The third condition is a `stat` and not a read, and it is here because the
        // no-player branch never touches the file: the first render of a video whose path no
        // longer resolved still offered "Show in folder" (seen in
        // `build/screens/viewer-04-video.png`), which is a button that can only apologise.
        val here = remember(target.path) { File(target.path).isFile }
        // __LOCAL_DATA_AT_REST_2026_09_22__ A sealed attachment is ciphertext on disk:
        // revealing it in a file manager would hand over a file nothing else can open. It
        // gets the two things a person actually wanted from "show in folder" instead —
        // open it in another app (via a scratch copy) or save a decrypted copy where they
        // choose. Still never for view-once.
        val sealed = remember(target.path) { com.oshi.desktop.store.MediaVault.isSealed(File(target.path)) }
        if (sealed && here && !target.viewOnce) {
            SealedMediaActions(target) { problem = it }
        }
        val reveal = FolderReveal.action
        if (reveal != RevealAction.NONE && !target.viewOnce && here && !sealed) {
            Text(
                reveal.label,
                style = OshiTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = OshiTheme.brand,
                modifier = Modifier
                    .clip(OshiTheme.pill)
                    .background(OshiTheme.brand.copy(alpha = 0.10f))
                    .focusRing(OshiTheme.pill)
                    .clickable { problem = FolderReveal.reveal(File(target.path)) }
                    .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
            )
        }
        problem?.let { Text(it, fontSize = 11.sp, color = OshiTheme.danger) }
    }
}

@Composable
private fun SealedMediaActions(target: MediaViewerTarget, onProblem: (String?) -> Unit) {
    var working by remember(target.path) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
        if (MediaExport.canOpenExternally) {
            TextAction("Open in another app") {
                if (!working) {
                    working = true
                    MediaExport.inBackground({ MediaExport.openExternally(File(target.path)) }) {
                        working = false; onProblem(it)
                    }
                }
            }
        }
        TextAction("Save a decrypted copy…") {
            val dest = MediaExport.chooseDestination(target.fileName)
            if (dest != null && !working) {
                working = true
                MediaExport.inBackground({ MediaExport.saveCopy(File(target.path), dest) }) {
                    working = false; onProblem(it)
                }
            }
        }
    }
}

/**
 * __LOCAL_DATA_AT_REST_2026_09_22__ The only two ways plaintext of a sealed attachment
 * leaves this process on purpose.
 *
 *  - [openExternally] decrypts to `media-tmp/` (the vault's scratch dir) and hands that path
 *    to the OS. This process cannot know when the other app is done with it, so the copy is
 *    deleted at client close, at JVM exit, and by the sweep at the next start — not sooner.
 *  - [saveCopy] streams a decrypted copy to a path the USER picked. That is an export; it is
 *    theirs and is not tracked or deleted.
 *
 * Both stream (one chunk in memory) and delete their own output on a failed decrypt.
 */
object MediaExport {
    val canOpenExternally: Boolean by lazy {
        try {
            java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)
        } catch (_: Throwable) {
            false
        }
    }

    /** Null on success, or a sentence to put on screen. */
    fun openExternally(file: File): String? = try {
        val vault = com.oshi.desktop.store.MediaVault.current()
            ?: return "No media key is loaded in this window, so the attachment cannot be decrypted."
        java.awt.Desktop.getDesktop().open(vault.decryptToScratch(file))
        null
    } catch (t: Throwable) {
        "Could not open a decrypted copy (${t.javaClass.simpleName}: ${t.message ?: "no detail"})."
    }

    /** Null on success, or a sentence to put on screen. */
    fun saveCopy(file: File, dest: File): String? = try {
        val vault = com.oshi.desktop.store.MediaVault.current()
            ?: return "No media key is loaded in this window, so the attachment cannot be decrypted."
        vault.decryptTo(file, dest)
        null
    } catch (t: Throwable) {
        "The copy was not saved (${t.javaClass.simpleName}: ${t.message ?: "no detail"}); nothing was left at ${dest.path}."
    }

    fun chooseDestination(suggested: String): File? = try {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Save a decrypted copy", java.awt.FileDialog.SAVE)
        dialog.file = suggested
        dialog.isVisible = true
        val name = dialog.file
        val dir = dialog.directory
        if (name == null || dir == null) null else File(dir, name)
    } catch (_: Throwable) {
        null
    }

    /** Runs [work] off the event thread and delivers its result back on it. */
    fun inBackground(work: () -> String?, done: (String?) -> Unit) {
        Thread({
            val result = runCatching(work).getOrElse { "Failed: ${it.javaClass.simpleName}" }
            EventQueue.invokeLater { done(result) }
        }, "oshi-media-export").apply { isDaemon = true; start() }
    }
}

// ---------------------------------------------------------------------------- reveal

/** What the platform can actually be asked to do with a folder. */
enum class RevealAction(val label: String) {
    /** Nothing. No control is drawn. */
    NONE(""),

    /** `Desktop.browseFileDirectory` — opens the folder with the file selected. */
    SELECT_IN_FOLDER("Show in folder"),

    /** `Desktop.open` on the PARENT — the folder opens, nothing is selected. */
    OPEN_FOLDER("Open containing folder"),
}

/**
 * `java.awt.Desktop`, and nothing else — no new dependency for this.
 *
 * Three states, not two, because the good API is the one least likely to be there:
 * `BROWSE_FILE_DIR` is unsupported on Windows in several JDKs and on most Linux desktops,
 * while `OPEN` on the parent directory works nearly everywhere. Falling back gives a working
 * control with an honest label ("Open containing folder" does not claim the file will be
 * selected), and when neither is available the control does not exist. A disabled button is
 * still a claim that the feature is a thing this app does.
 *
 * The probe is `by lazy` so no AWT class is touched until something draws the footer — a
 * headless test that never opens the viewer never initialises AWT at all.
 */
object FolderReveal {

    /** The pure half: given what AWT reports, what should the window draw? */
    fun choose(desktopSupported: Boolean, canSelectFile: Boolean, canOpen: Boolean): RevealAction = when {
        !desktopSupported -> RevealAction.NONE
        canSelectFile -> RevealAction.SELECT_IN_FOLDER
        canOpen -> RevealAction.OPEN_FOLDER
        else -> RevealAction.NONE
    }

    val action: RevealAction by lazy {
        try {
            if (!java.awt.Desktop.isDesktopSupported()) return@lazy RevealAction.NONE
            val d = java.awt.Desktop.getDesktop()
            choose(
                desktopSupported = true,
                canSelectFile = d.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR),
                canOpen = d.isSupported(java.awt.Desktop.Action.OPEN),
            )
        } catch (t: Throwable) {
            // `getDesktop()` throws HeadlessException, and a JDK without the desktop module
            // throws on class initialisation. Either way the answer is the same.
            RevealAction.NONE
        }
    }

    /**
     * Reveals [file]. Returns null on success, or a sentence to put on screen.
     *
     * `isSupported` returning true is not a promise: `browseFileDirectory` is documented to
     * throw `UnsupportedOperationException` at call time, and on a machine with no file
     * manager registered the call fails with an IOException. Both become a red line under
     * the path rather than a stack trace.
     */
    fun reveal(file: File): String? = try {
        when (action) {
            RevealAction.SELECT_IN_FOLDER -> java.awt.Desktop.getDesktop().browseFileDirectory(file)
            RevealAction.OPEN_FOLDER -> java.awt.Desktop.getDesktop().open(file.parentFile ?: file)
            RevealAction.NONE -> Unit
        }
        null
    } catch (t: Throwable) {
        "This machine's desktop refused to open the folder (${t.javaClass.simpleName}). " +
            "The path above is the file; nothing about the file has changed."
    }
}
