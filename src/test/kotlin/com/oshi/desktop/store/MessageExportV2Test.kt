package com.oshi.desktop.store

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.app.MessageBackup
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * __EXPORT_V2_INTEROP_2026_09_22__ The platform-neutral export payload (version 2).
 *
 * The fixture `docs/fixtures/export_v2_sample.json` is the SAME file OSHI-Android's
 * `MessageExportV2Test` and the iOS storage self-test parse: all three mapping it to their
 * native models is what proves an export from any platform imports on any other.
 */
class MessageExportV2Test {

    private val dir: File = Files.createTempDirectory("oshi-export-v2").toFile()

    @After
    fun tearDown() { dir.deleteRecursively() }

    private val katPriv = ByteArray(32) { (it + 1).toByte() }
    private val me = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw="
    private val bob = "gbY32PzSxtpjWeaWMROhFw3nleS3JbhNHgtM/Z7FjOk="
    private val carol = "TCbZB0wn2J7eWScMCsFLceBxsVI5UZ91R0svO6Y0gfU="
    private val gid = "5B0E7C1A-3F2D-4E8B-9A61-2C7D4F0B8E13"
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
    )

    private fun fixture(): String {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "docs/fixtures/export_v2_sample.json")
            if (f.isFile) return f.readText()
            d = d.parentFile
        }
        error("docs/fixtures/export_v2_sample.json not found above ${System.getProperty("user.dir")}")
    }

    private fun kat(): DesktopIdentity {
        val ed = Ed25519PrivateKeyParameters(java.security.SecureRandom())
        return DesktopIdentity(
            OSHICryptoV2.X25519Pair(katPriv.copyOf(), OSHICryptoV2.x25519PubFromPriv(katPriv)),
            ed.encoded, ed.generatePublicKey().encoded,
        )
    }

    @Test
    fun `shared fixture parses and maps to desktop messages`() {
        assertEquals(me, Base64.getEncoder().encodeToString(OSHICryptoV2.x25519PubFromPriv(katPriv)))
        val parsed = ExportV2.parse(JSONObject(fixture()))
        assertEquals(8, parsed.payload.messages.size)
        assertEquals("undeclared conversation", 1, parsed.invalid)
        assertEquals("unknown conversation kind", 1, parsed.skippedUnknownKind)
        assertEquals(listOf("Bob", "Carol"), parsed.payload.contacts.map { it.alias })
        assertEquals(me, parsed.payload.accountPublicKey)

        val mapped = DesktopExportV2(me, groupExists = { it == gid }).toNative(parsed.payload)
        assertEquals(0, mapped.invalid)
        assertEquals(0, mapped.skippedGroups)
        val byId = mapped.messages.associateBy { it.id }
        assertEquals(8, byId.size)

        val m1 = byId.getValue("6F1C2A3B-0000-4000-8000-000000000001")
        assertEquals(bob, m1.conversationId)
        assertTrue(m1.fromMe)
        assertEquals(me, m1.senderAddress)
        assertEquals(bob, m1.recipientAddress)
        assertEquals("Hello Bob", m1.content)
        assertEquals(DeliveryStatus.READ, m1.deliveryStatus)
        assertEquals(1_789_891_200_000L, m1.sentAtMs) // 2026-09-20T08:00:00.000Z

        val m2 = byId.getValue("6f1c2a3b-0000-4000-8000-000000000002")
        assertEquals("Hi! été 👋", m2.content)
        assertEquals(bob, m2.senderAddress)
        assertEquals(me, m2.recipientAddress)
        assertEquals("6F1C2A3B-0000-4000-8000-000000000001", m2.replyToId)
        assertEquals(1_789_891_290_250L, m2.sentAtMs)

        val m3 = byId.getValue("6F1C2A3B-0000-4000-8000-000000000003")
        assertEquals(MediaType.IMAGE, m3.mediaType)
        assertEquals("dot.png", m3.content)

        val m4 = byId.getValue("6F1C2A3B-0000-4000-8000-000000000004")
        assertEquals("See you at 10", m4.editedContent)
        assertEquals(1_789_891_440_000L, m4.editedAtMs)

        val m5 = byId.getValue("6F1C2A3B-0000-4000-8000-000000000005")
        assertTrue(m5.isDeletedForEveryone)
        assertNull(m5.content)

        val m6 = byId.getValue("6F1C2A3B-0000-4000-8000-000000000006")
        assertTrue(m6.isViewOnce)
        assertTrue(m6.viewOnceOpened)
        assertEquals(DeliveryStatus.FAILED, m6.deliveryStatus)
        assertEquals(MediaType.VIDEO, m6.mediaType)

        val g1 = byId.getValue("7A000000-0000-4000-8000-000000000001")
        assertEquals(gid, g1.conversationId)
        assertEquals(carol, g1.senderAddress)
        assertEquals(mapOf("👍" to setOf(me)), g1.reactions)
        val g2 = byId.getValue("7A000000-0000-4000-8000-000000000002")
        assertTrue(g2.fromMe)
        assertEquals(gid, g2.recipientAddress)

        // Without the group on this machine its messages are skipped, not orphaned.
        val noGroup = DesktopExportV2(me, groupExists = { false }).toNative(parsed.payload)
        assertEquals(6, noGroup.messages.size)
        assertEquals(2, noGroup.skippedGroups)
    }

    @Test
    fun `fixture sealed for the account imports through MessageBackup, with media and dedupe`() {
        val source = File(dir, "fixture.oshiexport")
        EncryptedMessageExport.writeSealed(katPriv, fixture().toByteArray(Charsets.UTF_8), source)
        val store = MessageStore(File(dir, "store"), ByteArray(32) { 3 })
        // Already here under the lower-cased id an Android/desktop peer would have stored.
        store.append(Message(
            id = "6f1c2a3b-0000-4000-8000-000000000001", conversationId = bob, senderAddress = me,
            recipientAddress = bob, fromMe = true, content = "local copy", sentAtMs = 1_789_891_200_000L,
            sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
        ))
        val contacts = ContactStore(File(dir, "contacts.json"))
        val groups = com.oshi.desktop.app.GroupStore(File(dir, "groups.json"))
        val media = File(dir, "media")
        val backup = MessageBackup(kat(), store, contacts, groups, media)

        val r = backup.import(source)
        assertEquals("m2..m6 (group not held)", 5, r.imported)
        assertEquals(1, r.alreadyPresent)
        assertEquals(1, r.invalid)
        assertEquals(2, r.skippedGroups)
        assertEquals(1, r.skippedUnknown)
        assertEquals("local copy", store.messages(bob).first().content)
        val m3 = store.message(bob, "6F1C2A3B-0000-4000-8000-000000000003")!!
        assertArrayEquals(png, File(m3.mediaRef!!).readBytes())
        assertEquals("Bob", contacts.get(bob)!!.displayName)

        // Idempotent.
        val again = backup.import(source)
        assertEquals(0, again.imported)
        assertEquals(6, again.alreadyPresent)
    }

    @Test
    fun `round trip - desktop messages to v2 and back keep every mapped field`() {
        val mediaFile = File(dir, "a1b2c3d4-photo.png").also { it.writeBytes(png) }
        val originals = listOf(
            Message(id = "d1", conversationId = bob, senderAddress = me, recipientAddress = bob, fromMe = true,
                content = "hello", sentAtMs = 1_789_891_200_123L, sentAtSource = TimestampSource.LOCAL_CLOCK,
                deliveryStatus = DeliveryStatus.DELIVERED, replyToId = "d0"),
            Message(id = "d2", conversationId = bob, senderAddress = bob, recipientAddress = me, fromMe = false,
                content = "photo.png", mediaType = MediaType.IMAGE, mediaRef = mediaFile.absolutePath,
                sentAtMs = 1_789_891_201_000L, sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
                deliveryStatus = DeliveryStatus.READ, reactions = mapOf("❤️" to setOf(me))),
            Message(id = "d3", conversationId = bob, senderAddress = me, recipientAddress = bob, fromMe = true,
                content = "typo", editedContent = "fixed", editedAtMs = 1_789_891_300_000L,
                sentAtMs = 1_789_891_202_000L, sentAtSource = TimestampSource.LOCAL_CLOCK, deliveryStatus = DeliveryStatus.SENT),
            Message(id = "d4", conversationId = bob, senderAddress = bob, recipientAddress = me, fromMe = false,
                content = null, isDeletedForEveryone = true,
                sentAtMs = 1_789_891_203_000L, sentAtSource = TimestampSource.RELAY_ENVELOPE_MS, deliveryStatus = DeliveryStatus.DELIVERED),
            Message(id = "G1", conversationId = gid, senderAddress = carol, recipientAddress = gid, fromMe = false,
                content = "group line", sentAtMs = 1_789_891_204_000L, sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
                deliveryStatus = DeliveryStatus.DELIVERED),
            Message(id = "b1", conversationId = "bot!x", senderAddress = "bot!x", recipientAddress = me, fromMe = false,
                content = "bot says", sentAtMs = 1_789_891_205_000L, sentAtSource = TimestampSource.LOCAL_CLOCK,
                deliveryStatus = DeliveryStatus.DELIVERED),
        )
        val stored = mutableMapOf<String, ByteArray>()
        val mapper = DesktopExportV2(
            selfAddress = me,
            groupName = { if (it == gid) "Team" else null },
            mediaWriter = { id, _, bytes -> stored[id] = bytes; "mem:$id" },
        )
        val built = mapper.build(originals, listOf(ExportV2.Contact(bob, "Bob")), 1_758_400_000_000L)
        val json = JSONObject(String(ExportV2.encode(built.payload), Charsets.UTF_8))
        assertEquals(2, json.getInt("version"))
        assertEquals("desktop", json.getString("platform"))
        assertEquals("2026-09-20T08:00:00.123Z", json.getJSONArray("messages").getJSONObject(0).getString("timestamp"))
        assertEquals("Team", json.getJSONArray("conversations").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("kind") == "group" }.getString("groupName")
        })

        val back = mapper.toNative(ExportV2.parse(json).payload).messages.associateBy { it.id }
        assertEquals(originals.size, back.size)
        for (o in originals) {
            val b = back.getValue(o.id)
            assertEquals(o.id, o.conversationId, b.conversationId)
            assertEquals(o.id, o.fromMe, b.fromMe)
            assertEquals(o.id, o.senderAddress, b.senderAddress)
            assertEquals(o.id, o.content, b.content)
            assertEquals(o.id, o.mediaType, b.mediaType)
            assertEquals(o.id, o.replyToId, b.replyToId)
            assertEquals(o.id, o.sentAtMs, b.sentAtMs)
            assertEquals(o.id, o.deliveryStatus, b.deliveryStatus)
            assertEquals(o.id, o.editedContent, b.editedContent)
            assertEquals(o.id, o.editedAtMs, b.editedAtMs)
            assertEquals(o.id, o.isDeletedForEveryone, b.isDeletedForEveryone)
            assertEquals(o.id, o.reactions, b.reactions)
        }
        assertArrayEquals(png, stored.getValue("d2"))
        assertEquals("mem:d2", back.getValue("d2").mediaRef)
    }
}
