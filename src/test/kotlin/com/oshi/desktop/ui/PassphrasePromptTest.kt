package com.oshi.desktop.ui

import com.oshi.desktop.store.KeyVault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * __NO_KEYRING_PASSPHRASE_WINDOW_2026_11_21__ The window that replaced "set OSHI_PASSPHRASE" on a
 * machine with no usable keyring. Driven through its seams against a REAL passphrase vault.
 */
class PassphrasePromptTest {

    private fun tmpVault(): File = File(Files.createTempDirectory("oshi-pass").toFile(), KeyVault.FILE_NAME)

    @Test
    fun `a new passphrase must be long enough and typed twice the same`() {
        assertEquals(PassphrasePrompt.Problem.TOO_SHORT, PassphrasePrompt.problem(true, "", ""))
        assertEquals(PassphrasePrompt.Problem.TOO_SHORT, PassphrasePrompt.problem(true, "short", "short"))
        assertEquals(PassphrasePrompt.Problem.MISMATCH, PassphrasePrompt.problem(true, "correct horse", "correct hose"))
        assertNull(PassphrasePrompt.problem(true, "correct horse", "correct horse"))
        // Unlocking an existing vault: any non-empty passphrase is tried (the vault decides).
        assertNull(PassphrasePrompt.problem(false, "x", ""))
    }

    @Test
    fun `a fresh profile asks in create mode and keeps what was typed`() {
        val vault = tmpVault()
        val modes = ArrayList<Boolean>()
        val got = PassphrasePrompt.obtain(vault, ask = { creating, _ -> modes += creating; "correct horse".toCharArray() })
        assertArrayEquals("correct horse".toCharArray(), got)
        assertEquals(listOf(true), modes)
    }

    @Test
    fun `a wrong passphrase is asked again with a reason, never opened as an empty vault`() {
        val vault = tmpVault()
        // Make a real passphrase vault holding one entry.
        KeyVault.open(vault, null, "right passphrase".toCharArray()).apply { put("probe", byteArrayOf(1, 2, 3)) }
        assertTrue(vault.isFile)
        val errors = ArrayList<String?>()
        val answers = ArrayDeque(listOf("wrong passphrase", "right passphrase"))
        val got = PassphrasePrompt.obtain(vault, ask = { creating, error ->
            assertEquals(false, creating)
            errors += error
            answers.removeFirst().toCharArray()
        })
        assertArrayEquals("right passphrase".toCharArray(), got)
        assertEquals(2, errors.size)
        assertNull(errors[0])
        assertTrue("second ask must say why", !errors[1].isNullOrBlank())
        assertArrayEquals(byteArrayOf(1, 2, 3), KeyVault.open(vault, null, got).get("probe"))
    }

    @Test
    fun `quitting the window returns null and leaves no vault behind`() {
        val vault = tmpVault()
        assertNull(PassphrasePrompt.obtain(vault, ask = { _, _ -> null }))
        assertTrue(!vault.exists())
    }

    @Test
    fun `an unreadable vault is not mistaken for a wrong passphrase`() {
        val vault = tmpVault()
        vault.writeText("not json")
        try {
            PassphrasePrompt.obtain(vault, ask = { _, _ -> "whatever123".toCharArray() })
            fail("a corrupt vault must reach the failure window, not loop on the passphrase")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("JSON"))
        }
    }
}
