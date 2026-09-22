package com.oshi.desktop.app

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.block.BlockPolicy
import com.oshi.desktop.call.CallLane
import com.oshi.desktop.call.CallSignalClient
import com.oshi.desktop.call.CallStateMachine
import com.oshi.desktop.crypto.SafetyNumber
import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupFanout
import com.oshi.desktop.group.GroupIdentity
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupMessageWire
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.group.GroupUpdateWire
import com.oshi.desktop.group.MinimalGroupUpdate
import com.oshi.desktop.mesh.MeshNode
import com.oshi.desktop.msg.ControlEvent
import com.oshi.desktop.msg.ControlPayloadRouter
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.DeliveryReceipt
import com.oshi.desktop.msg.MessageActionPayload
import com.oshi.desktop.msg.ReactionPayload
import com.oshi.desktop.msg.ReadReceipt
import com.oshi.desktop.msg.TypingPayload
import com.oshi.desktop.net.AccountDeletionReceipt
import com.oshi.desktop.net.RouterState
import com.oshi.desktop.net.V2AccountClient
import com.oshi.desktop.net.V2BlobClient
import com.oshi.desktop.net.V2ConfigGate
import com.oshi.desktop.bot.BotApi
import com.oshi.desktop.bot.BotEnvelope
import com.oshi.desktop.bot.BotQueueClient
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.net.V2Inbound
import com.oshi.desktop.net.V2KeysClient
import com.oshi.desktop.net.V2MessagesClient
import com.oshi.desktop.net.V2Router
import com.oshi.desktop.pairing.ContactQr
import com.oshi.desktop.place.PlaceEvent
import com.oshi.desktop.place.PlaceRouter
import com.oshi.desktop.scheduled.ScheduledMessage
import com.oshi.desktop.scheduled.ScheduledMessageRunner
import com.oshi.desktop.scheduled.ScheduledMessageStore
import com.oshi.desktop.store.ContactStore
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.MessageStore
import com.oshi.desktop.store.PrekeyStore
import com.oshi.desktop.store.SecretStore
import com.oshi.desktop.store.SessionStore
import com.oshi.desktop.store.TimestampSource
import com.oshi.desktop.sync.ArchiveKeys
import com.oshi.desktop.sync.ContactSyncRecord
import com.oshi.desktop.sync.LegacySyncClient
import com.oshi.desktop.sync.SyncCursor
import com.oshi.desktop.sync.SyncEngine
import com.oshi.desktop.sync.SyncProtocol
import com.oshi.desktop.sync.V2SyncClient
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.V2FileKeyMessage
import java.io.File
import java.security.SecureRandom
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
 * ============================================================ THE DISPATCH
 *
 * [receive] is the ONE place an inbound plaintext is decided about, and the order is the
 * whole design. A plaintext is, in order:
 *
 *  1. dropped if the sender is blocked — after decryption, before anything is read
 *     (PARITY.md 0.21; see the note in [receive] for why not earlier);
 *  2. a `📢GROUP_UPDATE📢` → [GroupIngest], which is where row 0.17's authorizer lives;
 *  3. a group MESSAGE, if the envelope carried a `groupId` → [GroupMessageWire];
 *  4. a `{"kind":"v2file"}` key message → the blob is fetched and decrypted (row 0.15);
 *  5. a row-0.18 control payload — receipt, typing, reaction, edit/delete →
 *     [ControlPayloadRouter], which APPLIES it to the store and never appends a row;
 *  6. a row-0.19 sentinel — location, check-in → [PlaceRouter], rendered to one line;
 *  7. a foreign sentinel some other row owns — logged, never shown as a wall of JSON;
 *  8. and only then, prose.
 *
 * A control payload that reached step 8 would be a JSON bubble in a chat log; a prose
 * message that reached step 5 would be silently swallowed. Both are one misordered `if`
 * away, which is why the order is stated here and asserted in
 * `src/test/kotlin/com/oshi/desktop/app/`.
 *
 * WHAT IT CAN AND CANNOT DO, stated here because it is the first thing that looks like a
 * messenger and is therefore the first thing that can mislead:
 *
 *  - **Text, media, groups, receipts, reactions, edits and deletes work end to end over
 *    the relay**, encrypted with the same X3DH + Double Ratchet the phones use.
 *  - **The mesh is transport and discovery only.** Its payloads are NOT readable by this
 *    client and this client's are not readable by a phone: the shipped mesh carries the
 *    LEGACY ratchet, not V2 (PARITY.md row 0.16). Mesh traffic is surfaced as a
 *    diagnostic, never stored as a conversation, because storing an unreadable blob as a
 *    message row is how a chat log fills with bubbles nobody can open.
 *  - **Location and check-ins are RECEIVE-ONLY.** The JDK has no GPS and none is faked
 *    (PARITY.md 0.19), so there is no `/location` command and there must not be one.
 *  - **A scheduled message cannot be sent while this process is stopped** and does not
 *    pretend otherwise — see [ScheduledMessageRunner]'s note and [onScheduledRun].
 *
 * THREADING. The poll loop runs on one scheduled thread; the mesh has its own. Inbound
 * delivery — store, contact upsert, callback — happens on the thread that received it,
 * BEFORE the relay is acked, so a message is durable before the server is told it may
 * drop it. Every network call this class makes on its own initiative (the scheduled-message
 * sweep, the sync drain when armed) happens on that same poll thread.
 */
