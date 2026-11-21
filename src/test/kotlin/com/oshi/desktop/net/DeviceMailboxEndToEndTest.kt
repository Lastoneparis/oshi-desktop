package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.devsync.DesktopDevSync
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.PrekeyStore
import com.oshi.desktop.store.SessionStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.file.Files

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ Two REAL desktop clients of ONE account (the second restored
 * from the first's recovery key, as a user would), plus contacts, against a local run of the REAL
 * server (`ServerPatches/per_device_mailbox/v2/v2_server.js`, `device_mailbox_enabled:true`):
 * register, approve, receive while the other device is offline, independent acks, self copies,
 * old-sender envelopes dropped quietly with zero resets, removal (410) and the flag going off.
 *
 * Skips (never fails) when node or the server sources are absent — the standalone repository.
 */
class DeviceMailboxEndToEndTest {

    private val dirs = ArrayList<File>()
    private val clients = ArrayList<OshiClient>()
    private val syncs = ArrayList<DesktopDevSync>()
    private var node: Process? = null
    private lateinit var cfgFile: File
    private val verbose = System.getenv("DEVMBOX_VERBOSE") != null

    @After
    fun tearDown() {
        syncs.forEach { runCatching { it.stop() } }
        clients.forEach { runCatching { it.close() } }
        node?.destroy()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dir(p: String) = Files.createTempDirectory(p).toFile().also { dirs += it }

    private fun findUp(rel: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) { File(d, rel).takeIf { it.exists() }?.let { return it }; d = d.parentFile }
        return null
    }

    private fun startServer(flag: Boolean): String {
        // The devsync_device_auth server is per_device_mailbox + the device-bound relay upgrade the
        // clients now speak; per_device_mailbox's own devsync_relay.js is still the account-only one
        // (401 for current clients), so the devsync pairing test can only pass against the former.
        val src = findUp("ServerPatches/devsync_device_auth/v2/v2_server.js")
            ?: findUp("ServerPatches/per_device_mailbox/v2/v2_server.js")
        val nodeBin = listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { File(it).canExecute() }
        Assume.assumeTrue("per_device_mailbox server sources / node not available", src != null && nodeBin != null)
        val work = dir("devmbox-server")
        cfgFile = File(work, "cfg.json").apply { writeText(JSONObject().put("device_mailbox_enabled", flag).toString()) }
        val port = ServerSocket(0).use { it.localPort }
        val pb = ProcessBuilder(nodeBin, src!!.absolutePath).redirectErrorStream(true)
        pb.environment()["OSHI_V2_PORT"] = port.toString()
        pb.environment()["OSHI_V2_CONFIG_FILE"] = cfgFile.absolutePath
        val p = pb.start()
        node = p
        val out = BufferedReader(InputStreamReader(p.inputStream))
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val line = out.readLine() ?: break
            if (verbose) println("[server] $line")
            if (line.contains("OSHI v2 server on")) {
                Thread({ runCatching { while (true) { val l = out.readLine() ?: break; if (verbose) println("[server] $l") } } }, "node-out")
                    .apply { isDaemon = true; start() }
                return "http://127.0.0.1:$port"
            }
        }
        throw AssertionError("server did not start")
    }

    private fun setFlag(on: Boolean) {
        cfgFile.writeText(JSONObject().put("device_mailbox_enabled", on).toString())
        Thread.sleep(5_200)   // the server caches /v2/config for 5 s
    }

    private fun client(url: String, name: String, recovery: String? = null): OshiClient {
        val home = dir("devmbox-$name")
        val secrets = InMemorySecretStore()
        if (recovery != null) {
            val vault = KeyVault.open(File(home, KeyVault.FILE_NAME), secrets, null)
            IdentityStore.importRecoveryKey(vault, recovery)
        }
        return OshiClient(home = home, secretStore = secrets, serverUrl = url, displayName = name,
            log = { if (verbose) println("[$name] $it") }).also { clients += it }
    }

    /** One poll-loop tick, in the order `OshiClient.start` runs it. */
    private fun step(vararg cs: OshiClient) {
        for (c in cs) {
            c.config.refresh(c.address)
            c.router.publishBundleIfNeeded()
            c.deviceMailbox.tick()
            c.router.poll()
        }
    }

    private fun texts(c: OshiClient, conv: String) = c.messages.messages(conv).map { it.content }

    /** An OLD sender: the V2 router with no device mailbox at all (today's builds). */
    private inner class OldClient(url: String, name: String) {
        val home = dir("devmbox-old-$name")
        val vault = KeyVault.open(File(home, KeyVault.FILE_NAME), InMemorySecretStore())
        val identity: DesktopIdentity = IdentityStore.loadOrCreate(vault)
        val prekeys = PrekeyStore(vault)
        val http = V2Http(DesktopV2Signer(identity), url)
        val keys = V2KeysClient(http, identity, prekeys)
        val router = V2Router(identity, V2ConfigGate(baseUrl = url).also { it.refresh(identity.userKey) }, keys,
            V2MessagesClient(http), SessionStore(vault), prekeys, RouterState(File(home, "rs.json")))
        val address get() = identity.userKey
    }

    private fun liveAccountSpk(id: DesktopIdentity, url: String): String? {
        val r = V2Http(DesktopV2Signer(id), url).get("/v2/keys/${DesktopV2Signer.encodeIdentity(id.userKey)}")
        return if (r.isSuccess) JSONObject(r.body).getJSONObject("signedPreKey").getString("key") else null
    }

    // ================================================================== the main scenario

    @Test
    fun twoDesktopsOfOneAccount_registerApproveReceiveIndependentlyAndSeeEachOthersSends() {
        val url = startServer(flag = true)
        val primary = client(url, "primary")                 // plays the phone: generated the identity
        val recovery = IdentityStore.exportRecoveryKey(primary.identity)
        val desk = client(url, "desk", recovery)              // the restored desktop: SHARED identity
        val bob = client(url, "bob")                         // an updated contact
        assertEquals(primary.address, desk.address)
        val me = primary.address

        // 1. First device: no approval, and it holds the account bundle it published.
        step(primary)
        val p = primary.deviceMailbox.snapshot()
        assertEquals(DeviceMailbox.Status.REGISTERED, p.status)
        assertTrue("the first device holds the account bundle", p.holdsAccountBundle)
        val accountSpk = liveAccountSpk(primary.identity, url)
        assertNotNull(accountSpk)

        // 2. The restored desktop does NOT republish the account bundle (the old fight) and
        //    cannot register without an approval.
        step(desk)
        assertEquals("the desktop must not replace the phone's account bundle", accountSpk, liveAccountSpk(primary.identity, url))
        assertEquals(DeviceMailbox.Status.NEEDS_APPROVAL, desk.deviceMailbox.snapshot().status)
        assertFalse(desk.deviceMailbox.active)

        // 3. Approval, through the exact hooks devsync calls (APPROVAL records both ways).
        val deskId = desk.deviceMailbox.deviceId
        val primaryId = primary.deviceMailbox.deviceId
        val deskApproval = desk.deviceMailbox.approvalExtras(primaryId, primary.deviceMailbox.dkPub)!!
        assertNull("desk is not registered: it cannot approve anyone", deskApproval.optJSONObject("mailbox"))
        primary.deviceMailbox.onApprovalReceived(deskId, deskApproval)          // primary learns desk's dsk
        val primaryApproval = primary.deviceMailbox.approvalExtras(deskId, desk.deviceMailbox.dkPub)!!
        assertNotNull("the registered device signs the new one in", primaryApproval.optJSONObject("mailbox"))
        desk.deviceMailbox.onApprovalReceived(primaryId, primaryApproval)
        step(desk)
        val d = desk.deviceMailbox.snapshot()
        assertEquals(DeviceMailbox.Status.REGISTERED, d.status)
        assertFalse("only one device holds the account bundle", d.holdsAccountBundle)
        assertEquals(primaryId, d.devices.first { it.deviceId == deskId }.approvedBy)
        assertTrue(d.devices.all { it.hasBundle })
        assertEquals("still no fight after registering", accountSpk, liveAccountSpk(primary.identity, url))

        // 4. Bob (updated) writes while the primary is OFFLINE: the desk shows it live.
        step(bob)
        assertTrue(bob.deviceMailbox.active)
        assertEquals(OshiClient.SendOutcome.SENT, bob.send(me, "hello both devices"))
        step(desk)
        assertTrue("desk received it with the primary offline", "hello both devices" in texts(desk, bob.address))
        // …and the desk's ack did not take it from the primary: acks are per device.
        step(primary)
        assertTrue("primary still got it after desk acked", "hello both devices" in texts(primary, bob.address))

        // 5. Sent from the desk: bob gets it, the primary shows it as OUTGOING (self copy).
        assertEquals(OshiClient.SendOutcome.SENT, desk.send(bob.address, "sent from the desk"))
        step(bob)
        assertTrue("sent from the desk" in texts(bob, me))
        step(primary)
        val copy = primary.messages.messages(bob.address).firstOrNull { it.content == "sent from the desk" }
        assertNotNull("the primary shows the desk's message", copy)
        assertTrue("…as outgoing", copy!!.fromMe)
        assertEquals("self-copy", copy.transport)
        // And the other way.
        assertEquals(OshiClient.SendOutcome.SENT, primary.send(bob.address, "sent from the primary"))
        step(desk, bob)
        assertTrue(desk.messages.messages(bob.address).any { it.content == "sent from the primary" && it.fromMe })
        assertTrue("sent from the primary" in texts(bob, me))

        // 6. Reset-storm check (§6.5): 50 account envelopes from an OLD sender. Only the holder
        //    decrypts; the desk drops them quietly — no session, no retry, nothing held.
        val carol = OldClient(url, "carol")
        assertTrue(carol.router.publishBundleIfNeeded())
        repeat(50) { i -> assertTrue(carol.router.sendText(me, "old $i".toByteArray())) }
        val dropsBefore = desk.router.foreignDrops
        step(desk)
        assertEquals(50, desk.router.foreignDrops - dropsBefore)
        assertTrue("no message row from an envelope the desk could not open", texts(desk, carol.address).isEmpty())
        step(primary)
        assertEquals(50, texts(primary, carol.address).count { it?.startsWith("old ") == true })
        step(desk)
        assertEquals("nothing held for a retry", 50, desk.router.foreignDrops - dropsBefore)

        // 7. Remove the desk from the primary: the desk gets 410, alerts, and falls back.
        assertTrue(primary.deviceMailbox.unregister(deskId).isSuccess)
        step(desk)
        val removed = desk.deviceMailbox.snapshot()
        assertEquals(DeviceMailbox.Status.REMOVED, removed.status)
        assertTrue("removal is announced unprompted", removed.removedAlert)
        assertFalse(desk.deviceMailbox.active)
        // Bob's cached list still names the desk: the server's 409 corrects it on the next send.
        assertEquals(OshiClient.SendOutcome.SENT, bob.send(me, "after removal"))
        step(primary)
        assertTrue("after removal" in texts(primary, bob.address))
    }

    // ================================================================== approval over real devsync

    @Test
    fun theMailboxApprovalRidesTheDevsyncPairing() {
        val url = startServer(flag = true)
        val primary = client(url, "primary")
        val desk = client(url, "desk", IdentityStore.exportRecoveryKey(primary.identity))
        step(primary)
        step(desk)
        assertEquals(DeviceMailbox.Status.NEEDS_APPROVAL, desk.deviceMailbox.snapshot().status)

        val sp = DesktopDevSync(primary, File(primary.mediaDir.parentFile.absolutePath), { if (verbose) println("[primary] $it") }, lanDiscoveryFactory = { null })
            .also { syncs += it; it.setOverride(true) }
        val sd = DesktopDevSync(desk, File(desk.mediaDir.parentFile.absolutePath), { if (verbose) println("[desk] $it") }, lanDiscoveryFactory = { null })
            .also { syncs += it; it.setOverride(true) }
        waitUntil("pairing prompt on both, over the relay") { sp.snapshot().pending.isNotEmpty() && sd.snapshot().pending.isNotEmpty() }
        // The NEW device confirms first (its APPROVAL carries its `dsk`); the registered one then
        // answers with the signed `mailbox` approval. See DeviceMailbox.approvalExtras for why the
        // other order needs the core to carry `dsk` in HELLO.
        sd.approve(sd.snapshot().pending.first().peerId, true)
        waitUntil("the desk holds a mailbox approval") { desk.deviceMailbox.snapshot().pendingApproval }
        step(desk)
        assertEquals(DeviceMailbox.Status.REGISTERED, desk.deviceMailbox.snapshot().status)
        assertEquals(primary.deviceMailbox.deviceId,
            desk.deviceMailbox.snapshot().devices.first { it.deviceId == desk.deviceMailbox.deviceId }.approvedBy)
    }

    // ================================================================== flag off

    @Test
    fun flagOffMidSession_bothFallBackToLegacyAndNothingIsLost() {
        val url = startServer(flag = true)
        val primary = client(url, "primary")
        val desk = client(url, "desk", IdentityStore.exportRecoveryKey(primary.identity))
        val bob = client(url, "bob")
        step(primary, desk)
        desk.deviceMailbox.acceptApproval(primary.deviceMailbox.approvalFor(desk.deviceMailbox.deviceId, desk.deviceMailbox.dskPub)!!)
        step(desk, bob)
        assertTrue(desk.deviceMailbox.active && primary.deviceMailbox.active && bob.deviceMailbox.active)
        assertEquals(OshiClient.SendOutcome.SENT, bob.send(primary.address, "before"))
        // primary offline; the flag goes off with bob's per-device copy still queued for it.
        step(desk)
        setFlag(false)
        for (c in listOf(primary, desk, bob)) { c.config.invalidate(); c.config.refresh(c.address) }
        assertFalse(primary.deviceMailbox.active)
        assertTrue("registration is kept for when the flag returns", primary.deviceMailbox.isRegistered)
        // Legacy view shows everything: the per-device copy queued while the flag was on is
        // delivered to the legacy poller, and a new message travels the old way.
        assertEquals(OshiClient.SendOutcome.SENT, bob.send(primary.address, "after"))
        step(primary)
        assertTrue("before" in texts(primary, bob.address))
        assertTrue("after" in texts(primary, bob.address))
    }

    @Test
    fun flagOff_aGeneratedDesktopPublishesAsBefore_aSharedOneNeverOverwritesThePhone() {
        val url = startServer(flag = false)
        val phone = client(url, "phone")
        step(phone)
        val spk = liveAccountSpk(phone.identity, url)
        assertNotNull("generated identity: published exactly as before", spk)
        assertFalse(phone.deviceMailbox.active)
        val desk = client(url, "desk", IdentityStore.exportRecoveryKey(phone.identity))
        step(desk)
        assertEquals("a restored desktop no longer takes the phone's account bundle", spk, liveAccountSpk(phone.identity, url))
        // A contact can still reach the account (the phone holds it).
        val bob = client(url, "bob")
        step(bob)
        assertEquals(OshiClient.SendOutcome.SENT, bob.send(phone.address, "hi phone"))
        step(phone)
        assertTrue("hi phone" in texts(phone, bob.address))
        // …and the desktop that could not open it did not break the phone's delivery.
        assertEquals(0, desk.router.foreignDrops)
    }

    private fun waitUntil(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out: $what")
    }
}
