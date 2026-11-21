package com.oshi.desktop.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** __CONVERSATION_EXPORT_2027__ */
class ConversationTextExportTest {
    @Test
    fun `the format is locale-free and keeps multi-line messages readable`() {
        val utc = ZoneId.of("UTC")
        val text = ConversationTextExport.render(
            "Camille",
            listOf(
                ConversationTextExport.Line(1_763_480_000_000L, "Camille", "Hi"),
                ConversationTextExport.Line(1_763_480_060_000L, "Me", "two\nlines"),
                ConversationTextExport.Line(0L, "Me", "no date"),
            ),
            utc,
        )
        assertEquals(
            "OSHI — Camille\n\n[2025-11-18 15:33] Camille: Hi\n[2025-11-18 15:34] Me: two\n    lines\n[—] Me: no date\n",
            text,
        )
    }
}
