package com.oshi.desktop.devsync

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.messenger.network.v2.devsync.BytesMediaSource
import com.oshi.messenger.network.v2.devsync.DevSyncConfig
import com.oshi.messenger.network.v2.devsync.DevSyncCrypto
import com.oshi.messenger.network.v2.devsync.DevSyncEngine
import com.oshi.messenger.network.v2.devsync.DevSyncListener
import com.oshi.messenger.network.v2.devsync.DevSyncSession
import com.oshi.messenger.network.v2.devsync.InMemoryDevSyncStore
import com.oshi.messenger.network.v2.devsync.InMemoryStateStore
import com.oshi.messenger.network.v2.devsync.MediaSource
import com.oshi.messenger.network.v2.devsync.PairingRequest
import com.oshi.messenger.network.v2.devsync.DevSyncKeys
import com.oshi.messenger.network.v2.devsync.RelayListener
import com.oshi.messenger.network.v2.devsync.RelaySocket
import com.oshi.messenger.network.v2.devsync.SeedRelayDeviceCredentials
import com.oshi.messenger.network.v2.devsync.SyncCodec
import com.oshi.messenger.network.v2.devsync.SyncContact
import com.oshi.messenger.network.v2.devsync.SyncConversation
import com.oshi.messenger.network.v2.devsync.SyncGroup
import com.oshi.messenger.network.v2.devsync.SyncGroupKey
import com.oshi.messenger.network.v2.devsync.SyncMedia
import com.oshi.messenger.network.v2.devsync.SyncMessage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * __DEVSYNC_DIRECT_2026_09_22__ Two (or three) in-process desktop clients of ONE account syncing
 * directly: over loopback LAN (TCP + Noise), and over a LOCAL RUN of the real
 * `ServerPatches/devsync_device_auth/v2/devsync_relay.js` (node) with signed, identity-bound
 * upgrades. Plus flow control and interrupted-media resume.
 */
class DevSyncEndToEndTest {

