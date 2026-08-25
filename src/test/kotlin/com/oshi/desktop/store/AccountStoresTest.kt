package com.oshi.desktop.store

import com.oshi.desktop.DesktopIdentity
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHIRatchetV2
import com.oshi.messenger.network.v2.V2Session
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Identity, prekeys and ratchet sessions — PARITY.md rows 0.5 to 0.7.
 *
 * The common thread: everything here is state whose loss is invisible at the time and
 * fatal later. A regenerated identity is a new address nobody writes to; a lost prekey
 * private is a contact whose first message can never be opened; a rewound ratchet is an
 * AES-GCM nonce reused across two different plaintexts.
 */
class AccountStoresTest {

    private val dir: File = Files.createTempDirectory("oshi-stores-test").toFile()
    private val secrets = InMemorySecretStore()
    private fun vault() = KeyVault.open(File(dir, KeyVault.FILE_NAME), secrets)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ identity

    /** The point of the whole vault: the same address after a restart. */
    @Test
    fun `the account is created once and is the same on the next run`() {
        val first = IdentityStore.loadOrCreate(vault())
        val second = IdentityStore.loadOrCreate(vault())

        assertEquals("the address changed between runs — every contact now writes to a dead account",
            first.userKey, second.userKey)
        assertArrayEquals(first.signingPub, second.signingPub)

        // …and the private halves came back too: signatures made now verify against the
        // key published then.
        val message = "canonical\nstring".toByteArray()
        assertTrue(DesktopIdentity.verify(message, second.sign(message), first.signingPub))
    }

    @Test
    fun `the address is the standard padded base64 of the X25519 public key`() {
        val id = IdentityStore.loadOrCreate(vault())
        assertEquals(java.util.Base64.getEncoder().encodeToString(id.identity.pub), id.userKey)
        assertTrue("an OSHI address is standard base64, never base64url",
            id.userKey.none { it == '-' || it == '_' })
    }

    @Test
    fun `an incomplete account reads as no account, not as a broken one`() {
        val v = vault()
        IdentityStore.loadOrCreate(v)
        v.delete(IdentityStore.ACCOUNT_ED25519_PRIV)
        assertNull("half an account must not load", IdentityStore.load(vault()))
        assertTrue(!IdentityStore.exists(vault()))
    }

    @Test
    fun `erase removes every half of the account`() {
        val v = vault()
        IdentityStore.loadOrCreate(v)
        IdentityStore.erase(v)
        assertNull(IdentityStore.load(vault()))
        val fresh = IdentityStore.loadOrCreate(vault())
        assertNotNull(fresh.userKey)
    }

    // ------------------------------------------------------------------ prekeys

    @Test
    fun `the signed prekey is stable until it is rotated`() {
        val store = PrekeyStore(vault())
        val first = store.currentSignedPreKey()
        assertEquals(first.keyId, store.currentSignedPreKey().keyId)
        assertEquals("it must survive a reopen", first.keyId, PrekeyStore(vault()).currentSignedPreKey().keyId)

        val rotated = store.rotateSignedPreKey()
        assertNotEquals(first.keyId, rotated.keyId)
        assertNull("the old private must be gone after a rotation", store.signedPreKeyPriv(first.keyId))
        assertNotNull(store.signedPreKeyPriv(rotated.keyId))
    }

    @Test
    fun `the signed prekey private is the one that matches the published public`() {
        val store = PrekeyStore(vault())
        val spk = store.currentSignedPreKey()
        val priv = store.signedPreKeyPriv(spk.keyId)!!
        // The pair has to agree: a DH with any other key gives a different secret, and the
        // responder side of X3DH would silently derive a shared secret nobody else has.
        val other = OSHICryptoV2.generateX25519()
        assertArrayEquals(
            OSHICryptoV2.dh(priv, other.pub),
            OSHICryptoV2.dh(other.priv, spk.pair.pub),
        )
    }

    /**
     * Peek, verify, THEN burn. A consuming lookup would destroy the key that a
     * legitimate retry of the same first message needs.
     */
    @Test
    fun `a one-time prekey is peeked without being consumed and burned only once`() {
        val store = PrekeyStore(vault())
        val ids = store.generateOneTimePreKeys(3).map { it.first }
        assertEquals(3, store.oneTimePreKeyCount())

        val peeked = store.oneTimePreKeyPrivPeek(ids[0])
        assertNotNull(peeked)
        assertEquals("a peek must not consume", 3, store.oneTimePreKeyCount())

        val burned = store.consumeOneTimePreKeyPriv(ids[0])
        assertArrayEquals(peeked, burned)
        assertEquals(2, store.oneTimePreKeyCount())
        assertNull("a one-time prekey is single use", store.consumeOneTimePreKeyPriv(ids[0]))
    }

    @Test
    fun `the one-time prekey pool is capped and always keeps the batch just minted`() {
        val store = PrekeyStore(vault())
        repeat(11) { store.generateOneTimePreKeys(20) }      // 220 > cap
        assertEquals(PrekeyStore.OPK_CAP, store.oneTimePreKeyCount())

        val fresh = store.generateOneTimePreKeys(20).map { it.first }
        assertEquals(PrekeyStore.OPK_CAP, store.oneTimePreKeyCount())
        for (id in fresh) {
            assertNotNull("the batch we just published was evicted — those publics are on the server",
                store.oneTimePreKeyPrivPeek(id))
        }
    }

