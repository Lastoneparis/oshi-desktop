package com.oshi.desktop.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The read-back check, tested against stores that LIE.
 *
 * This guard was written after watching `security add-generic-password` accept a password
 * on stdin, exit 0, and store an empty value. On a machine whose key store behaves, the
 * check is invisible — removing it changes nothing observable, which is exactly why it
 * needs a test with a store that misbehaves on purpose. Two kinds of lie are covered
 * because they fail differently: storing NOTHING, and storing SOMETHING ELSE.
 */
class VerifiedSecretStoreTest {

    /** Exits successfully, keeps nothing — the `security` bug, distilled. */
    private class SilentlyDiscardingStore : SecretStore {
        override val id = "store that discards"
        override fun get(account: String): ByteArray? = null
        override fun put(account: String, secret: ByteArray) { /* "succeeds" */ }
        override fun delete(account: String) {}
    }

    /** Keeps something, but not what it was given (a truncating or re-encoding store). */
    private class TruncatingStore : SecretStore {
        private val map = HashMap<String, ByteArray>()
        override val id = "store that truncates"
        override fun get(account: String): ByteArray? = map[account]
        override fun put(account: String, secret: ByteArray) { map[account] = secret.copyOf(secret.size / 2) }
        override fun delete(account: String) { map.remove(account) }
    }

    @Test
    fun `a store that keeps nothing is caught on the write`() {
        try {
            VerifiedSecretStore(SilentlyDiscardingStore()).put("master-key", ByteArray(32) { 1 })
            fail("a write that stored nothing was reported as success — this is how an account " +
                "becomes unopenable with no error anywhere")
        } catch (e: SecretStoreException) {
            assertTrue("the error must name the backend: ${e.message}", e.message!!.contains("discards"))
            assertTrue(e.message!!.contains("cannot be read back"))
        }
    }

    @Test
    fun `a store that keeps a different value is caught too`() {
        try {
            VerifiedSecretStore(TruncatingStore()).put("master-key", ByteArray(32) { 2 })
            fail("a truncated write was reported as success")
        } catch (e: SecretStoreException) {
            assertTrue(e.message!!.contains("DIFFERENT value"))
        }
    }

    @Test
    fun `a store that behaves is passed through unchanged`() {
        val backing = InMemorySecretStore()
        val verified = VerifiedSecretStore(backing)
        val secret = ByteArray(32) { 3 }

        verified.put("master-key", secret)
        assertArrayEquals(secret, verified.get("master-key"))
        assertArrayEquals(secret, backing.get("master-key"))

        verified.delete("master-key")
        assertTrue(verified.get("master-key") == null && backing.get("master-key") == null)
    }
}
