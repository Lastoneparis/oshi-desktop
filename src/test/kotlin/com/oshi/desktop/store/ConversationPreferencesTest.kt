package com.oshi.desktop.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** __CONVERSATION_PIN_MUTE_2027__ */
class ConversationPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `pins and mutes survive a restart`() {
        val f = File(tmp.root, ConversationPreferences.FILE_NAME)
        ConversationPreferences(f).apply { setPinned("a", true); setPinned("b", true); setMuted("c", true) }
        val again = ConversationPreferences(f)
        assertEquals(listOf("a", "b"), again.pinnedIds())
        assertTrue(again.isMuted("c"))
        assertFalse(again.isMuted("a"))
        again.setPinned("a", false)
        assertEquals(listOf("b"), ConversationPreferences(f).pinnedIds())
    }

    @Test
    fun `an unreadable file is nothing pinned, never a crash`() {
        val f = File(tmp.root, ConversationPreferences.FILE_NAME).apply { writeText("{not json") }
        val p = ConversationPreferences(f)
        assertTrue(p.pinnedIds().isEmpty())
        assertFalse(p.isMuted("x"))
    }

    @Test
    fun `pinned rows go first in pin order and everything else keeps its order`() {
        val list = listOf("n1", "n2", "p2", "n3", "p1")
        assertEquals(
            listOf("p1", "p2", "n1", "n2", "n3"),
            ConversationPreferences.pinnedFirst(list, listOf("p1", "p2", "gone")) { it },
        )
        assertEquals(list, ConversationPreferences.pinnedFirst(list, emptyList()) { it })
    }
}
