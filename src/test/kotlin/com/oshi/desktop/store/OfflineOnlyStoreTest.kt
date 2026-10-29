package com.oshi.desktop.store

import com.oshi.desktop.msg.OfflineOnlyPayload
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_OFFLINE_ONLY_2026_10_29__ The four rules this store's own header names — and
 * which nothing was holding it to.
 *
 * The class documents each as "a decision", and says of the first: *"This is the rule that
 * makes the count trustworthy, so it is the one worth breaking a test over."* There was no
 * test. Nothing in the test tree named `OfflineOnlyStore` at all, so every rule below was
 * an assertion in a comment.
 *
 * The rules are about a conversation restricted to the mesh and the radio, and about who
 * has echoed that back — so getting them wrong shows a user "3 confirmed" for a request
 * those three never saw.
 */
class OfflineOnlyStoreTest {

    private val dirs = ArrayList<File>()
    @After fun tearDown() { dirs.forEach { it.deleteRecursively() } }

    private fun store(): OfflineOnlyStore {
        val d = Files.createTempDirectory("offline-only").toFile().also { dirs += it }
        return OfflineOnlyStore(File(d, OfflineOnlyStore.FILE_NAME))
    }

    private val conv = "DwFvJQ1t3mZ8kYpR2sXnB7cLd4eHgTuV9wA0yC6iQfE="
    private val peer = "RuYUgKUpwc+5UCjv7gl0OxwdIwn2s/KS5DcvIxTzfTk="

    /** Rule 1 — the one the header says is worth breaking a test over. */
    @Test
    fun `turning it on again clears the confirmations`() {
        val s = store()
        s.set(on = true, conversationId = conv, atUnix = 100.0)
        assertTrue(s.noteConfirmation(peer, conv))
        assertEquals(1, s.confirmedCount(conv))

        s.set(on = true, conversationId = conv, atUnix = 200.0)
        assertEquals("a new request must be answered again", 0, s.confirmedCount(conv))
    }

    /** Rule 2 — a confirmation for a lifted restriction must not resurrect or inflate it. */
    @Test
    fun `a confirmation is ignored once the restriction is off`() {
        val s = store()
        s.set(on = true, conversationId = conv, atUnix = 100.0)
        s.set(on = false, conversationId = conv, atUnix = 110.0)
        assertFalse("a stale ack must not count", s.noteConfirmation(peer, conv))
        assertEquals(0, s.confirmedCount(conv))
    }

    @Test
    fun `the same peer confirming twice counts once`() {
        val s = store()
        s.set(on = true, conversationId = conv, atUnix = 100.0)
        assertTrue(s.noteConfirmation(peer, conv))
        assertFalse("the second one changed nothing, and the caller may skip a redraw",
            s.noteConfirmation(peer, conv))
        assertEquals(1, s.confirmedCount(conv))
    }

    /** Rule 3 — state AND stamp, with the tolerance a JSON round trip on another platform needs. */
    @Test
    fun `an ack answers the current request only on the state and the stamp`() {
        val s = store()
        s.set(on = true, conversationId = conv, atUnix = 1000.0)

        assertTrue(s.answersCurrentRequest(OfflineOnlyPayload.Request(on = true, atUnix = 1000.0), conv))
        // Within iOS's `< 1.0`: a float re-encode must not reject a correct ack.
        assertTrue(s.answersCurrentRequest(OfflineOnlyPayload.Request(on = true, atUnix = 1000.4), conv))
        // Outside it: this answers some other request.
        assertFalse(s.answersCurrentRequest(OfflineOnlyPayload.Request(on = true, atUnix = 1002.0), conv))
        // The opposite state never answers, which is what stops a quick off-then-on
        // counting an ack for the request it replaced.
        assertFalse(s.answersCurrentRequest(OfflineOnlyPayload.Request(on = false, atUnix = 1000.0), conv))
    }

    /** Rule 4 — mesh delivery is unordered, so an older request is ordinary, not an error. */
    @Test
    fun `an incoming request older than what we hold is ignored`() {
        val s = store()
        assertTrue(s.applyIncoming(OfflineOnlyPayload.Request(on = true, atUnix = 500.0), conv))
        assertTrue(s.isOfflineOnly(conv))

        assertFalse("an out-of-order request must not flip it back",
            s.applyIncoming(OfflineOnlyPayload.Request(on = false, atUnix = 400.0), conv))
        assertTrue("still restricted", s.isOfflineOnly(conv))

        assertTrue("a NEWER one is honoured",
            s.applyIncoming(OfflineOnlyPayload.Request(on = false, atUnix = 600.0), conv))
        assertFalse(s.isOfflineOnly(conv))
    }

    @Test
    fun `an incoming request we already hold changes nothing`() {
        val s = store()
        assertTrue(s.applyIncoming(OfflineOnlyPayload.Request(on = true, atUnix = 500.0), conv))
        assertFalse("a redelivery is not a change",
            s.applyIncoming(OfflineOnlyPayload.Request(on = true, atUnix = 500.0), conv))
    }

    /**
     * The fold exists so one conversation cannot end up as two entries that disagree —
     * the standard and URL-safe base64 spellings of the same public key.
     */
    @Test
    fun `the two base64 spellings of one key are one conversation`() {
        val s = store()
        val urlSafe = peer.replace('+', '-').replace('/', '_').trimEnd('=')
        s.set(on = true, conversationId = peer, atUnix = 100.0)
        assertTrue("the same conversation, spelled the other way", s.isOfflineOnly(urlSafe))
        assertTrue(s.noteConfirmation(conv, urlSafe))
        assertEquals("and the confirmation landed on the same entry", 1, s.confirmedCount(peer))
    }

    @Test
    fun `state survives a reload, confirmations included`() {
        val d = Files.createTempDirectory("offline-only").toFile().also { dirs += it }
        val f = File(d, OfflineOnlyStore.FILE_NAME)
        OfflineOnlyStore(f).apply {
            set(on = true, conversationId = conv, atUnix = 1234.5)
            noteConfirmation(peer, conv)
        }
        val reopened = OfflineOnlyStore(f)
        val st = reopened.state(conv)
        assertNull("an untouched conversation has no state", reopened.state("Zm9vYmFy"))
        assertTrue(st!!.on)
        assertEquals(1234.5, st.changedAtUnix, 0.0001)
        assertEquals(1, st.confirmedBy.size)
    }
}
