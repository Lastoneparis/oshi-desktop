package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.SystemFlavorMap
import java.io.InputStream

/** __CLIPBOARD_HISTORY_LEAK_2026_11_20__ */
class SensitiveClipboardTest {

    @Test
    fun `a copy carries the three Windows exclusion formats with a zero DWORD`() {
        val sel = SensitiveClipboard.selection("secret")
        assertEquals("secret", sel.getTransferData(DataFlavor.stringFlavor))
        val map = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
        val natives = sel.transferDataFlavors.filter { it != DataFlavor.stringFlavor }.flatMap { map.getNativesForFlavor(it) }
        for (f in SensitiveClipboard.WINDOWS_EXCLUSION_FORMATS) assertTrue("$f not mapped: $natives", f in natives)
        val extra = sel.transferDataFlavors.first { it != DataFlavor.stringFlavor }
        assertEquals(listOf<Byte>(0, 0, 0, 0), (sel.getTransferData(extra) as InputStream).readBytes().toList())
    }

    @Test
    fun `the timed clear empties only our own copy`() {
        val cb = Clipboard("test")
        val sel = SensitiveClipboard.selection("secret")
        cb.setContents(sel, sel)
        SensitiveClipboard.clearIfStillOurs(cb, sel)
        assertEquals("", cb.getData(DataFlavor.stringFlavor))

        val mine = SensitiveClipboard.selection("secret2")
        cb.setContents(mine, mine)
        cb.setContents(StringSelection("the person copied this elsewhere"), null) // we lose ownership
        java.awt.EventQueue.invokeAndWait {} // lostOwnership is delivered on the event queue
        assertFalse(mine.owned)
        SensitiveClipboard.clearIfStillOurs(cb, mine)
        assertEquals("the person copied this elsewhere", cb.getData(DataFlavor.stringFlavor))
    }
}
