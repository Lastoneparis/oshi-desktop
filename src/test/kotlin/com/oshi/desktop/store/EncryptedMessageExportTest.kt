package com.oshi.desktop.store

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.app.MessageBackup
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64

/**
 * __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ The `.oshiexport` container, and the at-rest
 * migration it sits beside.
 *
 * The known-answer vector is the one iOS CryptoKit produced; matching it byte for byte is
 * what proves an iPhone and this client agree on key derivation, account tag, AAD and layout.
 */
class EncryptedMessageExportTest {

    private val dir: File = Files.createTempDirectory("oshi-export-test").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ------------------------------------------------------------------ known answer (iOS)

    private val katPriv = ByteArray(32) { (it + 1).toByte() } // 0x01..0x20
    private val katSalt = ByteArray(32) { 0xAA.toByte() }
    private val katNonce = ByteArray(12) { 0xBB.toByte() }
    private val katPlain =
        """{"version":1,"platform":"test","exportedAt":"2026-09-22T00:00:00Z","messages":[]}""".toByteArray(Charsets.UTF_8)
    private val katFile =
        "4f5348494558503101aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa8fff703b50b22" +
            "bbbbbbbbbbbbbbbbbbbbbbbbb5256d95a7583fe347ae099e632f327a065babf433d21ae321c2cca3b3c87344c0dfd509" +
            "ba4005fea6781e0d0f94e4061ac4d6dd81801b98b91711d4b251588db789dcfa457f2c295642ecbe81c3de90fec87a1db" +
            "76fc2ce593f558f02762a7963"

    @Test
    fun `known answer - public key, key, account tag and file match iOS CryptoKit`() {
        val pub = OSHICryptoV2.x25519PubFromPriv(katPriv)
        assertEquals("07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c", hex(pub))
        assertEquals(
            "6702997f760f73d1f44545bb52cbc46cffa1c498ac905fde28a2aab9be0fbb28",
            hex(EncryptedMessageExport.deriveKey(katPriv, katSalt)),
        )
        assertEquals("aaa8fff703b50b22", hex(EncryptedMessageExport.accountTag(pub)))
        assertEquals(katFile, hex(EncryptedMessageExport.seal(katPriv, katPlain, katSalt, katNonce)))
    }

    @Test
    fun `known answer - the iOS file opens here, and is refused as another platform's schema`() {
        assertArrayEquals(katPlain, EncryptedMessageExport.open(katPriv, unhex(katFile)))
        try {
            EncryptedMessageExport.read(katPriv, File(dir, "k.oshiexport").also { it.writeBytes(unhex(katFile)) })
            fail("a platform:test payload must not import as desktop messages")
        } catch (e: MessageExportException) {
            assertEquals(MessageExportException.Kind.OTHER_PLATFORM, e.kind)
            assertEquals("test", e.platform)
        }
    }

    // ------------------------------------------------------------------ round trip & refusals

    private val baseMs = 1_770_000_000_000L

    private fun msg(id: String, conv: String, content: String, at: Long = 0L) = Message(
        id = id, conversationId = conv, senderAddress = conv, recipientAddress = "me", fromMe = false,
        content = content, sentAtMs = baseMs + at, sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
        deliveryStatus = DeliveryStatus.READ, reactions = mapOf("👍" to setOf("me")),
    )

    private fun identity(): DesktopIdentity {
        val ed = Ed25519PrivateKeyParameters(java.security.SecureRandom())
        return DesktopIdentity(OSHICryptoV2.generateX25519(), ed.encoded, ed.generatePublicKey().encoded)
    }

    @Test
    fun `round trip - export from one store, import into a fresh one on the same account`() {
        val account = identity()
        val source = MessageStore(File(dir, "a"), ByteArray(32) { 1 })
        source.append(msg("m1", "peer-a", "first secret line", 1))
        source.append(msg("m2", "peer-a", "second", 2))
        source.append(msg("g1", "group-1", "group line", 3))

        val result = MessageBackup(account, source).export(File(dir, "out"))
        assertTrue(result.file.name.endsWith(".oshiexport"))
        assertEquals(3, result.messages)
        val bytes = result.file.readBytes()
        assertEquals("OSHIEXP1", String(bytes, 0, 8, Charsets.US_ASCII))
        assertFalse("plaintext leaked into the export", String(bytes, Charsets.ISO_8859_1).contains("first secret line"))
        assertEquals(0, dir.walk().count { it.isFile && it.readBytes().toString(Charsets.ISO_8859_1).contains("first secret line") })

        // A different machine: different history key, same account.
        val target = MessageStore(File(dir, "b"), ByteArray(32) { 2 })
        target.append(msg("m2", "peer-a", "local copy wins", 2))
        val imported = MessageBackup(account, target).import(result.file)
        assertEquals(2, imported.imported)
        assertEquals(1, imported.alreadyPresent)
        assertEquals(0, imported.invalid)

        val reopened = MessageStore(File(dir, "b"), ByteArray(32) { 2 })
        assertEquals(listOf("first secret line", "local copy wins"), reopened.messages("peer-a").map { it.content })
        assertEquals("group line", reopened.messages("group-1").single().content)
        assertEquals(DeliveryStatus.READ, reopened.message("peer-a", "m1")!!.deliveryStatus)
        assertEquals(mapOf("👍" to setOf("me")), reopened.message("peer-a", "m1")!!.reactions)

        // Importing twice is a no-op.
        assertEquals(0, MessageBackup(account, reopened).import(result.file).imported)

        // The payload is the documented shape.
        val plain = JSONObject(String(EncryptedMessageExport.open(account.identity.priv, bytes), Charsets.UTF_8))
        assertEquals(2, plain.getInt("version")) // __EXPORT_V2_INTEROP_2026_09_22__ neutral payload
        assertEquals("desktop", plain.getString("platform"))
        Instant.parse(plain.getString("exportedAt"))
        assertEquals(3, plain.getJSONArray("messages").length())
    }