    @Test
    fun `prekey privates survive a reopen and are not on disk in the clear`() {
        val store = PrekeyStore(vault())
        val (id, _) = store.generateOneTimePreKeys(1).first()
        val priv = store.oneTimePreKeyPrivPeek(id)!!

        assertArrayEquals(priv, PrekeyStore(vault()).oneTimePreKeyPrivPeek(id))

        val onDisk = File(dir, KeyVault.FILE_NAME).readBytes()
        val b64 = java.util.Base64.getEncoder().encodeToString(priv).toByteArray()
        assertTrue("a prekey private is readable on disk", !String(onDisk).contains(String(b64)))
    }

    // ------------------------------------------------------------------ sessions

    @Test
    fun `a ratchet session round trips field for field`() {
        val store = SessionStore(vault())
        val rec = sampleSession("PEER+key/=")
        rec.ratchet.ns = 5; rec.ratchet.nr = 3; rec.ratchet.pn = 2
        rec.ratchet.skipped["deadbeef:1"] = ByteArray(32) { 8 }
        store.save(rec)

        val back = SessionStore(vault()).load("PEER+key/=")!!
        assertEquals(rec.peerUserKey, back.peerUserKey)
        assertEquals(rec.role, back.role)
        assertArrayEquals(rec.ratchet.dhs.priv, back.ratchet.dhs.priv)
        assertArrayEquals(rec.ratchet.dhs.pub, back.ratchet.dhs.pub)
        assertArrayEquals(rec.ratchet.rk, back.ratchet.rk)
        assertArrayEquals(rec.ratchet.cks, back.ratchet.cks)
        assertEquals(5L, back.ratchet.ns)
        assertEquals(3L, back.ratchet.nr)
        assertEquals(2L, back.ratchet.pn)
        assertArrayEquals(ByteArray(32) { 8 }, back.ratchet.skipped["deadbeef:1"])
        assertArrayEquals(rec.peerIdentityKey, back.peerIdentityKey)
        assertEquals(rec.pendingInitiatorHeader!!.signedPreKeyId, back.pendingInitiatorHeader!!.signedPreKeyId)
    }

    /**
     * A persisted session must decrypt what the live one would. This is the property the
     * store exists for, and it is stronger than comparing fields: it exercises the state
     * through the ratchet itself.
     */
    @Test
    fun `a reloaded session decrypts a message the live session encrypted`() {
        val alicePair = OSHICryptoV2.generateX25519()
        val bobSpk = OSHICryptoV2.generateX25519()
        val sk = ByteArray(32) { 42 }
        val alice = OSHIRatchetV2.initAlice(sk, bobSpk.pub)
        val bob = OSHIRatchetV2.initBob(sk, bobSpk)

        val store = SessionStore(vault())
        store.save(SessionStore.Record("bob", SessionStore.Role.RESPONDER, bob, null, null, null))

        val (header, ct) = OSHIRatchetV2.encrypt(alice, "hello across a restart".toByteArray(), ByteArray(0))
        val reloaded = SessionStore(vault()).load("bob")!!
        val plain = OSHIRatchetV2.decrypt(reloaded.ratchet, header, ct, ByteArray(0))
        assertEquals("hello across a restart", String(plain))

        // And the advance the decrypt made is itself persistable — otherwise the next
        // message would be decrypted against a stale receive chain.
        SessionStore(vault()).save(reloaded)
        val (h2, c2) = OSHIRatchetV2.encrypt(alice, "second".toByteArray(), ByteArray(0))
        val again = SessionStore(vault()).load("bob")!!
        assertEquals("second", String(OSHIRatchetV2.decrypt(again.ratchet, h2, c2, ByteArray(0))))
    }

    @Test
    fun `an unreadable session record raises rather than reading as no session`() {
        val v = vault()
        SessionStore(v).save(sampleSession("peer1"))
        v.put(SessionStore.PREFIX + "peer1", "{ not a session }".toByteArray())
        try {
            SessionStore(vault()).load("peer1")
            fail("a corrupt session must not read as absent — a fresh X3DH would strand the peer")
        } catch (e: SessionStoreException) {
            assertTrue(e.message!!.contains("unreadable"))
        }
    }

    @Test
    fun `sessions are listed and cleared without touching the identity`() {
        val v = vault()
        val id = IdentityStore.loadOrCreate(v)
        val store = SessionStore(v)
        store.save(sampleSession("peer1"))
        store.save(sampleSession("peer2"))
        assertEquals(listOf("peer1", "peer2"), store.peers().sorted())

        store.clear()
        assertEquals(emptyList<String>(), store.peers())
        assertEquals("clearing sessions must not touch the account", id.userKey, IdentityStore.load(v)!!.userKey)
    }

    private fun sampleSession(peer: String): SessionStore.Record {
        val dhs = OSHICryptoV2.generateX25519()
        return SessionStore.Record(
            peerUserKey = peer,
            role = SessionStore.Role.INITIATOR,
            ratchet = OSHIRatchetV2.State(
                dhs = dhs, dhr = ByteArray(32) { 1 }, rk = ByteArray(32) { 2 },
                cks = ByteArray(32) { 3 }, ckr = null,
            ),
            peerIdentityKey = ByteArray(32) { 4 },
            peerSigningKey = ByteArray(32) { 5 },
            pendingInitiatorHeader = V2Session.X3DHHeader(
                identityKey = ByteArray(32) { 6 },
                ephemeralKey = ByteArray(32) { 7 },
                signedPreKeyId = "spk-1",
                oneTimePreKeyId = "opk-1",
            ),
        )
    }
}
