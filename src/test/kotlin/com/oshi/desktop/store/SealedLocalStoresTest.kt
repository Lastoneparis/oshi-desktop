package com.oshi.desktop.store

import com.oshi.desktop.app.GroupStore
import com.oshi.desktop.app.GroupStoreException
import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.scheduled.ScheduledMessage
import com.oshi.desktop.scheduled.ScheduledMessageStore
import com.oshi.desktop.scheduled.ScheduledStoreException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * __LOCAL_DATA_AT_REST_2026_09_22__ contacts.json, groups.json and scheduled-messages.json
 * sealed at rest: round trip, legacy migration, and every way a sealed file must REFUSE
 * rather than read as empty.
 */
class SealedLocalStoresTest {

    private val dir: File = Files.createTempDirectory("oshi-sealed-stores").toFile()
    private val root = ByteArray(32) { (it * 7 + 3).toByte() }
    private val contactsKey = LocalDataKeys.derive(root, LocalDataKeys.CONTACTS)
    private val groupsKey = LocalDataKeys.derive(root, LocalDataKeys.GROUPS)
    private val scheduledKey = LocalDataKeys.derive(root, LocalDataKeys.SCHEDULED)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun isSealedEnvelope(f: File) = SealedJsonFile.isEnvelope(JSONObject(f.readText()))

    // ------------------------------------------------------------------ keys

    @Test
    fun `subkeys are distinct per purpose and deterministic`() {
        assertFalse(contactsKey.contentEquals(groupsKey))
        assertFalse(groupsKey.contentEquals(scheduledKey))
        assertArrayEquals(contactsKey, LocalDataKeys.derive(root.copyOf(), LocalDataKeys.CONTACTS))
    }

    // ------------------------------------------------------------------ contacts

    @Test
    fun `contacts round trip sealed and no alias or address is on disk`() {
        val f = File(dir, "contacts.json")
        ContactStore(f, contactsKey).seen("addr-SECRET-1", 1_000L, "Alice Secretname")
        ContactStore(f, contactsKey).block("addr-SECRET-1")

        val text = f.readText()
        assertTrue(isSealedEnvelope(f))
        assertFalse("alias leaked", text.contains("Secretname"))
        assertFalse("address leaked", text.contains("addr-SECRET-1"))

        val c = ContactStore(f, contactsKey).get("addr-SECRET-1")!!
        assertEquals("Alice Secretname", c.displayName)
        assertTrue(c.blocked)
    }

    @Test
    fun `legacy plaintext contacts are migrated in place and nothing is lost`() {
        val f = File(dir, "contacts.json")
        ContactStore(f).apply { // null key = the old plaintext format
            seen("a", 1L, "Ann"); seen("b", 2L, "Bob"); block("b")
        }
        assertFalse(isSealedEnvelope(f))

        val migrated = ContactStore(f, contactsKey)
        assertEquals(listOf("a", "b"), migrated.all().map { it.address })
        assertTrue("opening with a key must seal the legacy file", isSealedEnvelope(f))
        assertFalse(f.readText().contains("Ann"))

        val reopened = ContactStore(f, contactsKey).all()
        assertEquals(listOf("Ann", "Bob"), reopened.map { it.displayName })
        assertTrue(reopened.single { it.address == "b" }.blocked)
        assertEquals("no temp file may survive", listOf("contacts.json"), dir.list()!!.sorted())
    }

    @Test
    fun `a write whose read-back does not verify leaves the previous file exactly as it was`() {
        // The legacy plaintext is the "previous file" a migration would replace.
        val f = File(dir, "contacts.json")
        ContactStore(f).seen("a", 1L, "Ann")
        val before = f.readBytes()

        val e = runCatching {
            SealedJsonFile.write(f, contactsKey, LocalDataKeys.CONTACTS, """{"v":1,"contacts":[]}""") { false }
        }.exceptionOrNull()
        assertTrue(e is SealedStoreException)
        assertArrayEquals("plaintext must be untouched after a failed upgrade", before, f.readBytes())
        assertEquals("the temp file must not survive", listOf("contacts.json"), dir.list()!!.sorted())
    }

    @Test
    fun `a sealed contacts file with no key refuses, and is never overwritten with an empty list`() {
        val f = File(dir, "contacts.json")
        ContactStore(f, contactsKey).seen("a", 1L, "Ann")
        val before = f.readBytes()

        val noKey = ContactStore(f)
        assertTrue(runCatching { noKey.all() }.exceptionOrNull() is ContactStoreException)
        assertTrue(runCatching { noKey.seen("z", 9L) }.exceptionOrNull() is ContactStoreException)
        assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun `wrong key, tampered ciphertext and a cross-store swap are all refused`() {
        val f = File(dir, "contacts.json")
        ContactStore(f, contactsKey).seen("a", 1L, "Ann")

        assertTrue(runCatching { ContactStore(f, ByteArray(32)).all() }.exceptionOrNull() is ContactStoreException)
        // The groups key opening a contacts envelope — a swapped file.
        assertTrue(runCatching { ContactStore(f, groupsKey).all() }.exceptionOrNull() is ContactStoreException)

        val o = JSONObject(f.readText())
        val ct = java.util.Base64.getDecoder().decode(o.getString("ct"))
        ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 1).toByte()
        f.writeText(o.put("ct", java.util.Base64.getEncoder().encodeToString(ct)).toString())
        val tampered = f.readBytes()
        assertTrue(runCatching { ContactStore(f, contactsKey).all() }.exceptionOrNull() is ContactStoreException)
        assertArrayEquals("a refused file is left as it is", tampered, f.readBytes())
    }

