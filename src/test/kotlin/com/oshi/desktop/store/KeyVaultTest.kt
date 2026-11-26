package com.oshi.desktop.store

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The vault, and above all the ways it must refuse to work.
 *
 * Half of these tests assert on FAILURE, because the dangerous outcome here is not a
 * crash — it is a vault that quietly reads as empty. The layer above would then generate
 * a new identity, and a new identity is a new address: contacts keep writing to an
 * account nobody reads, and the app looks healthy the whole time.
 */
class KeyVaultTest {

    private val dir: File = Files.createTempDirectory("oshi-vault-test").toFile()
    private val file: File get() = File(dir, KeyVault.FILE_NAME)

    @After
    fun tearDown() { dir.deleteRecursively() }

    private fun store() = InMemorySecretStore()

    @Test
    fun `secrets survive a reopen`() {
        val s = store()
        val identity = ByteArray(32) { it.toByte() }
        KeyVault.open(file, s).put("identity-x25519", identity)

        val again = KeyVault.open(file, s)
        assertArrayEquals(identity, again.get("identity-x25519"))
        assertEquals(setOf("identity-x25519"), again.accounts())
        assertEquals("os-store", again.protectedBy)
    }

    @Test
    fun `a wrong master key fails loudly instead of reading as an empty vault`() {
        val real = store()
        val written = KeyVault.open(file, real).apply { put("identity-x25519", ByteArray(32) { 7 }) }

        val impostor = InMemorySecretStore().apply { put(written.masterKeyAccount!!, ByteArray(32) { 9 }) }
        try {
            val v = KeyVault.open(file, impostor)
            fail("opened with the wrong master key and reported ${v.accounts().size} entries — " +
                "this is the failure that silently replaces an account")
        } catch (e: KeyVaultException) {
            assertTrue("the error must name the file: ${e.message}", e.message!!.contains(file.absolutePath))
            assertTrue("the error must say why starting fresh is refused: ${e.message}",
                e.message!!.contains("NEW identity"))
        }
    }

    @Test
    fun `a missing master key is a recoverable error, not a fresh start`() {
        val s = store()
        val v = KeyVault.open(file, s).apply { put("identity-x25519", ByteArray(32) { 3 }) }
        s.delete(v.masterKeyAccount!!)

        try {
            KeyVault.open(file, s)
            fail("a vault with no master key must not open")
        } catch (e: KeyVaultException) {
            assertTrue("the error must tell the user where the key was: ${e.message}",
                e.message!!.contains(v.masterKeyAccount!!))
        }
    }

    @Test
    fun `a single flipped byte is caught by the GCM tag`() {
        val s = store()
        KeyVault.open(file, s).put("identity-x25519", ByteArray(32) { 5 })

        val text = file.readText()
        val o = org.json.JSONObject(text)
        val ct = java.util.Base64.getDecoder().decode(o.getString("ct"))
        ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 0x01).toByte()
        o.put("ct", java.util.Base64.getEncoder().encodeToString(ct))
        file.writeText(o.toString())

