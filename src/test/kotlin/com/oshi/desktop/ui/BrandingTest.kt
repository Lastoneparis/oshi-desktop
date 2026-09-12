package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The app wears OSHI's icon everywhere a user can see it.
 *
 * This is a branding test and it earns its place because EVERY failure mode here is
 * silent. jpackage matches its resource-directory overrides by EXACT filename and ignores
 * everything else without a word, so an icon named `OSHI-Desktop.ico` — which is what
 * `platform/windows/packaging/README.md` told a reader to use before this test existed,
 * the app being named `OSHI` — produces a green build, a working installer, and Duke in
 * the Start menu. The window icon fails the same way: [oshiWindowIcon] is deliberately
 * null-safe, because a branding asset must never be why a messenger refuses to open, and
 * the price of that is that a missing resource looks exactly like no resource.
 *
 * Nothing here opens a display, so it runs on the headless Windows and Linux runners that
 * are the only machines where the packaged result can actually be checked.
 *
 * NOT COVERED: that jpackage put the icon where Windows reads it. That needs a Windows
 * host and it is the `package / windows-latest` job's business, not this file's.
 */
class BrandingTest {

    /** What `--name OSHI` makes jpackage look for. Both spellings are load-bearing. */
    private val windowsIcons = listOf("OSHI.ico", "OSHI-Console.ico")

    @Test
    fun `the window icon is on the CLASSPATH, not merely on disk`() {
        // getResourceAsStream, never File(): the packaged .msi has no src/ directory, so a
        // file-system read here would pass on a developer's machine and prove nothing at
        // all about the installed app — which is the only place the icon has to work.
        val bytes = javaClass.getResourceAsStream("/branding/oshi-256.png")?.use { it.readBytes() }
        assertTrue(
            "/branding/oshi-256.png is not on the classpath — the window falls back to the " +
                "JVM's default icon and nothing else goes red.",
            bytes != null && bytes.isNotEmpty(),
        )
        val img = ImageIO.read(bytes!!.inputStream())
        assertTrue("the window icon does not decode as an image", img != null)
        assertEquals("the window icon must be square", img.width, img.height)
        assertTrue("the window icon is smaller than 128px: ${img.width}", img.width >= 128)
    }

    @Test
    fun `every Windows launcher has an icon under the exact name jpackage looks up`() {
        for (name in windowsIcons) {
            val f = File("platform/windows/packaging/$name")
            assertTrue(
                "missing platform/windows/packaging/$name. jpackage matches --resource-dir " +
                    "overrides by exact filename and ignores anything else in silence, so a " +
                    "rename here is a stock icon in the Start menu with a green build.",
                f.isFile,
            )
            assertTrue("$name is not an ICO file", isIco(f.readBytes()))
        }
    }

    /**
     * A 256-only `.ico` is the classic mistake: it installs, it shows up, and Windows
     * downsamples it for the 16px taskbar and the 32px Alt-Tab entry, which looks like a
     * blurry mistake rather than a brand. The shipped sizes are asserted, not assumed.
     */
    @Test
    fun `the Windows icon carries the small sizes Windows actually draws`() {
        for (name in windowsIcons) {
            val sizes = icoSizes(File("platform/windows/packaging/$name").readBytes())
            for (needed in listOf(16, 32, 48)) {
                assertTrue(
                    "$name has no ${needed}px entry (has: ${sizes.sorted()}). Windows would " +
                        "downsample the largest entry for the taskbar.",
                    needed in sizes,
                )
            }
        }
    }

    // ---------------------------------------------------------------- ICO, by hand
    // ImageIO reads .ico only through a reader that is not in every JDK image, and the
    // header is six bytes of contract: reserved(2)=0, type(2)=1, count(2), then one
    // 16-byte directory entry each whose first byte is the width — with 0 meaning 256,
    // because the field is a single byte and 256 does not fit in it.

    private fun isIco(b: ByteArray): Boolean =
        b.size >= 6 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 1 && b[3].toInt() == 0

    private fun icoSizes(b: ByteArray): List<Int> {
        val count = (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8)
        return (0 until count).map {
            val w = b[6 + it * 16].toInt() and 0xFF
            if (w == 0) 256 else w
        }
    }
}
