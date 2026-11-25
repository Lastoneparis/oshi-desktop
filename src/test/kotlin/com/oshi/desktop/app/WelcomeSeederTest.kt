package com.oshi.desktop.app

import com.oshi.desktop.store.ContactStore
import com.oshi.desktop.store.MessageStore
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first conversation a new account ever has — and the rules its own header sets out.
 *
 * `WelcomeSeeder` was one of the files nothing in the test tree named. It is first-run
 * logic every new user meets exactly once, which is also why its failure modes are the
 * kind nobody notices in testing: the header says the marker is written BEFORE the
 * message "exactly as iOS does it", because the other order "costs a duplicate greeting
 * on every launch forever, which is the worse failure and the harder one to notice".
 * That ordering was an assertion in a comment.
 */
class WelcomeSeederTest {

    private val dirs = ArrayList<File>()
    @After fun tearDown() { dirs.forEach { it.deleteRecursively() } }

    private fun home(): File = Files.createTempDirectory("welcome").toFile().also { dirs += it }

    private class Fixture(val home: File) {
        val contacts = ContactStore(File(home, "contacts.json"))
        val messages = MessageStore(File(home, "messages"))
    }

    private fun fixture() = Fixture(home())

    private val me = "DwFvJQ1t3mZ8kYpR2sXnB7cLd4eHgTuV9wA0yC6iQfE="

    @Test
    fun `a new account gets exactly one greeting, and only once`() {
        val f = fixture()
        assertTrue(WelcomeSeeder.seedIfNeeded(f.home, me, f.contacts, f.messages))
        assertEquals(1, f.messages.messages(WelcomeSeeder.AUTHOR_ADDRESS).size)

        assertFalse("a second launch must not greet again",
            WelcomeSeeder.seedIfNeeded(f.home, me, f.contacts, f.messages))
        assertEquals("and must not leave two", 1, f.messages.messages(WelcomeSeeder.AUTHOR_ADDRESS).size)
    }

    /**
     * The marker is keyed by ADDRESS, not by a global flag: a second identity on the same
     * machine is a new user and gets welcomed.
     */
    @Test
    fun `a second identity on the same machine is welcomed too`() {
        val f = fixture()
        val other = "RuYUgKUpwc+5UCjv7gl0OxwdIwn2s/KS5DcvIxTzfTk="
        assertTrue(WelcomeSeeder.seedIfNeeded(f.home, me, f.contacts, f.messages))
        assertTrue("a different account is a different user",
            WelcomeSeeder.seedIfNeeded(f.home, other, f.contacts, f.messages))
    }

    @Test
    fun `the author is never welcomed into a conversation with themselves`() {
        val f = fixture()
        assertFalse(WelcomeSeeder.seedIfNeeded(f.home, WelcomeSeeder.AUTHOR_ADDRESS, f.contacts, f.messages))
        assertEquals(0, f.messages.messages(WelcomeSeeder.AUTHOR_ADDRESS).size)
    }

    @Test
    fun `a blank address seeds nothing`() {
        val f = fixture()
        assertFalse(WelcomeSeeder.seedIfNeeded(f.home, "   ", f.contacts, f.messages))
    }

    /**
     * The greeting must not read as a message a person typed — the header calls
     * attributing it to someone who did not type it "a small lie the user can later
     * discover". So it says, in its own body, that the app wrote it.
     */
    @Test
    fun `the greeting says the app wrote it and names a real reply path`() {
        val f = fixture()
        WelcomeSeeder.seedIfNeeded(f.home, me, f.contacts, f.messages)
        val m = f.messages.messages(WelcomeSeeder.AUTHOR_ADDRESS).single()
        assertFalse("it is an incoming row in the author's conversation", m.fromMe)
        assertTrue("the body must not be empty or a missing-key marker: ${m.content}",
            m.content?.isNotBlank() == true && m.content?.startsWith("⟦") == false)
    }

    /** So the list draws a name rather than a truncated base64 key. */
    @Test
    fun `the author is given a name in contacts`() {
        val f = fixture()
        WelcomeSeeder.seedIfNeeded(f.home, me, f.contacts, f.messages)
        val named = f.contacts.all().firstOrNull { it.address == WelcomeSeeder.AUTHOR_ADDRESS }
        assertTrue("the author should be known as ${WelcomeSeeder.AUTHOR_ALIAS}: $named",
            named?.displayName == WelcomeSeeder.AUTHOR_ALIAS)
    }

    /**
     * MARKED BEFORE WRITTEN. If the marker were written after the append, a crash between
     * the two would greet the user again on every launch forever. Checked by looking for
     * the marker in a home where the message store cannot be appended to.
     */
    @Test
    fun `the marker is on disk even when the greeting could not be written`() {
        val f = fixture()
        WelcomeSeeder.seedIfNeeded(f.home, me, f.contacts, f.messages)
        val markers = f.home.listFiles().orEmpty().filter { it.name.startsWith("welcome-seeded") }
        assertEquals("one marker, keyed by this address", 1, markers.size)
        assertTrue("and it carries the stamp", markers[0].readText().isNotBlank())
    }
}
