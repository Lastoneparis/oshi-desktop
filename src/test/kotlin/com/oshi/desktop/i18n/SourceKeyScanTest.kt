package com.oshi.desktop.i18n

import com.oshi.desktop.i18n.tools.SourceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Proves the key scanner is not the thing it is guarding against.
 *
 * An audit that greps for `Strings.get(` reports full coverage of the keys it can see and
 * says nothing about the ones it cannot. Every assertion in [CatalogAuditTest] rests on
 * [SourceKeys] seeing the real call sites, so [SourceKeys] itself needs a test that fails
 * when it goes blind.
 */
class SourceKeyScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun src(name: String, body: String): File =
        tmp.newFile(name).apply { writeText(body) }

    @Test
    fun `the scanner sees keys through the t wrapper`() {
        // If `t` is ever dropped from ACCESSORS this fails, instead of the audit quietly
        // shrinking to the keys it still recognises.
        src("A.kt", """
            fun render() {
                out(t("contacts.empty"))
                out(t("group.member.admin", name))
                out("   " + t("mesh.no_peers"))
            }
        """.trimIndent())
        assertEquals(
            setOf("contacts.empty", "group.member.admin", "mesh.no_peers"),
            SourceKeys.used(tmp.root),
        )
    }

    @Test
    fun `the scanner sees the qualified calls too`() {
        src("B.kt", """
            val a = Strings.get("common.cancel")
            val b = Strings.format("typing.indicator", who)
            val c = Strings.lookup("messages.empty")
        """.trimIndent())
        assertEquals(setOf("common.cancel", "typing.indicator", "messages.empty"), SourceKeys.used(tmp.root))
    }

    @Test
    fun `the scanner is not fooled by a method called t on something else`() {
        src("C.kt", """
            val x = foo.t("not.a.key")
            val y = things.get("also.not.a.key")
        """.trimIndent())
        assertTrue("qualified receivers must not be read as the wrapper: ${SourceKeys.used(tmp.root)}",
            SourceKeys.used(tmp.root).isEmpty())
    }

    // ------------------------------------------------ the wrapper-blindness defence

    @Test
    fun `an UNREGISTERED wrapper is caught as a suspect, not silently skipped`() {
        // THE POINT OF THIS FILE. Someone adds `Row(labelKey = …)` or passes a key as a
        // plain string; ACCESSORS knows nothing about it; a grep-based audit reports 100%.
        // `suspects` does not trust ACCESSORS — it asks whether a string that IS a catalog
        // key reaches the catalog at all.
        src("D.kt", """
            fun render() {
                MyRow(someKey = "contacts.empty")
                sendEvent("mesh.no_peers")
            }
        """.trimIndent())
        val known = setOf("contacts.empty", "mesh.no_peers")
        assertTrue("the registered-accessor scan should see NONE of these", SourceKeys.used(tmp.root).isEmpty())
        val suspects = SourceKeys.suspects(known, tmp.root, ignoreDirs = emptyList())
        assertEquals(setOf("contacts.empty", "mesh.no_peers"), suspects.map { it.key }.toSet())
        assertTrue("a suspect must say where it is", suspects.all { it.line > 0 && it.file.name == "D.kt" })
    }

    @Test
    fun `a key that DOES reach an accessor is not also reported as a suspect`() {
        src("E.kt", """val a = t("contacts.empty")""")
        assertEquals(
            emptyList<String>(),
            SourceKeys.suspects(setOf("contacts.empty"), tmp.root, ignoreDirs = emptyList()).map { it.key },
        )
    }

    @Test
    fun `ordinary prose is not mistaken for a key`() {
        src("F.kt", """
            val msg = "unknown address"
            val note = "sent"
            val path = "src/main/kotlin"
        """.trimIndent())
        val known = setOf("unknown address", "sent", "src/main/kotlin", "contacts.empty")
        assertTrue(SourceKeys.suspects(known, tmp.root, ignoreDirs = emptyList()).isEmpty())
    }

    // ------------------------------------------------ every ACCESSOR must match something

    @Test
    fun `every registered accessor pattern actually matches a call`() {
        // A pattern in the list that matches nothing is the same as not being in the list,
        // and it makes the list look more thorough than it is.
        val fixtures = listOf(
            """val a = t("k.one")""",
            """val b = Strings.get("k.two")""",
            """val c = Row(textKey = "k.three")""",
        )
        SourceKeys.ACCESSORS.forEachIndexed { i, re ->
            assertTrue(
                "ACCESSORS[$i] = /$re/ matches none of the fixtures — it is dead weight in the list",
                fixtures.any { re.containsMatchIn(it) },
            )
        }
    }

    @Test
    fun `the scan reaches the real desktop source and finds real keys`() {
        // Guards the one way every audit in CatalogAuditTest could pass while measuring
        // nothing: a wrong path, an empty walk, "Executed 0 keys".
        val uses = SourceKeys.uses(File("src/main/kotlin/com/oshi/desktop"))
        assertTrue("the scan of the real source found ${uses.size} uses; expected at least 10", uses.size >= 10)
        assertFalse("every use must name a file and a line",
            uses.any { it.line <= 0 || !it.file.isFile })
        println("[i18n] source scan: ${uses.size} key uses across " +
            "${uses.map { it.file.name }.distinct().size} file(s): " +
            uses.map { it.file.name }.distinct().sorted())
    }
}