    @Test
    fun `same key but another store's purpose does not open`() {
        // Purpose is bound in the AAD as well as in the key derivation: with ONE key
        // reused for both, the envelope still refuses to open as the other store.
        val f = File(dir, "x.json")
        SealedJsonFile.write(f, contactsKey, LocalDataKeys.CONTACTS, """{"v":1,"contacts":[]}""")
        assertNotNull(SealedJsonFile.read(f, contactsKey, LocalDataKeys.CONTACTS))
        assertTrue(runCatching { SealedJsonFile.read(f, contactsKey, LocalDataKeys.GROUPS) }.exceptionOrNull() is SealedStoreException)
    }

    // ------------------------------------------------------------------ groups

    private fun group(id: String, name: String) = GroupDefinition(
        groupId = id,
        name = name,
        type = GroupType.COLLABORATIVE,
        adminPublicKey = "admin-key",
        members = listOf(GroupMember(publicKey = "admin-key", joinedAtUnixMillis = 1_700_000_000_000L, isAdmin = true)),
        createdAtUnixMillis = 1_700_000_000_000L,
        lastActivityUnixMillis = 1_700_000_000_000L,
        stateVersion = 1,
    )

    @Test
    fun `groups round trip sealed and migrate from plaintext`() {
        val f = File(dir, "groups.json")
        GroupStore(f).put(group("AAAA-1111", "Plaintext Club"))
        assertTrue(f.readText().contains("Plaintext Club"))

        val s = GroupStore(f, groupsKey)
        assertEquals("Plaintext Club", s.get("AAAA-1111")!!.name)
        assertTrue(isSealedEnvelope(f))
        assertFalse(f.readText().contains("Plaintext Club"))

        s.put(group("BBBB-2222", "Second Secret"))
        val reopened = GroupStore(f, groupsKey)
        assertEquals(setOf("Plaintext Club", "Second Secret"), reopened.all().map { it.name }.toSet())
        assertTrue(runCatching { GroupStore(f).all() }.exceptionOrNull() is GroupStoreException)
        assertTrue(runCatching { GroupStore(f, contactsKey).all() }.exceptionOrNull() is GroupStoreException)
    }

    // ------------------------------------------------------------------ scheduled

    private fun sched(id: String, body: String, at: Long) = ScheduledMessage(
        id = id, recipient = "peer", content = body, scheduledAtMs = at, createdAtMs = at - 10,
    )

    @Test
    fun `scheduled bodies are sealed, round trip and migrate`() {
        val f = File(dir, "scheduled-messages.json")
        ScheduledMessageStore(f).put(sched("m1", "unsent secret body", 2_000L))
        assertTrue(f.readText().contains("unsent secret body"))

        val s = ScheduledMessageStore(f, scheduledKey)
        assertEquals("unsent secret body", s.get("m1")!!.content)
        assertTrue(isSealedEnvelope(f))
        assertFalse(f.readText().contains("unsent secret body"))

        s.put(sched("m2", "second", 3_000L))
        assertTrue(s.cancel("m2"))
        val reopened = ScheduledMessageStore(f, scheduledKey)
        assertEquals(listOf("m1", "m2"), reopened.all().map { it.id })
        assertEquals(ScheduledMessage.Status.CANCELLED, reopened.get("m2")!!.status)
        assertTrue(runCatching { ScheduledMessageStore(f).all() }.exceptionOrNull() is ScheduledStoreException)
    }

    @Test
    fun `a legacy schedule with an unreadable row is NOT migrated, so the row is not destroyed`() {
        val f = File(dir, "scheduled-messages.json")
        f.writeText(
            """{"v":1,"messages":[""" +
                """{"id":"ok","recipient":"peer","content":"keep me","scheduledAtMs":2000,"createdAtMs":1000},""" +
                """{"id":"","recipient":"peer","content":"a row this build cannot parse"}]}"""
        )
        val before = f.readBytes()
        val s = ScheduledMessageStore(f, scheduledKey)
        assertEquals(listOf("ok"), s.all().map { it.id })
        assertArrayEquals("the upgrade would have made the skipped row's loss permanent", before, f.readBytes())
    }
}
