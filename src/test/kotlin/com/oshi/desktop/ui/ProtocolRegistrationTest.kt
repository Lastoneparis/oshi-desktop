package com.oshi.desktop.ui

import com.oshi.desktop.store.InstanceActivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __OSHI_LINKS_ON_PC_2026_11_20__ */
class ProtocolRegistrationTest {
    @Test fun `the open command quotes the exe and the link`() {
        assertEquals("\"C:\\Users\\Jean Dupont\\AppData\\Local\\OSHI\\OSHI.exe\" \"%1\"",
            ProtocolRegistration.commandValue("C:\\Users\\Jean Dupont\\AppData\\Local\\OSHI\\OSHI.exe"))
    }

    @Test fun `reg query output is parsed in English and localized Windows`() {
        val en = "\r\nHKEY_CURRENT_USER\\Software\\Classes\\oshi\\shell\\open\\command\r\n    (Default)    REG_SZ    \"C:\\a b\\OSHI.exe\" \"%1\"\r\n"
        assertEquals("\"C:\\a b\\OSHI.exe\" \"%1\"", ProtocolRegistration.parseDefault(en))
        val fr = "    (par défaut)    REG_SZ    x"
        assertNull(ProtocolRegistration.parseDefault(fr))   // two words: re-registers, harmless
        assertEquals("x", ProtocolRegistration.parseDefault("    (Standard)    REG_SZ    x"))
        assertNull(ProtocolRegistration.parseDefault(null))
    }

    @Test fun `values travel by environment, and New-Item touches only our own key`() {
        val s = ProtocolRegistration.SCRIPT
        assertTrue(s.contains("\$env:OSHI_OPEN_CMD") && s.contains("'URL Protocol'"))
        assertTrue(Regex("""New-Item -Path (\S+)""").findAll(s).all { it.groupValues[1].contains("\$k") })
    }

    @Test fun `only oshi links are carried, never other schemes or multi-line payloads`() {
        assertEquals("oshi://add?key=abc", InstanceActivation.acceptableLink(" oshi://add?key=abc "))
        assertNull(InstanceActivation.acceptableLink("https://evil.example"))
        assertNull(InstanceActivation.acceptableLink("oshi://a\nb"))
        assertNull(InstanceActivation.acceptableLink("--ui"))
    }
}
