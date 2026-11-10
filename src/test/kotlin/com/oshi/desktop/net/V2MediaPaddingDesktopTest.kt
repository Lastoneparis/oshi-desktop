package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.PrekeyStore
import com.oshi.desktop.store.SessionStore
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHICryptoV2Streaming
import com.oshi.messenger.network.v2.V2FileKeyMessage
import com.oshi.messenger.network.v2.V2Padding
import com.oshi.messenger.network.v2.V2PaddingPeers
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * __MEDIA_PADDING_2026_11_10__ Padmé padding of v2 media on the desktop
 * (docs/MEDIA_PADDING_AND_OHTTP_MEDIA_SPEC.md). The desktop COMPILES Android's
 * `OSHICryptoV2.kt` / `OSHICryptoV2Streaming.kt` / `V2Padding.kt`; the vectors below are the
 * spec's, pinned identically by Android `V2MediaPaddingTest` and by iOS.
 */
class V2MediaPaddingDesktopTest {

    private val dir: File = Files.createTempDirectory("oshi-media-padding-test").toFile()
    private val relay = RelayServer()

    @Before fun reset() = V2PaddingPeers.clearForTesting()

    @After
    fun tearDown() {
        relay.close()
        dir.deleteRecursively()
        V2PaddingPeers.clearForTesting()
    }

    private inner class Client(name: String) {
        val home = File(dir, name).apply { mkdirs() }
        val vault = KeyVault.open(File(home, KeyVault.FILE_NAME), InMemorySecretStore())
        val identity: DesktopIdentity = IdentityStore.loadOrCreate(vault)
        val prekeys = PrekeyStore(vault)
        val sessions = SessionStore(vault)
        val http = V2Http(DesktopV2Signer(identity), relay.baseUrl)
        val keys = V2KeysClient(http, identity, prekeys)
        val config = V2ConfigGate(baseUrl = relay.baseUrl, buildNumber = 1)
        val state = RouterState(File(home, RouterState.FILE_NAME))
        val router = V2Router(identity, config, keys, V2MessagesClient(http), sessions, prekeys, state)
        val inbox = mutableListOf<V2Inbound>()

        init {
            router.onMessage = { inbox.add(it) }
            config.refresh(identity.userKey)
            assertTrue(router.publishBundleIfNeeded())
        }

        val address: String get() = identity.userKey
    }

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(8) { (0xa0 + it).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    // ---------------------------------------------------------------- shared vectors

    @Test
    fun `Padme vectors`() {
        val v = linkedMapOf(
            0L to 4096L, 1L to 4096L, 4096L to 4096L, 4097L to 4352L, 10000L to 10240L,
            65537L to 67584L, 100000L to 100352L, 1000000L to 1015808L, 1048576L to 1048576L,
            2097153L to 2162688L, 5243003L to 5373952L, 100000000L to 100663296L,
            209715199L to 209715200L, 209715200L to 209715200L,
        )
        for ((l, p) in v) assertEquals("L=$l", p, OSHICryptoV2.mediaPaddedSize(l))
        assertEquals(8, V2Padding.CAP_MEDIA_PADDING)
        assertTrue((V2ConfigGate.MY_CAPS and V2Padding.CAP_MEDIA_PADDING) != 0)
    }

    @Test
    fun `byte-exact vector - small file`() {
        val data = "héllo 👋".toByteArray(Charsets.UTF_8)
        val enc = OSHICryptoV2.encryptFile(data, "a.txt", "text/plain", reuseFileKey = key, reuseFileNonce = nonce, pad = true)
        assertEquals(
            "{\"filename\":\"a.txt\",\"mime\":\"text/plain\",\"size\":11,\"chunkCount\":1,\"chunkSize\":2097152,\"paddedSize\":4096}",
            String(enc.infoJson, Charsets.UTF_8),
        )
        assertEquals(4112, enc.chunks.single().size)
        assertEquals("467e96605b69cdd2047136dd6640282f4f7e54efc66c61bd1a95e6f4b244502e", sha(enc.chunks[0]))
        assertEquals(119, enc.manifest.size)
        assertEquals("f66c94d1f2742ffc6742d4b4d97df162d7f89f4dea408c042d729be1cd3bed5d", sha(enc.manifest))
        assertArrayEquals(data, OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks, enc.manifest))
    }

    @Test
    fun `byte-exact vector - two chunks`() {
        val data = ByteArray(3145733) { 'a'.code.toByte() }
        val enc = OSHICryptoV2.encryptFile(data, "a.txt", "text/plain", reuseFileKey = key, reuseFileNonce = nonce, pad = true)
        assertEquals(listOf(2097168, 1114128), enc.chunks.map { it.size })
        assertEquals("a04c905db45c6098e7175d2ceea2b5f3793481f39900fcd011d7b9fe7f099df6", sha(enc.chunks[0] + enc.chunks[1]))
        val out = ByteArrayOutputStream()
        assertEquals(data.size.toLong(), OSHICryptoV2Streaming.decryptToStream(enc.fileKey, enc.fileNonce, enc.manifest, out, 2) { enc.chunks[it] })
        assertArrayEquals(data, out.toByteArray())
    }

    // ---------------------------------------------------------------- receive-side geometry

