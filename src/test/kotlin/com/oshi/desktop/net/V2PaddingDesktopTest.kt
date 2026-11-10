package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.PrekeyStore
import com.oshi.desktop.store.SessionStore
import com.oshi.messenger.network.v2.V2Padding
import com.oshi.messenger.network.v2.V2PaddingPeers
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * __V2_PADDING_2026_11_09__ Relay-path padding on the desktop, end to end through two real
 * routers and the in-process [RelayServer] — plus the SHARED VECTOR, asserted byte for byte
 * here, in Android `V2PaddingTest` and in iOS `OSHITests/V2PaddingTests.swift`.
 */
class V2PaddingDesktopTest {

    private val dir: File = Files.createTempDirectory("oshi-padding-test").toFile()
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

    private fun lastCiphertextLength(to: String): Int =
        Base64.getDecoder().decode(relay.queued(to).last().getString("ciphertext")).size

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ---------------------------------------------------------------- shared vector

    @Test
    fun `the shared cross-platform vector`() {
        val pt = "héllo 👋".toByteArray(Charsets.UTF_8)
        val p = V2Padding.pad(pt)
        assertEquals(256, p.size)
        assertEquals("ff500168c3a96c6c6f20f09f918b80", hex(p.copyOfRange(0, 15)))
        assertEquals("0c052d9de9ec87516729769b8157f701379a272b35818911dae78ad5b4cba84c",
            hex(MessageDigest.getInstance("SHA-256").digest(p)))
        assertArrayEquals(pt, (V2Padding.unpad(p) as V2Padding.Unpadded.Padded).bytes)
        val a253 = V2Padding.pad(ByteArray(253) { 0x61 })
        assertEquals(1024, a253.size)
        assertEquals("d16075e9b0dadb54e365d0163e2409bee1f5b5f54d3a9290ccadca898ceaa438",
            hex(MessageDigest.getInstance("SHA-256").digest(a253)))
        assertEquals(4, V2Padding.CAP_PADDING)
    }

    @Test
    fun `this build announces padding in its bundle`() {
        assertEquals(14, V2ConfigGate.MY_CAPS)   // __MEDIA_PADDING_2026_11_10__ + CAP_MEDIA_PADDING (8)
        assertTrue((V2ConfigGate.MY_CAPS and V2Padding.CAP_PADDING) != 0)
    }

    // ---------------------------------------------------------------- end to end

    @Test
    fun `two current clients pad both ways and read each other`() {
        val alice = Client("alice")
        val bob = Client("bob")
        assertTrue(alice.router.sendText(bob.address, "hi".toByteArray()))
        assertEquals("a 2-byte message travels as one 256-byte bucket", 256 + 16, lastCiphertextLength(bob.address))
        assertEquals(1, bob.router.poll())
        assertEquals("hi", bob.inbox.single().text)

        // Bob learnt from that padded first message that Alice strips padding: his reply is padded.
        assertTrue(bob.router.sendText(alice.address, "a somewhat longer reply, still one bucket".toByteArray()))
        assertEquals(256 + 16, lastCiphertextLength(alice.address))
        assertEquals(1, alice.router.poll())
        assertEquals("a somewhat longer reply, still one bucket", alice.inbox.single().text)

        // A long message lands in the next bucket and still reads back exactly.
        val long = "x".repeat(300)
        assertTrue(alice.router.sendText(bob.address, long.toByteArray()))
        assertEquals(1024 + 16, lastCiphertextLength(bob.address))
        bob.router.poll()
        assertEquals(long, bob.inbox.last().text)
    }

    @Test
    fun `an old peer (caps 2, today's desktop) receives exactly today's bytes`() {
        val alice = Client("alice")
        val old = Client("old")
        relay.capsOverride[old.address] = 2
        assertTrue(alice.router.sendText(old.address, "hi".toByteArray()))
        assertEquals("unpadded: plaintext + GCM tag", 2 + 16, lastCiphertextLength(old.address))
        old.router.poll()
        assertEquals("hi", old.inbox.single().text)
    }

    @Test
    fun `a bundle without any caps field is never padded`() {
        val alice = Client("alice")
        val old = Client("old")
        relay.capsOverride[old.address] = Int.MIN_VALUE
        assertTrue(alice.router.sendText(old.address, "hello".toByteArray()))
        assertEquals(5 + 16, lastCiphertextLength(old.address))
    }

    @Test
    fun `a new client still reads an unpadded message from an old one`() {
        val old = Client("old")
        val bob = Client("bob")
        relay.capsOverride[bob.address] = 2        // the OLD sender believes Bob is old → never pads
        assertTrue(old.router.sendText(bob.address, "from 1.4.3".toByteArray()))
        assertEquals(10 + 16, lastCiphertextLength(bob.address))
        bob.router.poll()
        assertEquals("from 1.4.3", bob.inbox.single().text)
    }

    @Test
    fun `a group with one old member pads nobody`() {
        val alice = Client("alice")
        val bob = Client("bob")
        val carol = Client("carol")
        relay.capsOverride[carol.address] = 2
        // Sessions first, so both members' bundles have been read.
        assertTrue(alice.router.ensureSession(bob.address))
        assertTrue(alice.router.ensureSession(carol.address))
        assertTrue(alice.router.groupPaddingAllowed(listOf(bob.address)))
        val gate = alice.router.groupPaddingAllowed(listOf(bob.address, carol.address))
        assertFalse(gate)
        assertTrue(alice.router.sendText(bob.address, "group hi".toByteArray(), groupId = "g1", padGate = gate))
        assertEquals("bob is capable, but carol's copy would reveal the length anyway", 8 + 16, lastCiphertextLength(bob.address))
        assertTrue(alice.router.sendText(carol.address, "group hi".toByteArray(), groupId = "g1", padGate = gate))
        assertEquals(8 + 16, lastCiphertextLength(carol.address))
        bob.router.poll(); carol.router.poll()
        assertEquals("group hi", bob.inbox.single().text)
        assertEquals("group hi", carol.inbox.single().text)
    }

    // ---------------------------------------------------------------- no padding off the relay

    @Test
    fun `padding never reaches the mesh or LoRa paths`() {
        val root = File("src/main/kotlin/com/oshi/desktop")
        assertTrue("source tree not found from ${File(".").absolutePath}", root.isDirectory)
        val padders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            if (t.contains("V2Padding.pad(")) padders += f.name
            val rel = f.relativeTo(root).path
            if (rel.startsWith("mesh") || rel.startsWith("lora")) {
                assertFalse("$rel must not pad", t.contains("V2Padding"))
            }
        }
        assertEquals(listOf("V2Router.kt"), padders)
    }
}