    @Test
    fun `wrong account is rejected by tag, before any decryption`() {
        val file = File(dir, "x.oshiexport")
        EncryptedMessageExport.write(identity().identity.priv, listOf(msg("m1", "p", "hi")), file)
        try {
            EncryptedMessageExport.read(identity().identity.priv, file)
            fail("another account's export must be refused")
        } catch (e: MessageExportException) {
            assertEquals(MessageExportException.Kind.OTHER_ACCOUNT, e.kind)
            assertTrue(e.userMessage().contains("another OSHI account"))
        }
    }

    @Test
    fun `any flipped byte is rejected`() {
        val account = identity()
        val sealed = EncryptedMessageExport.seal(account.identity.priv, EncryptedMessageExport.buildPayload(listOf(msg("m1", "p", "hi")), Instant.now()))
        // Salt (AAD), nonce, ciphertext, tag: each must fail authentication.
        for (index in listOf(9, 20, EncryptedMessageExport.HEADER_BYTES, EncryptedMessageExport.HEADER_BYTES + 12, sealed.size / 2 + 30, sealed.size - 1)) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            try {
                EncryptedMessageExport.open(account.identity.priv, tampered)
                fail("flipped byte at $index was accepted")
            } catch (e: MessageExportException) {
                assertEquals("byte $index", MessageExportException.Kind.CORRUPTED, e.kind)
            }
        }
        // The account tag is in the AAD too, but it is checked first: flipping it reads as another account.
        val tagFlip = sealed.copyOf().also { it[45] = (it[45].toInt() xor 0x01).toByte() }
        try { EncryptedMessageExport.open(account.identity.priv, tagFlip); fail() } catch (e: MessageExportException) {
            assertEquals(MessageExportException.Kind.OTHER_ACCOUNT, e.kind)
        }
        val badMagic = sealed.copyOf().also { it[0] = 'X'.code.toByte() }
        try { EncryptedMessageExport.open(account.identity.priv, badMagic); fail() } catch (e: MessageExportException) {
            assertEquals(MessageExportException.Kind.NOT_AN_EXPORT, e.kind)
        }
        val badVersion = sealed.copyOf().also { it[8] = 2 }
        try { EncryptedMessageExport.open(account.identity.priv, badVersion); fail() } catch (e: MessageExportException) {
            assertEquals(MessageExportException.Kind.UNSUPPORTED_VERSION, e.kind)
        }
    }

    @Test
    fun `the recovery key's first 32 bytes are the export key's IKM, as on iOS`() {
        val account = identity()
        val recovery = Base64.getDecoder().decode(com.oshi.desktop.store.IdentityStore.exportRecoveryKey(account))
        assertEquals(64, recovery.size)
        assertArrayEquals(account.identity.priv, recovery.copyOfRange(0, 32))
        val restored = com.oshi.desktop.store.IdentityStore.parseRecoveryKey(Base64.getEncoder().encodeToString(recovery))
        val file = File(dir, "r.oshiexport")
        EncryptedMessageExport.write(account.identity.priv, listOf(msg("m1", "p", "hi")), file)
        assertEquals("hi", EncryptedMessageExport.read(restored.identity.priv, file).messages.single().content)
    }

    // ------------------------------------------------------------------ at-rest migration

    @Test
    fun `at-rest migration - plaintext journal becomes encrypted and reloads equal to the original`() {
        val journals = File(dir, "messages")
        val plain = MessageStore(journals)
        val originals = listOf(msg("m1", "peer-a", "clear one", 1), msg("m2", "peer-a", "clear two", 2), msg("m3", "peer-b", "clear three", 3))
        originals.forEach { plain.append(it) }
        val before = plain.snapshot().messages
        assertTrue(journals.listFiles()!!.any { it.readText().contains("clear one") })

        val key = ByteArray(32) { 7 }
        MessageStore(journals, key).conversations() // opening migrates
        val files = journals.listFiles()!!.filter { it.isFile }
        assertEquals("no temp file may survive the migration", 2, files.size)
        files.forEach { f ->
            assertFalse("plaintext left in ${f.name}", listOf("clear one", "clear two", "clear three").any { f.readText().contains(it) })
            f.readLines().filter { it.isNotBlank() }.forEach { assertTrue(JSONObject(it).has("ct")) }
        }
        assertEquals(before, MessageStore(journals, key).snapshot().messages)
    }

    @Test
    fun `at-rest migration never folds away rows sealed under another key`() {
        val journals = File(dir, "messages")
        val otherKey = ByteArray(32) { 9 }
        MessageStore(journals, otherKey).append(msg("sealed", "peer-a", "under another key", 1))
        // A legacy plaintext row lands in the same journal (e.g. an old build ran on it).
        MessageStore(journals).append(msg("legacy", "peer-a", "plain row", 2))
        val file = journals.listFiles()!!.single()
        val before = file.readText()

        MessageStore(journals, ByteArray(32) { 7 }).messages("peer-a")
        assertEquals("the journal must be left untouched, not rewritten without the sealed row", before, file.readText())
    }
}
