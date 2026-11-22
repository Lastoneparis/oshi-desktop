package com.oshi.desktop.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** __FILE_DIALOGS_OPENED_BEHIND_2026_11_20__ every file dialog goes through DialogOwner. */
class DialogOwnerTest {
    @Test fun `no file dialog is built without an owner outside DialogOwner`() {
        val root = File("src/main/kotlin")
        val hits = root.walk().filter { it.isFile && it.extension == "kt" && it.name != "DialogOwner.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (Regex("""FileDialog\(\s*null""").containsMatchIn(l)) "${f.path}:${i + 1}" else null } }
            .toList()
        assertTrue("owner-less FileDialog (opens behind the window on Windows):\n$hits", hits.isEmpty())
    }
}
