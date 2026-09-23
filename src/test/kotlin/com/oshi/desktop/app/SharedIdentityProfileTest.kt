package com.oshi.desktop.app

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * __SHARED_NICKNAME_2026_09_22__ A desktop restored from a phone's recovery key holds the
 * PHONE'S identity key. A profile update from it would be recorded by every contact against
 * the phone — and it cannot carry the phone's `groupEnvelopeV3`/`ratchetV3`/`pqPublicKey`/
 * avatar, which both phones read as "absent = cannot" and REMOVE. So such a desktop must
 * never put a `📸PROFILE_UPDATE📸` on the wire, by any path; nor may one whose origin is
 * unknown. Receiving and displaying nicknames is unaffected.
 */
class SharedIdentityProfileTest {

    private val fx = WiringFixture()
    private val extraDirs = ArrayList<File>()
    private val extraClients = ArrayList<OshiClient>()

    @After
    fun tearDown() {
        extraClients.forEach { runCatching { it.close() } }
        fx.close()
        extraDirs.forEach { it.deleteRecursively() }
    }

    /** A desktop whose vault was filled by `importRecoveryKey` with [phone]'s key. */
    private fun restoredDesktop(phone: OshiClient): OshiClient {
        val dir = Files.createTempDirectory("oshi-restored").toFile().also { extraDirs += it }
        val store = InMemorySecretStore()
        val vault = KeyVault.open(File(dir, KeyVault.FILE_NAME), store, null)
        IdentityStore.importRecoveryKey(vault, IdentityStore.exportRecoveryKey(phone.identity))
        return OshiClient(home = dir, secretStore = store, serverUrl = fx.relay.baseUrl, displayName = "desk")
            .also { extraClients += it; it.router.refreshConfig() }
    }

    @Test
    fun `the origin marker is written with the keys and removed with them`() {
        val dir = Files.createTempDirectory("oshi-origin").toFile().also { extraDirs += it }
        val v1 = KeyVault.open(File(dir, "a.vault"), InMemorySecretStore(), null)
        IdentityStore.loadOrCreate(v1)
        assertEquals(IdentityStore.Origin.GENERATED, IdentityStore.origin(v1))
        val key = IdentityStore.exportRecoveryKey(IdentityStore.load(v1)!!)

        val v2 = KeyVault.open(File(dir, "b.vault"), InMemorySecretStore(), null)
        IdentityStore.importRecoveryKey(v2, key)
        assertEquals(IdentityStore.Origin.IMPORTED, IdentityStore.origin(v2))

        // A vault from before the marker: keys present, no origin.
        v1.delete(IdentityStore.ACCOUNT_ORIGIN)
        assertEquals(IdentityStore.Origin.UNKNOWN, IdentityStore.origin(v1))

        IdentityStore.erase(v2)
        assertNull(v2.get(IdentityStore.ACCOUNT_ORIGIN))
    }

    @Test
    fun `a desktop restored from the phone's key never broadcasts a profile`() {
        val phone = fx.client("phone")
        val contact = fx.client("contact")
        val desk = restoredDesktop(phone)
        assertEquals(phone.address, desk.address)
        assertFalse(desk.mayBroadcastProfile)

        // Eligible in every other respect: we have written to this contact.
        fx.seedOutgoing(desk, contact.address, "m1", "hi")

        val u = desk.setOwnNickname("Hugo")
        assertTrue(u.withheld)
        assertEquals(0, u.sent)
        assertEquals("Hugo", desk.ownNickname)              // still kept locally
        assertEquals("Hugo", desk.displayName)
        assertEquals(OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD, desk.sendProfileUpdate(contact.address))
        assertEquals(0 to 0, desk.broadcastProfile())

        // A profile request from the contact is not answered either.
        fx.inbound(desk, contact.address, ControlPrefix.PROFILE_REQUEST)

        assertEquals("nothing may have reached the relay for the contact", 0, contact.router.poll())
        assertNull(contact.contacts.get(phone.address)?.sharedNickname)
        assertTrue(fx.repl(desk, "/nick Hugo2").single().contains("NOT sent to contacts"))
    }

    @Test
    fun `a first reply from a shared identity carries no profile`() {
        val phone = fx.client("phone")
        val contact = fx.client("contact")
        val desk = restoredDesktop(phone)
        desk.router.publishBundleIfNeeded()
        desk.vault.put(OshiClient.OWN_NICKNAME_ACCOUNT, "Hugo".toByteArray())
        assertEquals(OshiClient.SendOutcome.SENT, desk.send(contact.address, "first"))
        assertEquals("only the prose may arrive", 1, contact.router.poll())
        assertNull(contact.contacts.get(phone.address)?.sharedNickname)
    }

    @Test
    fun `an identity of unknown origin is treated as shared`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        me.vault.delete(IdentityStore.ACCOUNT_ORIGIN)
        assertFalse(me.mayBroadcastProfile)
        fx.seedOutgoing(me, peer.address, "m1", "hi")
        assertTrue(me.setOwnNickname("Me").withheld)
        assertEquals(0, peer.router.poll())
    }

    @Test
    fun `a shared identity still RECEIVES and displays a contact's nickname`() {
        val phone = fx.client("phone")
        val desk = restoredDesktop(phone)
        val peer = fx.client("peer", publish = false).address
        fx.inbound(desk, peer, ControlPrefix.PROFILE_UPDATE + """{"type":"profile_update","lastSeen":0,"version":1,"displayName":"Zoé"}""")
        assertEquals("Zoé", desk.contacts.get(peer)!!.sharedNickname)
    }

    @Test
    fun `a desktop-generated identity still broadcasts`() {
        assertTrue(fx.client("fresh").mayBroadcastProfile)
    }
}
