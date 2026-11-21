package com.oshi.desktop.store

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLockTest {
    @get:Rule val tmp = TemporaryFolder()
    private var saved = 0

    @Before fun fast() { saved = AppLock.ITERATIONS; AppLock.ITERATIONS = 1_000 }
    @After fun restore() { AppLock.ITERATIONS = saved; AppLock.attachForTest(null) }

    @Test
    fun `the passcode is never written, only a salted hash`() {
        val f = tmp.root.resolve(AppLock.FILE_NAME)
        AppLock.attachForTest(f)
        assertTrue(AppLock.setPasscode("2468"))
        val text = f.readText()
        assertFalse("passcode in clear", text.contains("2468"))
        val o = JSONObject(text)
        assertTrue(o.has("salt") && o.has("hash"))
        // Same passcode twice ⇒ different salt and hash.
        val first = o.getString("hash")
        assertTrue(AppLock.setPasscode("2468", current = "2468"))
        assertTrue(first != JSONObject(f.readText()).getString("hash"))
    }

    @Test
    fun `a set passcode locks at launch and the right one unlocks`() {
        val f = tmp.root.resolve(AppLock.FILE_NAME)
        AppLock.attachForTest(f)
        assertFalse(AppLock.locked)
        AppLock.setPasscode("1357")
        AppLock.attachForTest(f) // relaunch
        assertTrue(AppLock.locked)
        assertTrue(AppLock.unlock("0000") is AppLock.Attempt.Wrong)
        assertTrue(AppLock.locked)
        assertEquals(AppLock.Attempt.Unlocked, AppLock.unlock("1357"))
        assertFalse(AppLock.locked)
        assertEquals(0, AppLock.config.failures)
    }

    @Test
    fun `changing or removing needs the current passcode`() {
        AppLock.attachForTest(tmp.root.resolve(AppLock.FILE_NAME))
        AppLock.setPasscode("1111")
        assertFalse(AppLock.setPasscode("2222"))
        assertFalse(AppLock.setPasscode("2222", current = "9999"))
        assertFalse(AppLock.removePasscode("9999"))
        assertTrue(AppLock.removePasscode("1111"))
        assertFalse(AppLock.config.hasPasscode)
    }

    @Test
    fun `too short is refused`() {
        assertFalse(AppLock.isValidNew("123"))
        assertFalse(AppLock.isValidNew("    "))
        assertTrue(AppLock.isValidNew("1234"))
    }

    @Test
    fun `five free misses, then waits that double and cap — and nothing is ever erased`() {
        assertEquals(0, AppLock.waitAfter(4))
        assertEquals(30_000, AppLock.waitAfter(5))
        assertEquals(60_000, AppLock.waitAfter(6))
        assertEquals(AppLock.MAX_WAIT_MS, AppLock.waitAfter(40))
        var c = AppLock.Config().let { base ->
            val salt = ByteArray(16) { 1 }
            base.copy(saltB64 = java.util.Base64.getEncoder().encodeToString(salt),
                hashB64 = java.util.Base64.getEncoder().encodeToString(AppLock.derive("4321", salt, 1_000)), iterations = 1_000)
        }
        var now = 1_000_000L
        repeat(5) { c = AppLock.attempt(c, "0000", now).second }
        val (waiting, c2) = AppLock.attempt(c, "4321", now + 1_000)
        assertTrue("the right passcode is not even checked during a wait", waiting is AppLock.Attempt.Waiting)
        assertTrue(c2.hasPasscode)
        now += 31_000
        assertEquals(AppLock.Attempt.Unlocked, AppLock.attempt(c2, "4321", now).first)
    }

    @Test
    fun `the recovery key unlocks at any time and removes the passcode`() {
        val f = tmp.root.resolve(AppLock.FILE_NAME)
        AppLock.attachForTest(f)
        AppLock.setPasscode("8642")
        AppLock.attachForTest(f)
        repeat(7) { AppLock.unlock("0000") }
        assertTrue(AppLock.config.waitUntilMs > System.currentTimeMillis())
        assertFalse(AppLock.unlockWithRecoveryKey("not-it") { it == "the-key" })
        assertFalse(AppLock.unlockWithRecoveryKey("x") { error("boom") })
        assertTrue(AppLock.locked)
        assertTrue(AppLock.unlockWithRecoveryKey("  the-key ") { it == "the-key" })
        assertFalse(AppLock.locked)
        assertFalse(AppLock.config.hasPasscode)
        assertEquals(0L, AppLock.config.waitUntilMs)
    }

    @Test
    fun `auto-lock only after the chosen time without input, never without a passcode`() {
        val c = AppLock.Config(saltB64 = "AA==", hashB64 = "AA==", autoLockMinutes = 5)
        assertFalse(AppLock.shouldAutoLock(c, false, 0, 4 * 60_000L))
        assertTrue(AppLock.shouldAutoLock(c, false, 0, 5 * 60_000L))
        assertFalse(AppLock.shouldAutoLock(c.copy(autoLockMinutes = 0), false, 0, 99 * 60_000L))
        assertFalse(AppLock.shouldAutoLock(AppLock.Config(), false, 0, 99 * 60_000L))
    }

    @Test
    fun `an unreadable file does not lock anyone out`() {
        val f = tmp.newFile(AppLock.FILE_NAME).apply { writeText("{{{") }
        AppLock.attachForTest(f)
        assertFalse(AppLock.locked)
    }
}
