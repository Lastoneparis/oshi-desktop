package com.oshi.desktop.stego

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.service.stego.StegoPng
import com.oshi.messenger.service.stego.StegoV1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO

/**
 * __PRIVACY_DISGUISE_2026_11_18__ STEGO-v1 across the four clients, through the DESKTOP path
 * (addresses in, [DesktopPhotoDisguise] glue, ImageIO for covers).
 *
 * Android's own `StegoV1VectorTest` / `StegoV1Test` run in this module too (build.gradle.kts
 * test srcDir) — they prove the shared codec. This file proves what the desktop adds on top:
 *
 *  - `desktop_prod_v1.png` is written HERE through [DesktopPhotoDisguise.hide] (generated
 *    landscape cover, CSPRNG fill, fresh ephemeral, an attachment) for the vector recipient
 *    and is checked in; the iOS Swift harness reveals it (`stego-harness reveal`), and
 *    Android's codec reveals it in [androidCodecRevealsTheDesktopPng];
 *  - the PNGs Android and iOS wrote reveal through the desktop reveal path.
 */
class DesktopStegoInteropTest {

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private val senderPriv = sha("OSHI-STEGO-v1 vector sender")
    private val recipientPriv = sha("OSHI-STEGO-v1 vector recipient")
    private val senderPub = OSHICryptoV2.x25519PubFromPriv(senderPriv)
    private val recipientPub = OSHICryptoV2.x25519PubFromPriv(recipientPriv)
    private fun address(pub: ByteArray) = Base64.getEncoder().encodeToString(pub)
    private val decoy = address(OSHICryptoV2.generateX25519().pub)

