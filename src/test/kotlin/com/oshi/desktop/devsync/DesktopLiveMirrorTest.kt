package com.oshi.desktop.devsync

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import com.oshi.messenger.network.v2.devsync.DevSyncCrypto
import com.oshi.messenger.network.v2.devsync.DevSyncSession
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * __DEVSYNC_LIVE_UPGRADE_2026_09_23__ Two REAL desktop clients (not started: NO poll loop runs, so
 * the 3 s `tick` fingerprint cannot be what carries the change) over loopback LAN: a message written
 * to [com.oshi.desktop.store.MessageStore] reaches the other machine in well under a second through
 * the store's change hook, and so does a status change on an older message.
 */
class DesktopLiveMirrorTest {

    private val dirs = ArrayList<File>()
    private val clients = ArrayList<OshiClient>()
    private val syncs = ArrayList<DesktopDevSync>()

    @After
    fun tearDown() {
        syncs.forEach { runCatching { it.stop() } }
        clients.forEach { runCatching { it.close() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dir(p: String) = Files.createTempDirectory(p).toFile().also { dirs += it }

    private fun client(name: String, recoveryKey: String): OshiClient {
        val home = dir("oshi-live-$name")
        val secrets = InMemorySecretStore()
        val vault = KeyVault.open(File(home, KeyVault.FILE_NAME), secrets, null)
        IdentityStore.importRecoveryKey(vault, recoveryKey)
        return OshiClient(home = home, secretStore = secrets, serverUrl = "http://127.0.0.1:9", displayName = name).also { clients += it }
    }

    private fun devSync(c: OshiClient): DesktopDevSync =
        DesktopDevSync(c, File(c.mediaDir.parentFile.absolutePath), {}, lanDiscoveryFactory = { null })
            .also { syncs += it; it.setOverride(true) }

    private fun waitUntil(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out: $what")
    }

    private val bob = Base64.getEncoder().encodeToString(DevSyncCrypto.sha256("bob".toByteArray()))
    private val base = 1_758_000_000_000L

    private fun msg(c: OshiClient, id: String, fromMe: Boolean, i: Int, status: DeliveryStatus = DeliveryStatus.DELIVERED) =
        Message(
            id = id, conversationId = bob,
            senderAddress = if (fromMe) c.address else bob, recipientAddress = if (fromMe) bob else c.address,
            fromMe = fromMe, content = "m$i", mediaType = null, sentAtMs = base + i * 60_000L,
            sentAtSource = TimestampSource.ISO8601, deliveryStatus = status, transport = "relay",
        )

    @Test
    fun aWriteToTheMessageStore_isMirroredInWellUnderASecond_withoutThePollLoop() {
        val recovery = IdentityStore.exportRecoveryKey(DesktopIdentity.generate())
        val a = client("mac", recovery)
        val b = client("linux", recovery)
        for (i in 0 until 10) a.messages.append(msg(a, "A-$i", i % 2 == 0, i, DeliveryStatus.SENT))
        val sa = devSync(a)
        val sb = devSync(b)
        waitUntil("engines up") { (sb.engineOrNull?.lan?.port ?: -1) > 0 && (sa.engineOrNull?.lan?.port ?: -1) > 0 }
        sa.dial("127.0.0.1", sb.engineOrNull!!.lan!!.port)
        waitUntil("pairing prompt") { sa.snapshot().pending.isNotEmpty() }
        sa.approve(sa.snapshot().pending.first().peerId, true)
        waitUntil("initial sync") {
            b.messages.messages(bob).size == 10 &&
                sa.snapshot().sessions.any { it.transport == "lan" && it.state == DevSyncSession.State.SYNC && it.pullComplete }
        }
        Thread.sleep(300)

        val t0 = System.nanoTime()
        a.messages.append(msg(a, "LIVE-1", true, 20, DeliveryStatus.SENT))
        waitUntil("new message on the other machine", 5_000) { b.messages.message(bob, "LIVE-1") != null }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("desktop live mirror: $ms ms")
        assertTrue("mirrored in $ms ms (the poll loop is not running at all here)", ms < 1_000)

        val t1 = System.nanoTime()
        a.messages.advanceDeliveryStatus(bob, "A-0", DeliveryStatus.READ)
        waitUntil("status change on the other machine", 5_000) { b.messages.message(bob, "A-0")?.deliveryStatus == DeliveryStatus.READ }
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 1_000)

        // And the other way round.
        b.messages.append(msg(b, "FROM-B", false, 30))
        waitUntil("b -> a", 5_000) { a.messages.message(bob, "FROM-B") != null }
    }
}
