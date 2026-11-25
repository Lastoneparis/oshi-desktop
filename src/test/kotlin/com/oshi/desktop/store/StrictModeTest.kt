package com.oshi.desktop.store

import com.oshi.desktop.gif.GiphyClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** __STRICT_MODE_2026_11_22__ state, persistence, the passcode to turn it off, and every forced value. */
class StrictModeTest {
    private lateinit var dir: File
    private var savedIterations = 0

    @Before fun setUp() {
        savedIterations = AppLock.ITERATIONS
        dir = Files.createTempDirectory("strict-mode").toFile()
        StrictMode.attachForTest(null)
    }

    @After fun tearDown() {
        StrictMode.attachForTest(null)
        AppLock.attachForTest(null)
        AppLock.ITERATIONS = savedIterations
        dir.deleteRecursively()
    }

    @Test fun off_by_default_and_a_missing_or_corrupt_file_reads_as_off() {
        assertEquals(StrictMode.Values(), StrictMode.read(File(dir, "nope.json")))
        val bad = File(dir, StrictMode.FILE_NAME).apply { writeText("{not json") }
        assertFalse(StrictMode.read(bad).enabled)
        val d = StrictMode.Values()
        assertFalse(d.enabled); assertTrue(d.blockPaste); assertTrue(d.receiptsOff); assertFalse(d.keepScreenReader)
    }

    @Test fun state_persists_across_attach() {
        StrictMode.attach(dir)
        StrictMode.enable()
        StrictMode.setBlockPaste(false)
        StrictMode.setKeepScreenReader(true)
        StrictMode.attachForTest(null)
        StrictMode.attach(dir)
        assertEquals(StrictMode.Values(enabled = true, blockPaste = false, receiptsOff = true, keepScreenReader = true), StrictMode.values)
        val json = File(dir, StrictMode.FILE_NAME).readText()
        assertTrue(json, json.contains("strict_mode_enabled"))
    }

    @Test fun turning_off_needs_the_passcode_when_one_is_set() {
        AppLock.ITERATIONS = 1_000
        AppLock.attachForTest(File(dir, AppLock.FILE_NAME))
        assertTrue(AppLock.setPasscode("2468"))
        StrictMode.attach(dir)
        StrictMode.enable()
        assertEquals(StrictMode.DisableResult.PasscodeRequired, StrictMode.disable(null))
        assertEquals(StrictMode.DisableResult.WrongPasscode, StrictMode.disable("1111"))
        assertTrue(StrictMode.enabled)
        // a wrong code here is not a lock-screen failure
        assertEquals(0, AppLock.config.failures)
        assertEquals(StrictMode.DisableResult.Done, StrictMode.disable("2468"))
        assertFalse(StrictMode.enabled)
    }

    @Test fun without_a_passcode_it_turns_off_directly() {
        StrictMode.attach(dir)
        StrictMode.enable()
        assertEquals(StrictMode.DisableResult.Done, StrictMode.disable(null, AppLock.Config()))
        assertFalse(StrictMode.enabled)
    }

    @Test fun listeners_hear_each_change_once() {
        val seen = mutableListOf<Pair<Boolean, Boolean>>()
        val l: (StrictMode.Values, StrictMode.Values) -> Unit = { o, n -> seen += o.enabled to n.enabled }
        StrictMode.addChangeListener(l)
        try {
            StrictMode.enable(); StrictMode.enable()
            StrictMode.disable(null, AppLock.Config())
        } finally { StrictMode.removeChangeListener(l) }
        assertEquals(listOf(false to true, true to false), seen)
    }

    // ---------------------------------------------------------------- each guard switch

    private val on = StrictMode.Values(enabled = true)
    private val off = StrictMode.Values()

    @Test fun copy_and_paste() {
        assertTrue(StrictMode.copyAllowed(off)); assertFalse(StrictMode.copyAllowed(on))
        assertTrue(StrictMode.pasteAllowed(off)); assertFalse(StrictMode.pasteAllowed(on))
        assertTrue(StrictMode.pasteAllowed(on.copy(blockPaste = false)))
        assertTrue("block paste alone does nothing while the mode is off", StrictMode.pasteAllowed(off.copy(blockPaste = true)))
    }

