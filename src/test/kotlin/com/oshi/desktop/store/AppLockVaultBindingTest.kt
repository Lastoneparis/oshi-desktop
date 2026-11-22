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

/**
 * __APP_LOCK_FILE_DELETE_BYPASS_2026_11_20__ Before the fix, `rm app-lock.json` (or setting
 * `failures` back to 0) removed the passcode: [AppLock.attach] read a missing file as "no
 * passcode". Once bound to the vault, the vault decides.
 */
class AppLockVaultBindingTest {
    @get:Rule val tmp = TemporaryFolder()
    private var saved = 0

    @Before fun fast() { saved = AppLock.ITERATIONS; AppLock.ITERATIONS = 1_000 }
    @After fun restore() { AppLock.ITERATIONS = saved; AppLock.attachForTest(null) }

    private fun vault() = KeyVault.open(tmp.root.resolve("keyvault.json"), null, "pass-for-test".toCharArray())

    @Test
    fun `the unbound legacy file alone is bypassable - the defect`() {
        AppLock.attach(tmp.root)
        AppLock.setPasscode("2468")
        tmp.root.resolve(AppLock.FILE_NAME).delete()
        AppLock.attach(tmp.root) // relaunch without the vault binding = 1.5.1 behaviour
        assertFalse(AppLock.locked)
    }

    @Test
    fun `deleting app-lock json no longer unlocks once bound`() {
        AppLock.attach(tmp.root)
        AppLock.bindToVault(vault())
        assertTrue(AppLock.setPasscode("2468"))
        val file = tmp.root.resolve(AppLock.FILE_NAME)
        val onDisk = JSONObject(file.readText())
        assertFalse("hash must not be in the file", onDisk.has("hash") || onDisk.has("salt"))

        file.delete()
        AppLock.attach(tmp.root)
        AppLock.bindToVault(vault())
        assertTrue(AppLock.locked)
        assertTrue(AppLock.unlock("0000") is AppLock.Attempt.Wrong)
        assertEquals(AppLock.Attempt.Unlocked, AppLock.unlock("2468"))
    }

    @Test
    fun `a legacy passcode is migrated into the vault and failure counters cannot be reset by editing the file`() {
        AppLock.attach(tmp.root)
        AppLock.setPasscode("1357") // legacy: secret in file
        AppLock.attach(tmp.root)
        AppLock.bindToVault(vault())
        assertTrue(AppLock.locked)
        repeat(AppLock.FREE_ATTEMPTS) { AppLock.unlock("0000") }
        val waitUntil = AppLock.config.waitUntilMs
        assertTrue(waitUntil > 0)
        // attacker rewrites the file with zero counters
        tmp.root.resolve(AppLock.FILE_NAME).writeText("""{"v":1,"failures":0,"waitUntilMs":0,"autoLockMinutes":5}""")
        AppLock.attach(tmp.root)
        AppLock.bindToVault(vault())
        assertTrue(AppLock.locked)
        assertEquals(waitUntil, AppLock.config.waitUntilMs)
        assertTrue(AppLock.unlock("1357") is AppLock.Attempt.Waiting)
    }

    @Test
    fun `recovery key still removes the passcode from the vault`() {
        AppLock.attach(tmp.root)
        AppLock.bindToVault(vault())
        AppLock.setPasscode("2468")
        assertTrue(AppLock.unlockWithRecoveryKey("rk") { it == "rk" })
        AppLock.attach(tmp.root)
        AppLock.bindToVault(vault())
        assertFalse(AppLock.locked)
    }
}
