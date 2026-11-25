package com.oshi.desktop.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** __WINDOWS_BARE_EXE_HIJACK_2026_11_20__ */
class WindowsToolsTest {
    @Test
    fun `system tools resolve under SystemRoot, never by bare name`() {
        assertEquals(File(File("C:\\Windows", "System32"), "reg.exe").path, WindowsTools.system32("reg.exe", "C:\\Windows"))
        assertEquals("C:\\Windows", WindowsTools.systemRoot { if (it == "SystemRoot") "C:\\Windows" else null })
    }

    @Test
    fun `no source still calls a system tool by bare name`() {
        val src = File("src/main/kotlin/com/oshi/desktop").walkTopDown().filter { it.extension == "kt" }
            .filter { f -> Regex("""(ProcessBuilder|listOf|run)\(\s*"(reg|rundll32|powershell\.exe|cmd|cmd\.exe)"""").containsMatchIn(f.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), src)
    }

    @Test
    fun `which skips relative PATH entries and never picks a batch file`() {
        val seen = ArrayList<String>()
        val r = ExecutableLookup.which(
            "ffmpeg", path = ".;bin;C:\\tools", windows = true, pathExt = ".EXE;.CMD;.BAT",
            isExecutable = { seen += it.path; it.name.endsWith(".cmd") },
        )
        assertNull(r)
        assertFalse(seen.any { it.startsWith(".") || it.startsWith("bin") })
        assertFalse(seen.any { it.endsWith(".cmd") || it.endsWith(".bat") })
    }
}