class OshiClient(
    private val home: File = DesktopPaths.dataDir,
    secretStore: SecretStore? = SecretStore.detect(),
    passphrase: CharArray? = null,
    /**
     * Public because a surface has to be able to SAY which server it is talking to.
     * PARITY.md row 1.1's window prints it in its account pane, and "which relay am I on"
     * is not a question a user should have to answer by reading a launch flag they may not
     * have typed — the default comes from [V2Http.defaultBaseUrl].
     */
    val serverUrl: String = V2Http.defaultBaseUrl(),
    val displayName: String = defaultDisplayName(),
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    val vault: KeyVault = KeyVault.open(File(home, KeyVault.FILE_NAME), secretStore, passphrase)
    val identity: DesktopIdentity = IdentityStore.loadOrCreate(vault)

    /** This account's address: the standard padded base64 of its X25519 public key. */
    val address: String get() = identity.userKey

    val contacts = ContactStore(File(home, "contacts.json"))
    private val messageHistoryKey: ByteArray = vault.getOrCreate(MessageStore.HISTORY_KEY_ACCOUNT) {
        ByteArray(32).also(SecureRandom()::nextBytes)
    }
    val messages = MessageStore(File(home, "messages"), messageHistoryKey)

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

    // ------------------------------------------------------------- the wired rows

    /** Row 0.18: receipts, typing, reactions, edit/delete — applied to [messages]. */
    private val control = ControlPayloadRouter(messages)

    /** Row 0.19: location shares and check-ins. Receive-only, and holds the live-share state. */
    val places = PlaceRouter()

    /** Row 0.17: this account's groups, as the wire JSON an iPhone accepts. */
    val groups = GroupStore(File(home, "groups.json"))

    private val groupIngest = GroupIngest(groups) { address }

    /** Row 0.25: the queue. Driven by the poll loop — see [start]. */
    val scheduled = ScheduledMessageStore(File(home, "scheduled-messages.json"))

    val scheduler = ScheduledMessageRunner(scheduled, ::deliverScheduled)

    /** Where decrypted inbound media is written. Created on first use, not at construction. */
    val mediaDir: File get() = File(home, "media")

    private val syncCursor = SyncCursor(File(home, "sync-cursor.json"))

    /**
     * Row 0.24's V2 archive. LAZY, and that is not an optimisation: building it derives the
     * archive key from the vault and mints a device id on first use, neither of which
     * belongs in a constructor that has not been asked to sync anything yet.
     */
    val sync: SyncEngine by lazy {
        SyncEngine(
            client = V2SyncClient(http, address),
            archiveKey = ArchiveKeys.v2(vault),
            cursor = syncCursor,
            deviceId = syncCursor.deviceId(),
        )
    }

    /**
     * Row 0.24's LEGACY archive — a different protocol on a different route, with a
     * different key pair and a different path encoding. See [ArchiveKeys]'s class note for
     * what happens when the two are crossed.
     */
    val legacySync: LegacySyncClient by lazy {
        LegacySyncClient(
            ownerPublicKey = address,
            deviceId = syncCursor.deviceId(),
            archiveKey = ArchiveKeys.legacy(vault),
            baseUrl = serverUrl,
        )
    }

    // ------------------------------------------------------------------ bots (row 0.26)

    /**
     * The bot lane — **and it is not encrypted**.
     *
     * Every other transport this client speaks puts ciphertext on the wire. This one does
     * not, and the difference is not a detail a caller may discover later:
     *
     *  - a bot message is `bot:<messageId>:<base64 JSON>` in the hash slot of the LEGACY
     *    pending queue. Base64 is an encoding, not a cipher; the relay holds and forwards
     *    the plaintext, and the server's own source says so in as many words.
     *  - registering a bot group uploads the group NAME and EVERY MEMBER PUBLIC KEY in
     *    cleartext, which hands the relay the membership graph the V2 group design spends
     *    N pairwise ciphertexts withholding (`V2GroupSession`: the relay has zero knowledge
     *    of membership).
     *
     * It is wired because the product asked for it. What that obliges of every caller is
     * the one thing this class can enforce from here: [BOT_CHANNEL_IS_PLAINTEXT] exists so
     * no UI can present a bot composer without having been handed the fact, and
     * [sendBotMessage] returns it in [BotSendOutcome] rather than leaving it to a comment
     * nobody reads at the call site.
     */
    val botQueue: BotQueueClient by lazy {
        BotQueueClient(ownerPublicKey = address, deviceId = syncCursor.deviceId(), baseUrl = serverUrl)
    }

    val botApi: BotApi by lazy { BotApi(baseUrl = serverUrl) }

    // ------------------------------------------------------------------ calls (row 2.1)

    /**
     * The call SIGNALLING lane — **and there is no media under it.**
     *
     * This client can ring a peer, be rung, answer, decline and hang up. It cannot carry one
     * sample of audio in either direction: there is no capture, no playback, no media socket
     * and no ICE. [CallLane.NO_AUDIO_WILL_FLOW] is the single owner of that sentence and
     * every surface that starts a call prints it — the same discipline row 0.26's
     * [BOT_CHANNEL_IS_PLAINTEXT] enforces on the plaintext bot lane, for the same reason: a
     * caller must be handed the fact rather than left to find it in a doc comment.
     *
     * It rides a SEPARATE server from every other row here — the call server on port 8083,
     * reached through nginx at `/api/call/`, never `/v2/…` (see [CallSignalClient]) — and
     * that server has no authentication at all. The body is sealed; the metadata is not.
     *
     * LAZY for the same reason [sync] is: constructing it mints a device id and derives
     * nothing a client that never places a call should pay for.
     */
    private val callsLazy: Lazy<CallLane> = lazy {
        CallLane(
            myAddress = address,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(baseUrl = serverUrl, signer = DesktopV2Signer(identity)),
            deviceId = syncCursor.deviceId(),
            // The SAME ContactStore every other ingress uses, so a blocked peer cannot ring
            // this client — enforcement, not a second flag (PARITY.md 0.21).
            machine = CallStateMachine(address, contacts, syncCursor.deviceId()),
            // AUDIO. Opens a UDP socket, the microphone and the speaker on a connected
            // call, signals ICE candidates, and ENDS the call if no device can be opened
            // rather than connecting it in silence — a silent connected call makes two
            // people wait, which is the most expensive failure a messenger has.
            //
            // Still gated by `--calls`: with `callPollIntervalMs` at 0 nothing polls, so
            // nothing ever rings and nothing ever connects. There is also no call button
            // anywhere in the window, and that stays true until a call has been made
            // between two real people on two real machines. Audio has been shown crossing
            // LOOPBACK in a test; that is not the same claim.
            mediaOpener = com.oshi.desktop.call.CallMedia.real(),
            log = log,
        ).also { lane -> lane.onEvent = { event -> onCall(event) } }
    }

    val calls: CallLane by callsLazy

    /** What a bot send actually did, plus the warning it is obliged to carry. */
    data class BotSendOutcome(
        val delivered: Int,
        val totalMembers: Int,
        val messageId: String,
        val groupName: String,
        /** Always true. A field rather than a doc line so a UI cannot fail to receive it. */
        val plaintextChannel: Boolean = true,
    )

    /**
     * Post to a bot group. **The content leaves this machine in cleartext.**
     *
     * Deliberately NOT named `send`: [send] is the end-to-end encrypted path and the two
     * must not read as siblings at a call site.
     */
    fun sendBotMessage(token: String, groupId: String, content: String): Result<BotSendOutcome> =
        botApi.send(token, groupId, content).map {
            log("client: bot post to ${it.groupName} — ${it.delivered}/${it.totalMembers} delivered, IN CLEARTEXT")
            BotSendOutcome(it.delivered, it.totalMembers, it.messageId, it.groupName)
        }

    /**
     * Drain the bot queue, storing each post and acking it.
     *
     * Classification happens BEFORE any fetch, exactly as both phones do it, so a bot
     * envelope never reaches a gateway that would try to resolve it as a content id.
     *
     * @return how many bot messages were stored.
     */
    fun pollBots(): Int {
        val entries = botQueue.pending().getOrElse {
            log("client: bot queue unreachable: ${it.javaClass.simpleName}: ${it.message}")
            return 0
        }
        var stored = 0
        for (entry in entries) {
            if (entry !is BotQueueClient.QueueEntry.Bot) continue
            val msg = runCatching { BotEnvelope.parse(entry.raw) }.getOrElse {
                log("client: malformed bot envelope, skipped: ${it.message}")
                continue
            }
            // A bot post is not a ratcheted message and must never be filed as if a peer
            // had authenticated it: it goes under its own conversation key.
            val convo = "bot!" + msg.groupId
            // The store owns dedup (row 0.13, by msgId ACROSS transports). Asking it
            // first and then appending would be a second, weaker copy of that rule and a
            // window between the two; the outcome IS the answer.
            // TWO ids ride in a bot envelope and they can disagree. Dedup and ack BOTH
            // use the envelope id, because that is the key the server filed the queue
            // entry under: storing under one and acking the other would leave the entry
            // on the server and re-read the same post on every poll for ever.
            if (msg.idsDisagree) {
                log("client: bot envelope id ${msg.envelopeMessageId} != payload id ${msg.payloadMessageId}")
            }
            val outcome = messages.append(
                Message(
                    id = msg.envelopeMessageId,
                    conversationId = convo,
                    senderAddress = convo,
                    recipientAddress = address,
                    fromMe = false,
                    content = msg.content,
                    sentAtMs = msg.unixMillis ?: System.currentTimeMillis(),
                    // The bot queue's `timestamp` is an ISO-8601 STRING (epoch 4), not
                    // millis — so the source is ISO8601, which already exists. A post that
                    // carries no parseable timestamp gets ours, and SAYS it is ours.
                    sentAtSource = if (msg.unixMillis != null) TimestampSource.ISO8601
                                   else TimestampSource.LOCAL_CLOCK,
                )
            )
            if (outcome == MessageStore.AppendOutcome.INSERTED) stored++
            // Ack a duplicate too: the queue entry is still on the server, and leaving it
            // there means re-reading the same post on every poll for ever.
            botQueue.ackBot(msg.envelopeMessageId)
        }
        if (stored > 0) log("client: stored $stored bot message(s) — this lane is NOT encrypted")
        return stored
    }

    // ------------------------------------------------------------------ callbacks

    /** Called after an inbound message has been stored. */
    var onMessage: (Message) -> Unit = {}

    /** Called for mesh traffic, which is NOT a conversation — see the class note. */
    var onMeshTraffic: (String) -> Unit = {}

    /** A row-0.18 control payload was recognised. Nothing was appended to the log. */
    var onControl: (String, ControlEvent, ControlPayloadRouter.Outcome) -> Unit = { _, _, _ -> }

    /** A row-0.19 location share or check-in arrived. */
    var onPlace: (String, PlaceEvent) -> Unit = { _, _ -> }

    /**
     * A row-0.27 LoRa event: the link's own state, or a packet that arrived over it.
     *
     * Separate from [onMessage] on purpose. An OSHI envelope over LoRa is sealed with the
     * LEGACY ratchet this client does not implement (the same blocker row 0.16 records for
     * the mesh), and stock Meshtastic text is unauthenticated by construction — neither is
     * a message the ratchet vouched for, so neither may be printed as one.
     */
    var onLoRa: (String) -> Unit = {}

    /** A row-0.17 group update was ingested, applied or refused. */
    var onGroupEvent: (GroupIngest.Result) -> Unit = {}

    /**
     * A row-2.1 call event: a ring, an answer, an end, a refusal, a transport problem.
     *
     * Separate from [onMessage] because a call is not a conversation row, and because
     * [CallLane.CallEvent.Connected] carries `noAudio = true` — a surface that rendered it
     * beside messages would be one `if` away from drawing a working call.
     */
    var onCall: (CallLane.CallEvent) -> Unit = {}

    /** Every scheduled-message sweep that did something. See [ScheduledMessageRunner.DueRun]. */
    var onScheduledRun: (ScheduledMessageRunner.DueRun) -> Unit = {}

    /**
     * Both privacy toggles, checked at the SEND site exactly as both phones do. Off means
     * the payload is never emitted; it does not mean an inbound one is ignored.
     */
    var deliveryReceiptsEnabled: Boolean = true
    var readReceiptsEnabled: Boolean = true

    private val running = AtomicBoolean(false)
    private var timers: ScheduledExecutorService? = null
    private var callTimers: ScheduledExecutorService? = null

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
     *        on desktop (PARITY.md 2.3), so this interval IS the delivery latency — and,
     *        because the scheduled-message sweep rides the same tick, it is also the
     *        maximum lateness of a scheduled message while the process IS running.
     * @param callPollIntervalMs how often to ask the CALL server (a different host route and
     *        a different server) whether someone is ringing. Zero disables the lane entirely,
     *        and the cost of leaving it on is stated rather than hidden: a call signal is
     *        only readable while it sits in a 60-second server queue, so a client that does
     *        not poll does not miss a call politely — it never learns there was one. It runs
     *        on its OWN thread so a slow relay pull cannot delay a ring by three seconds.
     */
    /**
     * @param callPollIntervalMs **0 = off, and off is the default.** Polling reaches a
     *        SECOND server — `GET /api/call/signals/<yourkey>` — which by row 2.1's own
     *        reading has no authentication at all and whose journald logs both parties'
     *        key prefixes and raw UDP IPs. Defaulting it on announced this identity to
     *        that server every second for every user, including everyone who never places
     *        a call, and nothing in the UI or the README said so. A capability that talks
     *        to an unauthenticated third party is opt-in.
     *
     *        The cost of off is real and is the reason the parameter exists at all: an
     *        offer is only readable while it sits in a 60-second server queue, so a client
     *        that does not poll does not decline politely — it never learns there was a
     *        call. Callers that switch it on must also bind [onCall], or the ring is
     *        invisible and the 45-second watchdog answers on the user's behalf.
     */
    fun start(
        pollIntervalMs: Long = 3_000,
        withMesh: Boolean = true,
        callPollIntervalMs: Long = 0,
    ) {
        if (!running.compareAndSet(false, true)) return

        if (callPollIntervalMs > 0) {
            val callTimer = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "oshi-call-poll").apply { isDaemon = true }
            }
            callTimers = callTimer
            callTimer.scheduleWithFixedDelay({
                // A SHUTDOWN IS NOT A SERVER OUTAGE, and it used to be reported as one.
                //
                // `stop()` calls `shutdownNow()`, which INTERRUPTS without waiting. The
                // interrupt lands inside `HttpClient.send`, `CallSignalClient` maps that
                // to -1 ("never reached the server"), `poll()` returns null, and
                // `pollOnce()` emits `TransportProblem("the call server did not answer a
                // poll")`. Every clean shutdown accused the call server of being down.
                //
                // That is the failure mode this codebase cares about most — an error path
                // that lies about whose fault it was — and it was invisible only because
                // nothing subscribed to `onCall` until this release. Checking `running`
                // before the poll and again before reporting keeps the diagnosis honest:
                // a real outage still reports, a shutdown says nothing.
                if (!running.get()) return@scheduleWithFixedDelay
                try {
                    calls.pollOnce()
                    // The watchdogs live in CallStateMachine.tick and are evaluated nowhere
                    // else, so this is what turns 45 s of no answer into a callEnd.
                    calls.tick()
                } catch (e: Exception) {
                    if (running.get()) {
                        log("client: call poll failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }, 0, callPollIntervalMs, TimeUnit.MILLISECONDS)
        }

        val timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "oshi-poll").apply { isDaemon = true }
        }
        timers = timer

        // Publishing a prekey bundle is what makes iOS and Android route V2 traffic to
        // this address, so it happens on the poll thread AFTER the gate has been read —
        // never eagerly at construction, and never while the gate is closed.
        timer.scheduleWithFixedDelay({
            PollGuard.run(log) {
                router.refreshConfig()
                if (config.isEnabledCached() && !published) {
                    published = router.publishBundleIfNeeded()
                    if (published) log("client: prekey bundle published — peers can now reach us over V2")
                }
                router.poll()
                tick()
            }
        }, 0, pollIntervalMs, TimeUnit.MILLISECONDS)

        if (withMesh) mesh.start()
    }

    /**
     * The non-network half of one poll tick, split out so a test can drive it without a
     * relay — and so the two things it does are visible rather than buried in a lambda.
     *
     * **This is the scheduled-message driver.** [ScheduledMessageRunner.runDue] is called
     * on every tick, which means the first tick after `start()` sweeps everything that came
     * due while the process was stopped. That sweep is where an overdue message is sent —
     * late, and SAID to be late: [ScheduledMessageRunner.DueRun.lateByMs] is the gap
     * between the scheduled time and the send, it is logged here, and it reaches the UI
     * through [onScheduledRun]. A desktop cannot wake itself at a wall-clock time
     * (PARITY.md 2.3 and [ScheduledMessageRunner]'s own note), so the honest behaviour is
     * to deliver late and report the lateness — never to backdate it into looking punctual.
     *
     * It also prunes ended live-location sessions, which is the only thing keeping
     * [PlaceRouter]'s tracker from growing for the life of the process.
     */
    internal fun tick(nowMs: Long = System.currentTimeMillis()) {
        val run = scheduler.runDue(nowMs)
        if (run.attempted > 0) {
            if (run.wasLate) {
                log(
                    "client: sent ${run.sent} scheduled message(s), the latest ${run.lateByMs / 1000}s LATE — " +
                        "this process was not running when they came due (PARITY.md 0.25)"
                )
            }
            onScheduledRun(run)
        }
        places.tracker().pruneEnded(nowMs)
    }

    @Volatile private var published = false

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        timers?.shutdownNow(); timers = null

        // WAIT FOR THE POLL THREAD BEFORE TOUCHING THE LANE. `shutdownNow()` interrupts
        // and returns immediately, so without this the poll thread can be inside
        // `pollOnce()` — applying a signal to `CallStateMachine`, whose fields are plain
        // `var`s with no lock — while the line below calls `hangUp()` on the same object.
        // Observable outcomes were two `callEnd` packets for one call, and a call-log entry
        // whose direction was read after the other thread had already transitioned.
        //
        // Two seconds is generous: the only blocking work in that thread is one HTTP
        // request whose read timeout is 8 s, and it has just been interrupted. If it does
        // not finish, we proceed anyway — a shutdown that hangs is worse than a race, and
        // the daemon thread cannot keep the JVM alive.
        callTimers?.let { timer ->
            timer.shutdownNow()
            runCatching { timer.awaitTermination(2, TimeUnit.SECONDS) }
        }
        callTimers = null
        // Tell the peer BEFORE the poller dies. Quitting mid-call without a `callEnd` leaves
        // them ringing, or connected to a client that no longer exists, until their own
        // 45/55-second watchdog fires — and this client has no push to be woken by, so there
        // is no later moment when it could send one. `isInitialized()` keeps this from
        // constructing the lane for an account that never placed a call.
        if (callsLazy.isInitialized()) {
            val lane = callsLazy.value
            if (lane.state != com.oshi.desktop.call.CallState.IDLE &&
                lane.state != com.oshi.desktop.call.CallState.ENDED
            ) {
                runCatching { lane.hangUp() }
            }
        }
        mesh.stop()
        loraDetach()
        routerState.flush()
    }

    override fun close() = stop()

    // ------------------------------------------------------------------ send

    enum class SendOutcome { SENT, BLOCKED, NO_V2_PATH, GATE_CLOSED, REFUSED_CONTROL_PAYLOAD }

    /**
     * Send text to a peer and record it locally.
     *
     * The local row is written whatever happens, with the outcome in its delivery status:
     * a message the user typed does not disappear because the network refused it. A caller
     * that wants "did it leave" reads the return value, never the presence of the row.
     *
     * **A body that IS a control payload is refused.** Neither phone checks this and both
     * should: every row-0.18 and row-0.19 payload is an emoji sentinel on the same
     * `content` string a user types into, so a peer who can get us to send
     * `🔧ACTION🔧{…}` on their behalf has a message-editing primitive. PARITY.md working
     * rule 2 says the stricter option wins where the platforms disagree; here they agree
     * with each other and are both wrong. Every payload this client legitimately emits goes
     * out through its own method below, none of which routes through here.
     */
    fun send(peerAddress: String, text: String): SendOutcome {
        if (ControlPrefix.isControl(text)) return SendOutcome.REFUSED_CONTROL_PAYLOAD
        return sendPlaintext(peerAddress, text, storeRow = true)
    }

    private fun sendPlaintext(peerAddress: String, text: String, storeRow: Boolean): SendOutcome {
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
        if (storeRow) {
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
        }
        contacts.seen(peerAddress, now)
        return outcome
    }

    /** True when this peer can be reached over V2 right now (session or fetchable bundle). */
    fun canReach(peerAddress: String): Boolean = router.ensureCapable(peerAddress)

    // ------------------------------------------------------ row 0.21, blocking

    /**
     * Block a peer, creating the contact row if there is not one yet.
     *
     * [ContactStore.block] only mutates an EXISTING row — it is a flag on a contact, and a
     * flag needs something to sit on. Blocking someone you have never exchanged a message
     * with is a real case (a code you scanned, an address a friend pasted you, a peer seen
     * on the mesh once and never again), and going straight to `contacts.block` in that
     * case silently does nothing while every UI reports success. Creating the row first is
     * the whole fix, and it belongs here rather than in the store: `seen` means "this
     * address exists", which is exactly what a block asserts about it.
     */
    fun block(peerAddress: String) {
        contacts.seen(peerAddress, System.currentTimeMillis())
        contacts.block(peerAddress)
    }

    /**
     * Unblock EVERY base64 spelling of a key, and report how many rows were cleared.
     *
     * Not `contacts.unblock`, which is exact: iOS compares raw strings on unblock and
     * normalised ones on `isBlocked` (`BlockedContactsManager.swift:131` vs `:90-103`), so
     * a contact blocked under one spelling and unblocked under another stays blocked
     * forever. PARITY.md row 0.21 defect 4.
     */
    fun unblock(peerAddress: String): Int = BlockPolicy.unblockEverySpelling(contacts, peerAddress)

    // ------------------------------------------------------ row 0.18, outbound

    /**
     * Add or remove one emoji on one of OUR OR THEIR messages in this conversation.
     *
     * No ownership check, deliberately, and the asymmetry with [editMessage] is the shipped
     * one: reacting to another person's message is the entire feature.
     */
    fun sendReaction(peerAddress: String, messageId: String, emoji: String, adding: Boolean = true): SendOutcome {
        val local = messages.message(peerAddress, messageId)
            ?: return SendOutcome.NO_V2_PATH
        val payload = ReactionPayload.encode(
            messageId = local.id,
            emoji = emoji,
            senderPublicKey = address,
            senderName = displayName,
            isAdding = adding,
            atUnixMillis = System.currentTimeMillis(),
        )
        val outcome = sendPlaintext(peerAddress, payload, storeRow = false)
        // Applied locally whatever the network said: a reaction the user pressed is local
        // state first. The peer's copy converges on the next successful send.
        if (outcome != SendOutcome.BLOCKED) {
            messages.setReaction(peerAddress, local.id, address, if (adding) emoji else null)
        }
        return outcome
    }

    /**
     * Edit one of OUR messages, for everyone.
     *
     * The ownership rule (C-MSG-4) is enforced on the way IN by [ControlPayloadRouter]; it
     * is enforced here on the way OUT too, because a client that can be talked into emitting
     * an edit for someone else's id is the other half of the same attack surface.
     */
    fun editMessage(peerAddress: String, messageId: String, newText: String): SendOutcome {
        val local = messages.message(peerAddress, messageId) ?: return SendOutcome.NO_V2_PATH
        if (!local.fromMe) return SendOutcome.REFUSED_CONTROL_PAYLOAD
        val now = System.currentTimeMillis()
        val payload = MessageActionPayload.encode(
            actionType = MessageActionPayload.ActionType.EDIT_MESSAGE,
            targetMessageId = local.id,
            newContent = newText,
            atUnixMillis = now,
        )
        val outcome = sendPlaintext(peerAddress, payload, storeRow = false)
        if (outcome != SendOutcome.BLOCKED) messages.editContent(peerAddress, local.id, newText, now)
        return outcome
    }

    /** Delete one of OUR messages, for everyone. Same ownership rule as [editMessage]. */
    fun deleteMessage(peerAddress: String, messageId: String): SendOutcome {
        val local = messages.message(peerAddress, messageId) ?: return SendOutcome.NO_V2_PATH
        if (!local.fromMe) return SendOutcome.REFUSED_CONTROL_PAYLOAD
        val now = System.currentTimeMillis()
        val payload = MessageActionPayload.encode(
            actionType = MessageActionPayload.ActionType.DELETE_FOR_EVERYONE,
            targetMessageId = local.id,
            newContent = null,
            atUnixMillis = now,
        )
        val outcome = sendPlaintext(peerAddress, payload, storeRow = false)
        if (outcome != SendOutcome.BLOCKED) messages.deleteForEveryone(peerAddress, local.id, now)
        return outcome
    }

    /** `⌨️TYPING⌨️`. Ephemeral on both ends: nothing is stored here or there. */
    fun sendTyping(peerAddress: String, typing: Boolean): SendOutcome =
        sendPlaintext(
            peerAddress,
            TypingPayload.encode(
                senderPublicKey = address,
                isTyping = typing,
                atUnixMillis = System.currentTimeMillis(),
                senderName = displayName,
            ),
            storeRow = false,
        )

    /**
     * `📖READ_RECEIPT📖` — not per-message on either platform, so not here either.
     *
     * Gated on [readReceiptsEnabled] at the SEND site, which is where both phones check
     * their own toggle.
     */
    fun sendReadReceipt(peerAddress: String): SendOutcome {
        if (!readReceiptsEnabled) return SendOutcome.REFUSED_CONTROL_PAYLOAD
        return sendPlaintext(
            peerAddress,
            ReadReceipt.encode(address, System.currentTimeMillis()),
            storeRow = false,
        )
    }

    // ------------------------------------------------------ row 0.15, media

    /**
     * Send a file the way both phones do: AEAD-seal it into 2 MiB chunks under a per-file
     * key, upload the opaque chunks to `/v2/blobs`, then send the KEY MATERIAL as an
     * ordinary ratchet message (`{"kind":"v2file",…}`).
     *
     * The inline `{"type":"IMAGE","data":…}` shape is deliberately NOT used: iOS's V2
     * receiver never parses it and renders it as a raw-JSON text bubble
     * (`MessageManager+V2.swift:947-956`).
     *
     * No resume ledger here, unlike Android's: a resumed upload needs a persisted
     * {blobId, fileKey, fileNonce} record bound to a plaintext digest, and inventing one
     * without the store to hold it would be a retry that re-encrypts with a FRESH key and
     * tops up a blob whose earlier chunks were sealed with the old one. Restarting the
     * upload is correct and slow; a wrong resume is corrupt media.
     */
    /**
     * @param mediaType override the type this file is announced as. **Leave it null.**
     *        The type is otherwise derived from the probed MIME by [MediaType.forMime],
     *        which is what the receiving phone would have derived itself if the field
     *        were absent — and the field is never absent, so a wrong one here is final.
     *        This defaulted to [MediaType.DOCUMENT] and `/sendfile` never overrode it, so
     *        every photo, clip and voice note this client sent arrived on a phone as a
     *        file attachment: no thumbnail, no player, "Download" instead of an image.
     */
    fun sendFile(peerAddress: String, file: File, mediaType: MediaType? = null): SendOutcome {
        val mime = probeMime(file)
        return sendMedia(
            peerAddress = peerAddress,
            bytes = file.readBytes(),
            filename = file.name,
            mime = mime,
            mediaType = mediaType ?: MediaType.forMime(mime),
            localRef = file.absolutePath,
        )
    }

    /**
     * Share one contact with another — PARITY.md row 0.19's third payload.
     *
     * A contact card is **not a sentinel**: it is attachment bytes with
     * `mediaType: contact`, which is why [com.oshi.desktop.place.PlaceRouter] does not route
     * it and why it could not be wired until the media path was. It goes out over exactly
     * the same blob route a photo does, and the receiver recognises it by the media type on
     * the key message.
     */
    fun sendContactCard(peerAddress: String, cardAddress: String, alias: String? = null): SendOutcome {
        val body = com.oshi.desktop.place.ContactCardPayload.encode(
            publicKey = cardAddress,
            alias = alias ?: contacts.get(cardAddress)?.displayName,
            timestampUnixMillis = System.currentTimeMillis(),
        )
        return sendMedia(
            peerAddress = peerAddress,
            bytes = body.toByteArray(Charsets.UTF_8),
            filename = "contact.json",
            mime = "application/json",
            mediaType = MediaType.CONTACT,
            localRef = null,
        )
    }

    private fun sendMedia(
        peerAddress: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
        mediaType: MediaType,
        localRef: String?,
    ): SendOutcome {
        if (BlockPolicy.outgoingText(contacts, peerAddress) == BlockPolicy.Outbound.REFUSE_BLOCKED) {
            return SendOutcome.BLOCKED
        }
        if (!config.isEnabledCached()) return SendOutcome.GATE_CLOSED
        if (bytes.isEmpty() || bytes.size > V2BlobClient.MAX_PLAINTEXT_BYTES) return SendOutcome.NO_V2_PATH

        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        // The session first: uploading to a peer with no bundle burns the uplink for
        // nothing, and doing it through sendText's own fetch pops a second one-time prekey.
        if (!router.ensureSession(peerAddress)) return SendOutcome.NO_V2_PATH

        val enc = OSHICryptoV2.encryptFile(bytes, filename, mime)
        val blobId = blobs.uploadBlob(peerAddress, enc.chunks) ?: return SendOutcome.NO_V2_PATH
        val key = V2FileKeyMessage(
            blobId = blobId,
            fileKey = enc.fileKey,
            fileNonce = enc.fileNonce,
            chunkCount = enc.chunks.size,
            manifest = enc.manifest,
            filename = filename,
            mime = mime,
            mediaType = mediaType.wire,
        )
        val sent = router.sendText(peerAddress, key.toBytes(), msgId)
        messages.append(
            Message(
                id = msgId,
                conversationId = peerAddress,
                senderAddress = address,
                recipientAddress = peerAddress,
                fromMe = true,
                content = filename,
                mediaType = mediaType,
                mediaRef = localRef,
                sentAtMs = now,
                sentAtSource = TimestampSource.LOCAL_CLOCK,
                deliveryStatus = if (sent) DeliveryStatus.SENT else DeliveryStatus.FAILED,
                transport = if (sent) "relay-v2" else "none",
            )
        )
        contacts.seen(peerAddress, now)
        return if (sent) SendOutcome.SENT else SendOutcome.NO_V2_PATH
    }

    // ------------------------------------------------------ row 0.17, groups

    /**
     * Create a group with us as its admin and broadcast it.
     *
     * TWO payloads go to each member, which is what iOS does and is not redundancy: the
     * full `📢GROUP_UPDATE📢` definition is the only shape an iPhone acts on for anything
     * structural, and the `{"type":"created"}` shape exists **only** for Android — iOS
     * emits it (`GroupMessaging.swift:1877-1889`) and cannot parse its own emission
     * (PARITY.md row 0.17). Sending one and not the other leaves one platform out.
     */
    fun createGroup(
        name: String,
        memberAddresses: List<String>,
        type: GroupType = GroupType.COLLABORATIVE,
    ): GroupDefinition {
        require(name.isNotBlank()) { "a group needs a name" }
        val now = System.currentTimeMillis()
        val roster = (listOf(address) + memberAddresses).distinctBy(GroupIdentity::canonicalIdentity)
        val def = GroupDefinition(
            // UPPERCASE from the start: iOS spells a group id as `UUID.uuidString`, which is
            // uppercase, and `GroupIdentity.canonicalGroupId` folds to that. Minting a
            // lowercase id would leave the definition carrying one spelling while every
            // envelope, store key and conversation id carried the other.
            groupId = UUID.randomUUID().toString().uppercase(java.util.Locale.US),
            name = name,
            type = type,
            adminPublicKey = address,
            members = roster.map {
                GroupMember(
                    publicKey = it,
                    joinedAtUnixMillis = now,
                    isAdmin = GroupIdentity.sameIdentity(it, address),
                )
            },
            createdAtUnixMillis = now,
            lastActivityUnixMillis = now,
            stateVersion = 1,
        )
        groups.put(def)
        broadcastGroupUpdate(def, GroupUpdateWire.encodeDefinitionFramed(def))
        broadcastGroupUpdate(
            def,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeCreated(
                MinimalGroupUpdate.Created(
                    groupId = def.groupId,
                    name = def.name,
                    description = "",
                    creatorPublicKey = address,
                    memberKeys = def.memberKeys,
                    adminKeys = def.adminKeys,
                    isPublic = type == GroupType.PUBLIC,
                )
            ),
        )
        return def
    }

    /** Add a member locally and tell the group. Admin-gated exactly as the ingest path is. */
    fun addGroupMember(groupId: String, memberAddress: String): GroupDefinition? {
        val g = groups.get(groupId) ?: return null
        if (g.isMember(memberAddress)) return g
        val now = System.currentTimeMillis()
        val updated = bumpVersion(
            g.copy(members = g.members + GroupMember(memberAddress, joinedAtUnixMillis = now, isAdmin = false))
        )
        groups.put(updated)
        broadcastGroupUpdate(
            updated,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberAdded(updated.groupId, memberAddress),
        )
        // The new member has no copy of the group at all, so the minimal update is useless
        // to them — they get the whole definition, which is the payload their own ingest
        // path's unknown-group gate is written for.
        sendPlaintext(memberAddress, GroupUpdateWire.encodeDefinitionFramed(updated), storeRow = false)
        return updated
    }

    /** Remove a member locally and tell the group (including the person removed). */
    fun removeGroupMember(groupId: String, memberAddress: String): GroupDefinition? {
        val g = groups.get(groupId) ?: return null
        val remaining = g.members.filterNot { GroupIdentity.sameIdentity(it.publicKey, memberAddress) }
        if (remaining.size == g.members.size) return g
        val updated = bumpVersion(g.copy(members = remaining))
        groups.put(updated)
        val payload = ControlPrefix.GROUP_UPDATE +
            GroupUpdateWire.encodeMemberRemoved(updated.groupId, memberAddress)
        broadcastGroupUpdate(updated, payload)
        sendPlaintext(memberAddress, payload, storeRow = false)
        return updated
    }

    /** Rename a group locally and tell the members. */
    fun renameGroup(groupId: String, name: String): GroupDefinition? {
        val g = groups.get(groupId) ?: return null
        val updated = bumpVersion(g.copy(name = name))
        groups.put(updated)
        broadcastGroupUpdate(
            updated,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeGroupRenamed(updated.groupId, name),
        )
        return updated
    }

    /**
     * Ask ONE peer for their copy of a group's roster.
     *
     * Only for a group we are already in — see [MinimalGroupUpdate.MemberSyncRequest]: on
     * iOS this payload auto-adds the requester, so sending one to a group we are NOT in
     * would be exploiting a defect PARITY.md records rather than working around it.
     */
    fun requestGroupRoster(groupId: String, peerAddress: String): SendOutcome {
        val g = groups.get(groupId) ?: return SendOutcome.NO_V2_PATH
        if (!g.isMember(address)) return SendOutcome.REFUSED_CONTROL_PAYLOAD
        return sendPlaintext(
            peerAddress,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberSyncRequest(g.groupId, address),
            storeRow = false,
        )
    }

    /** Ask a peer for EVERY group they think we belong to. `{"type":"sync_request"}`. */
    fun requestAllGroups(peerAddress: String): SendOutcome =
        sendPlaintext(
            peerAddress,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeSyncRequest(address),
            storeRow = false,
        )

    /**
     * Send text to a group: N members, N pairwise ratchet ciphertexts, one message id.
     *
     * There is no group key and no sender key on any platform (PARITY.md row 0.17) — this
     * IS the protocol, not a simplification of it. [GroupFanout.plan] decides the
     * recipients (everyone but us, by canonical identity) and the envelope's `groupId` is
     * what routes each leg.
     */
    fun sendGroupText(groupId: String, text: String): GroupSendReport {
        if (ControlPrefix.isControl(text)) {
            return GroupSendReport(groupId, 0, 0, emptyList(), refusedControlPayload = true)
        }
        val g = groups.get(groupId) ?: return GroupSendReport(groupId, 0, 0, emptyList())
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val plaintext = GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = msgId,
                groupId = g.groupId,
                senderPublicKey = address,
                body = text,
                timestampUnixMillis = now,
                senderName = displayName,
            )
        ).toByteArray(Charsets.UTF_8)

        var sent = 0
        val blocked = ArrayList<String>()
        val recipients = GroupFanout.plan(g, address)
        for (member in recipients) {
            // A blocked member is skipped, not silently included. iOS delivers group
            // messages from and to blocked members (PARITY.md 0.21 defect 2); this does not.
            if (BlockPolicy.outgoingText(contacts, member) == BlockPolicy.Outbound.REFUSE_BLOCKED) {
                blocked += member
                continue
            }
            if (router.sendText(member, plaintext, msgId, groupId = g.groupId)) sent++
        }
        messages.append(
            Message(
                id = msgId,
                conversationId = GroupIdentity.canonicalGroupId(g.groupId),
                senderAddress = address,
                recipientAddress = g.groupId,
                fromMe = true,
                content = text,
                sentAtMs = now,
                sentAtSource = TimestampSource.LOCAL_CLOCK,
                deliveryStatus = if (sent > 0) DeliveryStatus.SENT else DeliveryStatus.FAILED,
                transport = if (sent > 0) "relay-v2" else "none",
            )
        )
        groups.put(g.copy(lastActivityUnixMillis = now))
        return GroupSendReport(g.groupId, recipients.size, sent, blocked)
    }

    data class GroupSendReport(
        val groupId: String,
        val recipients: Int,
        val sent: Int,
        val skippedBlocked: List<String>,
        val refusedControlPayload: Boolean = false,
    )

    private fun bumpVersion(g: GroupDefinition): GroupDefinition = g.copy(
        // Only ever bumped when we already carry one: a defaulted 0 on a group whose peers
        // emit none would make every one of their updates read as stale. See GroupDefinition.
        stateVersion = g.stateVersion?.plus(1),
        lastActivityUnixMillis = System.currentTimeMillis(),
    )

    private fun broadcastGroupUpdate(g: GroupDefinition, payload: String) {
        for (member in GroupFanout.plan(g, address)) {
            sendPlaintext(member, payload, storeRow = false)
        }
    }

    // ------------------------------------------------------ row 0.22 / 0.20

    /** This account's QR payload — the bare identity key in STANDARD base64. */
    fun qrPayload(): String = ContactQr.payloadFor(address)

    /** The share text a person can copy out of a terminal. */
    fun qrShareText(): String = ContactQr.shareText(address)

    /**
     * Accept a scanned or pasted code and record the contact.
     *
     * Returns the parse result unchanged — a rejection is a REASON, not a null, because
     * "that is my own code", "that is a link to a host OSHI does not own" and "that is not
     * 32 bytes" are three different things to tell a person.
     */
    fun scanContact(raw: String?): ContactQr.Scan {
        val scan = ContactQr.parse(raw, address)
        if (scan is ContactQr.Scan.Contact) contacts.seen(scan.address, System.currentTimeMillis())
        return scan
    }

    /** The 60-digit safety number binding this account's key to [peerAddress]'s. */
    fun safetyNumber(peerAddress: String): String = SafetyNumber.compute(address, peerAddress)

    /**
     * Compare a safety number QR a peer showed us against the one we compute.
     *
     * EXACT BYTE EQUALITY of the payload, never a re-derivation — iOS `SafetyNumber.verify`
     * (`SafetyNumber.swift:41-43`). On a match the contact is marked verified.
     */
    fun verifySafetyNumber(peerAddress: String, scannedPayload: String): Boolean {
        val ok = SafetyNumber.verify(scannedPayload, address, peerAddress)
        contacts.setVerification(
            peerAddress,
            if (ok) ContactStore.VerificationState.VERIFIED else ContactStore.VerificationState.SAFETY_NUMBER_CHANGED,
        )
        return ok
    }

    // ------------------------------------------------------ row 0.24, sync

    /** Push every contact — blocked ones INCLUDED — into the V2 archive. */
    fun syncPushContacts(): Result<SyncProtocol.PushResult> {
        val now = System.currentTimeMillis()
        // exportAll asserts that every locally-blocked contact is represented. That guard
        // is the whole point of the row: Android's payload is built from a query that
        // excludes blocked rows, so its `isBlocked` can only ever be false and a block
        // never propagates (PARITY.md row 0.24).
        val records = ContactSyncRecord.exportAll(contacts, now)
        return sync.archive(
            kind = SyncProtocol.SyncKind.CONTACT,
            // Versioned by the stamp: a mutable record needs a changing logical id or the
            // server dedupes the update away.
            logicalId = "contacts@$now",
            payload = ContactSyncRecord.encodePayload(records, now, syncCursor.deviceId()),
        )
    }

    /** Drain the V2 archive and apply what it holds. Idempotent. */
    fun syncPull(): Result<SyncEngine.DrainReport> {
        val now = System.currentTimeMillis()
        return sync.drain { record ->
            when (record.kind) {
                SyncProtocol.SyncKind.CONTACT -> {
                    val parsed = ContactSyncRecord.parsePayload(record.payload)
                    val outcome = ContactSyncRecord.applyTo(contacts, parsed, now)
                    log("sync: contacts +${outcome.inserted} ~${outcome.updated} stale=${outcome.ignoredAsStale}")
                }
                // Every other kind is a shape this client does not model yet. Counted by
                // the drain and skipped here rather than guessed at.
                else -> log("sync: ignoring a '${record.rawKind}' record (${record.payload.size} bytes)")
            }
        }
    }

    /** Ask whether the archive holds anything past our cursor — a head call, no transfer. */
    fun syncIsBehind(): Result<Boolean> = sync.isBehind()

    /**
     * Tell the server everything up to [upToSeq] is durably consumed by every device.
     *
     * Destructive server-side, so it is never called automatically by the drain: a
     * checkpoint taken on ONE device's cursor deletes archive items a second device has not
     * pulled yet.
     */
    fun syncCheckpoint(upToSeq: Int): Result<SyncProtocol.CheckpointResult> =
        V2SyncClient(http, address).checkpoint(upToSeq)

    /** The legacy `/api/sync` alias map — aliases only, no block state. See [ContactSyncRecord]. */
    fun legacySyncPushAliases(): Result<Unit> =
        legacySync.push(SyncProtocol.LegacyKind.CONTACTS, ContactSyncRecord.encodeLegacyAliasMap(contacts))

    /** Gap-fill from the legacy alias map. Never overwrites a local alias. Returns rows added. */
    fun legacySyncPullAliases(): Result<Int> =
        legacySync.pull(SyncProtocol.LegacyKind.CONTACTS).map { body ->
            if (body == null) 0
            else ContactSyncRecord.applyLegacyAliasMap(contacts, body, System.currentTimeMillis())
        }

    // ------------------------------------------------------------ row 0.27, LoRa

    private val loraInbound = com.oshi.desktop.lora.LoRaInbound()
    @Volatile private var lora: com.oshi.desktop.lora.LoRaLink? = null

    val loraAttached: Boolean get() = lora?.isConnected == true

    /**
     * Attach to a Meshtastic node over TCP — PARITY.md row 0.27.
     *
     * **What this buys, stated before anyone types it into a UI:**
     *  - stock Meshtastic TEXT becomes readable, quarantined under `lora!<nodehex>` and
     *    flagged unverified on every decision ([com.oshi.desktop.lora.LoRaInbound]);
     *  - an OSHI↔OSHI envelope is REPORTED and NOT OPENED. It is sealed with the legacy
     *    Double Ratchet, which this client does not implement — the identical blocker
     *    row 0.16 records for the mesh. Attaching a radio does not change that.
     *
     * So this is a receive lane that mostly tells you what it cannot read. That is worth
     * having — it is the difference between a silent radio and a visible one — and it is
     * not messaging over LoRa.
     *
     * The link is a real socket to a real node; nothing here has been exercised against a
     * radio, only against `FakeMeshtasticNode`.
     */
    fun loraAttach(host: String, port: Int = com.oshi.desktop.lora.LoRaAttach.TCP_PORT) {
        loraDetach()
        val link = com.oshi.desktop.lora.LoRaLink(
            host = host,
            port = port,
            onFromRadio = { fromRadio -> onFromRadio(fromRadio) },
            log = { log("lora: $it"); onLoRa(it) },
        )
        lora = link
        link.start()
    }

    fun loraDetach() {
        lora?.stop()
        lora = null
    }

    /**
     * One `FromRadio` off the link.
     *
     * A `FromRadio` wraps a `MeshPacket` in field 2; anything else in the stream (config,
     * node info, the `config_complete_id` that ends the handshake burst) is not a packet
     * and is dropped rather than guessed at — a partial protobuf reader that invented
     * meanings for fields it does not implement would be worse than one that ignores them.
     */
    private fun onFromRadio(fromRadio: ByteArray) {
        val packetField = com.oshi.desktop.lora.LoRaProto.field(fromRadio, 2, wire = 2) ?: return
        when (val d = loraInbound.route(packetField.payload, address, System.currentTimeMillis())) {
            is com.oshi.desktop.lora.LoRaInbound.Decision.InteropText -> {
                // Stock Meshtastic text: nothing ratcheted it and no peer authenticated it,
                // so it is filed under its own conversation key and never beside a real
                // message — the same rule row 0.26 applies to a bot post.
                val stored = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = d.conversationKey,
                    senderAddress = d.conversationKey,
                    recipientAddress = address,
                    fromMe = false,
                    content = d.text,
                    sentAtMs = System.currentTimeMillis(),
                    sentAtSource = TimestampSource.LOCAL_CLOCK,
                    deliveryStatus = DeliveryStatus.DELIVERED,
                    transport = "lora",
                )
                messages.append(stored)
                onLoRa("interop text from node ${d.nodeNum} (UNVERIFIED — not authenticated): ${d.text.take(200)}")
            }
            is com.oshi.desktop.lora.LoRaInbound.Decision.Envelope -> {
                // Reported, not opened, and not stored. See loraAttach's doc.
                onLoRa(
                    "OSHI envelope from node ${d.nodeNum} — UNREADABLE by this client " +
                        "(legacy Double Ratchet, PARITY.md rows 0.16 / 0.27)"
                )
            }
            is com.oshi.desktop.lora.LoRaInbound.Decision.Routing ->
                onLoRa("routing ack from node ${d.nodeNum}")
            com.oshi.desktop.lora.LoRaInbound.Decision.Ignored -> {}
        }
    }

    // ------------------------------------------------------ row 0.25, scheduled

    /**
     * The [ScheduledMessageRunner.Sender] seam, and the reason it is not just [send].
     *
     * [send] writes a local row on every attempt, including a failed one — right for a
     * message a user just typed, wrong for a queued one, whose row IS the schedule entry
     * and stays PENDING. So this delivers WITHOUT storing, and appends the row only once
     * the send actually left.
     *
     * The three answers follow iOS rather than Android: a refusal that might pass later
     * leaves the row PENDING, a refusal that cannot pass is terminal. Android marks FAILED
     * on anything and then retries the row it just marked FAILED.
     */
    private fun deliverScheduled(m: ScheduledMessage): ScheduledMessageRunner.Outcome {
        if (m.isGroup) {
            val report = sendGroupText(m.recipient, m.content)
            return when {
                report.refusedControlPayload -> ScheduledMessageRunner.Outcome.FAILED
                report.recipients == 0 -> ScheduledMessageRunner.Outcome.FAILED
                report.sent > 0 -> ScheduledMessageRunner.Outcome.SENT
                else -> ScheduledMessageRunner.Outcome.DEFERRED
            }
        }
        val outcome = sendPlaintext(m.recipient, m.content, storeRow = false)
        if (outcome != SendOutcome.SENT) {
            return when (outcome) {
                // Blocked and malformed are terminal; the network ones are not.
                SendOutcome.BLOCKED, SendOutcome.REFUSED_CONTROL_PAYLOAD ->
                    ScheduledMessageRunner.Outcome.FAILED
                else -> ScheduledMessageRunner.Outcome.DEFERRED
            }
        }
        messages.append(
            Message(
                id = m.id,
                conversationId = m.recipient,
                senderAddress = address,
                recipientAddress = m.recipient,
                fromMe = true,
                content = m.content,
                sentAtMs = System.currentTimeMillis(),
                sentAtSource = TimestampSource.LOCAL_CLOCK,
                deliveryStatus = DeliveryStatus.SENT,
                transport = "relay-v2",
            )
        )
        return ScheduledMessageRunner.Outcome.SENT
    }

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
        //
        // THE GATE COVERS CONTROL PAYLOADS TOO, and that is not incidental. Below this line
        // a reaction mutates our message log, an edit rewrites a body and a group update
        // rewrites a roster. A block that stopped only at prose would leave a blocked peer
        // able to delete our messages — blocking someone must not leave them holding a
        // write primitive against our own history.
        if (BlockPolicy.inbound(contacts, inbound.from) == BlockPolicy.Inbound.DROP_BLOCKED) {
            log("client: dropped a message from a blocked contact ${inbound.from.take(12)}…")
            return
        }
        dispatch(inbound)
    }

    /**
     * The post-block half of [receive] — see THE DISPATCH in the class doc for the order and
     * why it is that order. Internal so a test can drive one inbound plaintext without a
     * relay, a ratchet or a network.
     */
    internal fun dispatch(inbound: V2Inbound) {
        val text = inbound.text

        // 2. A group update. Checked before the envelope's groupId because a definition
        //    broadcast travels as an ordinary 1:1 message carrying the sentinel.
        if (ControlPrefix.match(text) == ControlPrefix.GROUP_UPDATE) {
            val result = groupIngest.ingest(text, inbound.from)
            log("client: group update from ${inbound.from.take(12)}… → ${result.outcome} (${result.detail})")
            result.respondTo?.let { sendPlaintext(inbound.from, GroupUpdateWire.encodeDefinitionFramed(it), false) }
            contacts.seen(inbound.from, inbound.ts)
            onGroupEvent(result)
            return
        }

        // 3. A group MESSAGE. The envelope's groupId is what routes it; the payload's own
        //    copy is a claim by the sender and is not trusted for routing.
        if (!inbound.groupId.isNullOrBlank()) {
            receiveGroupMessage(inbound)
            return
        }

        // 4. Media: a `{"kind":"v2file"}` key message. Parsed before the control catalog
        //    because it carries no sentinel at all and would otherwise read as prose.
        V2FileKeyMessage.parse(text)?.let { key ->
            receiveMedia(inbound, key)
            return
        }

        // 5. Row 0.18. `apply` classifies AND applies; a receipt/typing/reaction/action
        //    never becomes a row here, which is the whole difference between "the desktop
        //    shows the edit" and "the desktop shows JSON in a bubble".
        val (event, outcome) = control.apply(inbound.from, inbound.from, text)
        when (event) {
            is ControlEvent.Delivered, is ControlEvent.Read, is ControlEvent.Typing,
            is ControlEvent.Reaction, is ControlEvent.Action,
            -> {
                contacts.seen(inbound.from, inbound.ts)
                onControl(inbound.from, event, outcome)
                return
            }

            is ControlEvent.Unparseable -> {
                // One of OUR sentinels with a body we could not read. Counted, never shown:
                // a payload that fails to parse produces no error a user would ever see.
                log("client: unparseable ${event.prefix} from ${inbound.from.take(12)}…")
                onControl(inbound.from, event, outcome)
                return
            }

            is ControlEvent.Foreign -> {
                // 6. Row 0.19 — location and check-in — consumes exactly this case.
                //
                // THE CLOCK IS OURS, NEVER THE SENDER'S. `inbound.ts` is the envelope's
                // `ts`: read with `optLong("ts", 0)`, sitting OUTSIDE the AEAD (every
                // `OSHIRatchetV2.decrypt` call passes EMPTY associated data), so it is
                // chosen by the sender and rewritable by the relay. Passing it here made
                // every expiry decision in this row — the EXPIRY_NEVER_EXTENDS ratchet
                // that PARITY 0.19 credits for refusing two shipped bugs — a comparison
                // against a number the attacker wrote. A 2-minute share sent with
                // `"ts": 1600000000000` renders LIVE for five years and is PERSISTED that
                // way, while `/live` (which uses the real clock) correctly says EXPIRED:
                // two surfaces disagreeing, and the wrong one is the bubble the user reads.
                //
                // PlaceRouter takes an explicit clock so expiry can be tested at its
                // boundary, not so a caller can supply the peer's idea of the time.
                val nowMs = System.currentTimeMillis()
                val place = places.route(text, nowMs)
                if (place !is PlaceEvent.NotMine) {
                    onPlace(inbound.from, place)
                    val rendered = places.renderToText(text, nowMs)
                    if (rendered != null) storeInbound(inbound, rendered, ControlPrefix.suppressesPush(text))
                    else log("client: unparseable ${event.prefix} from ${inbound.from.take(12)}…")
                    return
                }
                // 7. A sentinel belonging to a row this client has not built (call signal,
                //    profile/wallpaper update, reply/forward envelope). Known, not ours,
                //    and NOT rendered — the body is JSON and a user would see it raw.
                log("client: ignoring a ${event.prefix} payload (${event.kind}) from ${inbound.from.take(12)}…")
                onControl(inbound.from, event, outcome)
                return
            }

            is ControlEvent.Prose -> Unit
        }

        // 8. Prose.
        storeInbound(inbound, text, suppressNotification = false)
        // The delivery receipt is sent only for prose, and only AFTER the row is durable.
        // Note where this sits: below the block gate, so a blocked contact never gets one.
        // iOS sends it above its own gate (`MessageManager+V2.swift:1017`), which makes
        // blocking observable to the person you blocked — PARITY.md row 0.21 defect 1.
        if (deliveryReceiptsEnabled) {
            sendPlaintext(inbound.from, DeliveryReceipt.encode(inbound.msgId), storeRow = false)
        }
    }

    private fun storeInbound(inbound: V2Inbound, content: String, suppressNotification: Boolean) {
        val stored = Message(
            id = inbound.msgId,
            conversationId = inbound.from,
            senderAddress = inbound.from,
            recipientAddress = address,
            fromMe = false,
            content = content,
            sentAtMs = inbound.ts,
            // The v2 relay envelope is one of the few places this protocol is unambiguous
            // about time: Unix epoch MILLISECONDS.
            sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            deliveryStatus = DeliveryStatus.DELIVERED,
            transport = "relay-v2",
        )
        val outcome = messages.append(stored)
        contacts.seen(inbound.from, inbound.ts)
        if (outcome != MessageStore.AppendOutcome.DUPLICATE && !suppressNotification) onMessage(stored)
    }

    /**
     * A group message: bare base64 of a JSON `GroupMessage`, routed by the ENVELOPE's
     * groupId.
     *
     * Two checks that are not in the codec because they need local state:
     *  - the group must be one we hold, and the sender must be in ITS roster. iOS delivers
     *    group messages from blocked and non-member senders alike (PARITY.md row 0.17/0.21);
     *  - the id is matched case-insensitively against what we already hold, because an
     *    iPhone uppercases every UUID it round-trips and a plain `==` drops the second copy
     *    of a message that reached us over two transports as two different rows.
     */
    private fun receiveGroupMessage(inbound: V2Inbound) {
        val gid = GroupIdentity.canonicalGroupId(inbound.groupId!!)
        val group = groups.get(gid)
        if (group == null) {
            log("client: group message for a group we do not hold (${gid.take(12)}…)")
            return
        }
        if (!group.isMember(inbound.from)) {
            log("client: dropped a group message from a non-member ${inbound.from.take(12)}…")
            return
        }
        val payload = GroupMessageWire.decodeFromEnvelope(inbound.text)
        if (payload == null) {
            log("client: undecodable group message from ${inbound.from.take(12)}…")
            return
        }
        if (messages.messages(gid).any { GroupMessageWire.sameMessageId(it.id, payload.messageId) }) return

        val body = GroupMessageWire.resolveIncomingBody(
            contentField = payload.body,
            plaintextContentField = payload.plaintextContent.orEmpty(),
            decodedEncryptedContent = payload.body,
        )
        val stored = Message(
            id = payload.messageId,
            conversationId = gid,
            senderAddress = inbound.from,
            recipientAddress = gid,
            fromMe = false,
            content = body,
            sentAtMs = payload.timestampUnixMillis,
            sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            deliveryStatus = DeliveryStatus.DELIVERED,
            transport = "relay-v2",
        )
        val outcome = messages.append(stored)
        contacts.seen(inbound.from, inbound.ts)
        groups.put(group.copy(lastActivityUnixMillis = maxOf(group.lastActivityUnixMillis, payload.timestampUnixMillis)))
        if (outcome != MessageStore.AppendOutcome.DUPLICATE) onMessage(stored)
    }

    /**
     * A `{"kind":"v2file"}` key message: fetch the opaque chunks and decrypt them straight
     * to a file.
     *
     * Streaming, not [V2BlobClient.downloadBlob] + a whole-file decrypt: peak memory is one
     * chunk regardless of the file's size, and a failed download deletes the partial file so
     * it can never be mistaken for media. A download that fails stores the row anyway, with
     * no `mediaRef` — the message EXISTS and its bytes do not, and those are two different
     * facts.
     */
    private fun receiveMedia(inbound: V2Inbound, key: V2FileKeyMessage) {
        val safeName = key.filename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "file" }
        val out = File(mediaDir, "${inbound.msgId.take(8)}-$safeName")
        val written = blobs.downloadAndDecryptToFile(
            blobId = key.blobId,
            chunkCount = key.chunkCount,
            fileKey = key.fileKey,
            fileNonce = key.fileNonce,
            manifest = key.manifest,
            out = out,
        )
        if (written == null) log("client: media ${key.blobId.take(12)}… could not be fetched or decrypted")

        // A contact card is media bytes with `mediaType: contact`, never a sentinel — so
        // this is the only place it can be recognised, and recognising it is what turns an
        // opaque attachment into a contact the user can message.
        // THE SENDER CHOOSES `mediaType`, SO THE SENDER CHOSE THIS BRANCH. `readText` on a
        // file whose size the sender also chose is an OutOfMemoryError one message away,
        // and the poll thread above used to die of it forever. A contact card is a short
        // JSON object — iOS and Android both emit well under a kilobyte — so anything past
        // this ceiling is not a card, whatever the field claims, and is left as an ordinary
        // attachment rather than read into memory.
        val card = if (
            written != null &&
            key.mediaType == MediaType.CONTACT.wire &&
            out.length() in 1..MAX_CONTACT_CARD_BYTES
        ) {
            runCatching {
                com.oshi.desktop.place.ContactCardPayload.decode(out.readText(Charsets.UTF_8))
            }.getOrNull()
        } else {
            if (written != null && key.mediaType == MediaType.CONTACT.wire) {
                log(
                    "client: an attachment claimed mediaType=contact at ${out.length()} bytes — " +
                        "over the ${MAX_CONTACT_CARD_BYTES}-byte ceiling, so it was NOT parsed as one"
                )
            }
            null
        }
        if (card != null) {
            contacts.seen(card.publicKey, inbound.ts, displayNameHint = card.alias)
            log("client: contact card for ${card.displayName} added")
        }

        val stored = Message(
            id = inbound.msgId,
            conversationId = inbound.from,
            senderAddress = inbound.from,
            recipientAddress = address,
            fromMe = false,
            content = card?.let { "Contact: ${it.displayName} (${it.publicKey})" } ?: key.filename,
            // The field FIRST, the MIME only when it is absent — Android's order
            // (`mediaTypeFromV2:4123-4131`). Defaulting a missing field to DOCUMENT
            // instead of asking the MIME made this client the only one of the three that
            // would file a peer's `image/jpeg` as a document.
            mediaType = key.mediaType?.takeIf { it.isNotBlank() }
                ?.let { MediaType.fromWire(it) }
                ?: MediaType.forMime(key.mime),
            mediaRef = if (written != null) out.absolutePath else null,
            sentAtMs = inbound.ts,
            sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            deliveryStatus = DeliveryStatus.DELIVERED,
            transport = "relay-v2",
            isViewOnce = key.isViewOnce == true,
        )
        val outcome = messages.append(stored)
        contacts.seen(inbound.from, inbound.ts)
        if (outcome != MessageStore.AppendOutcome.DUPLICATE) onMessage(stored)
        if (deliveryReceiptsEnabled) {
            sendPlaintext(inbound.from, DeliveryReceipt.encode(inbound.msgId), storeRow = false)
        }
    }

    /**
     * Enough of a MIME guess for the manifest, and no more.
     *
     * The manifest is AEAD plaintext the receiver reads for a filename and a type hint; a
     * wrong guess costs an icon. `Files.probeContentType` is consulted first because it is
     * the JDK's own answer and is right on every desktop that has a mime database, with a
     * short extension table behind it for the machines that do not.
     */
    private fun probeMime(file: File): String =
        runCatching { java.nio.file.Files.probeContentType(file.toPath()) }.getOrNull()
            ?: when (file.extension.lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "mp4", "mov", "webm" -> "video/mp4"
                // EVERY audio extension this client can produce must be here, and `wav`
                // was the one that was not. `Files.probeContentType` returns null on some
                // Linux JDKs with no shared mime database, and this table is the only
                // thing standing between a voice note and `application/octet-stream` —
                // which `MediaType.forMime` maps to DOCUMENT, which is a "Download" button
                // on the phone instead of a player. That is not a hypothetical: it is
                // exactly the defect PARITY.md row 0.12 records as having already shipped
                // once, for every photo, clip and voice note this client sent.
                "m4a", "mp3", "aac" -> "audio/mp4"
                "wav", "wave" -> "audio/wav"
                "ogg", "opus" -> "audio/ogg"
                "pdf" -> "application/pdf"
                "txt", "md" -> "text/plain"
                else -> "application/octet-stream"
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

    /**
     * `DELETE /v2/account` and then the local wipe — PARITY.md row 0.11, guideline 5.1.1(v).
     *
     * **The server goes FIRST, and a server that did not answer aborts the local half.**
     * The two orders are not equivalent and the wrong one is unrecoverable: the erase is
     * authorised by a signature from the identity's own Ed25519 key, so wiping the vault
     * before the server has answered destroys the only credential that could ever ask
     * again. The bundle, the queued envelopes and the sync ciphertext would then sit on
     * the relay for ever, with nobody left who can delete them — the exact outcome the
     * route exists to prevent. Failing here leaves a usable account and a retry.
     *
     * Returns the RECEIPT rather than a Boolean, for the reason [V2AccountClient] gives:
     * a 207 means some stores were erased and others were unreachable, and the caller has
     * to be able to tell a user which. Collapsing it to success would report a finished
     * deletion while data is still out there.
     */
    fun deleteAccount(): Result<AccountDeletionReceipt> {
        val receipt = account.deleteAccount()
        if (receipt.isFailure) return receipt
        sessions.clear()
        prekeys.clear()
        IdentityStore.erase(vault)
        routerState.clear()
        syncCursor.reset()
        return receipt
    }

    companion object {

        /**
         * The sentence a UI is obliged to show beside any bot composer (row 0.26).
         *
         * One string with one owner, rather than a warning each surface re-invents or
         * quietly omits. The bot lane is the only transport this client speaks that puts
         * the user's words on the wire in the clear.
         */
        const val BOT_CHANNEL_IS_PLAINTEXT: String =
            "Bot messages are NOT end-to-end encrypted. The server can read this, " +
                "and it already knows the group's name and every member."
        /**
         * The biggest thing this client will read into memory because a SENDER said it was
         * a contact card.
         *
         * 64 KiB is roughly a hundred times the largest card iOS or Android emits, so it
         * refuses nothing real, and it is small enough that a hostile peer cannot spend
         * this client's heap by lying about `mediaType`. The number is a CEILING on trust,
         * not a format limit: a legitimate card that somehow grew past it is still
         * delivered, just as an attachment rather than as a contact.
         */
        const val MAX_CONTACT_CARD_BYTES: Long = 64L * 1024

        fun defaultDisplayName(): String =
            System.getenv("OSHI_NAME")?.takeIf { it.isNotBlank() }
                ?: "${System.getProperty("user.name") ?: "OSHI"} (${System.getProperty("os.name")})"
    }
}