    private val bob = key(1)
    private val carol = key(2)
    private val me = key(3)
    private val gid = "5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D"
    private val engines = CopyOnWriteArrayList<DevSyncEngine>()
    private var node: Process? = null
    private val tmpDirs = ArrayList<File>()

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.stop() } }
        node?.destroy()
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun key(n: Int): String = Base64.getEncoder().encodeToString(DevSyncCrypto.sha256(byteArrayOf(n.toByte())))

    private fun tmp(): File = kotlin.io.path.createTempDirectory("devsync-e2e").toFile().also { tmpDirs += it }

    private class Recorder : DevSyncListener {
        val requests = CopyOnWriteArrayList<PairingRequest>()
        val synced = CopyOnWriteArrayList<String>()
        val alerts = CopyOnWriteArrayList<String>()
        override fun onPairingRequest(request: PairingRequest) { requests += request }
        override fun onSynced(peerId: String, stats: DevSyncSession.SessionStats) { synced += peerId }
        override fun onAlert(kind: String, deviceName: String) { alerts += kind }
    }

    private fun engine(
        name: String,
        account: ByteArray,
        store: com.oshi.messenger.network.v2.devsync.DevSyncStore,
        windowRecords: Int = 32,
        windowBytes: Long = 2L * 1024 * 1024,
        state: InMemoryStateStore = InMemoryStateStore(),
        deviceKey: ByteArray = DevSyncCrypto.randomBytes(32),
    ): Pair<DevSyncEngine, Recorder> {
        val cfg = DevSyncConfig(
            deviceName = name, platform = "desktop", appVersion = "test", cacheDir = tmp(),
            windowRecords = windowRecords, windowBytes = windowBytes, relayDelayMs = 50,
        )
        val e = DevSyncEngine(cfg, account, { deviceKey }, store, state) { if (System.getenv("DEVSYNC_VERBOSE") != null) println("$name $it") }
        val rec = Recorder()
        e.addListener(rec)
        engines += e
        return e to rec
    }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun msg(id: String, conv: SyncConversation, out: Boolean, ts: Long, text: String?, status: String = if (out) "sent" else "delivered", media: SyncMedia? = null, read: Boolean = true) =
        SyncMessage(id = id, conversation = conv.id, outgoing = out, senderPublicKey = if (out) me else bob, timestampMs = ts, text = text, status = status, read = read, media = media)

    private fun digests(s: InMemoryDevSyncStore): Map<String, String> =
        s.conversations().associate { c -> c.id to SyncCodec.digest(s.messages(c.id).map { it.id to SyncCodec.rev(it) }) }

    /** "Phone" A: 1:1 history with media, a group (definition + key + messages), a contact alias. */
    private fun phoneStore(mediaBytes: ByteArray): InMemoryDevSyncStore {
        val group = SyncGroup(gid, "Team", nameAtMs = 1_700_000_000_000, type = "collaborative", creator = me,
            members = listOf(me, bob, carol), admins = listOf(me),
            updatedAtMs = 1_700_000_100_000,
            keys = listOf(SyncGroupKey(SyncGroupKey.KIND_LEGACY_AES, 0, Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))))
        val s = InMemoryDevSyncStore(listOf(group))
        val d = SyncConversation.direct(bob)
        val base = 1_758_000_000_000L
        val list = (0 until 30).map { i -> msg("MSG-$i", d, i % 2 == 0, base + i * 3_600_000L, "text $i") }.toMutableList()
        val sha = s.addMedia(mediaBytes)
        list += msg("MEDIA-1", d, false, base + 40 * 3_600_000L, null, media = SyncMedia("image", "cat.jpg", "image/jpeg", mediaBytes.size.toLong(), sha))
        list[4] = list[4].copy(status = "delivered")
        s.add(d, *list.toTypedArray())
        val g = SyncConversation.group(gid, "Team")
        s.add(g, *(0 until 5).map { i -> msg("G-$i", g, false, base + i * 1000L, "group $i").copy(senderPublicKey = carol, senderName = "Carol") }.toTypedArray())
        s.putContact(SyncContact(bob, alias = "Bob", aliasAtMs = base))
        return s
    }

    /** "Mac" B: part of the same history (one message in a different state, one id in another case), plus messages the phone never saw. */
    private fun macStore(): InMemoryDevSyncStore {
        val s = InMemoryDevSyncStore()
        val d = SyncConversation.direct(bob)
        val base = 1_758_000_000_000L
        val list = (0 until 10).map { i -> msg(if (i == 3) "msg-$i" else "MSG-$i", d, i % 2 == 0, base + i * 3_600_000L, "text $i") }.toMutableList()
        list[4] = list[4].copy(status = "read")   // the Mac saw the read receipt; the phone only "delivered"
        list += (0 until 5).map { i -> msg("MAC-ONLY-$i", d, false, base + (50 + i) * 3_600_000L, "arrived while the phone was off $i") }
        s.add(d, *list.toTypedArray())
        return s
    }

    private fun assertConverged(a: InMemoryDevSyncStore, b: InMemoryDevSyncStore, mediaSha: String) {
        assertEquals(digests(a), digests(b))
        assertEquals(36 + 5, a.count())
        val merged = a.get(SyncConversation.direct(bob).id, "MSG-4")!!
        assertEquals("read", merged.status)
        assertEquals("read", b.get(SyncConversation.direct(bob).id, "msg-4")!!.status)
        assertNotNull("group definition synced before its messages", b.groups().firstOrNull { it.groupId == gid })
        assertEquals(1, b.groups().first().keys.size)
        assertEquals(3, b.groups().first().members.size)
        assertEquals(5, b.messages(SyncConversation.group(gid).id).size)
        assertEquals("Bob", b.contacts().first { it.publicKey == bob }.alias)
        assertTrue("media bytes fetched and verified", b.media.containsKey(mediaSha))
        assertTrue(b.attached.any { it.second == "MEDIA-1" && it.third == mediaSha })
    }

    // ============================================================================== LAN

    @Test
    fun lan_pairsWithCode_thenSyncsBothDirections_live_andPropagatesDevices() {
        val account = DevSyncCrypto.randomBytes(32)
        val media = ByteArray(300_000) { (it * 31).toByte() }
        val sa = phoneStore(media)
        val sb = macStore()
        val (a, ra) = engine("phone", account, sa)
        val (b, rb) = engine("mac", account, sb)
        a.start(lanEnabled = true); b.start(lanEnabled = true)
        waitUntil("listeners") { a.lan != null && b.lan!!.port > 0 && a.lan!!.port > 0 }
        a.lan!!.dial("127.0.0.1", b.lan!!.port)

        // §4.3: both screens show the same six digits.
        waitUntil("pairing prompts") { ra.requests.isNotEmpty() && rb.requests.isNotEmpty() }
        assertEquals(ra.requests.first().code, rb.requests.first().code)
        assertEquals(b.deviceId, ra.requests.first().peerId)
        assertTrue("B is new: it waits for approval", rb.requests.first().thisDeviceIsNew)
        a.approve(b.deviceId, true)   // tapped Allow on the phone; the fresh Mac accepts the approval

        waitUntil("converged") { digests(sa) == digests(sb) && sb.media.size == 1 }
        assertConverged(sa, sb, sa.media.keys.first())
        assertTrue(a.registry.isLinked(b.deviceId) && b.registry.isLinked(a.deviceId))

        // §9 LIVE: a message created while the session is open is mirrored at once.
        val d = SyncConversation.direct(bob)
        val live = msg("LIVE-1", d, true, 1_759_000_000_000, "hello from the phone")
        sa.add(d, live)
        a.onLocalMessages(d, listOf(live))
        waitUntil("live mirrored") { sb.get(d.id, "LIVE-1") != null }

        // A third device, approved on the PHONE, is then learned by the Mac through DEVICES (§4.3 step 4)
        // and the Mac and it sync with no prompt at all.
        val sc = InMemoryDevSyncStore()
        val (c, rc) = engine("ipad", account, sc)
        c.start(lanEnabled = true)
        waitUntil("c listener") { c.lan != null && c.lan!!.port > 0 }
        a.lan!!.dial("127.0.0.1", c.lan!!.port)
        waitUntil("pairing a-c") { ra.requests.any { it.peerId == c.deviceId } }
        a.approve(c.deviceId, true)
        waitUntil("c linked") { c.registry.isLinked(a.deviceId) && a.registry.isLinked(c.deviceId) }
        a.syncNow() // re-sends DEVICES? No: DEVICES goes at session start — reconnect the Mac:
        a.disconnectAll(); b.disconnectAll()
        Thread.sleep(200)
        b.lan!!.rediscover(); a.lan!!.rediscover()
        a.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("mac learns ipad") { b.registry.isLinked(c.deviceId) }
        c.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("mac <-> ipad sync without prompt") { sc.count() == sa.count() && digests(sc) == digests(sb) }
        assertTrue("no prompt between two devices both learned through DEVICES", rb.requests.none { it.peerId == c.deviceId } && rc.requests.none { it.peerId == b.deviceId })
    }

    @Test
    fun lan_unknownDevice_deniedAndRevokedDevice_refused() {
        val account = DevSyncCrypto.randomBytes(32)
        val sa = phoneStore(ByteArray(10))
        val (a, ra) = engine("phone", account, sa)
        val (b, _) = engine("mac", account, InMemoryDevSyncStore())
        a.start(lanEnabled = true); b.start(lanEnabled = true)
        waitUntil("listeners") { a.lan != null && b.lan != null && a.lan!!.port > 0 && b.lan!!.port > 0 }
        a.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("prompt") { ra.requests.isNotEmpty() }
        a.approve(b.deviceId, false)
        waitUntil("closed") { a.sessionsInfo().isEmpty() }
        assertFalse(a.registry.isLinked(b.deviceId))
        assertFalse(b.registry.isLinked(a.deviceId))
        assertEquals(0, (b.store as InMemoryDevSyncStore).count())

        // A device of ANOTHER account (different PSK) cannot even get past message 1.
        val (x, rx) = engine("stranger", DevSyncCrypto.randomBytes(32), InMemoryDevSyncStore())
        x.start(lanEnabled = true)
        waitUntil("x") { x.lan != null && x.lan!!.port > 0 }
        a.lan!!.rediscover()
        a.lan!!.dial("127.0.0.1", x.lan!!.port)
        Thread.sleep(700)
        assertTrue(rx.requests.isEmpty())
        assertTrue(ra.requests.none { it.peerId == x.deviceId })
    }

    /**
     * One side still trusts the other, the other lost its linked list (reinstall that kept the
     * device key, wiped app data…). Without care this deadlocks: the trusting side is in SYNC and
     * shows no prompt, the fresh side waits for an approval that never comes.
     */
    @Test
    fun relink_afterOneSideLostItsList_recoversWithoutAPrompt() {
        val account = DevSyncCrypto.randomBytes(32)
        val keyB = DevSyncCrypto.randomBytes(32)
        val sa = phoneStore(ByteArray(64) { 3 })
        val sb = InMemoryDevSyncStore()
        val (a, _) = engine("phone", account, sa)
        val (b, _) = engine("mac", account, sb, deviceKey = keyB)
        a.start(lanEnabled = true); b.start(lanEnabled = true)
        waitUntil("listeners") { (a.lan?.port ?: -1) > 0 && (b.lan?.port ?: -1) > 0 }
        a.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("prompt") { a.pendingApprovals().isNotEmpty() }
        a.approve(b.deviceId, true)
        waitUntil("first sync") { sb.count() == sa.count() }
        b.stop(); engines.remove(b)

        // Same device key, EMPTY state: the Mac forgot every linked device.
        val sb2 = InMemoryDevSyncStore()
        val (b2, rb2) = engine("mac", account, sb2, deviceKey = keyB)
        assertEquals(b.deviceId, b2.deviceId)
        assertTrue(a.registry.isLinked(b2.deviceId))
        assertFalse(b2.registry.isLinked(a.deviceId))
        b2.start(lanEnabled = true)
        waitUntil("b2 listener") { (b2.lan?.port ?: -1) > 0 }
        a.lan!!.rediscover()
        a.lan!!.dial("127.0.0.1", b2.lan!!.port)
        waitUntil("re-linked and re-synced with no user action", 30_000) {
            b2.registry.isLinked(a.deviceId) && sb2.count() == sa.count()
        }
        assertTrue("the phone never had to be asked again", a.pendingApprovals().isEmpty())
        assertTrue(rb2.requests.all { it.thisDeviceIsNew })
    }

    // ============================================================================== flow control + resume

    @Test
    fun flowControl_windowIsNeverExceeded_andSyncStillCompletes() {
        val account = DevSyncCrypto.randomBytes(32)
        val sa = InMemoryDevSyncStore()
        val d = SyncConversation.direct(bob)
        // ~2 000 messages with ~1 KB bodies: far more records than the window.
        sa.add(d, *(0 until 2000).map { i -> msg("FC-$i", d, false, 1_758_000_000_000 + i * 60_000L, "x".repeat(1000) + i) }.toTypedArray())
        val sb = InMemoryDevSyncStore()
        val (a, _) = engine("a", account, sa, windowRecords = 4, windowBytes = 64 * 1024)
        val (b, _) = engine("b", account, sb, windowRecords = 4, windowBytes = 64 * 1024)
        a.start(lanEnabled = true); b.start(lanEnabled = true)
        waitUntil("listeners") { a.lan != null && b.lan != null && a.lan!!.port > 0 && b.lan!!.port > 0 }
        a.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("prompt") { a.pendingApprovals().isNotEmpty() }
        a.approve(b.deviceId, true)
        waitUntil("synced 2000", 60_000) { sb.count() == 2000 }
        val infos = a.sessionsInfo() + b.sessionsInfo()
        assertTrue(infos.isNotEmpty())
        for (i in infos) {
            assertTrue("unacked records exceeded the window: ${i.stats.maxUnackedObserved}", i.stats.maxUnackedObserved <= 4)
        }
        assertTrue("records actually flowed through the window", infos.sumOf { it.stats.recordsOut } > 40)
    }

    /** A media source that reads slowly so the transfer can be cut in the middle. */
    private class SlowStore(private val delegate: InMemoryDevSyncStore) : com.oshi.messenger.network.v2.devsync.DevSyncStore by delegate {
        @Volatile var bytesServed = 0L
        override fun openMedia(sha256: String): MediaSource? {
            val src = delegate.openMedia(sha256) ?: return null
            return object : MediaSource {
                override val size = src.size
                override fun open(): InputStream = object : FilterInputStream(src.open()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        Thread.sleep(15)
                        val n = super.read(b, off, len)
                        if (n > 0) bytesServed += n
                        return n
                    }
                }
            }
        }
    }

    @Test
    fun media_interruptedTransfer_resumesFromThePartialFile() {
        val account = DevSyncCrypto.randomBytes(32)
        val media = DevSyncCrypto.randomBytes(3 * 1024 * 1024)
        val inner = InMemoryDevSyncStore()
        val d = SyncConversation.direct(bob)
        val sha = inner.addMedia(media)
        inner.add(d, msg("BIG", d, false, 1_758_000_000_000, null, media = SyncMedia("video", "v.mp4", "video/mp4", media.size.toLong(), sha)))
        val sa = SlowStore(inner)
        val sb = InMemoryDevSyncStore()
        val stateB = InMemoryStateStore()
        val (ea, _) = engine("a", account, sa)
        val (b, _) = engine("b", account, sb, state = stateB)
        ea.start(lanEnabled = true); b.start(lanEnabled = true)
        waitUntil("listeners") { ea.lan != null && b.lan != null && ea.lan!!.port > 0 && b.lan!!.port > 0 }
        ea.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("prompt") { ea.pendingApprovals().isNotEmpty() }
        ea.approve(b.deviceId, true)
        val partial = b.partialFile(sha)
        waitUntil("some media arrived", 30_000) { partial.isFile && partial.length() > 600_000 }
        b.disconnectAll("cut"); ea.disconnectAll("cut")
        waitUntil("sessions closed") { b.sessionsInfo().isEmpty() && ea.sessionsInfo().isEmpty() }
        val cutAt = partial.length()
        assertTrue(cutAt in 600_000 until media.size)
        assertFalse(sb.media.containsKey(sha))
        val servedBefore = sa.bytesServed

        // Next session: already linked, no prompt; MEDIA_GET{offset = partial size}.
        ea.lan!!.rediscover()
        ea.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("resumed and verified", 60_000) { sb.media.containsKey(sha) }
        assertTrue(media.contentEquals(sb.media[sha]))
        val servedAfter = sa.bytesServed - servedBefore
        // At most one chunk of overlap, never a restart from zero.
        assertTrue("resumed from $cutAt, re-served $servedAfter bytes", servedAfter <= media.size - cutAt + 61_440)
        assertFalse(partial.exists())
    }

    // ============================================================================== relay (real devsync_relay.js)

    private fun serverPatchesDir(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "ServerPatches/devsync_device_auth/v2")
            if (File(f, "devsync_relay.js").isFile) return f
            d = d.parentFile
        }
        return null
    }

    private fun nodeBinary(): String? = listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")
        .firstOrNull { File(it).canExecute() } ?: runCatching {
            ProcessBuilder("which", "node").start().inputStream.bufferedReader().readLine()?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private val harness = """
        'use strict';
        const dir = process.argv[2];
        process.env.OSHI_AUTH_MODE = 'enforce';
        process.env.OSHI_AUTH_BINDINGS_FILE = process.argv[3];
        const http = require('http');
        const auth = require(dir + '/oshi_auth.js');
        const { createRelay, makeAuthenticator } = require(dir + '/devsync_relay.js');
        // __DEVSYNC_DEVICE_AUTH_2026_09_23__ a stand-in for the per-device registry (enforcing):
        // POST /registry {identity, deviceId, dk, dsk} registers a device.
        const reg = new Map();
        const devices = {
          lookup: (i, id) => (reg.get(i) || new Map()).get(id) || null,
          count: (i) => (reg.get(i) || new Map()).size,
          enforcing: () => true,
        };
        const relay = createRelay({ authenticate: makeAuthenticator(auth), devices, limits: { pingIntervalMs: 60000 }, log: () => {} });
        const server = http.createServer((req, res) => {
          if (req.url === '/stats') { res.writeHead(200); return res.end(JSON.stringify(relay.stats())); }
          if (req.url === '/registry' && req.method === 'POST') {
            let b = ''; req.on('data', (c) => { b += c; });
            return req.on('end', () => {
              const o = JSON.parse(b); const i = auth.normalizeIdentity(o.identity);
              if (!reg.has(i)) reg.set(i, new Map());
              reg.get(i).set(o.deviceId, { dk: o.dk, dsk: o.dsk });
              res.writeHead(200); res.end('{}');
            });
          }
          if (req.url.startsWith('/tier?')) {
            const q = new URL(req.url, 'http://x').searchParams;
            res.writeHead(200); return res.end(JSON.stringify({ tier: relay._tierOf(q.get('identity'), q.get('device')) }));
          }
          res.writeHead(404); res.end();
        });
        server.on('upgrade', (req, socket, head) => { if (!relay.handleUpgrade(req, socket, head)) socket.end('HTTP/1.1 404 Not Found\r\n\r\n'); });
        server.listen(0, '127.0.0.1', () => console.log(JSON.stringify({ port: server.address().port })));
        process.stdin.on('end', () => process.exit(0));
        process.stdin.resume();
    """.trimIndent()

    private fun startRelay(bindings: Map<String, String>): Int {
        val dir = serverPatchesDir()
        val nodeBin = nodeBinary()
        Assume.assumeTrue("ServerPatches/devsync_device_auth/v2 not found", dir != null)
        Assume.assumeTrue("node not installed", nodeBin != null)
        val work = tmp()
        val script = File(work, "relay_harness.js").apply { writeText(harness) }
        val bindFile = File(work, "bindings.json").apply { writeText(JSONObject(bindings).toString()) }
        val p = ProcessBuilder(nodeBin, script.absolutePath, dir!!.absolutePath, bindFile.absolutePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        node = p
        val line = BufferedReader(InputStreamReader(p.inputStream)).readLine() ?: error("relay harness died")
        return JSONObject(line).getInt("port")
    }

    private fun relayStats(port: Int): JSONObject =
        JSONObject(java.net.URL("http://127.0.0.1:$port/stats").readText())

    private fun creds(dkPub: ByteArray = DevSyncCrypto.x25519Public(DevSyncCrypto.randomBytes(32))) =
        SeedRelayDeviceCredentials(dkPub, DevSyncCrypto.randomBytes(32))

    /** One raw relay socket: waits for open or refusal; returns (socket, listener state). */
    private class Probe : RelayListener {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val texts = CopyOnWriteArrayList<String>()
        val binaries = CopyOnWriteArrayList<ByteArray>()
        @Volatile var closeCode = 0
        @Volatile var didOpen = false
        override fun onOpen() { didOpen = true; opened.countDown() }
        override fun onText(text: String) { texts += text }
        override fun onBinary(bytes: ByteArray) { binaries += bytes }
        override fun onClosed(code: Int, reason: String) { closeCode = code; closed.countDown(); opened.countDown() }
        fun isOpen() = opened.await(10, TimeUnit.SECONDS) && closed.count == 1L
    }

    private fun probe(base: String, id: DesktopIdentity, c: SeedRelayDeviceCredentials, clock: () -> Long = System::currentTimeMillis): Pair<RelaySocket, Probe> {
        val p = Probe()
        val sock = DesktopRelayConnector(base, DesktopV2Signer(id), { c }, clock = clock).connect(DevSyncKeys.deviceIdHex(c.dkPub), p)
        return sock to p
    }

    private fun post(port: Int, path: String, body: JSONObject) {
        val c = java.net.URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        assertEquals(200, c.responseCode)
    }

    private fun tier(port: Int, identity: String, device: String): String? =
        JSONObject(java.net.URL("http://127.0.0.1:$port/tier?identity=" + java.net.URLEncoder.encode(identity, "UTF-8") + "&device=$device").readText())
            .optString("tier").takeIf { it.isNotEmpty() && it != "null" }

    /**
     * __DEVSYNC_DEVICE_AUTH_2026_09_23__ The two findings, through the real connector and the real relay:
     * (a) two devices of one account in the SAME millisecond both connect; (b) another device key cannot
     * take a live device id, nor a registered one; an unregistered device is a bootstrap ("pending") socket.
     */
    @Test
    fun relay_deviceBoundUpgrade_sameMillisecond_noHijack_registryTiers() {
        val id = DesktopIdentity.generate()
        val port = startRelay(mapOf(id.userKey to DesktopIdentity.B64.encodeToString(id.signingPub)))
        val base = "http://127.0.0.1:$port"

        // (a) same account key, same millisecond, two devices.
        val fixed = System.currentTimeMillis()
        val ca = creds(); val cb = creds()
        val (sa, pa) = probe(base, id, ca) { fixed }
        val (sb, pb) = probe(base, id, cb) { fixed }
        assertTrue("device A open", pa.isOpen())
        assertTrue("device B open in the same millisecond (was 401 replayed)", pb.isOpen())
        assertEquals(2, relayStats(port).getInt("connections"))

        // (b) the account key with ANOTHER device key, claiming A's live id: refused, A untouched.
        val thief = SeedRelayDeviceCredentials(ca.dkPub, DevSyncCrypto.randomBytes(32))
        val (_, pt) = probe(base, id, thief)
        assertTrue(pt.closed.await(10, TimeUnit.SECONDS))
        assertFalse("hijacker refused at the upgrade", pt.didOpen)
        val idA = DevSyncKeys.deviceIdHex(ca.dkPub)
        val idB = DevSyncKeys.deviceIdHex(cb.dkPub)
        assertTrue(sb.sendBinary(DevSyncCrypto.unhex(idA) + byteArrayOf(4, 1, 2, 3)))
        val end = System.currentTimeMillis() + 5000
        while (pa.binaries.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals("A still receives its frames", 1, pa.binaries.size)
        assertEquals(idB, DevSyncCrypto.hex(pa.binaries[0].copyOfRange(0, 16)))
        assertEquals(1L, pa.closed.count)

        // Registry: A registered; its id with another dsk is refused even once A is gone; a new device is pending.
        post(port, "/registry", JSONObject().put("identity", id.userKey).put("deviceId", idA)
            .put("dk", Base64.getEncoder().encodeToString(ca.dkPub)).put("dsk", Base64.getEncoder().encodeToString(ca.dskPub)))
        sa.close()
        Thread.sleep(200)
        val (_, pt2) = probe(base, id, thief)
        assertTrue(pt2.closed.await(10, TimeUnit.SECONDS))
        assertFalse("a registered id with another dsk is refused", pt2.didOpen)
        val (sa2, pa2) = probe(base, id, ca)
        assertTrue("the registered device reconnects with its own dsk", pa2.isOpen())
        assertEquals("registered", tier(port, id.userKey, idA))
        val cn = creds()
        val (sn, pn) = probe(base, id, cn)
        assertTrue(pn.isOpen())
        assertEquals("pending", tier(port, id.userKey, DevSyncKeys.deviceIdHex(cn.dkPub)))
        listOf(sa2, sb, sn).forEach { runCatching { it.close() } }
    }

    @Test
    fun relay_signedUpgrade_pairs_syncs_andRevokes_throughTheRealNodeRelay() {
        val id = DesktopIdentity.generate()
        val bindings = mapOf(id.userKey to DesktopIdentity.B64.encodeToString(id.signingPub))
        val port = startRelay(bindings)
        val base = "http://127.0.0.1:$port"

        // An identity that never bound its signing key is refused at the upgrade (401), in enforce mode.
        val stranger = DesktopIdentity.generate()
        val refused = CountDownLatch(1)
        var refusedCode = 0
        val strangerCreds = creds()
        DesktopRelayConnector(base, DesktopV2Signer(stranger), { strangerCreds }).connect(DevSyncKeys.deviceIdHex(strangerCreds.dkPub), object : RelayListener {
            override fun onOpen() {}
            override fun onText(text: String) {}
            override fun onBinary(bytes: ByteArray) {}
            override fun onClosed(code: Int, reason: String) { refusedCode = code; refused.countDown() }
        })
        assertTrue("unbound identity must be refused", refused.await(10, TimeUnit.SECONDS))
        assertEquals(-1, refusedCode)

        val account = id.identity.priv
        val media = ByteArray(200_000) { (it * 7).toByte() }
        val sa = phoneStore(media)
        val sb = macStore()
        val (a, ra) = engine("phone", account, sa)
        val (b, rb) = engine("mac", account, sb)
        // __DEVSYNC_DEVICE_AUTH_2026_09_23__ each engine's own dk + its own dsk.
        a.start(relayConnector = DesktopRelayConnector(base, DesktopV2Signer(id), creds(a.deviceKeyPub).let { c -> { c } }))
        b.start(relayConnector = DesktopRelayConnector(base, DesktopV2Signer(id), creds(b.deviceKeyPub).let { c -> { c } }))
        waitUntil("pairing prompts over the relay") { ra.requests.isNotEmpty() && rb.requests.isNotEmpty() }
        assertEquals("relay", ra.requests.first().transport)
        assertEquals(ra.requests.first().code, rb.requests.first().code)
        // Approval from EITHER device: here the Mac (a device that also holds history) approves.
        b.approve(a.deviceId, true)
        // The phone is also fresh (nothing linked yet) so it accepts; both are now linked.
        waitUntil("converged over the relay", 30_000) { digests(sa) == digests(sb) && sb.media.size == 1 && sa.media.size == 1 }
        assertConverged(sa, sb, sa.media.keys.first())
        val stats = relayStats(port)
        assertTrue("frames went through the relay: $stats", stats.getInt("framesForwarded") > 10)
        assertEquals(2, stats.getInt("connections"))

        // Revocation: the Mac removes the phone; the phone's next attempts are refused with BYE revoked.
        b.revoke(a.deviceId)
        waitUntil("revoked session closed") { a.sessionsInfo().none { it.state == DevSyncSession.State.SYNC } }
        assertTrue(b.registry.isRevoked(a.deviceId))
        a.syncNow()
        Thread.sleep(500)
        assertTrue(b.sessionsInfo().none { it.state == DevSyncSession.State.SYNC })
    }
}
