package com.oshi.desktop.devsync

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import com.oshi.messenger.network.v2.devsync.DevSyncCrypto
import com.oshi.messenger.network.v2.devsync.DevSyncSession
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
import java.nio.file.Files
import java.util.Base64

/**
 * __DEVSYNC_DIRECT_2026_09_22__ Two REAL desktop clients of one account (the second restored from
 * the first's recovery key, as a user would) syncing their actual stores — [com.oshi.desktop.store.MessageStore],
 * [com.oshi.desktop.app.GroupStore], [com.oshi.desktop.store.ContactStore], sealed media — through
 * [DesktopDevSync] and [DesktopDevSyncStore]:
 *  1. over a local run of the real `devsync_relay.js` (enforce-mode auth, identity-bound upgrade);
 *  2. over loopback LAN (TCP + Noise), with no server at all.
 */
class DesktopClientsDevSyncTest {

    private val dirs = ArrayList<File>()
    private val clients = ArrayList<OshiClient>()
    private val syncs = ArrayList<DesktopDevSync>()
    private var node: Process? = null

    @After
    fun tearDown() {
        syncs.forEach { runCatching { it.stop() } }
        clients.forEach { runCatching { it.close() } }
        node?.destroy()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dir(p: String) = Files.createTempDirectory(p).toFile().also { dirs += it }

    /** A desktop profile holding the account [recoveryKey] (as `importRecoveryKey` would on a real machine). */
    private fun client(serverUrl: String, name: String, recoveryKey: String): OshiClient {
        val home = dir("oshi-devsync-$name")
        val secrets = InMemorySecretStore()
        val vault = KeyVault.open(File(home, KeyVault.FILE_NAME), secrets, null)
        IdentityStore.importRecoveryKey(vault, recoveryKey)
        return OshiClient(home = home, secretStore = secrets, serverUrl = serverUrl, displayName = name).also { clients += it }
    }

    private fun devSync(c: OshiClient, name: String): DesktopDevSync {
        val home = File(c.mediaDir.parentFile.absolutePath)
        return DesktopDevSync(c, home, { if (System.getenv("DEVSYNC_VERBOSE") != null) println("$name $it") }, lanDiscoveryFactory = { null })
            .also { syncs += it; it.setOverride(true) }
    }

    private fun waitUntil(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out: $what")
    }

    private val bob = Base64.getEncoder().encodeToString(DevSyncCrypto.sha256("bob".toByteArray()))
    private val carol = Base64.getEncoder().encodeToString(DevSyncCrypto.sha256("carol".toByteArray()))
    private val gid = "0A1B2C3D-4E5F-4061-8272-93A4B5C6D7E8"
    private val base = 1_758_000_000_000L

    private fun msg(c: OshiClient, id: String, conv: String, fromMe: Boolean, i: Int, text: String?, status: DeliveryStatus = DeliveryStatus.DELIVERED, media: Pair<MediaType, String>? = null) =
        Message(
            id = id, conversationId = conv,
            senderAddress = if (fromMe) c.address else if (conv == gid) carol else conv,
            recipientAddress = if (fromMe || conv == gid) conv else c.address,
            fromMe = fromMe, content = media?.second ?: text, mediaType = media?.first, sentAtMs = base + i * 60_000L,
            sentAtSource = TimestampSource.ISO8601, deliveryStatus = status, transport = "relay",
        )

    /** The "phone-like" desktop: history, a photo (sealed at rest), a group and an alias. */
    private fun seedA(a: OshiClient, photo: ByteArray) {
        for (i in 0 until 40) a.messages.append(msg(a, "A-$i", bob, i % 2 == 0, i, "hello $i"))
        a.messages.append(msg(a, "SHARED-1", bob, true, 50, "both have this", DeliveryStatus.DELIVERED))
        val f = File(a.mediaDir, "photo.jpg")
        a.mediaVault.sealingStream(f).use { it.write(photo) }
        a.messages.append(msg(a, "PHOTO-1", bob, false, 60, null, media = MediaType.IMAGE to "photo.jpg").copy(mediaRef = f.absolutePath))
        a.groups.put(GroupDefinition(gid, "Team", GroupType.COLLABORATIVE, a.address,
            listOf(GroupMember(a.address, joinedAtUnixMillis = base, isAdmin = true), GroupMember(bob, joinedAtUnixMillis = base), GroupMember(carol, joinedAtUnixMillis = base)),
            base, base + 1000))
        for (i in 0 until 5) a.messages.append(msg(a, "G-$i", gid, false, 70 + i, "group $i"))
        a.contacts.seen(bob, base, displayNameHint = "Bob")
    }

    /** The "Mac": part of the history (one message further along), plus what the phone never saw. */
    private fun seedB(b: OshiClient) {
        b.messages.append(msg(b, "shared-1", bob, true, 50, "both have this", DeliveryStatus.READ))
        for (i in 0 until 6) b.messages.append(msg(b, "B-ONLY-$i", bob, false, 100 + i, "arrived while the other was off $i"))
    }

    private fun assertConverged(a: OshiClient, b: OshiClient, photo: ByteArray) {
        waitUntil("both stores converged") {
            a.messages.messages(bob).size == 48 && b.messages.messages(bob).size == 48 &&
                b.messages.messages(gid).size == 5 &&
                b.messages.messages(bob).firstOrNull { it.id == "PHOTO-1" }?.mediaRef?.let { File(it).isFile } == true
        }
        assertEquals(DeliveryStatus.READ, a.messages.message(bob, "SHARED-1")!!.deliveryStatus)
        val g = b.groups.get(gid)
        assertNotNull("group definition travelled (members + admin)", g)
        assertEquals(3, g!!.members.size)
        assertTrue(g.isAdmin(a.address))
        assertEquals("Bob", b.contacts.get(bob)?.displayName)
        val ref = File(b.messages.messages(bob).first { it.id == "PHOTO-1" }.mediaRef!!)
        assertTrue("the photo is sealed at rest on the receiving machine too", com.oshi.desktop.store.MediaVault.isSealed(ref))
        assertTrue("and decrypts to the same bytes", photo.contentEquals(b.mediaVault.openStream(ref).use { it.readBytes() }))
        assertTrue(a.messages.messages(bob).any { it.id == "B-ONLY-5" })
    }

    // ------------------------------------------------------------------ relay

    private val harness = """
        'use strict';
        const dir = process.argv[2];
        process.env.OSHI_AUTH_MODE = 'enforce';
        process.env.OSHI_AUTH_BINDINGS_FILE = process.argv[3];
        const http = require('http');
        const auth = require(dir + '/oshi_auth.js');
        const { createRelay, makeAuthenticator } = require(dir + '/devsync_relay.js');
        const relay = createRelay({ authenticate: makeAuthenticator(auth), limits: { pingIntervalMs: 60000 }, log: () => {} });
        const server = http.createServer((req, res) => {
          if (req.url === '/stats') { res.writeHead(200); return res.end(JSON.stringify(relay.stats())); }
          res.writeHead(404); res.end();
        });
        server.on('upgrade', (req, socket, head) => { if (!relay.handleUpgrade(req, socket, head)) socket.end('HTTP/1.1 404 Not Found\r\n\r\n'); });
        server.listen(0, '127.0.0.1', () => console.log(JSON.stringify({ port: server.address().port })));
        process.stdin.on('end', () => process.exit(0));
        process.stdin.resume();
    """.trimIndent()

    private fun startRelay(bindings: Map<String, String>): Int {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        var patches: File? = null
        while (d != null && patches == null) {
            File(d, "ServerPatches/devsync_device_auth/v2").takeIf { File(it, "devsync_relay.js").isFile }?.let { patches = it }
            d = d.parentFile
        }
        val nodeBin = listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { File(it).canExecute() }
        Assume.assumeTrue("relay sources / node not available", patches != null && nodeBin != null)
        val work = dir("relay")
        val script = File(work, "h.js").apply { writeText(harness) }
        val binds = File(work, "b.json").apply { writeText(JSONObject(bindings).toString()) }
        val p = ProcessBuilder(nodeBin, script.absolutePath, patches!!.absolutePath, binds.absolutePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        node = p
        return JSONObject(BufferedReader(InputStreamReader(p.inputStream)).readLine()).getInt("port")
    }

    @Test
    fun twoDesktopClients_syncTheirRealStores_throughTheRealRelay() {
        val photo = DevSyncCrypto.randomBytes(180_000)
        // One account. The relay authenticates identity-bound upgrades, so the account's
        // (userKey -> signing key) binding is what a prekey publish would have recorded.
        val account = DesktopIdentity.generate()
        val recovery = IdentityStore.exportRecoveryKey(account)
        val port = startRelay(mapOf(account.userKey to DesktopIdentity.B64.encodeToString(account.signingPub)))
        val url = "http://127.0.0.1:$port"
        val a = client(url, "mac", recovery)
        val b = client(url, "linux", recovery)
        assertEquals(a.address, b.address)
        seedA(a, photo)
        seedB(b)
        val sa = devSync(a, "mac")
        val sb = devSync(b, "linux")
        waitUntil("pairing prompt on both, over the relay") {
            sa.snapshot().pending.isNotEmpty() && sb.snapshot().pending.isNotEmpty()
        }
        val pa = sa.snapshot().pending.first()
        assertEquals("relay", pa.transport)
        assertEquals(pa.code, sb.snapshot().pending.first().code)
        sa.approve(pa.peerId, true)
        assertConverged(a, b, photo)
        assertTrue(sa.snapshot().linked.any { it.name == "linux" })
        assertTrue(sb.snapshot().linked.any { it.name == "mac" })
        val stats = JSONObject(java.net.URL("http://127.0.0.1:$port/stats").readText())
        assertTrue("it really went through the relay: $stats", stats.getInt("framesForwarded") > 20)
    }

    // ------------------------------------------------------------------ LAN

    @Test
    fun twoDesktopClients_syncTheirRealStores_overLoopbackLan_withNoServerAtAll() {
        val photo = DevSyncCrypto.randomBytes(120_000)
        val noServer = "http://127.0.0.1:9"
        val recovery = IdentityStore.exportRecoveryKey(DesktopIdentity.generate())
        val a = client(noServer, "mac", recovery)
        val b = client(noServer, "linux", recovery)
        seedA(a, photo)
        seedB(b)
        val sa = devSync(a, "mac")
        val sb = devSync(b, "linux")
        waitUntil("engines up") { (sb.engineOrNull?.lan?.port ?: -1) > 0 && (sa.engineOrNull?.lan?.port ?: -1) > 0 }
        sa.dial("127.0.0.1", sb.engineOrNull!!.lan!!.port)
        waitUntil("pairing prompt") { sa.snapshot().pending.isNotEmpty() }
        sa.approve(sa.snapshot().pending.first().peerId, true)
        assertConverged(a, b, photo)
        waitUntil("session is LAN") { sa.snapshot().sessions.any { it.transport == "lan" && it.state == DevSyncSession.State.SYNC } }

        // __DEVSYNC_PROFILE_READ_LEAVE_2026_09_23__ profile, read state and group leave (§8.4, §8.6, §8.7),
        // made on A while it is offline, reach B at the next session.
        sa.stop()
        assertNull(a.ownNickname)
        a.setOwnNickname("Hugo (Mac)")                       // local edit ⇒ stamped updatedAt
        sa.noteRead(bob)                                     // opened the conversation on A
        val newestFromBob = a.messages.messages(bob).filter { !it.fromMe }.maxOf { it.sentAtMs }
        val aState = SealedStateStore(File(a.mediaDir.parentFile, "devsync"),
            com.oshi.desktop.store.LocalDataKeys.derive(com.oshi.desktop.store.LocalDataKeys.root(a.vault), DesktopDevSync.STATE_PURPOSE))
        // The group was left on another device of the account and A learnt it (latest leftAt kept,
        // §8.4 rejoin rule — was "earliest" before __DEVSYNC_REJOIN_2026_09_23__).
        val aStore = DesktopDevSyncStore({ a.address }, a.messages, a.groups, a.contacts, a.mediaDir, a.mediaVault, aState)
        aStore.putGroup(com.oshi.messenger.network.v2.devsync.SyncGroup(groupId = gid, name = "Team", left = true, leftAtMs = base + 5_000, updatedAtMs = base + 5_000))
        aStore.putGroup(com.oshi.messenger.network.v2.devsync.SyncGroup(groupId = gid, name = "Team", left = true, leftAtMs = base + 9_000, updatedAtMs = base + 9_000))
        assertNull("left locally", a.groups.get(gid))
        val remoteReads = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
        sb.onRemoteRead { conv, upTo -> remoteReads += conv to upTo }
        sa.tick()
        waitUntil("A engine up again") { (sa.engineOrNull?.lan?.port ?: -1) > 0 }
        sa.dial("127.0.0.1", sb.engineOrNull!!.lan!!.port)
        waitUntil("profile, read state and leave reached B") {
            b.ownNickname == "Hugo (Mac)" && b.groups.get(gid) == null &&
                sb.readMarks.all()["direct:" + com.oshi.desktop.store.ExportV2.canonicalKey(bob)]?.readUpToMs == newestFromBob
        }
        assertTrue("the window was told to recount its badge", remoteReads.any { it.first == bob && it.second == newestFromBob })
        val bState = SealedStateStore(File(b.mediaDir.parentFile, "devsync"),
            com.oshi.desktop.store.LocalDataKeys.derive(com.oshi.desktop.store.LocalDataKeys.root(b.vault), DesktopDevSync.STATE_PURPOSE))
        val bLeft = DesktopDevSyncStore({ b.address }, b.messages, b.groups, b.contacts, b.mediaDir, b.mediaVault, bState)
            .groups().single { it.groupId == gid }
        assertTrue("left is sticky on B", bLeft.left)
        assertEquals("latest leftAt", base + 9_000, bLeft.leftAtMs)
        // Sticky: a later definition without `left` does not bring the group back...
        aStore.putGroup(com.oshi.messenger.network.v2.devsync.SyncGroup(groupId = gid, name = "Team", members = listOf(a.address), updatedAtMs = base + 20_000))
        assertNull(a.groups.get(gid))
        // ...and the group ingest refuses a member's re-share of it (the desktop's only tombstone).
        assertTrue("left group refused by the ingest", sa.refusesGroup(gid))
        // __DEVSYNC_REJOIN_2026_09_23__ unless the user REJOINED on another device (joinedAt > leftAt).
        aStore.putGroup(com.oshi.messenger.network.v2.devsync.SyncGroup(groupId = gid, name = "Team", members = listOf(a.address, bob),
            joinedAtMs = base + 30_000, updatedAtMs = base + 30_000))
        assertNotNull("rejoined elsewhere ⇒ back here", a.groups.get(gid))
        assertFalse("no longer refused", sa.refusesGroup(gid))
        assertEquals(base + 30_000, aStore.groups().single { it.groupId == gid }.joinedAtMs)
        // A leave made AFTER that rejoin wins again.
        aStore.putGroup(com.oshi.messenger.network.v2.devsync.SyncGroup(groupId = gid, name = "Team", left = true,
            leftAtMs = base + 40_000, joinedAtMs = base + 30_000, updatedAtMs = base + 40_000))
        assertNull(a.groups.get(gid))
        assertTrue(sa.refusesGroup(gid))
    }
}
