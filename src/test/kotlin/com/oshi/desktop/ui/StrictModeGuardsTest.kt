package com.oshi.desktop.ui

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.oshi.desktop.store.StrictMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** __STRICT_MODE_2026_11_22__ composer keys, the guarded composer clipboard, clipboard clearing. */
class StrictModeGuardsTest {
    @After fun reset() = StrictMode.attachForTest(null)

    private val on = StrictMode.Values(enabled = true)

    @Test fun composer_keys() {
        // copy/cut swallowed in strict mode; paste only with "block paste"
        assertTrue(StrictModeGuards.swallow(isCopyOrCut = true, isPaste = false, v = on))
        assertTrue(StrictModeGuards.swallow(isCopyOrCut = false, isPaste = true, v = on))
        assertFalse(StrictModeGuards.swallow(isCopyOrCut = false, isPaste = true, v = on.copy(blockPaste = false)))
        assertTrue(StrictModeGuards.swallow(isCopyOrCut = true, isPaste = false, v = on.copy(blockPaste = false)))
        // outside strict mode nothing is swallowed
        assertFalse(StrictModeGuards.swallow(isCopyOrCut = true, isPaste = false, v = StrictMode.Values()))
        assertFalse(StrictModeGuards.swallow(isCopyOrCut = false, isPaste = true, v = StrictMode.Values()))
        assertFalse(StrictModeGuards.swallow(isCopyOrCut = false, isPaste = false, v = on))
    }

    private class FakeManager(var text: String? = "from another app") : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) { text = annotatedString.text }
        override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)
    }

    @Test fun guarded_composer_clipboard_never_lets_text_out_and_paste_in_only_when_allowed() {
        val real = FakeManager()
        val blocked = GuardedClipboardManager(real, allowPaste = false)
        blocked.setText(AnnotatedString("secret draft"))
        assertEquals("from another app", real.text)
        assertNull(blocked.getText())
        assertFalse(blocked.hasText())

        val allowed = GuardedClipboardManager(real, allowPaste = true)
        allowed.setText(AnnotatedString("secret draft"))
        assertEquals("copy out stays blocked even when paste is allowed", "from another app", real.text)
        assertEquals("from another app", allowed.getText()?.text)
    }

    @Test fun clipboard_is_cleared_now_only_if_it_still_holds_oshis_copy() {
        val cb = Clipboard("test")
        SensitiveClipboard.copy("a message", cb)
        assertEquals("a message", cb.getData(DataFlavor.stringFlavor))
        assertTrue(SensitiveClipboard.clearNow())
        assertEquals("", cb.getData(DataFlavor.stringFlavor))

        SensitiveClipboard.copy("another", cb)
        cb.setContents(StringSelection("the person's own copy elsewhere"), null)   // ownership lost
        assertFalse(SensitiveClipboard.clearNow())
        assertEquals("the person's own copy elsewhere", cb.getData(DataFlavor.stringFlavor))
    }

    @Test fun message_text_is_withheld_from_accessibility_only_without_screen_reader_support() {
        StrictMode.preview(on)
        assertFalse(StrictMode.exposeMessageText())
        StrictMode.preview(on.copy(keepScreenReader = true))
        assertTrue(StrictMode.exposeMessageText())
    }
}
