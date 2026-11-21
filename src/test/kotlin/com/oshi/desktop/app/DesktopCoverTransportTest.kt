package com.oshi.desktop.app

import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.net.DesktopCover
import com.oshi.desktop.net.RelayServer
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import com.oshi.messenger.service.CoverCodec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * __PRIVACY_DISGUISE_2026_11_18__ "Disguise OSHI traffic" on the desktop — the wire format, the
 * fail-closed rule and the waiting state, measured end to end against the in-process relay.
 *
 * [CoverRelay] stands where the production relay's `cover_transport.js` stands: it decodes a
 * cover request with the SAME strict decoder the phones use, forwards the inner signed request
 * to [RelayServer], and wraps the answer. Anything that reaches it NOT disguised is counted in
 * [CoverRelay.plain] — that counter is the "nothing left undisguised" assertion.
 */
class DesktopCoverTransportTest {

    private lateinit var settings: File
    private val dirs = ArrayList<File>()
    private val clients = ArrayList<OshiClient>()

    @Before fun scratchSettings() {
        settings = Files.createTempFile("privacy-disguise", ".json").toFile().also { it.delete() }
        DesktopCover.useFile(settings)
    }

    @After fun reset() {
        DesktopCover.ShortConnections.reset()
        clients.forEach { runCatching { it.stop() } }
        DesktopCover.setEnabled(false)
        DesktopCover.useFile(settings) // keep pointing at the scratch file, never the user's
        dirs.forEach { it.deleteRecursively() }
        settings.delete()
    }

    // ------------------------------------------------------------------ settings

    @Test fun `off by default and a corrupt file reads as off`() {
        assertFalse(DesktopCover.isEnabled())
        settings.writeText("{not json")
        DesktopCover.useFile(settings)
        assertFalse(DesktopCover.isEnabled())
        DesktopCover.setEnabled(true)
        assertTrue(DesktopCover.isEnabled())
        assertEquals("Android's key name", true, JSONObject(settings.readText()).getBoolean("covert_channel_enabled"))
    }

    // ------------------------------------------------------------------ vectors

    /** docs/fixtures/cover_transport — the vectors Android, iOS and the relay all check. */
    @Test fun `shared cover vectors decode and the strict rejects hold`() {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/fixtures/cover_transport/vectors.json") }.first { it.isFile }
        val v = JSONObject(f.readText())
        val accept = v.getJSONArray("accept")
        for (i in 0 until accept.length()) {
            val t = accept.getJSONObject(i)
            val got = CoverCodec.decode(t.getString("body").toByteArray(), t.getString("contentType"))
            assertEquals(t.getString("name"), t.getJSONObject("expect").toMap(), got.toMap())
        }
        val reject = v.getJSONArray("reject")
        for (i in 0 until reject.length()) {
            val t = reject.getJSONObject(i)
            assertFalse(t.getString("name"), runCatching { CoverCodec.decode(t.getString("body").toByteArray(), t.getString("contentType")) }.isSuccess)
        }
        val paths = v.getJSONArray("endpointPaths")
        assertEquals(listOf("/v2/cover", "/v2/collect", "/v2/metrics"), (0 until paths.length()).map { paths.getString(it) })
    }

    // ------------------------------------------------------------------ V2Http

