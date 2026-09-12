package com.oshi.desktop.ui.components

import com.oshi.desktop.store.MediaType
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The guard on the viewer: **it opens what it was entitled to open, and nothing it cannot.**
 *
 * Two classes of failure live here and they are not the same size:
 *
 *   * A **privacy** failure — a sealed view-once row reachable through the viewer. One test,
 *     and it is the reason [MediaViewerTarget.of] is the only way to build a target.
 *   * A **survival** failure — a window killed, hung or blanked by a file a stranger chose.
 *     A messenger's decoder is the most exposed surface it has: the bytes arrive from
 *     someone else, the filename is theirs, the declared type is theirs. Every hostile shape
 *     below (truncated, empty, mislabelled, a 2.5-gigapixel claim in 200 bytes) is asserted
 *     to produce a NAMED refusal, never an exception and never a blank.
 *
 * The decodes here are real: real PNG bytes from `ImageIO`, a real skia decode, on the same
 * `org.jetbrains.skia` the window draws with. Only the filesystem is injectable, and only
 * where the point is a size the disk cannot conveniently be made to report.
 */
class MediaViewerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------ the privacy guard

    @Test
    fun `a sealed view-once row can not be opened in the viewer at all`() {
        val sealedRow = MediaPresentation.of("[image] /m/secret.jpg", viewOnce = true) { 4096L }!!
        assertTrue("precondition: the row is sealed", sealedRow.sealedViewOnce)
        assertNull(
            "a sealed view-once row must have NO viewer target — the viewer must not be a way " +
                "around ThreadPane's revealed set",
            MediaViewerTarget.of(sealedRow),
        )
    }

    @Test
    fun `a revealed view-once row opens, and stays marked view-once`() {
        val opened = MediaPresentation.of("[image] /m/secret.jpg", viewOnce = true, revealed = true) { 4096L }!!
        val target = MediaViewerTarget.of(opened)
        assertNotNull("revealing must actually open it", target)
        assertTrue(
            "the target must remember it is view-once, which is what suppresses reveal-in-folder",
            target!!.viewOnce,
        )
    }

    @Test
    fun `an ordinary image is not marked view-once and an unparseable reference does not open`() {
        val plain = MediaPresentation.of("[image] /m/cat.jpg") { 4096L }!!
        assertEquals(false, MediaViewerTarget.of(plain)!!.viewOnce)
        assertEquals(MediaType.IMAGE, MediaViewerTarget.of(plain)!!.kind)

        val junk = MediaPresentation.of("no-type-prefix.jpg") { 4096L }!!
        assertNull("this window will not open a path it could not parse", MediaViewerTarget.of(junk))
        assertNull(MediaViewerTarget.of(null))
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    fun `a real PNG on disk decodes, and reports its own measured facts`() {
        val f = tmp.newFile("cat.png").also { writePng(it, 64, 48, 0xFFE0218A.toInt()) }
        val load = MediaViewerLoader.load(f.absolutePath)
        assertTrue("a real PNG must open: $load", load is MediaLoad.Ready)
        load as MediaLoad.Ready
        assertEquals(64, load.width)
        assertEquals(48, load.height)
        assertEquals(64, load.image.width)
        assertEquals(48, load.image.height)
        assertEquals("PNG", load.format)
        assertEquals(f.length(), load.fileBytes)
        assertEquals("a still image has no caveats", emptyList<String>(), load.caveats)
    }

    // ------------------------------------------------------------------ THE HOSTILE FILES

    @Test
    fun `a file that is not there is refused by name and not by exception`() {
        val load = MediaViewerLoader.load(File(tmp.root, "never-written.png").absolutePath)
        assertTrue(load is MediaLoad.Refused)
        assertEquals("Not on this disk", (load as MediaLoad.Refused).headline)
        assertEquals(MediaViewerLimits.NOT_A_FILE, load.detail)
    }

    @Test
    fun `a zero-byte file is refused before anything is read`() {
        val f = tmp.newFile("empty.png")
        val load = MediaViewerLoader.load(f.absolutePath)
        assertEquals("Empty file", (load as MediaLoad.Refused).headline)
    }

    @Test
    fun `a file over the byte ceiling is refused WITHOUT being read`() {
        // The probe lies about the size and the reader would blow up if it were ever called.
        // That is the assertion: the ceiling is checked BEFORE the read, so a 4 GB file
        // costs a `stat` and not an OutOfMemoryError.
        var readCalls = 0
        val load = MediaViewerLoader.load(
            "/m/huge.png",
            probe = { MediaViewerLimits.MAX_FILE_BYTES + 1 },
            read = { readCalls++; error("the reader must never be reached for an oversize file") },
        )
        assertEquals(0, readCalls)
        assertEquals("Too large to open here", (load as MediaLoad.Refused).headline)
        assertTrue("the ceiling must be in the message", load.detail.contains("64.0 MB"))
    }

    @Test
    fun `the byte ceiling is inclusive at its exact value`() {
        val f = tmp.newFile("ok.png").also { writePng(it, 8, 8, 0xFF00FF00.toInt()) }
        val load = MediaViewerLoader.load(
            f.absolutePath,
            probe = { MediaViewerLimits.MAX_FILE_BYTES },
            read = { f.readBytes() },
        )
        assertTrue("at the cap exactly, it opens: $load", load is MediaLoad.Ready)
    }

    /**
     * The decompression bomb. 200-odd bytes of PNG whose IHDR claims 50000×50000 — ten
     * gigabytes of ARGB if anything were foolish enough to decode it.
     *
     * This is the test the whole `Codec`-before-`Image` ordering exists for. If the pixel
     * ceiling ever moves behind the decode, this assertion becomes an OutOfMemoryError or a
     * dead test runner rather than a red line, which is itself the signal.
     */
    @Test
    fun `a small file claiming enormous dimensions is refused without decoding`() {
        val f = tmp.newFile("bomb.png")
        f.writeBytes(pngWithForgedDimensions(50_000, 50_000))
        val load = MediaViewerLoader.load(f.absolutePath)
        assertTrue("the bomb must be refused, not decoded: $load", load is MediaLoad.Refused)
        assertEquals("Too many pixels to decode", (load as MediaLoad.Refused).headline)
        assertTrue("the refusal must name the dimensions", load.detail.contains("50000×50000"))
    }

    @Test
    fun `the pixel ceiling is inclusive at its exact value`() {
        // 8000 x 4000 = 32 MP exactly. Forged dimensions, so nothing is allocated: what is
        // under test is the comparison, and a real 32 MP file in a unit test would be 128 MB
        // of heap to prove an operator.
        val f = tmp.newFile("exact.png").also { it.writeBytes(pngWithForgedDimensions(8_000, 4_000)) }
        val load = MediaViewerLoader.load(f.absolutePath)
        assertTrue(
            "32 MP exactly is AT the cap and must pass the pixel gate; whatever the forged " +
                "body then does is a different question: $load",
            load !is MediaLoad.Refused || (load as MediaLoad.Refused).headline != "Too many pixels to decode",
        )
    }

    @Test
    fun `an MP4 called a PNG is named as an MP4 and never handed to the decoder`() {
        val f = tmp.newFile("holiday.png")
        f.writeBytes(byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(64))
        val load = MediaViewerLoader.load(f.absolutePath)
        assertEquals("Not a still image", (load as MediaLoad.Refused).headline)
        assertTrue("the refusal must say what the bytes really are", load.detail.contains("MP4"))
        assertTrue(
            "and must scope the refusal to THIS WINDOW rather than claim the project can " +
                "never play anything — the client grew an audio path of its own",
            load.detail.contains("This window draws still pictures and does not play anything"),
        )
    }

    @Test
    fun `a file truncated before its header has one is refused by name`() {
        val whole = tmp.newFile("whole.png").also { writePng(it, 120, 90, 0xFF3366CC.toInt()) }.readBytes()
        val f = tmp.newFile("stub.png").also { it.writeBytes(whole.copyOfRange(0, 40)) }
        val load = MediaViewerLoader.load(f.absolutePath)
        assertEquals("No image header", (load as MediaLoad.Refused).headline)
        assertEquals(MediaViewerLimits.NO_IMAGE_HEADER, load.detail)
    }

    /**
     * The finding that changed this file, measured 2026-09-11.
     *
     * A PNG cut in half DECODES. Skia returns an image at the declared size with the rows it
     * managed and blank for the rest, and reports no error at all — so the decoder cannot be
     * relied on to notice a half-written download, and without the caveat below this window
     * would draw a half-missing photograph as if it were the photograph.
     *
     * If skia's behaviour ever changes to a hard failure, this test goes red on the `Ready`
     * assertion rather than silently passing, and the caveat can be reconsidered then.
     */
    @Test
    fun `a truncated image is SHOWN and SAID to be incomplete, because skia decodes it happily`() {
        val whole = tmp.newFile("whole2.png").also { writePng(it, 120, 90, 0xFF3366CC.toInt()) }.readBytes()
        val f = tmp.newFile("half.png").also { it.writeBytes(whole.copyOfRange(0, whole.size / 2)) }
        val load = MediaViewerLoader.load(f.absolutePath)
        assertTrue("measured: skia decodes a half PNG rather than failing: $load", load is MediaLoad.Ready)
        assertTrue(
            "a partial picture MUST carry the incompleteness on screen — this is the guard",
            (load as MediaLoad.Ready).caveats.contains(MediaViewerLimits.TRUNCATED_CAVEAT),
        )
    }

    @Test
    fun `a complete image carries no caveats at all`() {
        val f = tmp.newFile("complete.png").also { writePng(it, 32, 32, 0xFF00AAFF.toInt()) }
        assertEquals(emptyList<String>(), (MediaViewerLoader.load(f.absolutePath) as MediaLoad.Ready).caveats)
    }

    @Test
    fun `the end-of-file check complains only where the format actually fixes the ending`() {
        val png = tmp.newFile("eof.png").also { writePng(it, 4, 4, 0xFF000000.toInt()) }.readBytes()
        assertTrue(!ByteSignature.looksTruncated(png, ByteSignature.PNG))
        assertTrue(ByteSignature.looksTruncated(png.copyOfRange(0, png.size - 1), ByteSignature.PNG))
        assertTrue(ByteSignature.looksTruncated(ByteArray(2), ByteSignature.PNG))

        assertTrue(!ByteSignature.looksTruncated(magic(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0xFF, 0xD9), ByteSignature.JPEG))
        assertTrue(ByteSignature.looksTruncated(magic(0xFF, 0xD8, 0xFF, 0xE0, 0x00), ByteSignature.JPEG))
        assertTrue(!ByteSignature.looksTruncated("GIF89a....;".toByteArray(), ByteSignature.GIF))
        assertTrue(ByteSignature.looksTruncated("GIF89a....".toByteArray(), ByteSignature.GIF))

        // FALSE MEANS "NO COMPLAINT", NOT "COMPLETE". A format whose ending is not fixed is
        // never accused, however short the file is — an accusation this window cannot back
        // up would be worse than staying quiet.
        assertTrue(!ByteSignature.looksTruncated(ByteArray(3), ByteSignature.WEBP))
        assertTrue(!ByteSignature.looksTruncated(ByteArray(3), ByteSignature.BMP))
        assertTrue(!ByteSignature.looksTruncated(ByteArray(3), ByteSignature.UNKNOWN))
    }

    @Test
    fun `pure noise is refused rather than crashing the decoder`() {
        val f = tmp.newFile("noise.bin")
        f.writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() })
        val load = MediaViewerLoader.load(f.absolutePath)
        assertTrue("noise must be refused: $load", load is MediaLoad.Refused)
    }

    @Test
    fun `a read that throws becomes a sentence`() {
        val load = MediaViewerLoader.load(
            "/m/locked.png",
            probe = { 1024L },
            read = { throw java.io.IOException("permission denied") },
        )
        assertEquals("Could not be read", (load as MediaLoad.Refused).headline)
        assertTrue(load.detail.contains("IOException"))
    }

    // ------------------------------------------------------------------ signatures

    @Test
    fun `signatures are read from the bytes and never from the name`() {
        assertEquals(ByteSignature.PNG, ByteSignature.of(magic(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(ByteSignature.JPEG, ByteSignature.of(magic(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(ByteSignature.GIF, ByteSignature.of("GIF89a".toByteArray() + ByteArray(16)))
        assertEquals(ByteSignature.WEBP, ByteSignature.of("RIFF????WEBPVP8 ".toByteArray()))
        assertEquals(ByteSignature.WAVE, ByteSignature.of("RIFF????WAVEfmt ".toByteArray()))
        assertEquals(ByteSignature.AVI, ByteSignature.of("RIFF????AVI LIST".toByteArray()))
        assertEquals(ByteSignature.MATROSKA, ByteSignature.of(magic(0x1A, 0x45, 0xDF, 0xA3, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(ByteSignature.OGG, ByteSignature.of("OggS".toByteArray() + ByteArray(16)))
        assertEquals(ByteSignature.PDF, ByteSignature.of("%PDF-1.7".toByteArray() + ByteArray(8)))
        assertEquals(ByteSignature.ZIP, ByteSignature.of(magic(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `an ftyp brand decides between a movie and a still picture`() {
        assertEquals(ByteSignature.MP4, ByteSignature.of(ftyp("mp42")))
        assertEquals(ByteSignature.MP4, ByteSignature.of(ftyp("qt  ")))
        // HEIC and AVIF are STILL images. They are offered to the decoder on purpose: the
        // honest message when skia has no HEIF codec is "this window could not decode it",
        // not "this is not an image".
        assertEquals(ByteSignature.HEIF, ByteSignature.of(ftyp("heic")))
        assertEquals(ByteSignature.HEIF, ByteSignature.of(ftyp("avif")))
        assertTrue(ByteSignature.HEIF.stillImage)
        assertTrue(!ByteSignature.MP4.stillImage)
    }

    @Test
    fun `unknown bytes are still offered to the decoder`() {
        // A signature table is a convenience; skia's format support is the authority. If this
        // ever flips to false, every format skia gains becomes invisible until someone edits
        // a table, which is the wrong failure direction for a client that receives files.
        assertTrue(ByteSignature.UNKNOWN.stillImage)
        assertEquals(ByteSignature.UNKNOWN, ByteSignature.of(ByteArray(64) { 0x42 }))
        assertEquals("a short file must not crash the sniffer", ByteSignature.UNKNOWN, ByteSignature.of(ByteArray(1)))
        assertEquals(ByteSignature.UNKNOWN, ByteSignature.of(ByteArray(0)))
    }

    // ------------------------------------------------------------------ reveal in folder

    @Test
    fun `the reveal control does not exist when the platform has no desktop`() {
        assertEquals(
            RevealAction.NONE,
            FolderReveal.choose(desktopSupported = false, canSelectFile = true, canOpen = true),
        )
    }

    @Test
    fun `the reveal control falls back to opening the folder, and disappears when neither works`() {
        assertEquals(
            RevealAction.SELECT_IN_FOLDER,
            FolderReveal.choose(desktopSupported = true, canSelectFile = true, canOpen = true),
        )
        assertEquals(
            RevealAction.OPEN_FOLDER,
            FolderReveal.choose(desktopSupported = true, canSelectFile = false, canOpen = true),
        )
        assertEquals(
            RevealAction.NONE,
            FolderReveal.choose(desktopSupported = true, canSelectFile = false, canOpen = false),
        )
        // The label must not claim more than the action does.
        assertTrue(RevealAction.OPEN_FOLDER.label.contains("folder"))
        assertTrue(!RevealAction.OPEN_FOLDER.label.contains("Show in"))
        assertEquals("", RevealAction.NONE.label)
    }

    // ------------------------------------------------------------------ the numbers

    @Test
    fun `sizes are rendered so a file and a ceiling can be compared by eye`() {
        assertEquals("512 bytes", MediaViewerLimits.humanBytes(512))
        assertEquals("1.0 KB", MediaViewerLimits.humanBytes(1024))
        assertEquals("64.0 MB", MediaViewerLimits.humanBytes(MediaViewerLimits.MAX_FILE_BYTES))
    }

    @Test
    fun `a French machine does not get a comma inside an English sentence`() {
        // This is not hypothetical: the first run of this suite was on a fr_FR machine and
        // printed "1,0 KB" and "2,5 megapixels" in otherwise English text, which also made
        // the ceiling the message states unsearchable. The window is not localised at all
        // (PARITY.md row 1.5), so its numbers must not be either.
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals("1.0 KB", MediaViewerLimits.humanBytes(1024))
            assertTrue(MediaViewerLimits.tooManyPixelsNote(50_000, 50_000).contains("2500.0"))
        } finally {
            java.util.Locale.setDefault(was)
        }
    }

    @Test
    fun `the viewer ceiling is above the inline one, and both are stated in their messages`() {
        assertTrue(
            "opening one file deliberately may cost more than every row that scrolls past",
            MediaViewerLimits.MAX_FILE_BYTES > MediaPresentation.MAX_INLINE_BYTES,
        )
        assertTrue(MediaPresentation.OVERSIZE_NOTE.contains("16 MiB"))
        assertTrue(MediaViewerLimits.tooManyPixelsNote(9000, 9000).contains("32 MP"))
    }

    // ------------------------------------------------------------------ helpers

    private fun magic(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun ftyp(brand: String) = byteArrayOf(0, 0, 0, 0x18) + "ftyp".toByteArray() + brand.toByteArray() + ByteArray(32)

    /** A real, valid PNG — `ImageIO` writes it, skia reads it. No fixture files in the repo. */
    private fun writePng(target: File, w: Int, h: Int, argb: Int) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb)
        ImageIO.write(img, "png", target)
    }

    /**
     * A valid PNG whose IHDR has been rewritten to claim [w]×[h], CRC repaired.
     *
     * Built from a genuine 2×2 PNG rather than hand-assembled so that everything except the
     * two forged fields is exactly what a real encoder produces — which is the point: skia
     * must accept the header far enough to report the dimensions, so that the PIXEL ceiling
     * is what refuses it. A hand-rolled header that skia rejected outright would pass this
     * test for the wrong reason.
     */
    private fun pngWithForgedDimensions(w: Int, h: Int): ByteArray {
        val seed = File.createTempFile("seed", ".png").also { it.deleteOnExit() }
        writePng(seed, 2, 2, 0xFF112233.toInt())
        val bytes = seed.readBytes()
        // 8-byte signature, then IHDR: 4 length + 4 type + width + height + 5 + 4 CRC.
        fun put(at: Int, v: Int) {
            bytes[at] = (v ushr 24).toByte(); bytes[at + 1] = (v ushr 16).toByte()
            bytes[at + 2] = (v ushr 8).toByte(); bytes[at + 3] = v.toByte()
        }
        put(16, w)
        put(20, h)
        val crc = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        put(29, crc)
        return bytes
    }
}
