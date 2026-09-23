package com.oshi.desktop.store

import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /**
     * Resolve the OS store — and, when the caller says there must be one, REFUSE to skip.
     *
     * On a developer's laptop "no OS secret store on this machine" is a legitimate skip.
     * In CI it is the entire reason the job exists: PARITY.md row 0.5 is amber on Windows
     * and Linux precisely because the DPAPI and libsecret backends had never been RUN, and
     * a Windows job whose only secret-store test assumed itself away would move that row
     * to green having measured nothing. `OSHI_EXPECT_SECRET_STORE` is how CI says "there
     * is a key store here and it must be THIS one":
     *
     *     OSHI_EXPECT_SECRET_STORE=Keychain    (macOS runner)
     *     OSHI_EXPECT_SECRET_STORE=DPAPI       (Windows runner)
     *     OSHI_EXPECT_SECRET_STORE=libsecret   (Linux runner, gnome-keyring started first)
     *
     * It checks the BACKEND NAME, not merely non-null, because "some store was found" is
     * not the claim being made — a Linux job that quietly fell through to a different
     * backend would prove nothing about libsecret.
     *
     * WATCHED FAILING (2026-08-25, macOS):
     *
     *     OSHI_EXPECT_SECRET_STORE=libsecret ./gradlew test --tests '*SecretStoreTest'
     *     SecretStoreTest > the real OS store round trips a master key FAILED
     *       java.lang.AssertionError: OSHI_EXPECT_SECRET_STORE=libsecret, but the
     *       detected backend is 'macOS Keychain'.
     *
     * — three tests that pass unconditionally without the guard, and that used to SKIP
     * silently on any machine without a key store.
     */
    private fun requiredStore(): SecretStore? {
        val expected = System.getenv("OSHI_EXPECT_SECRET_STORE")?.takeIf { it.isNotBlank() }
        val s = SecretStore.detect(service)
        if (expected == null) {
            assumeTrue("no OS secret store on this machine", s != null)
            return s
        }
        if (s == null) {
            fail(
                "OSHI_EXPECT_SECRET_STORE=$expected, but SecretStore.detect() found NO usable store on " +
                    "${System.getProperty("os.name")}. This test must not be skipped here: skipping is how " +
                    "an unrun backend gets reported as working. Either the key store daemon is not running " +
                    "(Linux: gnome-keyring + a session D-Bus) or the backend's isUsable() probe is wrong."
            )
        }
        if (!s!!.id.contains(expected, ignoreCase = true)) {
            fail("OSHI_EXPECT_SECRET_STORE=$expected, but the detected backend is '${s.id}'.")
        }
        return s
    }

    @After
    fun tearDown() {
        store?.let { s -> accounts.forEach { runCatching { s.delete(it) } } }
    }

    @Test
    fun `the real OS store round trips a master key`() {
        val s = requiredStore()
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
        val s = requiredStore()
        store = s
        assertNull(s!!.get("never-written-${System.nanoTime()}"))
    }

    /**
     * The whole vault, on the real OS store — the path a user actually takes on first
     * run. Separate from KeyVaultTest, which uses an in-memory store to stay hermetic.
     */
    @Test
    fun `a vault backed by the real OS store survives a reopen`() {
        val s = requiredStore()
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
    fun `malformed secret-store output becomes a named store error`() {
        try {
            decodeStoredSecret("not base64!", "test store")
            fail("malformed helper output escaped the secret-store error contract")
        } catch (e: SecretStoreException) {
            assertTrue(e.message!!.contains("test store"))
            assertTrue(e.message!!.contains("malformed base64"))
            assertTrue(e.cause is IllegalArgumentException)
        }
    }

    @Test
    fun `blank secret-store output remains a missing entry`() {
        assertNull(decodeStoredSecret(" \n", "test store"))
        assertArrayEquals(byteArrayOf(1, 2), decodeStoredSecret("AQI=\n", "test store"))
    }

    @Test
    fun `Linux secret tool prefers the trusted system path over PATH`() {
        val resolved = LinuxSecretToolStore.resolveSecretTool(
            pathLookup = { "/attacker/bin/secret-tool" },
            isExecutable = { it == "/usr/bin/secret-tool" },
        )
        assertEquals("/usr/bin/secret-tool", resolved)
    }

    @Test
    fun `Linux secret tool falls back only when the trusted binary is unavailable`() {
        val resolved = LinuxSecretToolStore.resolveSecretTool(
            pathLookup = { "/opt/minimal/bin/secret-tool" },
            isExecutable = { false },
        )
        assertEquals("/opt/minimal/bin/secret-tool", resolved)
    }

    @Test
    fun `Windows DPAPI prefers SystemRoot PowerShell over PATH`() {
        val root = "/windows"
        val trusted = File(root, "System32/WindowsPowerShell/v1.0/powershell.exe").path
        val resolved = WindowsDpapiStore.resolvePowerShell(
            systemRoot = root,
            pathLookup = { "/attacker/powershell.exe" },
            isExecutable = { it == trusted },
        )
        assertEquals(trusted, resolved)
    }

    @Test
    fun `Windows DPAPI falls back to PATH only without SystemRoot PowerShell`() {
        val resolved = WindowsDpapiStore.resolvePowerShell(
            systemRoot = "/windows",
            pathLookup = { binary -> if (binary == "powershell.exe") "/minimal/powershell.exe" else null },
            isExecutable = { false },
        )
        assertEquals("/minimal/powershell.exe", resolved)
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
