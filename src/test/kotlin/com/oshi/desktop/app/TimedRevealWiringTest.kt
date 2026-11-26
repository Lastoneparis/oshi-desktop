package com.oshi.desktop.app

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.V2FileKeyMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_TIMED_REVEAL_2026_10_29__ A timed photo reveal arriving from a phone.
 *
 * **The test plays the SENDING PHONE**, deliberately. This client has no composer for a
 * timed reveal (the sender half needs downscaling and a relay-held schedule), so a
 * desktop-to-desktop test could not produce one — and would not be worth much if it could,
 * since two copies of this client agree on a wrong format as readily as a right one. Instead
 * the test uploads real blobs through the real blob client and hands over the exact
 * `V2FileKeyMessage` JSON an iPhone emits, with the four reveal fields set.
 *
 * What was wrong: those fields have been in `V2FileKeyMessage` all along — the desktop
 * COMPILES that file as shared source, so it parsed them for free and acted on none. Each
 * layer became its own row, so the same photograph appeared once per layer, each copy
 * sharper than the last.
 */
class TimedRevealWiringTest {

    private val fx = WiringFixture()

    @After fun tearDown() = fx.close()

    /** Layer bytes: distinguishable per layer so the test can tell WHICH one is displayed. */
    private fun layerBytes(index: Int, size: Int) = ByteArray(size) { (index * 31 + it).toByte() }

    /**
     * Upload a blob to [to] and return the key JSON a phone would send for it, with the
     * reveal fields set. Uses the real `encryptFile` and the real uploader, so what arrives
     * is a genuinely fetchable blob and not a fixture.
     */
    private fun layerKey(
        from: OshiClient,
        to: String,
        revealId: String,
        index: Int,
        count: Int,
        bytes: ByteArray,
    ): String {
        val enc = OSHICryptoV2.encryptFile(bytes, "photo.jpg", "image/jpeg")
        val blobId = from.blobs.uploadBlob(to, enc.chunks)
        assertNotNull("the fixture relay refused the blob", blobId)
        return String(
            V2FileKeyMessage(
                blobId = blobId!!,
                fileKey = enc.fileKey,
                fileNonce = enc.fileNonce,
                chunkCount = enc.chunks.size,
                manifest = enc.manifest,
                filename = "photo.jpg",
                mime = "image/jpeg",
                mediaType = "image",
                revealId = revealId,
                revealLayerIndex = index,
                revealLayerCount = count,
                revealTotalMs = 10_000,
            ).toBytes(),
            Charsets.UTF_8,
        )
    }

    @Test
    fun `layers sharpen ONE row instead of making a row each`() {
        val phone = fx.client("phone")
        val desk = fx.client("desk")
        val revealId = "11111111-2222-3333-4444-555555555555"

        // Layer 0 — the carrier. An ordinary photo row, and its id is the revealId so the
        // layers that follow can find it (iOS's rule).
        fx.inbound(desk, phone.address, layerKey(phone, desk.address, revealId, 0, 3, layerBytes(0, 64)))
        val carrier = desk.history(phone.address).single()
        assertEquals("the carrier must be addressable by revealId", revealId, carrier.id)
        assertNotNull(carrier.mediaRef)
        val firstRef = carrier.mediaRef

        // Layers 1 and 2 — the same photograph, sharper. NO new rows.
        fx.inbound(desk, phone.address, layerKey(phone, desk.address, revealId, 1, 3, layerBytes(1, 256)))
        assertEquals(
            "a later layer became its own row — the same photo twice",
            1, desk.history(phone.address).size,
        )
        val afterOne = desk.history(phone.address).single().mediaRef
        assertNotNull(afterOne)
        assertTrue("the bytes on show must have been replaced", afterOne != firstRef)

        fx.inbound(desk, phone.address, layerKey(phone, desk.address, revealId, 2, 3, layerBytes(2, 1024)))
        assertEquals(1, desk.history(phone.address).size)
        val afterTwo = desk.history(phone.address).single().mediaRef
        assertTrue(afterTwo != afterOne)

        // And the row is still the photo it always was.
        val final = desk.history(phone.address).single()
        assertEquals(com.oshi.desktop.store.MediaType.IMAGE, final.mediaType)
        assertEquals(revealId, final.id)
    }

