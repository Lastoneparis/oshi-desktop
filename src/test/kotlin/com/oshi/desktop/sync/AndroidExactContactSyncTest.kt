package com.oshi.desktop.sync

import com.oshi.desktop.store.ContactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * __ANDROID_EXACT_SYNC_2026_10_29__ The phones' contact sync, reproduced on purpose.
 *
 * [ContactSyncBlockTest] asserts this client's SAFER payload and its refusal to under-report
 * a block. This file asserts the opposite path, chosen deliberately for exact parity with
 * the shipped phones (PARITY.md row 0.24): the cost of that choice is that a sync can
 * UNBLOCK somebody, and a cost that nobody can see in a test is one that gets re-litigated
 * as a bug later. So it is pinned here, named for what it is.
 */
class AndroidExactContactSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): ContactStore = ContactStore(File(tmp.newFolder(), "contacts.json"))

    private val ALICE = "QWxpY2VBbGljZUFsaWNlQWxpY2VBbGljZUFsaWNlMTI="
    private val BOB = "Qm9iQm9iQm9iQm9iQm9iQm9iQm9iQm9iQm9iQm9iMTI="

    @Test
    fun `a blocked contact is OMITTED from the payload, as WHERE isBlocked = 0 omits it`() {
        val s = store()
        s.seen(ALICE, 1_000L, displayNameHint = "Alice")
        s.seen(BOB, 1_000L, displayNameHint = "Bob")
        s.block(ALICE, 2_000L)

        val records = ContactSyncRecord.exportAllAndroidExact(s, nowMs = 9_000L)

        assertEquals("only the non-blocked row survives the query", 1, records.size)
        assertEquals(BOB, records.single().publicKey)
        assertFalse("every emitted row is structurally not-blocked", records.single().blocked!!)
    }

    @Test
    fun `an unedited row is stamped NOW, so it wins almost any conflict`() {
        val s = store()
        s.seen(BOB, 1_000L, displayNameHint = "Bob")

        val records = ContactSyncRecord.exportAllAndroidExact(s, nowMs = 9_999L)

        assertEquals(
            "Android defaults an unstamped contact to System.currentTimeMillis() (:556)",
            9_999L, records.single().syncTimestampMs,
        )
    }

    @Test
    fun `THE COST - a sync UNBLOCKS a contact blocked on this device`() {
        // Device B blocked Alice. Device A merely never blocked her.
        val b = store()
        b.seen(ALICE, 1_000L, displayNameHint = "Alice")
        b.block(ALICE, 2_000L)
        assertTrue("precondition: Alice is blocked here", b.all().single { it.address == ALICE }.blocked)

        // A's payload: Alice not blocked there, so she rides with isBlocked=false and a
        // brand-new stamp that beats B's block stamp.
        val fromA = listOf(
            ContactSyncRecord.Record(
                publicKey = ALICE,
                alias = "Alice",
                blocked = false,
                verified = false,
                syncTimestampMs = 9_000L,
            )
        )

        ContactSyncRecord.applyTo(b, fromA, nowMs = 9_000L)

        assertFalse(
            "this is the documented consequence of exact parity with the phones: " +
                "B's block is gone after a sync it did not initiate",
            b.all().single { it.address == ALICE }.blocked,
        )
    }

    @Test
    fun `a block still survives a record that carries NO block decision`() {
        // Rule 3 is untouched: only a record that CARRIES a decision may change one. This is
        // what stops an alias-only archive from clearing a block by omission.
        val s = store()
        s.seen(ALICE, 1_000L, displayNameHint = "Alice")
        s.block(ALICE, 2_000L)

        val aliasOnly = listOf(
            ContactSyncRecord.Record(
                publicKey = ALICE,
                alias = "Alice Renamed",
                blocked = null,
                verified = null,
                syncTimestampMs = 9_000L,
            )
        )
        ContactSyncRecord.applyTo(s, aliasOnly, nowMs = 9_000L)

        assertTrue(
            "a record with no isBlocked key must not clear a block",
            s.all().single { it.address == ALICE }.blocked,
        )
    }
}
