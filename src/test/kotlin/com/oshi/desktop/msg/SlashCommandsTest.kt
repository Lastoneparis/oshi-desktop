package com.oshi.desktop.msg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_SLASH_MENU_2026_10_29__ When the `/` menu opens, what it offers, and when it
 * closes. iOS's rules, including the two that look like details and are not.
 */
class SlashCommandsTest {

    @Test
    fun `a lone slash opens the full list — the discovery gesture`() {
        assertEquals("", SlashCommands.query("/"))
        assertEquals(SlashCommands.builtIns().size, SlashCommands.matches("/").size)
        assertTrue(SlashCommands.matches("/").any { it.command == "/pay" })
    }

    @Test
    fun `a prefix narrows the list`() {
        assertEquals(listOf("/pay"), SlashCommands.matches("/pa").map { it.command })
        assertEquals(listOf("/pay"), SlashCommands.matches("/PAY").map { it.command })
        assertTrue(SlashCommands.matches("/zz").isEmpty())
    }

    /**
     * A SPACE CLOSES THE MENU. `/pay 5` is a command being argued, not chosen, and a list
     * hovering over the composer while somebody types the amount is in the way.
     */
    @Test
    fun `a space or a newline closes the menu`() {
        assertNull(SlashCommands.query("/pay 5"))
        assertNull(SlashCommands.query("/pay\n"))
        assertTrue(SlashCommands.matches("/pay 5").isEmpty())
    }

    @Test
    fun `text that is not a command never opens the menu`() {
        assertNull(SlashCommands.query("hello"))
        assertNull(SlashCommands.query(""))
        assertNull(SlashCommands.query(" /pay"))
        assertTrue(SlashCommands.matches("hello").isEmpty())
    }

    /**
     * THE TRAILING SPACE IS LOAD-BEARING. It saves the keystroke before an argument, and it
     * closes the menu by the rule above — choosing a command must not leave the list open.
     */
    @Test
    fun `the completion ends in a space, which also closes the menu`() {
        val pay = SlashCommands.builtIns().single { it.command == "/pay" }
        assertEquals("/pay ", SlashCommands.completion(pay))
        assertNull(
            "the completion must not re-open the menu it just answered",
            SlashCommands.query(SlashCommands.completion(pay)),
        )
    }

    /**
     * The menu advertises a command this client ALREADY executes — that is the whole point.
     * If this ever fails, the menu is offering something the composer will not honour.
     */
    @Test
    fun `every offered command is one this client actually handles`() {
        for (c in SlashCommands.builtIns()) {
            assertTrue(
                "the menu offers ${c.command} and nothing parses it",
                com.oshi.messenger.util.CashuPayCommand.handles(c.command + " 5"),
            )
        }
    }

    @Test
    fun `a command carries a description and a source`() {
        for (c in SlashCommands.builtIns()) {
            assertTrue(c.description.isNotBlank())
            assertTrue("a raw catalog key reached the menu: ${c.description}", !c.description.contains("cashu_pay"))
            assertTrue(c.source.isNotBlank())
        }
    }
}