    /**
     * A LAYER WITH NO CARRIER IS DROPPED. iOS's reason, in its own words: rather than
     * fabricate an orphan bubble that would show a blurry image for ever, because the
     * sharper layers have nowhere to land.
     */
    @Test
    fun `a layer whose carrier never arrived is dropped, not stored`() {
        val phone = fx.client("phone")
        val desk = fx.client("desk")
        val before = desk.mediaDir.listFiles()?.size ?: 0
        fx.inbound(
            desk, phone.address,
            layerKey(phone, desk.address, "never-sent-layer-zero", 2, 4, layerBytes(2, 128)),
        )
        assertTrue("an orphan layer became a bubble", desk.history(phone.address).isEmpty())
        // AND THE BLOB IS NOT FETCHED. The carrier is checked BEFORE the download, and this
        // is the assertion that makes that ordering mean something: `replaceMedia` already
        // refuses an unknown row, so without this a mutation that removed the pre-check left
        // every test green while the client still downloaded and decrypted bytes it had no
        // use for — airtime, disk and CPU spent on a blob whose size a stranger chose.
        assertEquals(
            "an orphan layer's blob was downloaded anyway",
            before, desk.mediaDir.listFiles()?.size ?: 0,
        )
    }

    /**
     * A MALFORMED PLAN IS NOT A REVEAL, and must fall back to being an ordinary photo rather
     * than being dropped: the bytes are a real picture somebody sent. The bounds are iOS's.
     */
    @Test
    fun `an invalid layer count is treated as an ordinary photo`() {
        val phone = fx.client("phone")
        val desk = fx.client("desk")
        // count = 1 is not a reveal; count = 65 is past the ceiling that stops a peer
        // deciding how many blobs this client fetches for one message.
        fx.inbound(desk, phone.address, layerKey(phone, desk.address, "r1", 0, 1, layerBytes(0, 64)))
        fx.inbound(desk, phone.address, layerKey(phone, desk.address, "r2", 0, 65, layerBytes(0, 64)))
        val rows = desk.history(phone.address)
        assertEquals("both must arrive as normal photos", 2, rows.size)
        assertTrue("and must NOT be keyed by revealId", rows.none { it.id == "r1" || it.id == "r2" })
        rows.forEach { assertNotNull(it.mediaRef) }
    }

    /** An index at or past the count is nonsense and must not address the carrier. */
    @Test
    fun `an index past the count is refused`() {
        val phone = fx.client("phone")
        val desk = fx.client("desk")
        fx.inbound(desk, phone.address, layerKey(phone, desk.address, "r3", 0, 2, layerBytes(0, 64)))
        assertEquals(1, desk.history(phone.address).size)
        // index 5 of 2 — neither a carrier nor a valid layer, so it lands as an ordinary photo
        // rather than silently rewriting the carrier.
        fx.inbound(desk, phone.address, layerKey(phone, desk.address, "r3", 5, 2, layerBytes(5, 64)))
        assertEquals("a nonsensical index must not replace the carrier's bytes", 2, desk.history(phone.address).size)
    }

    /** An ordinary photo with no reveal fields is completely unaffected. */
    @Test
    fun `a plain photo still arrives as before`() {
        val phone = fx.client("phone")
        val desk = fx.client("desk")
        val dir = java.nio.file.Files.createTempDirectory("oshi-plain").toFile()
        try {
            val f = java.io.File(dir, "plain.jpg").apply { writeBytes(ByteArray(512) { it.toByte() }) }
            assertEquals(OshiClient.SendOutcome.SENT, phone.sendFile(desk.address, f, com.oshi.desktop.store.MediaType.IMAGE))
            assertEquals(1, desk.router.poll())
            val row = desk.history(phone.address).single()
            assertNotNull(row.mediaRef)
            // The id is the envelope's, NOT a revealId: nothing about the reveal path may
            // touch a photo that carries no reveal fields.
            assertTrue(row.id.isNotEmpty())
            assertEquals(com.oshi.desktop.store.MediaType.IMAGE, row.mediaType)
        } finally {
            dir.deleteRecursively()
        }
    }
}
