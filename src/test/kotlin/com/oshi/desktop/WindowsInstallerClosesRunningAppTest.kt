package com.oshi.desktop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * __CLOSE_RUNNING_OSHI_2026_11_20__ Closing the window hides OSHI in the notification area,
 * so installs find it running. PC smoke run on the published 1.5.0 (windows-latest, run
 * 44712414508): an upgrade over a running 1.4.5 ended 3010 with 129 files deferred to a
 * reboot and the OLD process still answering every launch; an uninstall left two OSHI
 * processes and 59 files. The MSI must end OSHI before RemoveExistingProducts and
 * InstallValidate, and must be jpackage 21's template (the release JDK).
 */
class WindowsInstallerClosesRunningAppTest {

    private val wxs = listOf(File("platform/windows/packaging/main.wxs"), File("OSHI-Desktop/platform/windows/packaging/main.wxs"))
        .first { it.isFile }

    private val doc by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(wxs)
    }

    private fun elements(tag: String): List<Element> {
        val nl = doc.getElementsByTagNameNS("*", tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    @Test
    fun `main wxs is well formed and is jpackage 21's template`() {
        assertTrue("jpackage 21 adds JpFindRelatedProducts; a 17 template would not have it",
            elements("CustomAction").any { it.getAttribute("Id") == "JpFindRelatedProducts" })
        assertTrue(elements("RemoveExistingProducts").single().getAttribute("Before") == "CostInitialize")
    }

    @Test
    fun `this user's OSHI and OSHI-Console are ended before the old version is removed`() {
        val ca = elements("CustomAction").single { it.getAttribute("Id") == "OshiCloseRunningApp" }
        assertEquals("immediate", ca.getAttribute("Execute"))
        assertEquals("ignore", ca.getAttribute("Return"))
        val cmd = elements("SetProperty").single { it.getAttribute("Id") == "WixQuietExec64CmdLine" }.getAttribute("Value")
        listOf("taskkill.exe", "/IM OSHI.exe", "/IM OSHI-Console.exe", "USERNAME eq [LogonUser]").forEach {
            assertTrue("command lacks '$it': $cmd", cmd.contains(it))
        }
        val seq = elements("InstallExecuteSequence").single()
        val custom = (0 until seq.childNodes.length).map { seq.childNodes.item(it) }.filterIsInstance<Element>()
            .single { it.getAttribute("Action") == "OshiCloseRunningApp" }
        // JpFindRelatedProducts runs right after FindRelatedProducts (25), long before
        // RemoveExistingProducts (Before CostInitialize, 800) and InstallValidate (1400).
        assertEquals("JpFindRelatedProducts", custom.getAttribute("After"))
    }
}