    @Test fun `with the cover on a V2 call leaves disguised, asks for identity encoding, and answers`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val id = IdentityStore.loadOrCreate(KeyVault.open(tmp("v").resolve(KeyVault.FILE_NAME), InMemorySecretStore(), null))
                val http = V2Http(DesktopV2Signer(id), proxy.baseUrl, ohttp = { null }, cover = { true })
                val r = http.get("/v2/config")
                assertEquals(200, r.code)
                assertTrue(r.body.contains("rollout") || r.body.startsWith("{"))
                assertEquals("nothing undisguised", 0, proxy.plain.get())
                assertEquals(1, proxy.covered.get())
                assertTrue("Accept-Encoding: identity on every cover request", proxy.acceptEncodings.all { it == "identity" })
                assertTrue(proxy.outerPaths.all { it in setOf("/v2/cover", "/v2/collect", "/v2/metrics") })
                assertEquals(0, http.directSends.get())
            }
        }
    }

    @Test fun `with the cover on a failing cover is a failed call, never a direct one`() {
        RelayServer().use { relay ->   // knows nothing about /v2/cover: every cover request 404s
            val id = IdentityStore.loadOrCreate(KeyVault.open(tmp("f").resolve(KeyVault.FILE_NAME), InMemorySecretStore(), null))
            val http = V2Http(DesktopV2Signer(id), relay.baseUrl, ohttp = { null }, cover = { true })
            val r = http.postJson("/v2/messages", """{"x":1}""".toByteArray())
            assertEquals(-1, r.code)
            assertEquals(-1, http.getBytes("/v2/blobs/x/chunk/0").first)
            assertEquals("not one direct request", 0, http.directSends.get())
        }
    }

    // ------------------------------------------------------------------ the client

    @Test fun `a 1to1 message rides the cover end to end`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val bob = client("bob", proxy.baseUrl)
                val alice = client("alice", proxy.baseUrl)
                DesktopCover.setEnabled(true)
                proxy.plain.set(0)
                assertEquals(OshiClient.SendOutcome.SENT, alice.send(bob.address, "hello under cover"))
                assertEquals(1, relay.mailboxSize(bob.address))
                assertEquals("alice sent nothing undisguised", 0, proxy.plain.get())
                assertTrue(proxy.covered.get() > 0)
            }
        }
    }

    @Test fun `cover on and unreachable - the message WAITS, is never sent plain, and settles`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val bob = client("bob", proxy.baseUrl)
                val alice = client("alice", proxy.baseUrl)
                DesktopCover.setEnabled(true)
                proxy.refuseCover = true
                proxy.plain.set(0)

                val outcome = alice.send(bob.address, "wait for the disguise")
                assertEquals(OshiClient.SendOutcome.WAITING_FOR_COVER, outcome)
                val row = alice.messages.messages(bob.address).single()
                assertEquals(DeliveryStatus.PENDING, row.deliveryStatus)
                assertEquals(CoverOutbox.TRANSPORT_WAITING, row.transport)
                assertTrue(alice.coverOutbox.isWaiting(row.id))
                assertEquals(0, relay.mailboxSize(bob.address))
                assertEquals("nothing left undisguised", 0, proxy.plain.get())

                // The disguise comes back: the next due retry delivers it, disguised.
                proxy.refuseCover = false
                forceDue(alice)
                alice.retryCoverOutbox()
                assertEquals(1, relay.mailboxSize(bob.address))
                val after = alice.messages.messages(bob.address).single()
                assertEquals(DeliveryStatus.SENT, after.deliveryStatus)
                assertEquals("relay-v2", after.transport)
                assertEquals(0, proxy.plain.get())
            }
        }
    }

    @Test fun `switching the cover OFF while a message waits does not send it plain`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val bob = client("bob", proxy.baseUrl)
                val alice = client("alice", proxy.baseUrl)
                DesktopCover.setEnabled(true)
                proxy.refuseCover = true
                alice.send(bob.address, "only disguised, please")
                DesktopCover.setEnabled(false)
                proxy.plain.set(0)
                alice.retryCoverOutbox()
                val row = alice.messages.messages(bob.address).single()
                assertEquals(DeliveryStatus.FAILED, row.deliveryStatus)
                assertEquals(0, relay.mailboxSize(bob.address))
                assertEquals(0, proxy.plain.get())
            }
        }
    }

    // ------------------------------------------------------------------ outbox + jitter

    @Test fun `outbox follows Android's backoff and gives up after an hour`() {
        var now = 1_000_000L
        val f = tmp("o").resolve(CoverOutbox.FILE_NAME)
        val box = CoverOutbox(f) { now }
        box.park("peer", "m1", "text")
        assertTrue(box.due().isEmpty())
        now += 10_000
        val p = box.due().single()
        assertEquals(CoverOutbox.Verdict.STILL_WAITING, box.record(p, false))
        now += 29_999; assertTrue(box.due().isEmpty())
        now += 1; assertEquals(1, box.due().size)
        now = 1_000_000L + CoverOutbox.GIVE_UP_AFTER_MS
        assertEquals(CoverOutbox.Verdict.GAVE_UP, box.record(box.waiting().single(), false))
        assertFalse(box.isWaiting("m1"))
        // ids only on disk — never the message text
        box.park("peer", "m2", "secret words")
        assertFalse(f.readText().contains("secret"))
        assertEquals(listOf("peer" to "m2"), CoverOutbox(f).recoverAfterRestart())
    }

    // ------------------------------------------------------------------ bad networks (netem ports)

    @Test fun `a gzipped or relabelled cover reply from a middlebox still answers`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val id = IdentityStore.loadOrCreate(KeyVault.open(tmp("g").resolve(KeyVault.FILE_NAME), InMemorySecretStore(), null))
                val http = V2Http(DesktopV2Signer(id), proxy.baseUrl, ohttp = { null }, cover = { true })
                proxy.gzipReply = true
                assertEquals("gzip-injected reply", 200, http.get("/v2/config").code)
                proxy.gzipReply = false; proxy.relabel = true
                assertEquals("text/plain + chunked reply", 200, http.get("/v2/config").code)
                proxy.relabel = false; proxy.portal = true
                assertEquals("a portal page is a failed call, never a message", -1, http.get("/v2/config").code)
                assertEquals("nothing undisguised", 0, proxy.plain.get())
                assertEquals(0, http.directSends.get())
            }
        }
    }

    @Test fun `cover timeouts grow with the padded body`() {
        assertEquals(15_000L, DesktopCover.coverTimeoutMs(0))
        assertEquals(15_000L + 100_000L, DesktopCover.coverTimeoutMs(4096 * 100))
        assertEquals("capped at 10 min", 600_000L, DesktopCover.coverTimeoutMs(50_000_000))
    }

    /**
     * __COVER_NO_SILENT_RETRY_2026_11_18__ A connection that breaks before the reply must cost
     * ONE request: the JDK client must not resend a signed POST on its own (the relay would see
     * the same signature twice). The app-level retry signs again (fresh timestamp).
     */
    @Test fun `a broken connection is not silently resent by the transport`() {
        java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { ss ->
            val requests = AtomicInteger()
            val t = Thread {
                while (!ss.isClosed) {
                    val sock = runCatching { ss.accept() }.getOrNull() ?: break
                    runCatching {
                        val r = sock.getInputStream().bufferedReader()
                        if (r.readLine()?.startsWith("POST") == true) requests.incrementAndGet()
                        Thread.sleep(150)
                        sock.close()            // no reply at all
                    }
                }
            }.apply { isDaemon = true; start() }
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val r = runCatching {
                DesktopCover.exchange(client, "POST", URI("http://127.0.0.1:${ss.localPort}/v2/messages"),
                    mapOf("x-oshi-signature" to "s", "x-oshi-timestamp" to "1"), "{}".toByteArray(), java.time.Duration.ofSeconds(5))
            }
            assertTrue(r.isFailure)
            Thread.sleep(300)
            assertEquals("one request on the wire, no transport-level resend", 1, requests.get())
            t.interrupt()
        }
    }

    @Test fun `a resend after a block keeps its date and the receiver shows send order`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val bob = client("bob", proxy.baseUrl)
                val alice = client("alice", proxy.baseUrl)
                DesktopCover.setEnabled(true)
                proxy.refuseCover = true
                assertEquals(OshiClient.SendOutcome.WAITING_FOR_COVER, alice.send(bob.address, "one"))
                Thread.sleep(5)
                assertEquals(OshiClient.SendOutcome.WAITING_FOR_COVER, alice.send(bob.address, "two"))
                Thread.sleep(5)
                proxy.refuseCover = false
                // The path is back and a NEW message leaves at once, before the parked retries.
                assertEquals(OshiClient.SendOutcome.SENT, alice.send(bob.address, "three"))
                forceDue(alice)
                alice.retryCoverOutbox()
                assertEquals(0, alice.coverOutbox.waiting().size)
                // Sender: every row keeps the date it was sent at.
                val sent = alice.messages.messages(bob.address)
                assertEquals(listOf("one", "two", "three"), sent.map { it.content })
                // Receiver: arrival order was three, one, two — the thread is in SEND order.
                var got = 0
                repeat(5) { if (got < 3) got += bob.router.poll() }
                val thread = bob.messages.messages(alice.address).filter { !it.fromMe }
                assertEquals(listOf("one", "two", "three"), thread.map { it.content })
                for (m in thread) assertEquals("dated as sent: ${m.content}", sent.first { it.content == m.content }.sentAtMs, m.sentAtMs)
            }
        }
    }

    @Test fun `a successful pull kicks parked retries forward, never closer than 5 s`() {
        var now = 1_000_000L
        val box = CoverOutbox(null) { now }
        box.park("peer", "m1", "text")
        now += 2_000
        box.kick()
        assertTrue("not within 5 s of the park", box.due().isEmpty())
        now += 3_000
        box.kick()
        assertEquals(1, box.due().size)
        val p = box.due().single()
        assertEquals(CoverOutbox.Verdict.STILL_WAITING, box.record(p, false))  // next step: 30 s
        now += 1_000; box.kick(); assertTrue(box.due().isEmpty())
        now += 4_000; box.kick(); assertEquals("kicked at +5 s instead of +30 s", 1, box.due().size)
    }

    @Test fun `after a restart a recent plain text resumes, the rest is not delivered`() {
        assertTrue(CoverOutbox.resumeAfterRestart(coverOn = true, isText = true, ageMs = 5 * 60_000L))
        assertFalse(CoverOutbox.resumeAfterRestart(coverOn = false, isText = true, ageMs = 1_000L))
        assertFalse(CoverOutbox.resumeAfterRestart(coverOn = true, isText = false, ageMs = 1_000L))
        assertFalse(CoverOutbox.resumeAfterRestart(coverOn = true, isText = true, ageMs = CoverOutbox.GIVE_UP_AFTER_MS))
        var now = 10_000_000L
        val box = CoverOutbox(null) { now }
        box.resume("peer", "m1", "hi", sentAtMs = now - 60_000L)
        val p = box.due().single()
        assertEquals("keeps its date", now - 60_000L, p.sentAtMs)
        now += 59 * 60_000L
        assertEquals("its hour counts from when it was sent", CoverOutbox.Verdict.GAVE_UP, box.record(p, false))
    }

    // ------------------------------------------------------------------ RN-HINT v1

    /**
     * __RESTRICTED_NETWORK_HINT_2026_11_18__ cover on + "let contacts know" on: the receiver's row
     * is flagged and its text is clean (no marker anywhere it could be shown or copied); the
     * sender's own row is clean too. Switch off, or cover off: no flag. Replies carry it on the
     * `content` value.
     */
    @Test fun `restricted network hint rides the encrypted text and is stripped at ingestion`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val bob = client("bob", proxy.baseUrl)
                val alice = client("alice", proxy.baseUrl)
                DesktopCover.setEnabled(true)
                assertTrue("default ON", DesktopCover.isHintEnabled())
                assertEquals(OshiClient.SendOutcome.SENT, alice.send(bob.address, "hinted"))
                val quote = com.oshi.desktop.msg.ReplyEnvelope.Quote("m-1", bob.address, "Where are you?", 1_763_460_000_000L)
                assertEquals(OshiClient.SendOutcome.SENT, alice.send(bob.address, "On my way", replyTo = quote))
                DesktopCover.setHintEnabled(false)
                assertTrue("the cover switch is kept", DesktopCover.isEnabled())
                assertEquals(OshiClient.SendOutcome.SENT, alice.send(bob.address, "not hinted"))
                DesktopCover.setHintEnabled(true)
                DesktopCover.setEnabled(false)
                assertTrue("the hint switch is kept", DesktopCover.isHintEnabled())
                assertEquals(OshiClient.SendOutcome.SENT, alice.send(bob.address, "cover off"))
                var got = 0
                repeat(6) { if (got < 4) got += bob.router.poll() }
                val rows = bob.messages.messages(alice.address).filter { !it.fromMe }
                val marker = com.oshi.desktop.msg.RestrictedNetworkHint.MARKER
                assertTrue(rows.none { it.content.orEmpty().contains(marker) })
                fun byText(t: String) = rows.single { (com.oshi.desktop.msg.ReplyEnvelope.unwrap(it.content)?.content ?: it.content) == t }
                assertTrue(byText("hinted").restrictedNetwork)
                assertTrue(byText("On my way").restrictedNetwork)
                assertFalse(byText("not hinted").restrictedNetwork)
                assertFalse(byText("cover off").restrictedNetwork)
                assertTrue("sender rows are stored clean", alice.messages.messages(bob.address).none { it.content.orEmpty().contains(marker) })
                // The flag survives a store reload (local only, never on the wire).
                val json = byText("hinted").toJson()
                assertTrue(com.oshi.desktop.store.Message.fromJson(json).restrictedNetwork)
            }
        }
    }

    /**
     * __COVER_SHORT_CONNECTIONS_2026_11_19__ Russia-TSPU-style per-connection cap (netem
     * `tspu16k`): a connection that has carried ~16 KB server->client is cut. Before: every
     * exchange after the 2nd on a kept-alive connection failed. After one transport failure the
     * desktop opens one connection per exchange, so each stays under the cap.
     */
    @Test fun `after a transport failure each cover exchange gets its own connection`() {
        DesktopCover.ShortConnections.reset()
        java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { ss ->
            val connections = AtomicInteger()
            // CoverCodec spreads 1 frame in 4 into the next bucket (16 KiB): such a reply alone
            // exceeds the emulated cap and made this test fail ~25 % of runs. Pin the 4 KiB one.
            val reply = generateSequence {
                CoverCodec.encode(JSONObject().put("v", 1).put("status", 200)
                    .put("headers", JSONObject().put("content-type", "application/json"))
                    .put("body", Base64.getEncoder().encodeToString("{}".toByteArray())), "event")
            }.first { it.body.size < 8_000 }
            val t = Thread {
                while (!ss.isClosed) {
                    val sock = runCatching { ss.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    Thread {
                        runCatching {
                            sock.use {
                                val input = java.io.BufferedInputStream(it.getInputStream())
                                val out = it.getOutputStream()
                                var sent = 0
                                while (true) {
                                    val head = StringBuilder()
                                    while (!head.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) return@runCatching; head.append(c.toChar()) }
                                    val len = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                                    input.readNBytes(len)
                                    val bytes = ("HTTP/1.1 200 OK\r\nContent-Type: ${reply.type}\r\nContent-Length: ${reply.body.size}\r\n\r\n").toByteArray() + reply.body
                                    if (sent + bytes.size > 16_384) return@runCatching   // the cap: connection cut
                                    out.write(bytes); out.flush(); sent += bytes.size
                                }
                            }
                        }
                    }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
            val shared = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            fun once() = runCatching {
                DesktopCover.exchange(shared, "GET", URI("http://127.0.0.1:${ss.localPort}/v2/config"),
                    emptyMap(), null, java.time.Duration.ofSeconds(5))
            }.isSuccess
            val before = (1..3).map { once() }
            assertEquals("2 exchanges fit under the cap, the 3rd is cut", listOf(true, true, false), before)
            assertTrue(DesktopCover.ShortConnections.active())
            val c0 = connections.get()
            val after = (1..8).map { once() }
            assertTrue("every exchange after the switch succeeds: $after", after.all { it })
            assertEquals("one connection per exchange", 8, connections.get() - c0)
            assertEquals(8, DesktopCover.ShortConnections.freshExchanges.get())
            // An HTTP status the relay chose to send is not a stalled link.
            DesktopCover.ShortConnections.reset()
            DesktopCover.ShortConnections.onFailure(java.io.IOException("Obfuscated transport HTTP 503"))
            assertFalse(DesktopCover.ShortConnections.active())
            assertEquals("healthy network: pulls unchanged", "", DesktopCover.pullBudgetQuery())
            DesktopCover.ShortConnections.onFailure(java.net.http.HttpTimeoutException("request timed out"))
            assertTrue(DesktopCover.ShortConnections.active())
            assertEquals("capped network: 4 KiB-bucket pull pages", "&budget=2400", DesktopCover.pullBudgetQuery())
            DesktopCover.ShortConnections.reset()
            t.interrupt()
        }
    }

    @Test fun `poll timing is jittered only with the cover on`() {
        assertEquals(3_000L, DesktopCover.nextPollDelayMs(3_000L, coverOn = false))
        val draws = (1..400).map { DesktopCover.nextPollDelayMs(3_000L, coverOn = true) }
        assertTrue(draws.all { it in DesktopCover.PULL_MIN_MS..DesktopCover.PULL_MAX_MS })
        assertTrue("not a fixed cadence", draws.toSet().size > 100)
    }

    // ------------------------------------------------------------------ helpers

    // ------------------------------------------------------------------ media (photo) retry

    /**
     * __COVER_MEDIA_RETRY_2026_11_18__ The integration matrix's gap: in cover mode a photo whose
     * upload failed (captive portal, 10-min block, 30 % loss) was dropped — NO_V2_PATH, no row.
     * Now it WAITS like a text, is retried disguised only, keeps its id and date, and the
     * receiver ends with exactly one photo.
     */
    @Test fun `cover on - a photo whose upload fails WAITS, then leaves disguised with its date`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val bob = client("bob", proxy.baseUrl)
                val alice = client("alice", proxy.baseUrl)
                DesktopCover.setEnabled(true)
                assertEquals(OshiClient.SendOutcome.SENT, alice.send(bob.address, "session first"))
                repeat(3) { bob.router.poll() }
                proxy.portal = true          // captive portal: every disguised request answered with HTML
                proxy.plain.set(0)
                val photo = photoFile()
                assertEquals(OshiClient.SendOutcome.WAITING_FOR_COVER, alice.sendFile(bob.address, photo))
                val row = alice.messages.messages(bob.address).single { it.mediaRef != null }
                assertEquals(DeliveryStatus.PENDING, row.deliveryStatus)
                assertEquals(CoverOutbox.TRANSPORT_WAITING, row.transport)
                assertTrue(alice.coverOutbox.waiting().single().media)

                // Still blocked at the next retry: still waiting, nothing ever sent plain.
                forceDue(alice); alice.retryCoverOutbox()
                assertTrue(alice.coverOutbox.isWaiting(row.id))
                assertEquals(0, proxy.plain.get())

                proxy.portal = false
                forceDue(alice); alice.retryCoverOutbox()
                assertTrue(alice.coverOutbox.waiting().isEmpty())
                val after = alice.messages.message(bob.address, row.id)!!
                assertEquals(DeliveryStatus.SENT, after.deliveryStatus)
                assertEquals("relay-v2", after.transport)
                assertEquals("nothing left undisguised", 0, proxy.plain.get())

                repeat(5) { bob.router.poll() }
                val got = bob.messages.messages(alice.address).filter { !it.fromMe && it.mediaType != null }
                assertEquals(1, got.size)
                assertEquals("same id on the wire", row.id, got.single().id)
                assertEquals("dated as sent", row.sentAtMs, got.single().sentAtMs)
            }
        }
    }

    @Test fun `30 percent loss - photos wait, get through disguised, and arrive once each`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val bob = client("bob", proxy.baseUrl)
                val alice = client("alice", proxy.baseUrl)
                DesktopCover.setEnabled(true)
                assertEquals(OshiClient.SendOutcome.SENT, alice.send(bob.address, "hi"))
                repeat(3) { bob.router.poll() }
                proxy.lossP = 0.30
                proxy.plain.set(0)
                val outcomes = (1..6).map { alice.sendFile(bob.address, photoFile()) }
                assertTrue(outcomes.all { it == OshiClient.SendOutcome.SENT || it == OshiClient.SendOutcome.WAITING_FOR_COVER })
                var rounds = 0
                while (alice.coverOutbox.waiting().isNotEmpty() && rounds++ < 40) { forceDue(alice); alice.retryCoverOutbox() }
                assertTrue("all left within 40 retries", alice.coverOutbox.waiting().isEmpty())
                val ours = alice.messages.messages(bob.address).filter { it.mediaRef != null }
                assertEquals(6, ours.size)
                assertTrue(ours.all { it.deliveryStatus == DeliveryStatus.SENT })
                assertEquals("nothing left undisguised", 0, proxy.plain.get())
                proxy.lossP = 0.0
                repeat(8) { bob.router.poll() }
                val got = bob.messages.messages(alice.address).filter { !it.fromMe && it.mediaType != null }
                assertEquals("one photo per send, none twice", ours.map { it.id }.toSet(), got.map { it.id }.toSet())
                assertEquals(6, got.size)
                for (m in got) assertEquals(ours.first { it.id == m.id }.sentAtMs, m.sentAtMs)
            }
        }
    }

    @Test fun `a media retry that reaches the receiver twice still shows one photo`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val bob = client("bob", proxy.baseUrl)
                val alice = client("alice", proxy.baseUrl)
                DesktopCover.setEnabled(true)
                assertEquals(OshiClient.SendOutcome.SENT, alice.sendFile(bob.address, photoFile()))
                val row = alice.messages.messages(bob.address).single()
                // The reply to a successful attempt was lost: the same row is retried once more.
                alice.coverOutbox.resume(bob.address, row.id, "", row.sentAtMs, media = true)
                forceDue(alice); alice.retryCoverOutbox()
                repeat(6) { bob.router.poll() }
                assertEquals(1, bob.messages.messages(alice.address).count { !it.fromMe && it.id == row.id })
            }
        }
    }

    @Test fun `a parked photo gives up after an hour and is not delivered`() {
        var now = 50_000_000L
        val box = CoverOutbox(null) { now }
        box.park("peer", "p1", "", sentAtMs = now, media = true)
        now += CoverOutbox.GIVE_UP_AFTER_MS
        assertEquals(CoverOutbox.Verdict.GAVE_UP, box.record(box.due().single(), false))
        // Restart rule: an attachment resumes only when its file can still be read.
        assertTrue(CoverOutbox.resumeAfterRestart(coverOn = true, isText = false, ageMs = 60_000L, mediaReadable = true))
        assertFalse(CoverOutbox.resumeAfterRestart(coverOn = true, isText = false, ageMs = 60_000L, mediaReadable = false))
        assertFalse(CoverOutbox.resumeAfterRestart(coverOn = false, isText = false, ageMs = 60_000L, mediaReadable = true))
        assertFalse(CoverOutbox.resumeAfterRestart(coverOn = true, isText = false, ageMs = CoverOutbox.GIVE_UP_AFTER_MS, mediaReadable = true))
    }

    @Test fun `a photo still waiting at a restart resumes with its id and date`() {
        RelayServer().use { relay ->
            CoverRelay(relay.baseUrl).use { proxy ->
                val bob = client("bob", proxy.baseUrl)
                val home = tmp("alice-restart")
                val secrets = InMemorySecretStore()
                val alice1 = OshiClient(home = home, secretStore = secrets, serverUrl = proxy.baseUrl, displayName = "alice", log = {})
                alice1.router.refreshConfig(); alice1.router.publishBundleIfNeeded()
                DesktopCover.setEnabled(true)
                assertEquals(OshiClient.SendOutcome.SENT, alice1.send(bob.address, "hi"))
                repeat(3) { bob.router.poll() }
                proxy.refuseCover = true
                assertEquals(OshiClient.SendOutcome.WAITING_FOR_COVER, alice1.sendFile(bob.address, photoFile()))
                val row = alice1.messages.messages(bob.address).single { it.mediaRef != null }
                alice1.stop()

                proxy.refuseCover = false
                val alice2 = OshiClient(home = home, secretStore = secrets, serverUrl = proxy.baseUrl, displayName = "alice", log = {})
                clients += alice2
                alice2.router.refreshConfig()
                alice2.retryCoverOutbox()     // recovery: resumed, due at once
                forceDue(alice2); alice2.retryCoverOutbox()
                assertEquals(DeliveryStatus.SENT, alice2.messages.message(bob.address, row.id)!!.deliveryStatus)
                repeat(5) { bob.router.poll() }
                val got = bob.messages.messages(alice2.address).single { !it.fromMe && it.mediaType != null }
                assertEquals(row.id, got.id)
                assertEquals(row.sentAtMs, got.sentAtMs)
            }
        }
    }

    /** A small real PNG on disk (a user's own file passes the vault as is). */
    private fun photoFile(): File {
        val img = java.awt.image.BufferedImage(32, 24, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 24) for (x in 0 until 32) img.setRGB(x, y, (x * 8 shl 16) or (y * 10 shl 8) or 0x80)
        val f = File(tmp("photo"), "photo.png")
        javax.imageio.ImageIO.write(img, "png", f)
        return f
    }

    private fun forceDue(c: OshiClient) {
        // The outbox clock is the wall clock in the client; re-park with a zero-backoff copy.
        val f = CoverOutbox::class.java.getDeclaredField("parked").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = f.get(c.coverOutbox) as LinkedHashMap<String, CoverOutbox.Parked>
        for ((k, v) in map.entries.toList()) map[k] = v.copy(nextAttemptMs = 0)
    }

    private fun tmp(name: String): File = Files.createTempDirectory("oshi-cover-$name").toFile().also { dirs += it }

    private fun client(name: String, url: String): OshiClient {
        val c = OshiClient(home = tmp(name), secretStore = InMemorySecretStore(), serverUrl = url, displayName = name, log = {})
        clients += c
        c.router.refreshConfig()
        c.router.publishBundleIfNeeded()
        return c
    }

    /** The relay's cover endpoint, in miniature: strict decode → forward → wrap. */
    private class CoverRelay(private val upstream: String) : AutoCloseable {
        val plain = AtomicInteger()
        val covered = AtomicInteger()
        val acceptEncodings = CopyOnWriteArrayList<String>()
        val outerPaths = CopyOnWriteArrayList<String>()
        @Volatile var refuseCover = false
        /** Middlebox behaviours (tools/covert-regression/netem): gzip the reply, relabel it, captive portal. */
        @Volatile var gzipReply = false
        @Volatile var relabel = false
        @Volatile var portal = false
        /** __COVER_MEDIA_RETRY_2026_11_18__ 30 %-loss row: drop a disguised exchange, half the time AFTER the relay acted (reply lost). */
        @Volatile var lossP = 0.0
        private val rnd = java.util.Random(30)
        private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex -> runCatching { handle(ex) }.onFailure { ex.sendResponseHeaders(500, -1); ex.close() } }
            start()
        }
        val baseUrl get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)

        private fun handle(ex: HttpExchange) {
            val path = ex.requestURI.rawPath
            val body = ex.requestBody.readBytes()
            if (path in setOf("/v2/cover", "/v2/collect", "/v2/metrics")) {
                covered.incrementAndGet()
                outerPaths += path
                acceptEncodings += ex.requestHeaders.getFirst("Accept-Encoding").orEmpty()
                if (refuseCover) { ex.sendResponseHeaders(503, -1); ex.close(); return }
                val drop = lossP > 0 && synchronized(rnd) { rnd.nextDouble() < lossP }
                val dropAfter = drop && synchronized(rnd) { rnd.nextBoolean() }
                if (drop && !dropAfter) { ex.sendResponseHeaders(503, -1); ex.close(); return }
                if (portal) {
                    val html = "<html><body>Please log in to the Wi-Fi</body></html>".toByteArray()
                    ex.responseHeaders.add("Content-Type", "text/html")
                    ex.sendResponseHeaders(200, html.size.toLong()); ex.responseBody.use { it.write(html) }; return
                }
                val rec = CoverCodec.decode(body, ex.requestHeaders.getFirst("Content-Type").orEmpty())
                val inner = HttpRequest.newBuilder(URI.create(upstream + rec.getString("path")))
                val h = rec.getJSONObject("headers")
                for (k in h.keys()) if (k != "content-length") inner.header(k, h.getString(k))
                val innerBody = Base64.getDecoder().decode(rec.getString("body"))
                inner.method(rec.getString("method"),
                    if (innerBody.isEmpty() && rec.getString("method") in setOf("GET", "DELETE")) HttpRequest.BodyPublishers.noBody()
                    else HttpRequest.BodyPublishers.ofByteArray(innerBody))
                val resp = http.send(inner.build(), HttpResponse.BodyHandlers.ofByteArray())
                if (dropAfter) { ex.sendResponseHeaders(502, -1); ex.close(); return }
                val reply = JSONObject().put("v", 1).put("status", resp.statusCode())
                    .put("headers", JSONObject().put("content-type", resp.headers().firstValue("Content-Type").orElse("application/json")))
                    .put("body", Base64.getEncoder().encodeToString(resp.body()))
                val out = CoverCodec.encode(reply, "event")
                when {
                    gzipReply -> {
                        val z = java.io.ByteArrayOutputStream().also { b -> java.util.zip.GZIPOutputStream(b).use { it.write(out.body) } }.toByteArray()
                        ex.responseHeaders.add("Content-Type", out.type)
                        ex.responseHeaders.add("Content-Encoding", "gzip")
                        ex.sendResponseHeaders(200, z.size.toLong()); ex.responseBody.use { it.write(z) }
                    }
                    relabel -> {
                        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                        ex.sendResponseHeaders(200, 0)   // chunked
                        ex.responseBody.use { it.write(out.body) }
                    }
                    else -> {
                        ex.responseHeaders.add("Content-Type", out.type)
                        ex.sendResponseHeaders(200, out.body.size.toLong())
                        ex.responseBody.use { it.write(out.body) }
                    }
                }
                return
            }
            // Only V2 traffic is the cover's to carry; push wake-ups and calls are documented
            // as NOT disguised (docs/stego/PRIVACY_DISGUISE_SCOPE.md) and are not counted.
            if (path.startsWith("/v2/")) plain.incrementAndGet()
            val fwd = HttpRequest.newBuilder(URI.create(upstream + ex.requestURI.toString()))
            ex.requestHeaders.forEach { (k, vs) ->
                if (k.lowercase() !in setOf("host", "content-length", "connection", "upgrade", "expect")) vs.forEach { fwd.header(k, it) }
            }
            fwd.method(ex.requestMethod, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            val resp = http.send(fwd.build(), HttpResponse.BodyHandlers.ofByteArray())
            ex.responseHeaders.add("Content-Type", resp.headers().firstValue("Content-Type").orElse("application/json"))
            ex.sendResponseHeaders(resp.statusCode(), if (resp.body().isEmpty()) -1 else resp.body().size.toLong())
            if (resp.body().isNotEmpty()) ex.responseBody.use { it.write(resp.body()) } else ex.close()
        }
    }
}
