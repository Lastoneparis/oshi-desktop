package com.oshi.desktop.ui.components

import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.ConversationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard: **"you have no conversations" and "nothing matched" are different sentences.**
 *
 * Showing the onboarding empty state to somebody who is mid-search reads as if their history
 * had been deleted, and it is the classic wrong-empty-state bug because both conditions
 * produce a zero-length list and the naive renderer branches on the length.
 * [ConversationFilter.emptyReason] is what separates them, and it is asserted here on the
 * three inputs that can produce zero rows.
 */
class ConversationFilterTest {

    private fun row(id: String, label: String, preview: String = "") = ConversationRow(
        id = id,
        label = label,
        kind = ConversationKind.DIRECT,
        messageCount = 1,
        lastActivityMs = 0L,
        lastActivity = "2026-08-28 10:00",
        preview = preview,
        unread = 0,
    )

    private val rows = listOf(
        row("AAA111", "Alice", "see you at the meetup"),
        row("BBB222", "Bob", "bluetooth mesh works offline"),
        row("bot!feed", "bot: feed", "daily digest"),
    )

    // ------------------------------------------------------------------ filtering

    @Test
    fun `a blank query returns the list unchanged and in order`() {
        assertSame(rows, ConversationFilter.apply(rows, ""))
        assertSame(rows, ConversationFilter.apply(rows, "   "))
    }

    @Test
    fun `it matches the label, the preview and the address, case-insensitively`() {
        assertEquals(listOf("AAA111"), ConversationFilter.apply(rows, "alice").map { it.id })
        assertEquals(listOf("BBB222"), ConversationFilter.apply(rows, "MESH").map { it.id })
        assertEquals(listOf("AAA111"), ConversationFilter.apply(rows, "aaa1").map { it.id })
    }

    @Test
    fun `filtering preserves the model's ordering`() {
        val hits = ConversationFilter.apply(rows, "e")
        assertEquals(rows.filter { it in hits }.map { it.id }, hits.map { it.id })
    }

    @Test
    fun `a query that matches nothing yields nothing`() {
        assertTrue(ConversationFilter.apply(rows, "zzzz").isEmpty())
    }

    // ------------------------------------------------------------------ THE GUARD

    @Test
    fun `an empty list with no query is the onboarding state`() {
        assertEquals(
            ConversationFilter.EmptyReason.NO_CONVERSATIONS,
            ConversationFilter.emptyReason(total = 0, shown = 0, query = ""),
        )
        assertEquals(
            "a search over an empty disk is still an empty disk",
            ConversationFilter.EmptyReason.NO_CONVERSATIONS,
            ConversationFilter.emptyReason(total = 0, shown = 0, query = "alice"),
        )
    }

    @Test
    fun `an empty result over a non-empty list is a no-match, never the onboarding state`() {
        assertEquals(
            ConversationFilter.EmptyReason.NO_MATCHES,
            ConversationFilter.emptyReason(total = 3, shown = 0, query = "zzzz"),
        )
    }

    @Test
    fun `a non-empty result has no empty state at all`() {
        assertNull(ConversationFilter.emptyReason(total = 3, shown = 1, query = "alice"))
        assertNull(ConversationFilter.emptyReason(total = 3, shown = 3, query = ""))
    }

    @Test
    fun `the placeholder does not promise message search`() {
        // The shipped `MessagesListView` searches messages AND contacts. This filters rows the
        // model already published, so the copy must not borrow the phone's promise.
        val p = ConversationFilter.PLACEHOLDER.lowercase()
        assertTrue(p.contains("filter"))
        assertTrue("must not claim to search inside messages", !p.contains("messages"))
    }

    // ================================================================ the Messages/Groups split

    /**
     * A bot channel is a group ON THE WIRE and must not be filed under Groups.
     *
     * `bot!` conversations are keyed by `groupId`, so the tempting implementation — "anything
     * with a group id goes in the Groups half" — puts an UNENCRYPTED lane (PARITY.md row
     * 0.26) under the same heading as ratcheted group conversations. The heading is what a
     * user reads as a category; it must not be the thing that merges those two.
     */
    @Test
    fun `bot and radio threads stay under Messages, never under Groups`() {
        val rows = listOf(
            row("peer", ConversationKind.DIRECT),
            row("g1", ConversationKind.GROUP),
            row("bot!x", ConversationKind.BOT),
            row("lora!y", ConversationKind.LORA),
        )
        assertEquals(
            listOf("peer", "bot!x", "lora!y"),
            ConversationFilter.ofKind(rows, ConversationFilter.Half.MESSAGES).map { it.id },
        )
        assertEquals(
            listOf("g1"),
            ConversationFilter.ofKind(rows, ConversationFilter.Half.GROUPS).map { it.id },
        )
    }

    /** Every row lands in exactly one half — no duplicates, and nothing silently dropped. */
    @Test
    fun `the two halves partition the list`() {
        val rows = ConversationKind.entries.map { row("id-$it", it) }
        val a = ConversationFilter.ofKind(rows, ConversationFilter.Half.MESSAGES)
        val b = ConversationFilter.ofKind(rows, ConversationFilter.Half.GROUPS)
        assertEquals(rows.size, a.size + b.size)
        assertEquals(emptyList<String>(), a.map { it.id }.intersect(b.map { it.id }.toSet()).toList())
    }

    /** The segment's badge counts only its own half, or it reads as the other one's mail. */
    @Test
    fun `each half's badge counts only its own unread`() {
        val rows = listOf(
            row("peer", ConversationKind.DIRECT, unread = 3),
            row("g1", ConversationKind.GROUP, unread = 4),
            row("bot!x", ConversationKind.BOT, unread = 1),
        )
        assertEquals(4, ConversationFilter.unreadIn(rows, ConversationFilter.Half.MESSAGES))
        assertEquals(4, ConversationFilter.unreadIn(rows, ConversationFilter.Half.GROUPS))
    }

    private fun row(id: String, kind: ConversationKind, unread: Int = 0) = ConversationRow(
        id = id,
        label = id,
        kind = kind,
        messageCount = 1,
        lastActivityMs = 0,
        lastActivity = "",
        preview = "",
        unread = unread,
    )
}