    private fun fixtures(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "docs/stego/STEGO-v1.md").exists()) return File(d, "docs/fixtures/stego")
            d = d.parentFile
        }
        error("docs/stego not found")
    }

    private val desktopText = "Desktop production path (generated cover, SecureRandom fill) → Android, iOS"
    private val desktopFile = StegoV1.Attachment("plan.txt", "text/plain", "porte B, 18h\n".toByteArray())

    private fun desktopPng(): File {
        val f = File(fixtures(), "desktop_prod_v1.png")
        if (!f.exists() || System.getenv("STEGO_WRITE_VECTORS") == "1") {
            val cover = DesktopPhotoDisguise.generatedCover(seed = 20261118L, w = 640, h = 480)
            val png = DesktopPhotoDisguise.hide(senderPriv, senderPub, address(recipientPub), cover,
                StegoV1.Payload(desktopText, 1_763_460_000_000L, desktopFile))
            f.writeBytes(png)
        }
        return f
    }

    @Test fun `the desktop PNG reveals for its recipient through the desktop path`() {
        val out = DesktopPhotoDisguise.reveal(recipientPriv, recipientPub, desktopPng().readBytes(), listOf(decoy, address(senderPub)))
        out as DesktopPhotoDisguise.Outcome.Found
        assertEquals(address(senderPub), out.senderAddress)
        assertEquals(desktopText, out.payload.text)
        assertArrayEquals(desktopFile.data, out.payload.file!!.data)
        assertEquals("plan.txt", out.payload.file!!.name)
    }

    @Test fun `androidCodecRevealsTheDesktopPng`() {
        // StegoV1/StegoPng here ARE the Android sources (compiled from OSHI-Android), called
        // exactly as Android's PhotoDisguise.reveal calls them.
        val r = StegoV1.reveal(StegoPng.decode(desktopPng().readBytes()), recipientPriv, recipientPub, listOf(senderPub))
        r as StegoV1.RevealResult.Found
        assertEquals(desktopText, r.payload.text)
    }

    @Test fun `the desktop PNG is a plain RGB PNG with no metadata chunks`() {
        val b = desktopPng().readBytes()
        assertTrue(StegoPng.isPng(b))
        val chunks = ArrayList<String>()
        var i = 8
        while (i + 8 <= b.size) {
            val len = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            chunks += String(b, i + 4, 4, Charsets.US_ASCII)
            i += 12 + len
        }
        assertEquals(setOf("IHDR", "IDAT", "IEND"), chunks.toSet())
        assertEquals("colour type 2 (RGB), bit depth 8", 8 to 2, (b[24].toInt() to b[25].toInt()))
    }

    @Test fun `Android and iOS PNGs reveal through the desktop path`() {
        val dir = fixtures()
        for ((name, prefix) in listOf("android_prod_v1.png" to "Android production", "ios_prod_v1.png" to "iOS production")) {
            val out = DesktopPhotoDisguise.reveal(recipientPriv, recipientPub, File(dir, name).readBytes(), listOf(decoy, address(senderPub)))
            if (out !is DesktopPhotoDisguise.Outcome.Found) fail("$name: $out")
            out as DesktopPhotoDisguise.Outcome.Found
            assertTrue("$name: ${out.payload.text}", out.payload.text.startsWith(prefix))
        }
        for (name in listOf("android_v1.png", "ios_v1.png")) {
            val out = DesktopPhotoDisguise.reveal(recipientPriv, recipientPub, File(dir, name).readBytes(), listOf(address(senderPub)))
            out as DesktopPhotoDisguise.Outcome.Found
            assertEquals("note.txt", out.payload.file!!.name)
        }
    }

    /**
     * A PNG made elsewhere just now (e.g. `stego-harness hide <out> <sender_priv> <recipient_pub> <text>`
     * on a Mac): STEGO_EXTRA_PNG=/path ./gradlew test --tests '*DesktopStegoInteropTest*'.
     * Skipped when unset.
     */
    @Test fun `an extra PNG made by another platform reveals here`() {
        val path = System.getenv("STEGO_EXTRA_PNG") ?: return
        val out = DesktopPhotoDisguise.reveal(recipientPriv, recipientPub, File(path).readBytes(), listOf(decoy, address(senderPub)))
        out as DesktopPhotoDisguise.Outcome.Found
        println("STEGO extra PNG revealed on desktop: \"${out.payload.text}\"")
    }

    @Test fun `someone else, a recompressed copy and a non-image all say not for you`() {
        val png = desktopPng().readBytes()
        val stranger = OSHICryptoV2.generateX25519()
        assertEquals(DesktopPhotoDisguise.Outcome.NotForYou,
            DesktopPhotoDisguise.reveal(stranger.priv, stranger.pub, png, listOf(address(senderPub))))
        // The sender's own contacts list does not include themselves: not for them either.
        assertEquals(DesktopPhotoDisguise.Outcome.NotForYou,
            DesktopPhotoDisguise.reveal(recipientPriv, recipientPub, png, listOf(decoy)))
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(ImageIO.read(png.inputStream()), "jpg", it) }.toByteArray()
        assertEquals(DesktopPhotoDisguise.Outcome.NotForYou,
            DesktopPhotoDisguise.reveal(recipientPriv, recipientPub, jpeg, listOf(address(senderPub))))
        assertEquals(DesktopPhotoDisguise.Outcome.Unreadable,
            DesktopPhotoDisguise.reveal(recipientPriv, recipientPub, "hello".toByteArray(), listOf(address(senderPub))))
    }

    /**
     * __STEGO_DAMAGED_NEVER_CRASHES_2026_11_18__ (Android c308647e, netem stego/): hostile copies
     * of every platform's PNG — truncations, bit flips, a short or huge IHDR, a re-save, a JPEG —
     * reveal EXACTLY or say "no hidden message"; never a crash, never different text.
     */
    @Test fun `hostile copies reveal exactly or not at all, never a crash`() {
        val dir = fixtures()
        val rnd = java.util.Random(20261118)
        for (name in listOf("desktop_prod_v1.png", "android_prod_v1.png", "ios_prod_v1.png")) {
            val good = if (name.startsWith("desktop")) desktopPng().readBytes() else File(dir, name).readBytes()
            val ok = DesktopPhotoDisguise.reveal(recipientPriv, recipientPub, good, listOf(address(senderPub)))
                as DesktopPhotoDisguise.Outcome.Found
            val copies = ArrayList<ByteArray>()
            for (cut in listOf(8, 20, 33, 40, good.size / 4, good.size / 2, good.size - 13, good.size - 1)) copies += good.copyOf(cut)
            repeat(60) { val b = good.copyOf(); repeat(1 + rnd.nextInt(8)) { val i = 8 + rnd.nextInt(b.size - 8); b[i] = (b[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }; copies += b }
            copies += good.copyOf().also { it[16] = 0x7F; it[17] = 0xFF.toByte() }            // huge width
            copies += good.copyOf().also { it[11] = 5 }                                      // 5-byte IHDR
            val img = ImageIO.read(good.inputStream())
            copies += ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()  // re-saved
            copies += ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()  // JPEG
            for ((i, c) in copies.withIndex()) {
                val out = try {
                    DesktopPhotoDisguise.reveal(recipientPriv, recipientPub, c, listOf(decoy, address(senderPub)))
                } catch (t: Throwable) { fail("$name copy $i threw ${t.javaClass.simpleName}: ${t.message}"); return }
                if (out is DesktopPhotoDisguise.Outcome.Found) assertEquals("$name copy $i: never different text", ok.payload.text, out.payload.text)
            }
        }
    }

    @Test fun `a cover too small for the message is refused before anything is written`() {
        val tiny = DesktopPhotoDisguise.generatedCover(1, 16, 16)
        try {
            DesktopPhotoDisguise.hide(senderPriv, senderPub, address(recipientPub), tiny, StegoV1.Payload("x".repeat(500), 0L))
            fail("expected CapacityException")
        } catch (e: StegoV1.CapacityException) {
            assertTrue(e.needed > e.available)
        }
    }

    @Test fun `covers are downscaled to the phones' 1600 px long side and base64url addresses work`() {
        val big = java.awt.image.BufferedImage(3200, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val c = DesktopPhotoDisguise.coverFromImage(big)
        assertEquals(1600, c.width); assertEquals(500, c.height)
        val url = address(senderPub).replace('+', '-').replace('/', '_').trimEnd('=')
        assertArrayEquals(senderPub, DesktopPhotoDisguise.keyBytes(url))
    }
}
