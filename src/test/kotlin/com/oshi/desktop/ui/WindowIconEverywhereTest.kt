package com.oshi.desktop.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __FIRST_RUN_WINDOW_ICON_2026_11_20__ Only the main window passed `icon = oshiWindowIcon()`.
 * The first-run window — the first thing a new PC user sees — showed the JVM's coffee cup in
 * its title bar and in the Windows taskbar (PC smoke run on the published 1.5.0,
 * windows-latest, screenshot 11-first-run-french.png). Every OS-level Compose window must
 * carry the OSHI icon.
 */
class WindowIconEverywhereTest {

    private val root = listOf(File("src/main/kotlin/com/oshi/desktop"), File("OSHI-Desktop/src/main/kotlin/com/oshi/desktop"))
        .first { it.isDirectory }

    @Test
    fun `every Compose Window and DialogWindow passes the OSHI icon`() {
        val call = Regex("""(?<![A-Za-z])(?:androidx\.compose\.ui\.window\.)?(Window|DialogWindow)\(""")
        val missing = ArrayList<String>()
        var seen = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val lines = f.readLines()
            lines.forEachIndexed { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                if (!call.containsMatchIn(code) || code.contains("fun ")) return@forEachIndexed
                // Only Compose windows (the call's trailing lambda content); AWT/Swing types differ.
                val window = lines.subList(i, minOf(lines.size, i + 16)).joinToString("\n")
                if (!window.contains("onCloseRequest")) return@forEachIndexed
                seen++
                if (!window.contains("icon = ") || !window.contains("oshiWindowIcon()")) missing += "${f.name}:${i + 1}"
            }
        }
        assertTrue("found only $seen Compose windows — the scan is not looking where the windows are", seen >= 6)
        assertTrue("windows without the OSHI icon (JVM coffee cup on Windows): $missing", missing.isEmpty())
    }
}