        try {
            KeyVault.open(file, s)
            fail("a modified vault must not decrypt")
        } catch (e: KeyVaultException) {
            assertTrue(e.message!!.contains("did not decrypt"))
        }
    }

    /**
     * The secret must not be on disk in any recoverable form. Checked against the raw
     * bytes, not against the format — a future field that accidentally carried the
     * plaintext would still be caught.
     */
    @Test
    fun `nothing readable is written to disk`() {
        val s = store()
        val secret = "SUPER-SECRET-PRIVATE-KEY-MATERIAL".toByteArray()
        KeyVault.open(file, s).put("identity-x25519", secret)

        val onDisk = file.readBytes()
        assertTrue("the raw secret is in the file", !contains(onDisk, secret))
        assertTrue("the base64 of the secret is in the file",
            !contains(onDisk, java.util.Base64.getEncoder().encodeToString(secret).toByteArray()))
        // The account NAME is not a secret and is expected to be visible only inside the
        // ciphertext too — the file must not leak which keys exist either.
        assertTrue("account names leak", !contains(onDisk, "identity-x25519".toByteArray()))
    }

    @Test
    fun `a passphrase vault round trips and rejects the wrong passphrase`() {
        val secret = ByteArray(32) { 11 }
        KeyVault.open(file, secretStore = null, passphrase = "correct horse".toCharArray())
            .put("identity-x25519", secret)

        assertArrayEquals(secret,
            KeyVault.open(file, secretStore = null, passphrase = "correct horse".toCharArray())
                .get("identity-x25519"))

        try {
            KeyVault.open(file, secretStore = null, passphrase = "wrong horse".toCharArray())
            fail("the wrong passphrase must not open the vault")
        } catch (e: KeyVaultException) {
            assertTrue(e.message!!.contains("did not decrypt"))
        }
    }

    /**
     * The scheme is a property of the FILE. Opening a passphrase vault on a machine that
     * happens to have a key store must not silently start a second, empty one over it.
     */
    @Test
    fun `the file decides the protection scheme, not the machine`() {
        KeyVault.open(file, secretStore = null, passphrase = "pw".toCharArray())
            .put("identity-x25519", ByteArray(32) { 1 })

        try {
            KeyVault.open(file, secretStore = store())   // an OS store IS available here
            fail("a passphrase vault must not be opened as an os-store vault")
        } catch (e: KeyVaultException) {
            assertTrue("must ask for the passphrase: ${e.message}", e.message!!.contains("passphrase is required"))
        }
        // …and with the passphrase it opens, on the same machine.
        assertArrayEquals(ByteArray(32) { 1 },
            KeyVault.open(file, secretStore = store(), passphrase = "pw".toCharArray()).get("identity-x25519"))
    }

    @Test
    fun `no key store and no passphrase writes nothing at all`() {
        try {
            KeyVault.open(file, secretStore = null, passphrase = null)
            fail("must refuse rather than write an unprotected vault")
        } catch (e: KeyVaultException) {
            assertTrue(e.message!!.contains("passphrase is required"))
        }
        assertTrue("a refused open must leave no file behind", !file.exists())
    }

    @Test
    fun `getOrCreate returns the same value on every call`() {
        val s = store()
        val v = KeyVault.open(file, s)
        val first = v.getOrCreate("session-key") { ByteArray(32) { 42 } }
        val second = v.getOrCreate("session-key") { ByteArray(32) { 99 } }
        assertArrayEquals(first, second)
        assertArrayEquals(first, KeyVault.open(file, s).get("session-key"))
    }

    @Test
    fun `a returned secret cannot be mutated through the caller's copy`() {
        val s = store()
        val v = KeyVault.open(file, s)
        v.put("k", ByteArray(4) { 1 })
        v.get("k")!!.fill(0)
        assertArrayEquals(ByteArray(4) { 1 }, v.get("k"))
    }

    @Test
    fun `putAll writes related secrets together and copies caller material`() {
        val s = store()
        val first = ByteArray(4) { 1 }
        val second = ByteArray(4) { 2 }
        KeyVault.open(file, s).putAll(mapOf("first" to first, "second" to second))
        first.fill(0)
        second.fill(0)

        val reopened = KeyVault.open(file, s)
        assertArrayEquals(ByteArray(4) { 1 }, reopened.get("first"))
        assertArrayEquals(ByteArray(4) { 2 }, reopened.get("second"))
    }

    @Test
    fun `destroy removes both the file and the master key`() {
        val s = store()
        val v = KeyVault.open(file, s)
        v.put("identity-x25519", ByteArray(32) { 4 })
        val account = v.masterKeyAccount!!
        v.destroy(s)
        assertTrue(!file.exists())
        assertNull(s.get(account))
    }

    // __PER_PROFILE_MASTER_KEY_2026_11_19__ ------------------------------------------------

    /** A vault byte-for-byte as 1.4.5 wrote it: no `keyAccount`, key under `master-key`. */
    private fun writeLegacyVault(target: File, s: SecretStore, entries: Map<String, ByteArray>): ByteArray {
        val key = ByteArray(32) { (it * 3 + 1).toByte() }
        s.put(KeyVault.MASTER_ACCOUNT, key)
        val plain = org.json.JSONObject()
        entries.forEach { (k, v) -> plain.put(k, java.util.Base64.getEncoder().encodeToString(v)) }
        val nonce = ByteArray(12) { 5 }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce))
        val ct = c.doFinal(plain.toString().toByteArray())
        val b64 = java.util.Base64.getEncoder()
        target.parentFile.mkdirs()
        target.writeText("{\"v\":1,\"protection\":\"os-store\",\"nonce\":\"${b64.encodeToString(nonce)}\",\"ct\":\"${b64.encodeToString(ct)}\"}")
        return key
    }

    @Test
    fun `a 1_4_5 vault still opens after the upgrade, and keeps opening under 1_4_5 after a downgrade`() {
        val s = store()
        val identity = ByteArray(32) { 42 }
        val legacyKey = writeLegacyVault(file, s, mapOf("identity-x25519" to identity))

        val v = KeyVault.open(file, s)
        assertArrayEquals(identity, v.get("identity-x25519"))
        val own = v.masterKeyAccount!!
        assertTrue("migrated to a per-vault entry: $own", own.startsWith(KeyVault.MASTER_ACCOUNT_PREFIX))
        assertArrayEquals("the per-vault entry holds the SAME key", legacyKey, s.get(own))
        assertArrayEquals("the legacy entry is copied, never moved", legacyKey, s.get(KeyVault.MASTER_ACCOUNT))
        assertTrue(file.readText().contains(own))

        // Reopen reads the recorded entry; and 1.4.5 (which ignores `keyAccount` and reads
        // `master-key`) can still decrypt the rewritten file, because the key did not change.
        assertArrayEquals(identity, KeyVault.open(file, s).get("identity-x25519"))
        val o = org.json.JSONObject(file.readText())
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(s.get(KeyVault.MASTER_ACCOUNT), "AES"),
            javax.crypto.spec.GCMParameterSpec(128, java.util.Base64.getDecoder().decode(o.getString("nonce"))))
        c.doFinal(java.util.Base64.getDecoder().decode(o.getString("ct")))
    }

    @Test
    fun `a legacy vault whose key cannot be copied still opens exactly as before`() {
        val s = store()
        val identity = ByteArray(32) { 8 }
        writeLegacyVault(file, s, mapOf("identity-x25519" to identity))
        val refusing = object : SecretStore by s {
            override val id = "refuses new entries"
            override fun put(account: String, secret: ByteArray) = throw SecretStoreException("locked")
        }
        val v = KeyVault.open(file, refusing)
        assertArrayEquals(identity, v.get("identity-x25519"))
        assertEquals(KeyVault.MASTER_ACCOUNT, v.masterKeyAccount)
        v.destroy(refusing)
        assertTrue("the shared legacy entry is never deleted", s.get(KeyVault.MASTER_ACCOUNT) != null)
    }

    @Test
    fun `two profiles on one machine get two keys, and destroying one leaves the other readable`() {
        val s = store()
        val other = File(dir, "profile-b/${KeyVault.FILE_NAME}")
        val a = KeyVault.open(file, s).apply { put("identity-x25519", ByteArray(32) { 1 }) }
        val b = KeyVault.open(other, s).apply { put("identity-x25519", ByteArray(32) { 2 }) }
        assertTrue(a.masterKeyAccount != b.masterKeyAccount)

        b.destroy(s)
        assertArrayEquals(ByteArray(32) { 1 }, KeyVault.open(file, s).get("identity-x25519"))
    }

    @Test
    fun `destroying a PASSPHRASE vault touches no OS key store entry`() {
        // The 1.4.5 data-loss bug: a throwaway passphrase profile's destroy() deleted the
        // shared `master-key`, and the real profile on the machine never opened again.
        val s = store()
        val legacyKey = writeLegacyVault(file, s, mapOf("identity-x25519" to ByteArray(32) { 6 }))
        val real = KeyVault.open(file, s)
        val realAccount = real.masterKeyAccount!!

        val throwaway = KeyVault.open(File(dir, "throwaway/${KeyVault.FILE_NAME}"), null, "pw".toCharArray())
        assertNull(throwaway.masterKeyAccount)
        throwaway.put("identity-x25519", ByteArray(32) { 9 })
        throwaway.destroy(s)

        assertArrayEquals(legacyKey, s.get(KeyVault.MASTER_ACCOUNT))
        assertArrayEquals(legacyKey, s.get(realAccount))
        assertArrayEquals(ByteArray(32) { 6 }, KeyVault.open(file, s).get("identity-x25519"))
    }

    @Test
    fun `a fresh os-store vault is written at once, so its key entry is never orphaned`() {
        val s = store()
        val v = KeyVault.open(file, s)
        assertTrue(file.isFile)
        assertEquals(v.masterKeyAccount, KeyVault.open(file, s).masterKeyAccount)
    }

    @Test
    fun `a tampered keyAccount naming some other entry is refused`() {
        val s = store()
        KeyVault.open(file, s).put("k", ByteArray(4))
        val o = org.json.JSONObject(file.readText()).put("keyAccount", "../../evil")
        file.writeText(o.toString())
        try {
            KeyVault.open(file, s); fail("must refuse")
        } catch (e: KeyVaultException) {
            assertTrue(e.message!!.contains("unexpected key store entry"))
        }
    }

    @Test
    fun `a write leaves no temporary file behind`() {
        val s = store()
        val v = KeyVault.open(file, s)
        repeat(5) { v.put("k$it", ByteArray(8) { b -> b.toByte() }) }
        val strays = dir.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("temporary files left in the data directory: $strays", strays.isEmpty())
    }

    @Test
    fun `a vault written by a newer build is refused, never overwritten`() {
        val s = store()
        KeyVault.open(file, s).put("k", ByteArray(4))
        val o = org.json.JSONObject(file.readText()).put("v", KeyVault.VERSION + 1)
        file.writeText(o.toString())
        try {
            KeyVault.open(file, s)
            fail("a newer vault version must not be opened")
        } catch (e: KeyVaultException) {
            assertTrue(e.message!!.contains("newer OSHI desktop build"))
        }
    }

    @Test
    fun `isOsStoreVault tells which vaults must not be refused on a flaky store probe`() {
        assertTrue(!KeyVault.isOsStoreVault(file))
        KeyVault.open(file, store()).put("k", ByteArray(1))
        assertTrue(KeyVault.isOsStoreVault(file))
        val p = File(dir, "p/${KeyVault.FILE_NAME}")
        KeyVault.open(p, null, "pw".toCharArray()).put("k", ByteArray(1))
        assertTrue(!KeyVault.isOsStoreVault(p))
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