    @Test fun capture_notifications_receipts_gif_relay_lock_a11y_clipboard() {
        assertTrue(StrictMode.captureProtected(false, on)); assertFalse(StrictMode.captureProtected(false, off))
        assertTrue(StrictMode.captureProtected(true, off))
        assertFalse(StrictMode.notificationPreview(true, on)); assertTrue(StrictMode.notificationPreview(true, off))
        assertFalse(StrictMode.readReceipts(true, on)); assertFalse(StrictMode.typing(true, on))
        assertTrue("receipts sub-toggle off keeps the person's choice", StrictMode.readReceipts(true, on.copy(receiptsOff = false)))
        assertTrue(StrictMode.typing(true, on.copy(receiptsOff = false)))
        assertFalse(StrictMode.readReceipts(false, off))
        assertFalse(StrictMode.gifSearch(on)); assertTrue(StrictMode.gifSearch(off))
        assertTrue(StrictMode.privacyRelay(false, available = true, v = on))
        assertFalse("never 'on' where no relay exists", StrictMode.privacyRelay(false, available = false, v = on))
        assertFalse(StrictMode.privacyRelay(false, available = true, v = off))
        assertTrue(StrictMode.lockOnLeave(true, on)); assertFalse(StrictMode.lockOnLeave(false, on)); assertFalse(StrictMode.lockOnLeave(true, off))
        assertFalse(StrictMode.exposeMessageText(on)); assertTrue(StrictMode.exposeMessageText(on.copy(keepScreenReader = true)))
        assertTrue(StrictMode.exposeMessageText(off))
        assertEquals(StrictMode.STRICT_CLIPBOARD_MS, StrictMode.clipboardClearMs(60_000, on))
        assertEquals(60_000L, StrictMode.clipboardClearMs(60_000, off))
    }

    // ---------------------------------------------------------------- restore of previous values

    @Test fun the_persons_own_settings_are_never_rewritten_so_turning_off_restores_them() {
        val notif = NotificationPreferences(File(dir, NotificationPreferences.FILE_NAME))
        notif.save(NotificationPreferences.Values(preview = true))
        val receipts = ReceiptPreferences(File(dir, ReceiptPreferences.FILE_NAME))
        receipts.save(ReceiptPreferences.Values(read = true, typing = true))
        ScreenCapturePreference.attach(dir)
        ScreenCapturePreference.set(false)
        val before = dir.listFiles()!!.filter { it.name != StrictMode.FILE_NAME }.associate { it.name to it.readText() }

        StrictMode.attach(dir)
        StrictMode.enable()
        assertFalse(StrictMode.notificationPreview(notif.load().preview))
        assertFalse(StrictMode.readReceipts(receipts.load().read))
        assertTrue(ScreenCapturePreference.effective)
        assertFalse("the person's switch itself is untouched", ScreenCapturePreference.enabled)

        assertEquals(StrictMode.DisableResult.Done, StrictMode.disable(null, AppLock.Config()))
        assertTrue(StrictMode.notificationPreview(notif.load().preview))
        assertTrue(StrictMode.readReceipts(receipts.load().read))
        assertFalse(ScreenCapturePreference.effective)
        val after = dir.listFiles()!!.filter { it.name != StrictMode.FILE_NAME }.associate { it.name to it.readText() }
        assertEquals(before, after)
        ScreenCapturePreference.set(true)
    }

    @Test fun giphy_is_not_called_in_strict_mode() {
        StrictMode.preview(on)
        // A key is set, so outside strict mode this would go to the network; inside it must not.
        val giphy = GiphyClient(apiKey = "test-key-not-used")
        assertEquals(emptyList<GiphyClient.Item>(), giphy.search("cats", GiphyClient.Kind.GIF))
        assertEquals(emptyList<GiphyClient.Item>(), giphy.trending(GiphyClient.Kind.STICKER))
    }
}
