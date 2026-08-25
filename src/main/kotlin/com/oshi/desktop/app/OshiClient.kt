package com.oshi.desktop.app

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.block.BlockPolicy
import com.oshi.desktop.mesh.MeshNode
import com.oshi.desktop.net.RouterState
import com.oshi.desktop.net.V2AccountClient
import com.oshi.desktop.net.V2BlobClient
import com.oshi.desktop.net.V2ConfigGate
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.net.V2Inbound
import com.oshi.desktop.net.V2KeysClient
import com.oshi.desktop.net.V2MessagesClient
import com.oshi.desktop.net.V2Router
import com.oshi.desktop.store.ContactStore
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.MessageStore
import com.oshi.desktop.store.PrekeyStore
import com.oshi.desktop.store.SecretStore
import com.oshi.desktop.store.SessionStore
import com.oshi.desktop.store.TimestampSource
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One OSHI account, assembled — the object an interface (a CLI today, a window later)
 * talks to.
 *
 * Everything under it has been built and tested on its own: an identity that survives a
 * restart, the V2 transport, the ratchet router, the mesh node, a message log and a
 * contact list. This is where they become a client: one address, one poll loop, one place
 * where an inbound message becomes a stored row and a contact.
 *
 * WHAT IT CAN AND CANNOT DO, stated here because it is the first thing that looks like a
 * messenger and is therefore the first thing that can mislead:
 *
 *  - **Text over the relay works end to end**, encrypted with the same X3DH + Double
 *    Ratchet the phones use.
 *  - **The mesh is transport and discovery only.** Its payloads are NOT readable by this
 *    client and this client's are not readable by a phone: the shipped mesh carries the
 *    LEGACY ratchet, not V2 (PARITY.md row 0.16). Mesh traffic is surfaced as a
 *    diagnostic, never stored as a conversation, because storing an unreadable blob as a
 *    message row is how a chat log fills with bubbles nobody can open.
 *  - **Media is not wired in yet.** [blobs] exists and is tested; nothing here sends one.
 *
 * THREADING. The poll loop runs on one scheduled thread; the mesh has its own. Inbound
 * delivery — store, contact upsert, callback — happens on the thread that received it,
 * BEFORE the relay is acked, so a message is durable before the server is told it may
 * drop it.
 */
