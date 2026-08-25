package com.oshi.desktop.store

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 0.14 — contacts, and the blocked-contact rules from [ContactStore]'s
 * doc comment.
 */
class ContactStoreTest {

    private val dir: File = Files.createTempDirectory("oshi-contacts-test").toFile()
    private val file = File(dir, "contacts.json")
    private fun store(): ContactStore = ContactStore(file)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------------ durability

    @Test
    fun `contacts survive a reopen of the store`() {
        store().seen("addr-1", atMs = 1_000L, displayNameHint = "Alice")

        val reopened = store().get("addr-1")
        assertNotNull(reopened)
        assertEquals("Alice", reopened!!.displayName)
        assertEquals(1_000L, reopened.firstSeenMs)
    }

    // ------------------------------------------------------------------------ blocked contacts

    @Test
    fun `a blocked contact is excluded from visible but not deleted`() {
        val s = store()
        s.seen("addr-1", atMs = 1_000L, displayNameHint = "Alice")
        s.seen("addr-2", atMs = 1_000L, displayNameHint = "Bob")

        s.block("addr-1")

        assertEquals("blocked contacts must not appear in what a chat list shows",
            listOf("addr-2"), s.visible().map { it.address })
        assertEquals("but the row must still exist for a 'Blocked contacts' screen",
            setOf("addr-1", "addr-2"), s.all().map { it.address }.toSet())
        assertNotNull("blocking must not delete the contact", s.get("addr-1"))
        assertTrue(s.isBlocked("addr-1"))
    }

    @Test
    fun `unblocking is a pure reversal that loses nothing`() {
        val s = store()
        s.seen("addr-1", atMs = 1_000L, displayNameHint = "Alice")
        s.setVerification("addr-1", ContactStore.VerificationState.VERIFIED)
        s.block("addr-1")

        s.unblock("addr-1")

        val c = s.get("addr-1")!!
        assertFalse(c.blocked)
        assertTrue("addr-1" in s.visible().map { it.address })
        assertEquals("verification state must survive a block/unblock round trip",
            ContactStore.VerificationState.VERIFIED, c.verification)
        assertEquals("Alice", c.displayName)
    }

    @Test
    fun `deleting a contact is a distinct operation from blocking it`() {
        val s = store()
        s.seen("addr-1", atMs = 1_000L)
        s.block("addr-1")
        assertNotNull("blocking alone must never delete", s.get("addr-1"))

        s.delete("addr-1")
        assertNull("only an explicit delete removes the row", s.get("addr-1"))
    }

    // ------------------------------------------------------------------------ seen()

    @Test
    fun `first-seen never moves, last-seen only moves forward`() {
        val s = store()
        s.seen("addr-1", atMs = 1_000L)
        s.seen("addr-1", atMs = 5_000L)

        var c = s.get("addr-1")!!
        assertEquals(1_000L, c.firstSeenMs)
        assertEquals(5_000L, c.lastSeenMs)

        // An out-of-order arrival (mesh vs relay race) with an EARLIER timestamp must
        // not rewind lastSeen, and must never touch firstSeen either.
        s.seen("addr-1", atMs = 2_000L)
        c = s.get("addr-1")!!
        assertEquals(1_000L, c.firstSeenMs)
        assertEquals("lastSeen must not regress on an out-of-order arrival", 5_000L, c.lastSeenMs)
    }

    @Test
    fun `a display name hint never overwrites a name the user already set`() {
        val s = store()
        s.seen("addr-1", atMs = 1_000L, displayNameHint = "Alice")
        s.setDisplayName("addr-1", "My Friend Alice")

        s.seen("addr-1", atMs = 2_000L, displayNameHint = "Alice From Contact Card")

        assertEquals("My Friend Alice", s.get("addr-1")!!.displayName)
    }

    // ------------------------------------------------------------------------ verification placeholder

    @Test
    fun `verification defaults to unverified and only changes explicitly`() {
        val s = store()
        s.seen("addr-1", atMs = 1_000L)
        assertEquals(ContactStore.VerificationState.UNVERIFIED, s.get("addr-1")!!.verification)

        s.setVerification("addr-1", ContactStore.VerificationState.VERIFIED)
        assertEquals(ContactStore.VerificationState.VERIFIED, s.get("addr-1")!!.verification)
    }

    // ------------------------------------------------------------------------ format

    @Test
    fun `the file is a single JSON object a human can read`() {
        val s = store()
        s.seen("addr-1", atMs = 1_000L, displayNameHint = "Alice")

        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"addr-1\""))
        assertTrue(text.contains("\"Alice\""))
        // org.json round-trips it; if this throws, the format broke.
        org.json.JSONObject(text)
    }
}
