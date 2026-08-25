package com.oshi.desktop.store

import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The OS-backed stores, exercised against the REAL store where this machine has one.
 *
 * A mocked keychain would prove nothing here: the whole content of these classes is what
 * a specific command-line tool does with specific arguments, and the bug this file was
 * written after is exactly that kind — `security` accepts a password on stdin, exits 0,
 * and stores an EMPTY value. Every backend therefore reads its own write back before
 * reporting success, and this test checks that on the real store.
 *
 * The entries are filed under a per-run service name and removed afterwards, so nothing
 * of the user's own keychain is touched.
 */
class SecretStoreTest {

    private val service = "com.oshi.desktop.test.${ProcessHandle.current().pid()}"
    private val accounts = mutableListOf<String>()
    private var store: SecretStore? = null

    @After
    fun tearDown() {
        store?.let { s -> accounts.forEach { runCatching { s.delete(it) } } }
    }

    @Test
    fun `the real OS store round trips a master key`() {
        val s = SecretStore.detect(service)
        assumeTrue("no OS secret store on this machine", s != null)
        store = s
        val account = "master-key-test"
        accounts.add(account)

        val secret = ByteArray(32) { (it * 7).toByte() }
        s!!.put(account, secret)
        assertArrayEquals("what came back is not what went in — ${s.id}", secret, s.get(account))

        val replaced = ByteArray(32) { (it * 13 + 1).toByte() }
        s.put(account, replaced)
        assertArrayEquals("an overwrite did not take — ${s.id}", replaced, s.get(account))

        s.delete(account)
        assertNull("delete left the entry behind — ${s.id}", s.get(account))
    }

    @Test
    fun `a missing entry is null, not an exception`() {
        val s = SecretStore.detect(service)
        assumeTrue("no OS secret store on this machine", s != null)
        store = s
        assertNull(s!!.get("never-written-${System.nanoTime()}"))
    }

    /**
     * The whole vault, on the real OS store — the path a user actually takes on first
     * run. Separate from KeyVaultTest, which uses an in-memory store to stay hermetic.
     */
    @Test
    fun `a vault backed by the real OS store survives a reopen`() {
        val s = SecretStore.detect(service)
        assumeTrue("no OS secret store on this machine", s != null)
        store = s
        accounts.add(KeyVault.MASTER_ACCOUNT)

        val dir = java.nio.file.Files.createTempDirectory("oshi-vault-os").toFile()
        try {
            val file = File(dir, KeyVault.FILE_NAME)
            val secret = ByteArray(32) { 21 }
            KeyVault.open(file, s).put("identity-x25519", secret)
            assertArrayEquals(secret, KeyVault.open(file, s).get("identity-x25519"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the in-memory store hands out copies, never its own array`() {
        val s = InMemorySecretStore()
        val secret = ByteArray(4) { 1 }
        s.put("a", secret)
        secret.fill(9)
        assertArrayEquals(ByteArray(4) { 1 }, s.get("a"))
        s.get("a")!!.fill(9)
        assertArrayEquals(ByteArray(4) { 1 }, s.get("a"))
    }

    @Test
    fun `which finds an executable that exists and nothing that does not`() {
        val known = if (DesktopPaths.isWindows) "cmd.exe" else "sh"
        assertNotNull("PATH lookup failed for $known", Proc.which(known))
        assertNull(Proc.which("definitely-not-a-real-binary-${System.nanoTime()}"))
    }

    @Test
    fun `a process that never exits is killed rather than hanging the caller`() {
        assumeTrue("POSIX only", !DesktopPaths.isWindows)
        val r = Proc.run(listOf("/bin/sh", "-c", "sleep 30"), timeoutSeconds = 1)
        assertTrue("the runner did not time out", r.timedOut)
    }

    @Test
    fun `secrets are passed to a child through the environment, never on the command line`() {
        assumeTrue("POSIX only", !DesktopPaths.isWindows)
        // The same shape MacKeychainStore uses: the value is expanded INSIDE the child,
        // so this process's argv — and `ps` — only ever see the variable name.
        val r = Proc.run(
            listOf("/bin/sh", "-c", "printf '%s' \"\$OSHI_SECRET_IN\""),
            env = mapOf("OSHI_SECRET_IN" to "top-secret"),
        )
        assertTrue(r.stdout == "top-secret")
    }
}
