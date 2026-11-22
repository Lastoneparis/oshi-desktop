package com.oshi.desktop.ui

import com.oshi.desktop.group.GroupType
import com.oshi.desktop.ui.components.AttachmentPanelSpec
import com.oshi.desktop.ui.state.ChatListMerge
import com.oshi.desktop.ui.state.ChatListMerge.Filter
import com.oshi.desktop.ui.state.ChatListMerge.GroupSub
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.ConversationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __MERGED_CHATS_2026_11_18__ / __ATTACH_PANEL_2027__ the rules, without a window. */
class ChatListMergeTest {

    private fun row(id: String, kind: ConversationKind, at: Long, unread: Int = 0, label: String = id, type: GroupType? = null) =
        ConversationRow(id, label, kind, 1, at, "", "preview $id", unread, groupType = type)

    private val rows = listOf(
        row("alice", ConversationKind.DIRECT, 50, unread = 2, label = "Alice"),
        row("g-admin", ConversationKind.GROUP, 40, label = "Board", type = GroupType.ADMIN_ONLY),
        row("g-collab", ConversationKind.GROUP, 70, unread = 1, label = "Friends", type = GroupType.COLLABORATIVE),
        row("g-public", ConversationKind.GROUP, 10, label = "Town", type = GroupType.PUBLIC),
        row("lora!LongFast", ConversationKind.LORA, 60, unread = 3, label = "LongFast"),
        row("bob", ConversationKind.DIRECT, 20, label = "Bob"),
    )

    @Test
    fun `all is one list newest first across both kinds`() {
        assertEquals(
            listOf("g-collab", "lora!LongFast", "alice", "g-admin", "bob", "g-public"),
            ChatListMerge.arrange(rows, Filter.ALL, GroupSub.ALL, "").map { it.id },
        )
    }

    @Test
    fun `chips filter by kind and unread, a radio channel is a room`() {
        assertEquals(listOf("g-collab", "lora!LongFast", "alice"), ChatListMerge.arrange(rows, Filter.UNREAD, GroupSub.ALL, "").map { it.id })
        assertEquals(listOf("g-collab", "lora!LongFast", "g-admin", "g-public"), ChatListMerge.arrange(rows, Filter.GROUPS, GroupSub.ALL, "").map { it.id })
        assertEquals(listOf("alice", "bob"), ChatListMerge.arrange(rows, Filter.PEOPLE, GroupSub.ALL, "").map { it.id })
    }

    @Test
    fun `group sub-filter keeps only that type, and only under Groups`() {
        assertEquals(listOf("g-admin"), ChatListMerge.arrange(rows, Filter.GROUPS, GroupSub.ADMIN_ONLY, "").map { it.id })
        assertEquals(listOf("g-public"), ChatListMerge.arrange(rows, Filter.GROUPS, GroupSub.PUBLIC, "").map { it.id })
        // Ignored outside the Groups chip.
        assertEquals(6, ChatListMerge.arrange(rows, Filter.ALL, GroupSub.PUBLIC, "").size)
    }

    @Test
    fun `search matches the name across both kinds, case-insensitive`() {
        assertEquals(listOf("g-collab"), ChatListMerge.arrange(rows, Filter.ALL, GroupSub.ALL, "friends").map { it.id })
        assertEquals(listOf("alice"), ChatListMerge.arrange(rows, Filter.ALL, GroupSub.ALL, "ALI").map { it.id })
    }

    @Test
    fun `counts are unread THREADS per chip`() {
        assertEquals(ChatListMerge.Counts(unread = 3, groups = 2, people = 1), ChatListMerge.counts(rows))
    }

    @Test
    fun `default is on only for a fresh profile, and a saved choice wins`() {
        assertTrue(ChatListMerge.defaultFor(profileIsFresh = true))
        assertFalse(ChatListMerge.defaultFor(profileIsFresh = false))
        val dir = java.nio.file.Files.createTempDirectory("chatlist").toFile()
        val prefs = com.oshi.desktop.store.ChatListPreferences(java.io.File(dir, "p.json"))
        assertNull(prefs.load())
        prefs.save(true); assertEquals(true, prefs.load())
        prefs.save(false); assertEquals(false, prefs.load())
        java.io.File(dir, "p.json").writeText("{not json")
        assertNull(prefs.load())
        dir.deleteRecursively()
    }

    @Test
    fun `attach panel tiles follow the phones' order and the thread's gates`() {
        val all = AttachmentPanelSpec.tiles(attachEnabled = true, gif = true, contact = true, poll = true, videoNote = true, disguise = true)
        assertEquals(
            listOf("GALLERY", "FILE", "GIF", "CONTACT", "POLL", "VIDEO_NOTE", "DISGUISE"),
            all.map { it.name },
        )
        // A group: GIF yes (__GROUP_GIF__), no contact card, no poll, no disguise.
        assertEquals(
            listOf("GALLERY", "FILE", "GIF", "VIDEO_NOTE"),
            AttachmentPanelSpec.tiles(true, gif = true, contact = false, poll = false, videoNote = true, disguise = false).map { it.name },
        )
        // Attachments refused: only the disguise, which never touches the relay.
        assertEquals(listOf("DISGUISE"), AttachmentPanelSpec.tiles(false, true, true, true, true, disguise = true).map { it.name })
    }
}
