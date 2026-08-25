package com.oshi.desktop.app

import com.oshi.desktop.net.RelayServer
import com.oshi.desktop.net.V2Inbound
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import java.io.File
import java.nio.file.Files

/**
 * The fixture the wiring tests share.
 *
 * Every client here is a REAL [OshiClient] over a REAL [RelayServer] in this process. None
 * of these tests mocks the client, because the thing under test is precisely the wiring —
 * a mock would assert that the method we wrote was called by the code we wrote, which is
 * the failure mode this whole pass exists to fix.
 *
 * `start()` is deliberately never called: it would take the mesh up and hand the poll loop
 * a thread, and every test here wants to drive the tick itself so the clock is an argument
 * rather than a race.
 */
class WiringFixture : AutoCloseable {

    val relay = RelayServer()
    private val dirs = ArrayList<File>()
    private val clients = ArrayList<OshiClient>()
    val logs = ArrayList<String>()

    /**
     * One account with the rollout gate already read and — when asked — a prekey bundle on
     * the server, which is what makes a peer able to reach it.
     */
    fun client(name: String, publish: Boolean = true): OshiClient {
        val dir = Files.createTempDirectory("oshi-wiring-$name").toFile().also { dirs += it }
        val c = OshiClient(
            home = dir,
            secretStore = InMemorySecretStore(),
            serverUrl = relay.baseUrl,
            displayName = name,
            log = { synchronized(logs) { logs += it } },
        )
        clients += c
        c.router.refreshConfig()
        if (publish) c.router.publishBundleIfNeeded()
        return c
    }

    /** Deliver one plaintext to [to] as if the ratchet had just produced it. */
    fun inbound(
        to: OshiClient,
        from: String,
        text: String,
        msgId: String = "m-" + java.util.UUID.randomUUID(),
        ts: Long = System.currentTimeMillis(),
        groupId: String? = null,
    ) {
        // Through `router.onMessage`, which is the callback `OshiClient` installs on itself
        // in its own `init`. Calling `dispatch` directly would skip the block gate, and the
        // block gate is one of the things being tested.
        to.router.onMessage(V2Inbound(from = from, msgId = msgId, ts = ts, groupId = groupId, text = text))
    }

    /** Seed a message we sent, so a receipt / reaction / edit has something to name. */
    fun seedOutgoing(client: OshiClient, peer: String, id: String, text: String): Message {
        val m = Message(
            id = id,
            conversationId = peer,
            senderAddress = client.address,
            recipientAddress = peer,
            fromMe = true,
            content = text,
            sentAtMs = System.currentTimeMillis(),
            sentAtSource = TimestampSource.LOCAL_CLOCK,
            deliveryStatus = DeliveryStatus.SENT,
            transport = "relay-v2",
        )
        client.messages.append(m)
        return m
    }

    /** Seed a message a peer sent us. */
    fun seedIncoming(client: OshiClient, peer: String, id: String, text: String): Message {
        val m = Message(
            id = id,
            conversationId = peer,
            senderAddress = peer,
            recipientAddress = client.address,
            fromMe = false,
            content = text,
            sentAtMs = System.currentTimeMillis(),
            sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            deliveryStatus = DeliveryStatus.DELIVERED,
            transport = "relay-v2",
        )
        client.messages.append(m)
        return m
    }

    /** Everything a REPL command printed. */
    fun repl(client: OshiClient, line: String): List<String> {
        val out = ArrayList<String>()
        ClientCommands.execute(client, line) { out += it }
        return out
    }

    override fun close() {
        clients.forEach { runCatching { it.stop() } }
        relay.close()
        dirs.forEach { it.deleteRecursively() }
    }
}