class OshiClient(
    home: File = DesktopPaths.dataDir,
    secretStore: SecretStore? = SecretStore.detect(),
    passphrase: CharArray? = null,
    serverUrl: String = V2Http.defaultBaseUrl(),
    val displayName: String = defaultDisplayName(),
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    val vault: KeyVault = KeyVault.open(File(home, KeyVault.FILE_NAME), secretStore, passphrase)
    val identity: DesktopIdentity = IdentityStore.loadOrCreate(vault)

    /** This account's address: the standard padded base64 of its X25519 public key. */
    val address: String get() = identity.userKey

    val contacts = ContactStore(File(home, "contacts.json"))
    val messages = MessageStore(File(home, "messages"))

    private val prekeys = PrekeyStore(vault)
    private val sessions = SessionStore(vault)
    private val http = V2Http(DesktopV2Signer(identity), serverUrl)
    val config = V2ConfigGate(baseUrl = serverUrl, log = log)
    private val keys = V2KeysClient(http, identity, prekeys)
    private val relay = V2MessagesClient(http)
    val blobs = V2BlobClient(http, identity)
    val account = V2AccountClient(http)
    private val routerState = RouterState(File(home, RouterState.FILE_NAME))

    val router = V2Router(identity, config, keys, relay, sessions, prekeys, routerState, log)
    val mesh = MeshNode(address, displayName, log)

    /** Called after an inbound message has been stored. */
    var onMessage: (Message) -> Unit = {}

    /** Called for mesh traffic, which is NOT a conversation — see the class note. */
    var onMeshTraffic: (String) -> Unit = {}

    private val running = AtomicBoolean(false)
    private var timers: ScheduledExecutorService? = null

    init {
        router.onMessage = { inbound -> receive(inbound) }
        mesh.onMessage = { m ->
            // PARITY.md 0.21. On the mesh the sender key rides the envelope in the clear,
            // so the block is applied BEFORE the payload is looked at — the position
            // Android uses at `MessageRepository.kt:2665` ("drop before decryption,
            // storage or notification"). Unlike the V2 path there is no ratchet of ours
            // to keep in step here, so there is no reason to look at it at all.
            if (BlockPolicy.isBlocked(contacts, m.senderPublicKey)) {
                log("client: dropped mesh ${m.type} from a blocked contact ${m.senderPublicKey.take(12)}…")
            } else {
                onMeshTraffic("${m.type} from ${m.senderName} (${m.platform}): ${m.payload.take(200)}")
            }
        }
        mesh.onPeersChanged = { peers ->
            // A mesh peer's public key IS an OSHI address, so seeing one is a genuine
            // "this contact exists nearby" signal — worth recording. It is NOT a message.
            // A blocked peer is still RECORDED — blocking hides someone, it does not make
            // the client forget they exist, and `ContactStore.seen` cannot resurrect a
            // contact's visibility (it never touches the flag).
            val now = System.currentTimeMillis()
            peers.forEach { contacts.seen(it.publicKey, now, it.displayName) }
        }
    }

    /**
     * Bring the client up.
     *
     * @param pollIntervalMs how often to ask the relay for new messages. There is no push
     *        on desktop (PARITY.md 2.3), so this interval IS the delivery latency.
     */
    fun start(pollIntervalMs: Long = 3_000, withMesh: Boolean = true) {
        if (!running.compareAndSet(false, true)) return

        val timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "oshi-poll").apply { isDaemon = true }
        }
        timers = timer

        // Publishing a prekey bundle is what makes iOS and Android route V2 traffic to
        // this address, so it happens on the poll thread AFTER the gate has been read —
        // never eagerly at construction, and never while the gate is closed.
        timer.scheduleWithFixedDelay({
            try {
                router.refreshConfig()
                if (config.isEnabledCached() && !published) {
                    published = router.publishBundleIfNeeded()
                    if (published) log("client: prekey bundle published — peers can now reach us over V2")
                }
                router.poll()
            } catch (e: Exception) {
                log("client: poll failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, 0, pollIntervalMs, TimeUnit.MILLISECONDS)

        if (withMesh) mesh.start()
    }

    @Volatile private var published = false

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        timers?.shutdownNow(); timers = null
        mesh.stop()
        routerState.flush()
    }

    override fun close() = stop()

    // ------------------------------------------------------------------ send

    enum class SendOutcome { SENT, BLOCKED, NO_V2_PATH, GATE_CLOSED }

    /**
     * Send text to a peer and record it locally.
     *
     * The local row is written whatever happens, with the outcome in its delivery status:
     * a message the user typed does not disappear because the network refused it. A caller
     * that wants "did it leave" reads the return value, never the presence of the row.
     */
    fun send(peerAddress: String, text: String): SendOutcome {
        // Through BlockPolicy, never through `contacts.isBlocked` directly: the store's
        // lookup is exact, and a block set under one base64 spelling has to hold against
        // every other spelling of the same key. See BlockPolicy's class note.
        if (BlockPolicy.outgoingText(contacts, peerAddress) == BlockPolicy.Outbound.REFUSE_BLOCKED) {
            return SendOutcome.BLOCKED
        }

        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val outcome = when {
            !config.isEnabledCached() -> SendOutcome.GATE_CLOSED
            router.sendText(peerAddress, text.toByteArray(Charsets.UTF_8), msgId) -> SendOutcome.SENT
            else -> SendOutcome.NO_V2_PATH
        }
        messages.append(
            Message(
                id = msgId,
                conversationId = peerAddress,
                senderAddress = address,
                recipientAddress = peerAddress,
                fromMe = true,
                content = text,
                sentAtMs = now,
                // Our own clock, not something read off a wire — recorded as such so a
                // later reader never has to guess which of the four epochs this was.
                sentAtSource = TimestampSource.LOCAL_CLOCK,
                deliveryStatus = if (outcome == SendOutcome.SENT) DeliveryStatus.SENT else DeliveryStatus.FAILED,
                transport = if (outcome == SendOutcome.SENT) "relay-v2" else "none",
            )
        )
        contacts.seen(peerAddress, now)
        return outcome
    }

    /** True when this peer can be reached over V2 right now (session or fetchable bundle). */
    fun canReach(peerAddress: String): Boolean = router.ensureCapable(peerAddress)

    // ------------------------------------------------------------------ receive

    private fun receive(inbound: V2Inbound) {
        // A blocked sender's message is dropped HERE, after decryption and before storage.
        // Two reasons, and the second is the one that is easy to miss: the relay envelope
        // is acked by the poll loop regardless, so refusing earlier would only strand it
        // on the server — AND the ratchet has to advance for a blocked sender too, or the
        // receiving chain falls behind the peer's sending chain and every message after an
        // UNBLOCK is undecryptable. Android's V2 gate sits in exactly this position
        // (`MessageRepository.kt:3163`) for the same reason. Nothing is stored, so nothing
        // surfaces.
        if (BlockPolicy.inbound(contacts, inbound.from) == BlockPolicy.Inbound.DROP_BLOCKED) {
            log("client: dropped a message from a blocked contact ${inbound.from.take(12)}…")
            return
        }
        val stored = Message(
            id = inbound.msgId,
            // One conversation per peer. Group traffic (PARITY.md 0.17) is not handled
            // yet and would key on `groupId` instead — hence the explicit check rather
            // than silently filing a group message into a 1:1 thread.
            conversationId = inbound.groupId ?: inbound.from,
            senderAddress = inbound.from,
            recipientAddress = address,
            fromMe = false,
            content = inbound.text,
            sentAtMs = inbound.ts,
            // The v2 relay envelope is one of the few places this protocol is unambiguous
            // about time: Unix epoch MILLISECONDS.
            sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            deliveryStatus = DeliveryStatus.DELIVERED,
            transport = "relay-v2",
        )
        val outcome = messages.append(stored)
        contacts.seen(inbound.from, inbound.ts)
        if (outcome != MessageStore.AppendOutcome.DUPLICATE) onMessage(stored)
    }

    // ------------------------------------------------------------------ reading

    /**
     * The conversation list, with blocked peers withheld — PARITY.md 0.21.
     *
     * iOS does the same at `MessageManager.swift:8352`, and again at `:8405` for the
     * pending (QR-scanned, no messages yet) entries, which is what stops re-scanning
     * someone's code from resurrecting a thread you blocked. Nothing is deleted: the
     * history is still in [MessageStore] and comes straight back on unblock.
     */
    fun conversations(): List<MessageStore.ConversationSummary> =
        BlockPolicy.filterConversations(messages.conversations(), contacts) { it.conversationId }

    /** Every conversation including blocked peers — what a "blocked" settings view reads. */
    fun conversationsIncludingBlocked(): List<MessageStore.ConversationSummary> = messages.conversations()

    fun history(peerAddress: String): List<Message> = messages.messages(peerAddress)

    /** Local wipe plus the server-side erase. Returns the server's receipt if it answered. */
    fun deleteAccount(): Result<Unit> {
        val receipt = account.deleteAccount()
        sessions.clear()
        prekeys.clear()
        IdentityStore.erase(vault)
        routerState.clear()
        return receipt.map { }
    }

    companion object {
        fun defaultDisplayName(): String =
            System.getenv("OSHI_NAME")?.takeIf { it.isNotBlank() }
                ?: "${System.getProperty("user.name") ?: "OSHI"} (${System.getProperty("os.name")})"
    }
}