    @Test
    fun `the phantom-chunk guard counts chunks on the padded size`() {
        val padded = OSHICryptoV2Streaming.Manifest("f", "x", size = 11, chunkCount = 2, chunkSize = 2048, paddedSize = 4096)
        assertTrue(V2BlobClient.isPlausibleManifest(padded, 2, V2BlobClient.MAX_PLAINTEXT_BYTES.toLong()))
        assertFalse("1 chunk would hold 11 B, not 4096", V2BlobClient.isPlausibleManifest(padded.copy(chunkCount = 1), 1, V2BlobClient.MAX_PLAINTEXT_BYTES.toLong()))
        assertFalse("paddedSize < size", V2BlobClient.isPlausibleManifest(padded.copy(size = 5000, paddedSize = 4096, chunkCount = 2), 2, V2BlobClient.MAX_PLAINTEXT_BYTES.toLong()))
        assertFalse("paddedSize over the cap", V2BlobClient.isPlausibleManifest(padded, 2, 4095))
        val unpadded = padded.copy(paddedSize = null, chunkCount = 1)
        assertTrue(V2BlobClient.isPlausibleManifest(unpadded, 1, V2BlobClient.MAX_PLAINTEXT_BYTES.toLong()))
    }

    // ---------------------------------------------------------------- gating, end to end

    @Test
    fun `a current peer is padded, an old one is not`() {
        val alice = Client("alice")
        val bob = Client("bob")
        val old = Client("old")
        relay.capsOverride[old.address] = 6            // 09/11 build: message padding, not media
        assertFalse("nothing known yet", alice.router.mediaPaddingAllowed(bob.address))
        assertTrue(alice.router.ensureSession(bob.address))
        assertTrue(alice.router.ensureSession(old.address))
        assertTrue(alice.router.mediaPaddingAllowed(bob.address))
        assertFalse(alice.router.mediaPaddingAllowed(old.address))
        assertTrue(alice.router.groupMediaPaddingAllowed(listOf(bob.address, alice.address)))
        assertFalse("one old member pads nobody", alice.router.groupMediaPaddingAllowed(listOf(bob.address, old.address)))
    }

    /** Every delivery path — the account slot AND each listed device — must be confirmed. */
    @Test
    fun `account slot and every device must be capable`() {
        val alice = Client("alice")
        val bob = Client("bob")
        val d1 = "a".repeat(32)
        val d2 = "b".repeat(32)
        relay.devicesOverride[bob.address] = listOf(d1, d2)
        // Devices capable, account slot unknown ⇒ unpadded.
        V2PaddingPeers.noteBundle(bob.address, d1, 14)
        V2PaddingPeers.noteBundle(bob.address, d2, 14)
        assertFalse(alice.router.mediaPaddingAllowed(bob.address))
        // Account slot capable (bundle read), all devices capable ⇒ padded.
        assertTrue(alice.router.ensureSession(bob.address))
        assertTrue(alice.router.mediaPaddingAllowed(bob.address))
        // Account slot capable, one device old ⇒ unpadded.
        V2PaddingPeers.noteBundle(bob.address, d2, 6)
        assertFalse(alice.router.mediaPaddingAllowed(bob.address))
        // …and so is a group holding that member.
        assertFalse(alice.router.groupMediaPaddingAllowed(listOf(bob.address)))
        // Shared rule, directly.
        assertFalse("list unknown", V2PaddingPeers.mediaAllows(bob.address, null))
        assertTrue(V2PaddingPeers.mediaAllows(bob.address, listOf(d1)))
    }

    @Test
    fun `a padded file key message read by the peer proves the sender reads padded media`() {
        val alice = Client("alice")
        val bob = Client("bob")
        val enc = OSHICryptoV2.encryptFile("photo".toByteArray(), "p.jpg", "image/jpeg", pad = true)
        val msg = V2FileKeyMessage(
            blobId = "blob-1", fileKey = enc.fileKey, fileNonce = enc.fileNonce, chunkCount = enc.chunks.size,
            manifest = enc.manifest, filename = "p.jpg", mime = "image/jpeg",
        )
        assertTrue(alice.router.sendText(bob.address, msg.toBytes()))
        bob.router.poll()
        assertTrue(V2PaddingPeers.mediaSlotCapable(alice.address, null))
    }

    @Test
    fun `media padding never reaches the mesh or LoRa paths`() {
        val root = File("src/main/kotlin/com/oshi/desktop")
        assertTrue(root.isDirectory)
        val padders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            if (Regex("""encryptFile\([^)]*pad\s*=""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(t)) padders += f.name
            val rel = f.relativeTo(root).path
            if (rel.startsWith("mesh") || rel.startsWith("lora")) {
                assertFalse("$rel must not pad media", t.contains("CAP_MEDIA_PADDING") || t.contains("pad = "))
            }
        }
        assertEquals(listOf("OshiClient.kt"), padders.distinct())
    }

    @Test
    fun `non-zero padding is rejected by the shared readers`() {
        val sealed = "hello".toByteArray() + ByteArray(4091)
        sealed[4000] = 1
        val chunk = OSHICryptoV2.aesGcmSeal(key, OSHICryptoV2.chunkNonce(nonce, 0), sealed, ByteArray(0))
        val info = OSHICryptoV2.manifestJson("f", "x", 5, 1, OSHICryptoV2.DEFAULT_CHUNK_SIZE, 4096)
        val manifest = OSHICryptoV2.aesGcmSeal(key, OSHICryptoV2.chunkNonce(nonce, OSHICryptoV2.MANIFEST_COUNTER), info, ByteArray(0))
        try { OSHICryptoV2.decryptFile(key, nonce, listOf(chunk), manifest); fail() } catch (_: IllegalArgumentException) {}
        try { OSHICryptoV2Streaming.decryptToStream(key, nonce, manifest, ByteArrayOutputStream(), 1) { chunk }; fail() } catch (_: IllegalArgumentException) {}
    }
}
