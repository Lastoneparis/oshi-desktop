package com.oshi.desktop.store

import com.oshi.desktop.msg.PollPayload
import com.oshi.desktop.msg.ProfileLinksPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * __SOCIAL_GRAPH_LEAK_AT_REST_2026_11_20__ The five small per-conversation maps are sealed:
 * the contact's key and anything the contact shared must not be readable from the file.
 *
 * Proof of the defect before the fix: each of these stores wrote `{"<peer key>": …}` through
 * AtomicFile in plaintext, so `grep <peer key> *.json` in a copied profile listed the person's
 * contacts even though `contacts.json` itself was encrypted.
 */
class SocialGraphAtRestTest {
    @get:Rule val tmp = TemporaryFolder()

    private val peer = "BPeerPublicKeyThatMustNotAppearOnDisk0123456789abcdef=="
    private val key = ByteArray(32) { it.toByte() }
    private fun k(purpose: String) = LocalDataKeys.derive(key, purpose)

    private fun assertSealed(f: File) {
        val text = f.readText(Charsets.UTF_8)
        assertTrue("${f.name} must be a sealed envelope", text.contains(SealedJsonFile.FORMAT))
        assertFalse("${f.name} leaks the peer key", text.contains(peer))
        assertFalse("${f.name} leaks shared links", text.contains("example.org"))
    }

    @Test
    fun `conversation preferences are sealed and survive a restart`() {
        val f = File(tmp.root, ConversationPreferences.FILE_NAME)
        ConversationPreferences(f, k(LocalDataKeys.CONVERSATION_PREFS)).apply { setPinned(peer, true); setMuted(peer, true) }
        assertSealed(f)
        val again = ConversationPreferences(f, k(LocalDataKeys.CONVERSATION_PREFS))
        assertEquals(listOf(peer), again.pinnedIds())
        assertTrue(again.isMuted(peer))
    }

    @Test
    fun `pinned messages, ballots, offline-only and links are sealed`() {
        val pin = File(tmp.root, PinnedMessageStore.FILE_NAME)
        PinnedMessageStore(pin, k(LocalDataKeys.PINNED_MESSAGES)).set(peer, "msg-1")
        assertSealed(pin)
        assertEquals("msg-1", PinnedMessageStore(pin, k(LocalDataKeys.PINNED_MESSAGES)).get(peer))

        val poll = File(tmp.root, PollTally.FILE_NAME)
        PollTally(poll, k(LocalDataKeys.POLL_BALLOTS)).record("poll-1", peer, PollPayload.Ballot(listOf(1), 10.0))
        assertSealed(poll)

        val off = File(tmp.root, OfflineOnlyStore.FILE_NAME)
        OfflineOnlyStore(off, k(LocalDataKeys.OFFLINE_ONLY)).set(true, peer, 10.0)
        assertSealed(off)
        assertTrue(OfflineOnlyStore(off, k(LocalDataKeys.OFFLINE_ONLY)).isOfflineOnly(peer))

        val links = File(tmp.root, ProfileLinksStore.FILE_NAME)
        ProfileLinksStore(links, k(LocalDataKeys.CONTACT_LINKS))
            .apply(peer, ProfileLinksPayload.Visibility(true, ProfileLinksPayload.Links(websiteUrl = "https://example.org/me")))
        assertSealed(links)
        assertEquals(
            "https://example.org/me",
            ProfileLinksStore(links, k(LocalDataKeys.CONTACT_LINKS)).linksOf(peer)?.websiteUrl,
        )
    }

    @Test
    fun `a legacy plaintext file is read and resealed on first load`() {
        val f = File(tmp.root, ConversationPreferences.FILE_NAME)
        ConversationPreferences(f).setPinned(peer, true) // the pre-fix plaintext writer
        assertTrue(f.readText().contains(peer))
        val p = ConversationPreferences(f, k(LocalDataKeys.CONVERSATION_PREFS))
        assertEquals(listOf(peer), p.pinnedIds())
        assertSealed(f)

        val pin = File(tmp.root, PinnedMessageStore.FILE_NAME)
        PinnedMessageStore(pin).set(peer, "m")
        assertEquals("m", PinnedMessageStore(pin, k(LocalDataKeys.PINNED_MESSAGES)).get(peer))
        assertSealed(pin)
    }

    @Test
    fun `a sealed file under another key is never overwritten`() {
        val f = File(tmp.root, ConversationPreferences.FILE_NAME)
        ConversationPreferences(f, k(LocalDataKeys.CONVERSATION_PREFS)).setPinned(peer, true)
        val before = f.readText()
        val wrong = ConversationPreferences(f, k(LocalDataKeys.CONTACTS))
        assertTrue(wrong.pinnedIds().isEmpty())
        wrong.setPinned("other", true)
        assertEquals(before, f.readText())
        // and the right key still reads the original
        assertEquals(listOf(peer), ConversationPreferences(f, k(LocalDataKeys.CONVERSATION_PREFS)).pinnedIds())
    }
}
