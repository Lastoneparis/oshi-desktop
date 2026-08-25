package com.oshi.desktop.sync

import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The account → archive-key wiring, and the key-pair swap it exists to prevent.
 *
 * The two archives derive from two different key pairs and both derivations accept any 32
 * bytes, so a swap produces 32 valid-looking bytes that decrypt nothing — a failure that
 * reads as "the server is serving corrupt items" rather than as a client bug. These tests
 * pin which vault account feeds which archive.
 */
class ArchiveKeysTest {

    private val dir: File = Files.createTempDirectory("oshi-archivekeys-test").toFile()
    private val secrets = InMemorySecretStore()
    private fun vault() = KeyVault.open(File(dir, KeyVault.FILE_NAME), secrets)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /**
     * The V2 archive key comes from the **Ed25519 signing** key — iOS's `"signingKey"`
     * Keychain item (`V2SyncManager.swift:55-66`), not the messaging key.
     */
    @Test
    fun `the v2 archive key is derived from the ed25519 signing key`() {
        val v = vault()
        val identity = IdentityStore.loadOrCreate(v)
        val ed = v.get(IdentityStore.ACCOUNT_ED25519_PRIV)!!

        assertArrayEquals(SyncCrypto.v2ArchiveKey(ed), ArchiveKeys.v2(v))
        // …and NOT from the X25519 key, which is what a swap would produce.
        assertFalse(
            "the two key pairs must not be interchangeable here",
            ArchiveKeys.v2(v).contentEquals(SyncCrypto.v2ArchiveKey(identity.identity.priv))
        )
    }

    /**
     * The legacy archive key comes from the **X25519 messaging** key — iOS's `"privateKey"`
     * item (`MultiDeviceSyncManager.swift:1004`), Android's `exportPrivateKeyBytes()`.
     */
    @Test
    fun `the legacy archive key is derived from the x25519 messaging key`() {
        val v = vault()
        val identity = IdentityStore.loadOrCreate(v)

        assertArrayEquals(SyncCrypto.legacyArchiveKey(identity.identity.priv), ArchiveKeys.legacy(v))
        assertFalse(
            ArchiveKeys.legacy(v).contentEquals(
                SyncCrypto.legacyArchiveKey(v.get(IdentityStore.ACCOUNT_ED25519_PRIV)!!)
            )
        )
    }

    @Test
    fun `the two archive keys of one identity differ`() {
        val v = vault()
        IdentityStore.loadOrCreate(v)
        assertFalse(ArchiveKeys.v2(v).contentEquals(ArchiveKeys.legacy(v)))
        assertEquals(32, ArchiveKeys.v2(v).size)
        assertEquals(32, ArchiveKeys.legacy(v).size)
    }

    /** Same identity across a restart means the same archive keys — or history is lost. */
    @Test
    fun `the archive keys survive a restart`() {
        val v1 = vault()
        IdentityStore.loadOrCreate(v1)
        val before = ArchiveKeys.v2(v1) to ArchiveKeys.legacy(v1)

        val v2 = vault()   // reopened from disk
        assertArrayEquals(before.first, ArchiveKeys.v2(v2))
        assertArrayEquals(before.second, ArchiveKeys.legacy(v2))
    }

    /**
     * GUARD — an empty vault RAISES rather than deriving from nothing.
     *
     * HKDF and SHA-256 both accept zero-length input and both return 32 plausible bytes.
     * A client that derived a key from a missing identity would push items nothing can
     * ever open and report every pulled item as corrupt.
     */
    @Test
    fun `an identity-less vault refuses to produce an archive key`() {
        val v = vault()   // no IdentityStore.loadOrCreate — nothing in it
        val e1 = runCatching { ArchiveKeys.v2(v) }.exceptionOrNull()
        val e2 = runCatching { ArchiveKeys.legacy(v) }.exceptionOrNull()

        assertTrue("expected MissingIdentityException, got $e1", e1 is MissingIdentityException)
        assertTrue("expected MissingIdentityException, got $e2", e2 is MissingIdentityException)
        // The message must name WHICH key pair, because naming the wrong one is the bug.
        assertTrue(e1!!.message!!.contains("signingKey"))
        assertTrue(e2!!.message!!.contains("privateKey"))
    }

    /** Two different identities never share an archive. */
    @Test
    fun `two identities derive different archive keys`() {
        val v1 = vault()
        IdentityStore.loadOrCreate(v1)
        val first = ArchiveKeys.v2(v1)

        IdentityStore.erase(v1)
        IdentityStore.loadOrCreate(v1)
        assertFalse(first.contentEquals(ArchiveKeys.v2(v1)))
    }
}
