package com.oshi.desktop.app

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.block.BlockPolicy
import com.oshi.desktop.block.BlockedByPeerStore
import com.oshi.desktop.block.BlockedNoticeGate
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
import com.oshi.desktop.i18n.t
import com.oshi.desktop.mesh.MeshNode
import com.oshi.desktop.msg.ControlEvent
import com.oshi.desktop.msg.ControlPayloadRouter
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.DeliveryReceipt
import com.oshi.desktop.msg.MessageActionPayload
import com.oshi.desktop.msg.PeerNickname
import com.oshi.desktop.msg.ProfileUpdateWire
import com.oshi.desktop.msg.ReactionPayload
import com.oshi.desktop.msg.ReadReceipt
import com.oshi.desktop.msg.TypingPayload
import com.oshi.desktop.net.AccountDeletionReceipt
import com.oshi.desktop.net.MessagePushClient
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
import com.oshi.desktop.store.LocalDataKeys
import com.oshi.desktop.store.MediaVault
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.ReceiptPreferences
import com.oshi.desktop.store.MessageStore
import com.oshi.desktop.store.PinnedMessageStore
import com.oshi.desktop.store.OfflineOnlyStore
import com.oshi.desktop.store.ProfileLinksStore
import com.oshi.desktop.msg.PollPayload
import com.oshi.desktop.store.PollTally
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
    /**
     * __SHARED_NICKNAME_2026_09_22__ The LOCAL fallback label (`--name`, `OSHI_NAME`, or
     * "user (os)"). It is never put on the wire as a nickname — only [ownNickname], which
     * the user set on purpose, is. See [displayName].
     */
    displayName: String = defaultDisplayName(),
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    private val fallbackDisplayName: String = displayName

    /**
     * What this account is called on this machine: the nickname set in Settings when there
     * is one, else the launch-time fallback. Read at use, so a rename shows up everywhere
     * (reaction/typing `senderName`, the window title on next draw) without a restart.
     */
    val displayName: String get() = ownNickname ?: fallbackDisplayName

    val vault: KeyVault = KeyVault.open(File(home, KeyVault.FILE_NAME), secretStore, passphrase)
    val identity: DesktopIdentity = IdentityStore.loadOrCreate(vault)

    /** This account's address: the standard padded base64 of its X25519 public key. */
    val address: String get() = identity.userKey

    /**
     * __LOCAL_DATA_AT_REST_2026_09_22__ One vault entry, one HKDF subkey per local store.
     * Contacts, groups, the schedule and attachments are sealed at rest under these; see
     * [LocalDataKeys]. A vault that does not open never gets this far, so no store below can
     * be constructed without its key and read as empty.
     */
    private val localDataRoot: ByteArray = LocalDataKeys.root(vault)

    val contacts = ContactStore(File(home, "contacts.json"), LocalDataKeys.derive(localDataRoot, LocalDataKeys.CONTACTS))
    // __BLOCKED_NOTICE_SWITCH_2026_09_23__ rate-limit stamps for the "you are blocked" notice.
    private val blockedNotice = BlockedNoticeGate(
        File(home, "blocked_notice.json"), LocalDataKeys.derive(localDataRoot, LocalDataKeys.BLOCKED_NOTICE),
    )
    /**
     * __BLOCKED_BY_PEER_2026_09_24__ peers whose 🚫BLOCKED🚫 notice told us they block us: never
     * dialled (no offer, no VoIP push) until they write to us again. See [BlockedByPeerStore].
     */
    val blockedByPeers = BlockedByPeerStore(
        File(home, "blocked_by_peer.json"), LocalDataKeys.derive(localDataRoot, LocalDataKeys.BLOCKED_BY_PEER),
    )
    private val receiptPreferences = ReceiptPreferences(File(home, ReceiptPreferences.FILE_NAME))
    // A distinct random data-encryption key keeps an exported history from becoming
    // readable merely because another vault entry is later repurposed. The vault itself is
    // OS-store/passphrase protected and fails closed before the client is assembled.
    private val messageHistoryKey: ByteArray = vault.getOrCreate(MessageStore.HISTORY_KEY_ACCOUNT) {
        ByteArray(32).also(SecureRandom()::nextBytes)
    }
    val messages = MessageStore(File(home, "messages"), messageHistoryKey)

    /**
     * __DESKTOP_DIRECT_PIN_2026_10_29__ The pinned message of each 1:1 conversation.
     *
     * A small JSON map beside `messages/`, not a row in the log: a pin is one mutable fact
     * per conversation, and both phones persist it exactly this way (iOS in `UserDefaults`
     * under `"pinnedMessages"`, Android in the `pinned_messages` SharedPreferences). It is
     * NOT a local preference — see [PinnedMessageStore] for why that distinction matters.
     */
    val pinnedMessages = PinnedMessageStore(File(home, PinnedMessageStore.FILE_NAME))

    /**
     * __DESKTOP_POLLS_2026_10_29__ Ballots, keyed by poll id. See [PollTally] — they live
     * here and never inside the poll message, which is what both phones do and for the
     * reason iOS records: the payload is the thing both devices re-render from.
     */
    val pollBallots = PollTally(File(home, PollTally.FILE_NAME))

    /**
     * __DESKTOP_OFFLINE_ONLY_2026_10_29__ Conversations restricted to the mesh and the radio.
     * See [OfflineOnlyStore]; the routing rule itself is [OfflineOnlyPayload.internetAllowed].
     */
    val offlineOnly = OfflineOnlyStore(File(home, OfflineOnlyStore.FILE_NAME))

    /**
     * __DESKTOP_PROFILE_LINKS_2026_10_29__ Each contact's shared profile links. See
     * [ProfileLinksStore]; every string in it was chosen by a peer, so nothing is fetched,
     * previewed or opened on its own.
     */
    val contactLinks = ProfileLinksStore(File(home, ProfileLinksStore.FILE_NAME))

    private val prekeys = PrekeyStore(vault)
    private val sessions = SessionStore(vault)
    private val http = V2Http(DesktopV2Signer(identity), serverUrl)

    /** __DESKTOP_REPORT_2026_09_23__ "Signaler" — see [V2ReportClient] for the privacy contract. */
    val reports = com.oshi.desktop.net.V2ReportClient(
        http, { identity.userKey },
        File(home, "reports.json"), LocalDataKeys.derive(localDataRoot, LocalDataKeys.REPORTS),
    )
    @Volatile private var lastReportRetryMs = 0L
    val config = V2ConfigGate(baseUrl = serverUrl, log = log)
    private val keys = V2KeysClient(http, identity, prekeys)
    private val relay = V2MessagesClient(http)
    val blobs = V2BlobClient(http, identity)
    val account = V2AccountClient(http)
    private val routerState = RouterState(File(home, RouterState.FILE_NAME))

    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ This install as one device of its account
     * (ServerPatches/per_device_mailbox/CLIENT_SPEC.md). Inert while `/v2/config.device_mailbox_enabled`
     * is false; it also decides whether this install may publish the ACCOUNT bundle, which is
     * what stops a desktop restored from the phone's recovery key from fighting the phone.
     */
    val deviceMailbox = com.oshi.desktop.net.DeviceMailbox(
        identity = identity,
        vault = vault,
        http = http,
        keys = keys,
        accountPrekeys = prekeys,
        devicePrekeys = PrekeyStore(vault, PrekeyStore.DEVICE_VAULT_ACCOUNT),
        config = config,
        stateFile = File(home, com.oshi.desktop.net.DeviceMailbox.FILE_NAME),
        identityOrigin = { IdentityStore.origin(vault) },
        log = log,
    ).also { http.deviceSigner = it }

    val router = V2Router(identity, config, keys, relay, sessions, prekeys, routerState, log, deviceMailbox)
    val mesh = MeshNode(address, displayName, log)

    /**
     * __DESKTOP_MESSAGE_PUSH_2026_09_23__ The signed generic wake after a v2 send (the relay does
     * not push). See [MessagePushClient]: fire-and-forget, ids only, never content or names.
     */
    private val messagePush = MessagePushClient(serverUrl, DesktopV2Signer(identity), address, log)

    // ------------------------------------------------------------- the wired rows

    /**
     * Row 0.18: receipts, typing, reactions, edit/delete — applied to [messages]; and the
     * 1:1 pin, applied to [pinnedMessages] since 2026-10-29. Before that the router decoded
     * an inbound pin and reported `PIN_NOT_APPLIED` because nothing was passed here.
     */
    private val control = ControlPayloadRouter(messages, pinnedMessages)

    /** Row 0.19: location shares and check-ins. Receive-only, and holds the live-share state. */
    val places = PlaceRouter()

    /** Row 0.17: this account's groups, as the wire JSON an iPhone accepts. */
    val groups = GroupStore(File(home, "groups.json"), LocalDataKeys.derive(localDataRoot, LocalDataKeys.GROUPS))

    // __DEVSYNC_REJOIN_2026_09_23__ a group LEFT on this account (devsync left-groups state) is not
    // re-created by a member's re-share, unless the user rejoined (design §8.4).
    private val groupIngest = GroupIngest(groups) { address }.also { ingest ->
        ingest.refusesGroup = { gid -> runCatching { devSync.refusesGroup(gid) }.getOrDefault(false) }
        ingest.isBlocked = { key -> BlockPolicy.outgoingText(contacts, key) == BlockPolicy.Outbound.REFUSE_BLOCKED }
    }

    /** Row 0.25: the queue. Driven by the poll loop — see [start]. */
    val scheduled = ScheduledMessageStore(
        File(home, "scheduled-messages.json"),
        LocalDataKeys.derive(localDataRoot, LocalDataKeys.SCHEDULED),
    )

    val scheduler = ScheduledMessageRunner(scheduled, ::deliverScheduled)

    /** Where inbound media is written — SEALED ("OSHIMED1", [MediaVault]). Created on first use. */
    val mediaDir: File get() = File(home, "media")

    /**
     * __LOCAL_DATA_AT_REST_2026_09_22__ The attachment vault. Installed process-wide so the
     * window's composables, which open media by path, can decrypt it. At construction: the
     * decrypted-copy scratch dir is swept (whatever a crashed run left), the plaintext files an
     * older build left in `media/` are LISTED synchronously — before this process downloads
     * anything, so nothing half-written can be listed — and sealed in place on a daemon thread.
     */
    val mediaVault: MediaVault = MediaVault(
        mediaDir = File(home, "media"),
        scratchDir = File(home, MediaVault.SCRATCH_DIR_NAME),
        key = LocalDataKeys.derive(localDataRoot, LocalDataKeys.MEDIA),
    ).also { v ->
        MediaVault.install(v)
        runCatching { v.sweepScratch() }
        val legacy = runCatching { v.legacyCandidates() }.getOrDefault(emptyList())
        if (legacy.isNotEmpty()) {
            Thread({
                val r = v.migratePlaintext(legacy)
                log("client: media at rest — sealed ${r.sealed}, failed ${r.failed} (left as they were), skipped ${r.skipped}")
            }, "oshi-media-migration").apply { isDaemon = true; start() }
        }
    }

    private val syncCursor = SyncCursor(File(home, "sync-cursor.json"))

    /**
     * __DEVSYNC_DIRECT_2026_09_22__ Direct own-device sync (docs/OSHI_DEVICE_SYNC_DIRECT.md):
     * LAN + live pass-through relay, nothing stored server-side. Gated by
     * `/v2/config.devsync_enabled` (default off) or the local override; ticked by the poll loop.
     */
    private val devSyncLazy = lazy { com.oshi.desktop.devsync.DesktopDevSync(this, home, log) }
    val devSync: com.oshi.desktop.devsync.DesktopDevSync get() = devSyncLazy.value

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

    /**
     * __CALL_RATING_2026_09_22__ "How was the call quality?" after a finished call.
     *
     * Held here rather than in [ClientCli] so the window can reach the same instance
     * when it grows a call surface — one ask-history, one cooldown, whichever surface
     * the person was using. WHETHER to ask is
     * [com.oshi.messenger.service.CallRatingPolicy], compiled from the Android tree.
     */
    val callRating: com.oshi.desktop.call.CallRatingClient by lazy {
        com.oshi.desktop.call.CallRatingClient(myPublicKey = address)
    }

    // ------------------------------------------------------------------ calls (row 2.1)

    /**
     * The call signalling and experimental desktop audio lane.
     *
     * This client can ring a peer, be rung, answer, decline and hang up. It also opens a
     * microphone, speaker and UDP/ICE leg after acceptance. That is not a claim of general
     * call reachability: there is no TURN fallback and the path is not yet validated between
     * real desktop devices or against phones. A call whose local audio devices or media path
     * cannot open is ended rather than represented as a silent connected call.
     *
     * It rides a SEPARATE server from every other row here — the call server on port 8083,
     * reached through nginx at `/api/call/`, never `/v2/…` (see [CallSignalClient]) — and
     * that server has no authentication at all. The body is sealed; the metadata is not.
     *
     * LAZY for the same reason [sync] is: constructing it mints a device id and derives
     * nothing a client that never places a call should pay for.
     */
    private val callsLazy: Lazy<CallLane> = lazy {
        // __CALL_LOG_AT_REST_2026_09_23__ Every call-lane line also goes to the sealed call
        // log (OSHILOG1, same file format and same redacted export as iOS / Android). Installed
        // here, with the lane, so a client that never places a call never opens one.
        com.oshi.desktop.diag.DesktopCallLog.install(
            home, LocalDataKeys.derive(localDataRoot, LocalDataKeys.CALL_LOG),
        )
        val callLog: (String) -> Unit = { line ->
            log(line)
            com.oshi.desktop.diag.DesktopCallLog.log(line)
        }
        CallLane(
            myAddress = address,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(baseUrl = serverUrl, signer = DesktopV2Signer(identity)),
            deviceId = syncCursor.deviceId(),
            // The SAME ContactStore every other ingress uses, so a blocked peer cannot ring
            // this client — enforcement, not a second flag (PARITY.md 0.21).
            machine = CallStateMachine(address, contacts, syncCursor.deviceId()).also { m ->
                // __BLOCKED_BY_PEER_2026_09_24__ never dial a peer that told us it blocks us.
                m.isBlockedBy = { peer -> blockedByPeers.isBlockedBy(peer) }
            },
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
            log = callLog,
        ).also { lane ->
            lane.onEvent = { event ->
                // Seal the call's last lines now: the process may be quit right after a hang-up.
                if (event is CallLane.CallEvent.Ended) com.oshi.desktop.diag.DesktopCallLog.flush()
                // A blocked caller never rings here; they get the same (rate-limited)
                // notice a blocked message gets, instead of ringing out into nothing.
                if (event is CallLane.CallEvent.Refused &&
                    event.refusal == com.oshi.desktop.call.CallRefusal.BLOCKED) {
                    event.from?.let { noticeBlockedSender(it, null) }
                }
                // __BLOCKED_BY_PEER_2026_09_24__ a peer that rings us no longer blocks us.
                if (event is CallLane.CallEvent.Ringing && event.incoming) blockedByPeers.clear(event.peer)
                onCall(event)
            }
        }
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
        var stored = 0
        // __BOT_E2E_2026_09_23__ `bot-seal-v1` envelopes are opened with the IDENTITY X25519
        // pair (the one behind [address]); legacy ones are read as before. drainBots acks
        // every bot entry, a dropped one included, so nothing cycles.
        botQueue.drainBots(identity.identity.priv, identity.identity.pub) { decoded ->
            val msg = when (decoded) {
                is BotEnvelope.Decoded.Message -> decoded.message
                is BotEnvelope.Decoded.Dropped -> {
                    // Reason only — never content, never the outer placeholder.
                    log("client: bot envelope dropped: ${decoded.reason}")
                    return@drainBots
                }
                is BotEnvelope.Decoded.CallbackAnswer -> {
                    // No inline keyboards on desktop, so no pending callback to answer.
                    log("client: bot callback answer received (${decoded.sealing.transport}); nothing to show")
                    return@drainBots
                }
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
                    // Persists how it was protected, for the honest thread label.
                    transport = msg.sealing.transport,
                )
            )
            if (outcome == MessageStore.AppendOutcome.INSERTED) stored++
            // A duplicate is acked too (by drainBots): the queue entry is still on the
            // server, and leaving it there means re-reading the same post on every poll.
        }.onFailure {
            log("client: bot queue unreachable: ${it.javaClass.simpleName}: ${it.message}")
            return 0
        }
        if (stored > 0) log("client: stored $stored bot message(s)")
        return stored
    }

    // ------------------------------------------------------------------ callbacks

    /** Called after an inbound message has been stored. */
    var onMessage: (Message) -> Unit = {}

    /** Called for mesh traffic, which is NOT a conversation — see the class note. */
    var onMeshTraffic: (String) -> Unit = {}

    /**
     * __DESKTOP_LORA_TRAFFIC_2026_10_29__ What the RADIO just sent and received — iOS's
     * `MeshtasticManager.traffic`, which this client had no equivalent of.
     *
     * Not [onMeshTraffic], which is the LAN mesh and a different thing entirely. This
     * records frame SHAPES only — direction, portnum, byte count, node — never payloads.
     * See [com.oshi.desktop.lora.LoRaTrafficLog] for why that is a rule.
     */
    val loraTraffic = com.oshi.desktop.lora.LoRaTrafficLog()

    /**
     * __DESKTOP_LORA_RADIO_STATE_2026_10_29__ What the node says about ITSELF — the region
     * it is actually on and whether it will transmit. iOS reads both; this client read
     * neither, so the pane could call a radio "attached" when it was never going to put a
     * byte on the air.
     */
    val loraRadioState = com.oshi.desktop.lora.LoRaRadioState()

    /**
     * __DESKTOP_LORA_REFUSAL_2026_10_29__ Why the last radio send did not happen, or null.
     * Android's `lastRefusal`; the pane shows it and the next send clears it.
     */
    @Volatile
    var loraLastRefusal: com.oshi.desktop.lora.LoRaSendRefusal? = null
        private set

    /**
     * The one gate every TRANSMITTING send passes through.
     *
     * Not admin: [RADIO_MUTED][com.oshi.desktop.lora.LoRaSendRefusal.RADIO_MUTED] and
     * [NO_REGION][com.oshi.desktop.lora.LoRaSendRefusal.NO_REGION] are precisely the two
     * states an owner repairs BY sending an admin frame, so refusing admin on those grounds
     * would make an unconfigured radio unconfigurable from the app that spotted it.
     */
    private fun loraRefuseSend(): com.oshi.desktop.lora.LoRaSendRefusal? {
        val why = com.oshi.desktop.lora.LoRaSendRefusal.forSend(
            attached = lora?.isConnected == true,
            state = loraRadioState,
        )
        loraLastRefusal = why
        if (why != null) onLoRa("lora: ${why.reason}")
        return why
    }

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
    private var receiptValues = receiptPreferences.load()
    var deliveryReceiptsEnabled: Boolean
        get() = receiptValues.delivery
        set(value) { receiptValues = receiptValues.copy(delivery = value); receiptPreferences.save(receiptValues) }
    var readReceiptsEnabled: Boolean
        get() = receiptValues.read
        set(value) { receiptValues = receiptValues.copy(read = value); receiptPreferences.save(receiptValues) }

    private val running = AtomicBoolean(false)
    private var timers: ScheduledExecutorService? = null
    private var callTimers: ScheduledExecutorService? = null

    init {
        router.onMessage = { inbound -> receive(inbound) }
        router.onSentCopy = { copy -> storeSentCopy(copy) }
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
                // __MESH_IS_NO_LONGER_OPAQUE_2026_10_28__ PARITY.md row 0.16's last
                // sentence — "the desktop mesh moves OPAQUE payloads and the CLI says so
                // where someone would be tempted to type a real message into it".
                //
                // That was true while this client had no legacy ratchet. It now compiles
                // the phones' own, and the LAN mesh carries the SAME `SecureMessage` JSON
                // as the radio does: Android builds it in `crossPlatformMeshSecureMessage`
                // (`MessageRepository.kt:383`), iOS in `meshWireSafe()`. So the payload was
                // never opaque in its format — only in this client's ability to open it.
                //
                // A payload that is not an envelope (a control frame, an identity exchange,
                // a reaction) simply fails to parse and falls through to the log above, as
                // before. Nothing that used to be visible stops being visible.
                com.oshi.desktop.lora.LoRaSecureMessage
                    .fromWireJson(m.payload.toByteArray(Charsets.UTF_8))
                    ?.let { envelope ->
                        openOshiEnvelope(envelope, transport = "mesh", nodeNum = null) {
                            onMeshTraffic(it)
                        }
                    }
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
                // Re-checked hourly, not once per process: the pool drains while we run, and
                // whether THIS install may refill the account bundle can change (DeviceMailbox).
                if (config.isEnabledCached() && (!published || System.currentTimeMillis() - publishedAt > REPUBLISH_CHECK_MS)) {
                    val wasPublished = published
                    published = router.publishBundleIfNeeded()
                    if (published) publishedAt = System.currentTimeMillis()
                    if (published && !wasPublished) log("client: prekey bundle published — peers can now reach us over V2")
                }
                // __PER_DEVICE_MAILBOX_2026_09_23__ register / keep this device's bundle fresh
                // (a no-op while the flag is off), BEFORE the poll picks device or legacy mode.
                // After the account publish: a brand-new identity is only authorisable on the
                // device routes once its first publish has bound its signing key.
                runCatching { deviceMailbox.tick() }
                    .onFailure { log("client: device mailbox tick failed: ${it.javaClass.simpleName}: ${it.message}") }
                router.poll()
                tick()
                // __DESKTOP_REPORT_2026_09_23__ a report filed offline goes out once the
                // server answers again ("sent automatically" is what the UI promises).
                if (System.currentTimeMillis() - lastReportRetryMs > 10 * 60_000L) {
                    lastReportRetryMs = System.currentTimeMillis()
                    runCatching { reports.retryPending() }
                }
                // __DEVSYNC_DIRECT_2026_09_22__ gate + engine lifecycle (a no-op while the flag is off).
                runCatching { devSync.tick() }.onFailure { log("client: devsync tick failed: ${it.javaClass.simpleName}: ${it.message}") }
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
    @Volatile private var publishedAt = 0L

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
        if (devSyncLazy.isInitialized()) runCatching { devSync.stop() }
        routerState.flush()
    }

    override fun close() {
        stop()
        runCatching { messagePush.close() }
        // Decrypted scratch copies (playback, "open in another app") do not outlive the client.
        runCatching { mediaVault.sweepScratch() }
        MediaVault.uninstall(mediaVault)
    }

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
    fun send(
        peerAddress: String,
        text: String,
        replyTo: com.oshi.desktop.msg.ReplyEnvelope.Quote? = null,
    ): SendOutcome {
        if (ControlPrefix.isControl(text)) return SendOutcome.REFUSED_CONTROL_PAYLOAD
        // __GROUP_PARITY_2026_09_23__ a reply travels in the iOS `💬REPLY💬` envelope, wrapped
        // AFTER the user text was checked (the envelope itself is a sentinel by design).
        // __DESKTOP_LINE_BREAKS_2026_10_29__ Both phones normalise prose before sending and
        // this client did not, so text pasted from Word or a classic-Mac document went out
        // with CR / VT / NEL / U+2028 / U+2029 in it and arrived on Android as one run-on
        // block — while the sender's own window showed the paragraphs. BEFORE the envelope
        // wrap, as on the phones: rewriting raw characters inside JSON is how one stops
        // decoding.
        val body = com.oshi.desktop.msg.MessageText.normalizedForSend(text)
        val wire = replyTo?.let { com.oshi.desktop.msg.ReplyEnvelope.wrap(body, it) } ?: body
        // __SHARED_NICKNAME_2026_09_22__ Read BEFORE the row is appended: "first reply".
        val firstReply = !hasSentUserMessage(peerAddress)
        val outcome = sendPlaintext(peerAddress, wire, storeRow = true)
        if (outcome == SendOutcome.SENT) afterUserSend(peerAddress, firstReply)
        return outcome
    }

    // ------------------------------------------- __SHARED_NICKNAME_2026_09_22__ profile

    /**
     * The nickname the user set for THIS account, sealed in the vault (never a plaintext
     * file — same protection as the identity, on macOS Keychain, Windows DPAPI and Linux
     * Secret Service alike). Null when none was set.
     */
    val ownNickname: String?
        get() = vault.get(OWN_NICKNAME_ACCOUNT)
            ?.let { PeerNickname.sanitize(String(it, Charsets.UTF_8)) }

    /** Peers already asked for their profile in this process — one request per peer per run. */
    private val profileRequested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The phones' privacy gate, ported: a profile goes only to someone we have WRITTEN to at
     * least once (iOS `hasSentUserMessage`, Android `hasOutgoingUserMessage`). Somebody who
     * merely messaged us does not learn our name until we answer.
     */
    fun hasSentUserMessage(peerAddress: String): Boolean =
        runCatching { messages.messages(peerAddress).any { it.fromMe } }.getOrDefault(false)

    /**
     * May this client announce a profile under its identity key? ONLY when the key was
     * minted here ([IdentityStore.Origin.GENERATED]).
     *
     * A desktop restored from a phone's recovery key holds the PHONE'S key. Every field of a
     * profile update is recorded by peers against that key, and "absent = cannot" is
     * deliberate on both phones: `groupEnvelopeV3`/`ratchetV3` absent ⇒ iOS
     * `PeerCapabilityStore.record(…: false)` and Android `PeerCapabilities.record(… false)`
     * REMOVE the capability; `profileImageData` absent ⇒ iOS `processProfileUpdate` sets the
     * presence avatar to nil. This client cannot truthfully claim what the phone advertises,
     * so one broadcast from here would downgrade the phone's sessions and group envelopes
     * with every contact. [IdentityStore.Origin.UNKNOWN] (a vault older than the marker) is
     * treated the same way: not broadcasting is the only answer that can never do that.
     *
     * Receiving and DISPLAYING peers' nicknames is unaffected, and so is the manual
     * "Send to my phone" archive write, which goes to our own devices, not to contacts.
     */
    val mayBroadcastProfile: Boolean
        get() = IdentityStore.origin(vault) == IdentityStore.Origin.GENERATED

    /**
     * Send our `📸PROFILE_UPDATE📸` to one peer, over V2 (the only lane this client has, and
     * the one iOS and Android now try first). Silent by construction: the V2 relay does not
     * push, the prefix is on every receiver's no-push list, and no receiver stores it as a
     * row. Nothing is stored here either. REFUSED (nothing leaves) on a shared identity —
     * see [mayBroadcastProfile]; every automatic path goes through here.
     */
    fun sendProfileUpdate(peerAddress: String, clear: Boolean = false): SendOutcome {
        if (!mayBroadcastProfile) return SendOutcome.REFUSED_CONTROL_PAYLOAD
        return sendPlaintext(peerAddress, ProfileUpdateWire.encode(ownNickname, clear), storeRow = false)
    }

    /**
     * Outcome of [setOwnNickname]: what is stored now, and how many peers were told.
     * [withheld] = stored locally but deliberately NOT broadcast (shared identity).
     */
    data class NicknameUpdate(
        val nickname: String?,
        val changed: Boolean,
        val sent: Int,
        val eligible: Int,
        val withheld: Boolean = false,
    )

    /**
     * Set (or, with null/blank, remove) this account's nickname, then tell every eligible
     * contact. Blocking I/O — call it off the UI thread. A removal is announced once as an
     * explicit empty `displayName`, so receivers drop the old name instead of keeping it.
     */
    fun setOwnNickname(name: String?): NicknameUpdate {
        val clean = PeerNickname.sanitize(name)
        val before = ownNickname
        if (clean == before) return NicknameUpdate(clean, changed = false, sent = 0, eligible = 0)
        if (clean == null) vault.delete(OWN_NICKNAME_ACCOUNT)
        else vault.put(OWN_NICKNAME_ACCOUNT, clean.toByteArray(Charsets.UTF_8))
        if (!mayBroadcastProfile) {
            log("client: nickname stored; NOT broadcast — this identity is shared with a phone (or its origin is unknown)")
            return NicknameUpdate(clean, changed = true, sent = 0, eligible = 0, withheld = true)
        }
        val (sent, eligible) = broadcastProfile(clear = clean == null)
        log("client: nickname ${if (clean == null) "removed" else "set"}; told $sent/$eligible contact(s)")
        return NicknameUpdate(clean, changed = true, sent = sent, eligible = eligible)
    }

    /**
     * __DEVSYNC_PROFILE_2026_09_23__ Adopt the nickname set on ANOTHER device of this account
     * (direct own-device sync, PROFILE record). Stored only: the editing device already told the
     * contacts. Returns true when it changed.
     */
    fun adoptOwnNicknameFromOwnDevice(name: String): Boolean {
        val clean = PeerNickname.sanitize(name) ?: return false
        if (clean == ownNickname) return false
        vault.put(OWN_NICKNAME_ACCOUNT, clean.toByteArray(Charsets.UTF_8))
        return true
    }

    /** Profile to every unblocked contact we have written to. Returns (sent, eligible). */
    fun broadcastProfile(clear: Boolean = false): Pair<Int, Int> {
        if (!mayBroadcastProfile) return 0 to 0
        val targets = contacts.all()
            .filter { !it.blocked && it.address != address && hasSentUserMessage(it.address) }
        var sent = 0
        for (c in targets) {
            if (runCatching { sendProfileUpdate(c.address, clear) }.getOrNull() == SendOutcome.SENT) sent++
        }
        return sent to targets.size
    }

    /**
     * After a message the USER wrote went out: on a first reply, introduce ourselves (the
     * phones do exactly this, `isFirstMessage → sendProfileUpdate`).
     */
    private fun afterUserSend(peerAddress: String, firstReply: Boolean) {
        if (firstReply && ownNickname != null && mayBroadcastProfile) runCatching { sendProfileUpdate(peerAddress) }
    }

    /**
     * After PROSE from a peer whose own name we still do not know: ask once per run
     * (`📸PROFILE_REQUEST📸`, silent). Only once we have written to them — both phones answer
     * a request only for someone they have written to, and it is the same courtesy back.
     * This is how contacts that predate the nickname feature get named without anyone
     * having to rename.
     */
    private fun maybeRequestProfile(peerAddress: String) {
        if (contacts.get(peerAddress)?.sharedNickname != null) return
        // __FIRST_CONTACT_NICKNAME_2026_09_23__ No "have we written to them" gate on ASKING any
        // more: the request discloses nothing of ours, and the peer's own gate (answer only
        // someone THEY wrote to) is satisfied by the very message we are reacting to. With the
        // gate, a stranger's first message — from a sender that did not attach its profile
        // (Android ≤ 1.6.25 when the contact row already existed) — stayed titled by address
        // until we replied.
        if (!profileRequested.add(peerAddress)) return
        runCatching { sendPlaintext(peerAddress, ControlPrefix.PROFILE_REQUEST, storeRow = false) }
    }

    /**
     * Own-device sync of the nickname, through the ONE mechanism the phones already share:
     * the legacy `/api/sync/profile` blob (`userName`). Only meaningful when this computer
     * holds the SAME account as the phone (restored from its recovery key); otherwise the
     * slot is simply this account's own. Manual on purpose — PARITY.md row 0.24 keeps this
     * unauthenticated route out of any automatic loop.
     *
     * Pull: adopt the phone's nickname (and announce it to contacts if it changed).
     */
    fun legacySyncPullNickname(): Result<NicknameUpdate?> =
        legacySync.pull(SyncProtocol.LegacyKind.PROFILE).map { blob ->
            ProfileUpdateWire.readLegacyProfileBlob(blob)?.let { setOwnNickname(it) }
        }

    /** Push: write our nickname into that blob, carrying the phone's avatar and links through. */
    fun legacySyncPushNickname(): Result<Unit> =
        legacySync.pull(SyncProtocol.LegacyKind.PROFILE).mapCatching { existing ->
            legacySync.push(
                SyncProtocol.LegacyKind.PROFILE,
                ProfileUpdateWire.mergeLegacyProfileBlob(existing, ownNickname),
            ).getOrThrow()
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

        // __DESKTOP_RADIO_LANE_2026_10_28__ THE RADIO IS A LANE, NOT A SCREEN.
        //
        // Until now this client's only transport was the relay, so "OSHI works with no
        // internet" was true of a feature inside it and false of the app. The order comes
        // from DesktopTransportPolicy rather than from branches here, so what a diagnostics
        // view lists and what the sender actually does cannot drift — iOS records that
        // drift as the whole reason its version exists.
        //
        // A control payload (profile, receipts, typing) is deliberately NOT given the
        // radio: it is silent traffic that no one is waiting for, and LongFast airtime is
        // 1-2 s a frame. Spending the mesh on a typing ping is how a radio becomes useless
        // for the message behind it.
        val prefs = transportPreferences.load()
        // __DESKTOP_OFFLINE_ONLY_2026_10_29__ The app-wide ban AND this conversation's own
        // switch. Either saying no means no — a per-conversation switch may restrict and
        // never widen, so a conversation set back to normal cannot re-enable a relay the
        // person banned app-wide.
        val internetAllowed = com.oshi.desktop.msg.OfflineOnlyPayload.internetAllowed(
            globalInternetAllowed = !prefs.neverOverInternet,
            conversationIsOfflineOnly = offlineOnly.isOfflineOnly(peerAddress),
        )
        val mayUseRadio = storeRow && loraAttached
        val lanes = com.oshi.desktop.lora.DesktopTransportPolicy.order(
            radioAttached = mayUseRadio,
            relayPossible = config.isEnabledCached(),
            preferRadio = prefs.preferRadio,
            internetAllowed = internetAllowed,
        )

        var usedLane: com.oshi.desktop.lora.DesktopTransportPolicy.Lane? = null
        var outcome = if (lanes.isEmpty()) {
            // Nowhere to go. WAITS — never "failed", and never quietly re-routed onto a
            // relay the person banned.
            if (!internetAllowed && !mayUseRadio) SendOutcome.NO_V2_PATH else SendOutcome.GATE_CLOSED
        } else {
            SendOutcome.NO_V2_PATH
        }
        for (lane in lanes) {
            val sent = when (lane) {
                com.oshi.desktop.lora.DesktopTransportPolicy.Lane.RELAY ->
                    // A stored row is a user message: its other devices get a sent-copy (§3.6).
                    router.sendText(peerAddress, text.toByteArray(Charsets.UTF_8), msgId, selfCopy = storeRow)
                com.oshi.desktop.lora.DesktopTransportPolicy.Lane.RADIO ->
                    loraSendOshiMessage(peerAddress, text, storeRow = false, messageId = msgId)
            }
            if (sent) { usedLane = lane; outcome = SendOutcome.SENT; break }
        }
        // __DESKTOP_MESSAGE_PUSH_2026_09_23__ user-visible rows only; silent control payloads
        // (profile, receipts, typing, group state…) never wake anyone — same catalog as the phones.
        if (storeRow && outcome == SendOutcome.SENT && !ControlPrefix.suppressesPush(text)) {
            messagePush.wakeDirect(peerAddress, msgId)
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
                    transport = when {
                        outcome != SendOutcome.SENT -> "none"
                        usedLane == com.oshi.desktop.lora.DesktopTransportPolicy.Lane.RADIO -> "lora"
                        else -> "relay-v2"
                    },
                )
            )
        }
        contacts.seen(peerAddress, now)
        if (usedLane == com.oshi.desktop.lora.DesktopTransportPolicy.Lane.RADIO && storeRow) {
            armRadioEscalation(peerAddress, msgId, text)
        }
        return outcome
    }

    /**
     * The radio was tried first. If nothing comes back, give the message to the relay.
     *
     * __DESKTOP_RADIO_LANE_2026_10_28__ Port of iOS's `escalateAfterLoRa`, and the reason
     * "prefer my radio over the internet" can be a PREFERENCE rather than a sacrifice:
     * re-spending airtime on a route the mesh could not serve would be pointless, but
     * handing the message to a working network path is not.
     *
     * **Silence is the only signal there is.** OSHI broadcasts, and Meshtastic does not
     * acknowledge a broadcast, so there is nothing to wait FOR except the peer's own
     * delivery receipt arriving by some other path. Ninety seconds is iOS's number and its
     * reason is physical: Meshtastic retries for well under a minute, so a later answer
     * would be for a packet id already reused.
     *
     * Three things are deliberately re-read at expiry rather than captured at send time:
     * whether the message has since been delivered (another transport may have won),
     * whether the relay is reachable, and whether the person still permits the internet.
     * The ban in particular must be re-read — someone who turns it on during those ninety
     * seconds has asked for the message to wait, not to leave a minute later.
     */
    /**
     * __DESKTOP_OFFLINE_ONLY_2026_10_29__ May the relay pick up what the radio could not, for
     * THIS conversation?
     *
     * This is the second of the two places that decide whether the relay may carry something,
     * and the one a per-conversation restriction gets missed in. The failure is silent and
     * total: the message leaves correctly over the radio, nothing acknowledges it, and ninety
     * seconds later the timer hands the SAME message to the relay — breaking the one promise
     * the switch makes completely, in a conversation someone deliberately took off the
     * internet. iOS carries the identical warning on its own version: *"Reading the global
     * here as well would let a conversation marked offline-only be escalated onto the relay
     * anyway."*
     *
     * **Extracted from the timer body on purpose.** Inline, the composition was unreachable
     * from a test: a mutation that dropped the per-conversation half and read only the
     * app-wide ban left the whole suite green, because the only test of it exercised the pure
     * policy function rather than this call site. A decision that cannot be mutated is a
     * decision that is not tested, so it became a function with a name.
     *
     * Both switches are re-read HERE, at expiry, rather than captured when the message was
     * sent — which is what lets a restriction turned on during those ninety seconds be
     * honoured.
     */
    internal fun relayMayPickUpAfterRadio(peerAddress: String, alreadyDelivered: Boolean): Boolean =
        com.oshi.desktop.lora.DesktopTransportPolicy.internetPicksUpAfterRadio(
            relayPossible = config.isEnabledCached(),
            internetAllowed = com.oshi.desktop.msg.OfflineOnlyPayload.internetAllowed(
                globalInternetAllowed = !transportPreferences.load().neverOverInternet,
                conversationIsOfflineOnly = offlineOnly.isOfflineOnly(peerAddress),
            ),
            alreadyDelivered = alreadyDelivered,
        )

    private fun armRadioEscalation(peerAddress: String, msgId: String, text: String) {
        val timer = timers ?: return
        timer.schedule({
            runCatching {
                val row = messages.messages(peerAddress).firstOrNull { it.id == msgId }
                // Delivered by anything at all? Then the radio did its job, or another lane did.
                if (row != null && row.deliveryStatus.rank >= DeliveryStatus.DELIVERED.rank) return@runCatching
                val prefs = transportPreferences.load()
                if (!relayMayPickUpAfterRadio(peerAddress, alreadyDelivered = false)) {
                    onLoRa("radio went unanswered for ${msgId.take(8)}… and the relay may not pick it up — it waits")
                    return@runCatching
                }
                // Same msgId on purpose: one logical message, one id across every transport,
                // so the receiver's cross-transport dedup sees a duplicate rather than a
                // second bubble if the radio copy turns up later.
                if (router.sendText(peerAddress, text.toByteArray(Charsets.UTF_8), msgId, selfCopy = true)) {
                    onLoRa("radio went unanswered for ${msgId.take(8)}… — the internet picked it up")
                } else {
                    onLoRa("radio went unanswered for ${msgId.take(8)}… and the relay could not take it either")
                }
            }
        }, com.oshi.desktop.lora.DesktopTransportPolicy.ACK_WINDOW_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
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
    fun sendFile(
        peerAddress: String,
        file: File,
        mediaType: MediaType? = null,
        /** __VIDEO_NOTE_2026_09_24__ a round video note (docs/VIDEO_NOTE_SPEC.md); null = ordinary. */
        videoNote: com.oshi.desktop.media.VideoNoteWire.Meta? = null,
    ): SendOutcome {
        val mime = if (videoNote != null) com.oshi.desktop.media.VideoNoteWire.MIME else probeMime(file)
        return sendMedia(
            peerAddress = peerAddress,
            // Through the vault: a GIF from GifOutbox is sealed at rest (a user's own file
            // passes through as is). __PLAINTEXT_LEFTOVERS_2026_09_24__
            bytes = mediaVault.openStream(file).use { it.readBytes() },
            filename = file.name,
            mime = mime,
            mediaType = if (videoNote != null) MediaType.VIDEO else mediaType ?: MediaType.forMime(mime),
            localRef = file.absolutePath,
            videoNote = videoNote,
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
        videoNote: com.oshi.desktop.media.VideoNoteWire.Meta? = null,
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
            mediaType = com.oshi.desktop.gif.GifWire.label(mediaType, mime),   // __GIF_PACK_2026_09_23__
            // __VIDEO_NOTE_2026_09_24__ spec §2.A, on the SHARED key message: `true` or absent.
            videoNote = if (videoNote != null && mediaType == MediaType.VIDEO) true else null,
            durationMs = videoNote?.takeIf { mediaType == MediaType.VIDEO }?.wireDurationMs,
        )
        val sent = router.sendText(peerAddress, key.toBytes(), msgId)
        if (sent) messagePush.wakeDirect(peerAddress, msgId)   // __DESKTOP_MESSAGE_PUSH_2026_09_23__
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
                videoNote = videoNote != null && mediaType == MediaType.VIDEO,
                mediaDurationMs = videoNote?.durationMs?.takeIf { mediaType == MediaType.VIDEO },
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
        // __GROUP_E2E_V2_2026_09_23__ spec §5.2: admin, or any member of a collaborative/public group.
        if (!g.isAdmin(address) && !(g.type != GroupType.ADMIN_ONLY && g.isMember(address))) return null
        if (g.isMember(memberAddress)) return g
        val now = System.currentTimeMillis()
        val updated = bumpVersion(
            g.copy(members = g.members + GroupMember(memberAddress, joinedAtUnixMillis = now, isAdmin = false))
        )
        groups.put(updated)
        // __GROUP_E2E_V2_2026_09_23__ spec §5.2: the definition to every member of the NEW roster
        // (the new member learns the group from it), then the optional Android companion to the
        // members who already had the group.
        broadcastGroupUpdate(updated, GroupUpdateWire.encodeDefinitionFramed(updated))
        broadcastGroupUpdate(
            g,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberAdded(updated.groupId, memberAddress),
        )
        return updated
    }

    /** Remove a member locally and tell the group (including the person removed). */
    fun removeGroupMember(groupId: String, memberAddress: String): GroupDefinition? {
        val g = groups.get(groupId) ?: return null
        if (!g.isAdmin(address)) return null
        val remaining = g.members.filterNot { GroupIdentity.sameIdentity(it.publicKey, memberAddress) }
        if (remaining.size == g.members.size) return g
        // __GROUP_E2E_V2_2026_09_23__ spec §5.2: the definition (member gone, added to
        // evictedMemberKeys) to the remaining members AND the removed one; the `member_removed`
        // companion is REQUIRED to the removed member.
        val removed = g.members.first { GroupIdentity.sameIdentity(it.publicKey, memberAddress) }.publicKey
        val updated = bumpVersion(
            g.copy(
                members = remaining,
                evictedMemberKeys = (g.evictedMemberKeys + removed).distinctBy(GroupIdentity::canonicalIdentity),
            )
        )
        groups.put(updated)
        val definition = GroupUpdateWire.encodeDefinitionFramed(updated)
        broadcastGroupUpdate(updated, definition)
        sendPlaintext(removed, definition, storeRow = false)
        sendPlaintext(
            removed,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberRemoved(updated.groupId, removed),
            storeRow = false,
        )
        return updated
    }

    /**
     * __GROUP_E2E_V2_2026_09_23__ Leave a group (spec §5.2): `member_removed` naming OURSELVES to
     * every other member, over v2 1:1 only, then forget the group here and record the leave so a
     * member's re-share cannot walk us back in (devsync C.16 tombstone, carried to own devices).
     */
    fun leaveGroup(groupId: String): Boolean {
        val g = groups.get(groupId) ?: return false
        broadcastGroupUpdate(
            g,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberRemoved(g.groupId, address),
        )
        runCatching { devSync.noteLocalLeave(g.groupId, g.name) }
            .onFailure { log("client: leave of ${g.groupId.take(8)}… not recorded for devsync: ${it.message}") }
        groups.delete(g.groupId)
        return true
    }

    /** Rename a group locally and tell the members. */
    fun renameGroup(groupId: String, name: String): GroupDefinition? {
        val g = groups.get(groupId) ?: return null
        if (!g.isAdmin(address) && !(g.type != GroupType.ADMIN_ONLY && g.isMember(address))) return null
        val updated = bumpVersion(g.copy(name = name))
        groups.put(updated)
        // __GROUP_E2E_V2_2026_09_23__ spec §5.2: the authoritative definition, then the companion.
        broadcastGroupUpdate(updated, GroupUpdateWire.encodeDefinitionFramed(updated))
        broadcastGroupUpdate(
            updated,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeGroupRenamed(updated.groupId, name),
        )
        return updated
    }

    /**
     * Grant or revoke a member's administrator role, then broadcast the authoritative roster.
     *
     * There is no minimal role-change frame shared by the phones: iOS uses a full group
     * definition for this operation. Sending that same frame matters because an admin set is
     * accepted only from someone who was already an admin in the recipient's current roster.
     * The creator key remains an admin, matching the safeguard in both phone clients.
     */
    fun setGroupMemberAdmin(groupId: String, memberAddress: String, isAdmin: Boolean): GroupDefinition? {
        val g = groups.get(groupId) ?: return null
        if (!g.isAdmin(address)) return null
        val member = g.members.firstOrNull { GroupIdentity.sameIdentity(it.publicKey, memberAddress) } ?: return null
        if (!isAdmin && GroupIdentity.sameIdentity(member.publicKey, g.adminPublicKey)) return null
        if (member.isAdmin == isAdmin) return g
        val updated = bumpVersion(
            g.copy(members = g.members.map {
                if (GroupIdentity.sameIdentity(it.publicKey, memberAddress)) it.copy(isAdmin = isAdmin) else it
            })
        )
        groups.put(updated)
        broadcastGroupUpdate(updated, GroupUpdateWire.encodeDefinitionFramed(updated))
        return updated
    }

    /** __GROUP_PARITY_2026_09_23__ iOS's invite link for a group we are in (`inviteLink(inviter:)`). */
    fun groupInviteLink(groupId: String): String? {
        val g = groups.get(groupId) ?: return null
        if (!g.isMember(address)) return null
        return com.oshi.desktop.group.GroupInvite.link(g.groupId, g.name, address)
    }

    enum class JoinOutcome { ALREADY_MEMBER, REQUESTED, NOT_AN_INVITE, NO_INVITER }

    /**
     * __GROUP_PARITY_2026_09_23__ Join from an invite link — iOS `joinGroupFromInvite`
     * (`GroupMessaging.swift:1731-1800`): a stub `{inviter (admin), us}` so the conversation
     * exists at once, then an authenticated `member_sync_request` to the inviter over v2, who
     * adds us and answers with the real definition (GROUP_E2E_V2_SPEC §5.3). A legacy link with no
     * inviter cannot be completed on the v2 channel and is refused rather than half-joined.
     */
    fun joinGroupFromInvite(raw: String): Pair<JoinOutcome, String?> {
        val inv = com.oshi.desktop.group.GroupInvite.parse(raw) ?: return JoinOutcome.NOT_AN_INVITE to null
        groups.get(inv.groupId)?.let { if (it.isMember(address)) return JoinOutcome.ALREADY_MEMBER to it.groupId }
        val inviter = inv.inviter?.takeIf { !GroupIdentity.sameIdentity(it, address) }
            ?: return JoinOutcome.NO_INVITER to inv.groupId
        runCatching { devSync.noteLocalJoin(inv.groupId) }
        val now = System.currentTimeMillis()
        val existing = groups.get(inv.groupId)
        val stub = existing?.copy(
            members = existing.members + GroupMember(publicKey = address, joinedAtUnixMillis = now, isAdmin = false),
            lastActivityUnixMillis = now,
        ) ?: GroupDefinition(
            groupId = inv.groupId,
            name = inv.name ?: t("group_invite_default_name"),
            type = GroupType.COLLABORATIVE, // iOS's default until the full definition arrives
            adminPublicKey = inviter,
            members = listOf(
                GroupMember(publicKey = inviter, joinedAtUnixMillis = now, isAdmin = true),
                GroupMember(publicKey = address, joinedAtUnixMillis = now, isAdmin = false),
            ),
            createdAtUnixMillis = now,
            lastActivityUnixMillis = now,
        )
        groups.put(stub)
        contacts.seen(inviter, now)
        sendPlaintext(
            inviter,
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberSyncRequest(inv.groupId, address),
            storeRow = false,
        )
        return JoinOutcome.REQUESTED to stub.groupId
    }

    /**
     * __GROUP_PARITY_2026_09_23__ May this account change the group's picture? iOS
     * `canChangeGroupPicture`: any member of a collaborative or public group, only an admin
     * of an admin-only one — the same rule [renameGroup] applies to the name.
     */
    fun canEditGroupInfo(groupId: String): Boolean {
        val g = groups.get(groupId) ?: return false
        return g.isAdmin(address) || (g.type != GroupType.ADMIN_ONLY && g.isMember(address))
    }

    /**
     * __GROUP_PARITY_2026_09_23__ Set ([imageBytes] = any readable picture) or remove (null) the
     * group picture, then broadcast the authoritative definition carrying it — iOS
     * `updateGroupPicture` / `removeGroupPicture` (`GroupMessaging.swift:2080-2150`).
     *
     * A REMOVAL does not propagate to the phones, and that is their rule, not a bug here:
     * iOS keeps its local picture whenever an incoming definition carries none
     * (`swift:1075`, "lightweight broadcasts strip picture"), and so does this client's
     * authorizer. iPhone-to-iPhone removal has the same limit.
     */
    fun setGroupPicture(groupId: String, imageBytes: ByteArray?): GroupDefinition? {
        val g = groups.get(groupId) ?: return null
        if (!canEditGroupInfo(groupId)) return null
        val jpeg = imageBytes?.let { com.oshi.desktop.group.GroupPicture.prepare(it) ?: return null }
        val updated = bumpVersion(
            g.copy(
                groupPictureBase64 = jpeg?.let(com.oshi.desktop.group.GroupPicture::encodeBase64),
                groupPictureUpdatedAtUnixMillis = System.currentTimeMillis(),
                groupPictureUpdatedBy = address,
            )
        )
        groups.put(updated)
        broadcastGroupUpdate(updated, GroupUpdateWire.encodeDefinitionFramed(updated))
        return updated
    }

    /**
     * __GROUP_PARITY_2026_09_23__ Mute a group's notifications on THIS device. `isMuted` is a
     * personal preference: never sent as true (the wire always says false, as Android pins
     * it) and re-imposed from the local copy on every ingest, exactly as iOS does (`swift:1028`).
     */
    fun setGroupMuted(groupId: String, muted: Boolean): GroupDefinition? =
        // __GROUP_MUTE_SYNC_2026_09_24__ stamped (mutedAt) so the account's other devices follow
        // through devsync GROUPS; the group-update WIRE still never carries it.
        groups.setMuted(groupId, muted, System.currentTimeMillis())

    fun isGroupMuted(groupId: String): Boolean = groups.get(groupId)?.isMuted == true

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
    fun sendGroupText(
        groupId: String,
        rawText: String,
        replyTo: com.oshi.desktop.msg.ReplyEnvelope.Quote? = null,
        /** __MENTIONS_2026_09_23__ members picked in the composer; kept only if still in the text. */
        mentions: List<com.oshi.desktop.group.MentionWire.Mention> = emptyList(),
    ): GroupSendReport {
        if (ControlPrefix.isControl(rawText)) {
            return GroupSendReport(groupId, 0, 0, emptyList(), refusedControlPayload = true)
        }
        // __GROUP_PARITY_2026_09_23__ same `💬REPLY💬` envelope inside the group body
        // (iOS GroupViews.swift:5328, Android GroupManager.buildReplyEnvelope).
        val text = replyTo?.let { com.oshi.desktop.msg.ReplyEnvelope.wrap(rawText, it) } ?: rawText
        val g = groups.get(groupId) ?: return GroupSendReport(groupId, 0, 0, emptyList())
        // __GROUP_E2E_V2_2026_09_23__ spec §2.2: `id` UPPERCASE (iOS UUID spelling), `senderName` "".
        val msgId = UUID.randomUUID().toString().uppercase(java.util.Locale.US)
        val now = System.currentTimeMillis()
        val sentMentions = com.oshi.desktop.group.MentionWire.stillPresent(rawText, mentions)
            .filter { m -> g.isMember(m.publicKey) }
        val plaintext = GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = msgId,
                groupId = g.groupId,
                senderPublicKey = address,
                body = text,
                timestampUnixMillis = now,
                mentions = sentMentions,
            )
        ).toByteArray(Charsets.UTF_8)

        val report = groupFanOut(g, msgId) { plaintext }
        // __DESKTOP_MESSAGE_PUSH_2026_09_23__ spec §2.5: text wakes the members it reached.
        messagePush.wakeGroup(report.reached, g.groupId, msgId)
        // __PER_DEVICE_MAILBOX_2026_09_23__ once per group message, not once per member (§7.2).
        if (report.sent > 0) runCatching {
            router.sendSelfCopy(com.oshi.desktop.net.SentCopy.conversationGroup(g.groupId), plaintext, msgId)
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
                deliveryStatus = if (report.delivered) DeliveryStatus.SENT else DeliveryStatus.FAILED,
                transport = if (report.sent > 0) "relay-v2" else "none",
                mentions = sentMentions,
            )
        )
        groups.put(g.copy(lastActivityUnixMillis = now))
        return report
    }

    data class GroupSendReport(
        val groupId: String,
        val recipients: Int,
        val sent: Int,
        val skippedBlocked: List<String>,
        val refusedControlPayload: Boolean = false,
        /**
         * __GROUP_E2E_V2_2026_09_23__ spec §6: members with no v2 path (no session, no bundle).
         * They receive NOTHING — there is no legacy/IPFS fallback — and the UI names them
         * (`group.member_must_update`).
         */
        val unreachable: List<String> = emptyList(),
        val messageId: String? = null,
        /** __DESKTOP_MESSAGE_PUSH_2026_09_23__ members a v2 envelope reached (they get the wake). */
        val reached: List<String> = emptyList(),
    ) {
        /** Spec §6: `sent` when at least one member was reached, or there is no other member. */
        val delivered: Boolean get() = sent > 0 || recipients - skippedBlocked.size <= 0
    }

    /**
     * __GROUP_E2E_V2_2026_09_23__ The ONE group content fan-out (spec §1, §2.1, §6, §7.1): one
     * pairwise ratchet envelope per member (per device in device mode — the router does that),
     * `type:"group"` + `groupId`, one app message id. A member with no v2 path gets nothing.
     * [payloadFor] returns the ratchet plaintext for that member (media differs per member:
     * each has its own blob reservation), or null to skip them.
     */
    private fun groupFanOut(g: GroupDefinition, msgId: String, payloadFor: (String) -> ByteArray?): GroupSendReport {
        var sent = 0
        val reached = ArrayList<String>()
        val blocked = ArrayList<String>()
        val unreachable = ArrayList<String>()
        val recipients = GroupFanout.plan(g, address)
        for (member in recipients) {
            // A blocked member is skipped, not silently included. iOS delivers group
            // messages from and to blocked members (PARITY.md 0.21 defect 2); this does not.
            if (BlockPolicy.outgoingText(contacts, member) == BlockPolicy.Outbound.REFUSE_BLOCKED) {
                blocked += member
                continue
            }
            val bytes = runCatching { payloadFor(member) }.getOrNull()
            if (bytes != null && router.sendText(member, bytes, msgId, groupId = g.groupId)) { sent++; reached += member } else unreachable += member
        }
        if (unreachable.isNotEmpty()) {
            log("client: group ${g.groupId.take(8)}… ${unreachable.size} member(s) without a v2 path — nothing sent to them")
        }
        return GroupSendReport(g.groupId, recipients.size, sent, blocked, unreachable = unreachable, messageId = msgId, reached = reached)
    }

    /** Spec §2.3 content body inside a GroupMessage, fanned out; no self copy, no row. */
    private fun sendGroupBody(g: GroupDefinition, body: String): GroupSendReport {
        val msgId = UUID.randomUUID().toString().uppercase(java.util.Locale.US)
        val plaintext = GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = msgId, groupId = g.groupId, senderPublicKey = address,
                body = body, timestampUnixMillis = System.currentTimeMillis(),
            )
        ).toByteArray(Charsets.UTF_8)
        return groupFanOut(g, msgId) { plaintext }
    }

    /** Spec §2.4 system command (edit/delete), fanned out; no self copy, no row. */
    private fun sendGroupSystem(g: GroupDefinition, type: String, data: Map<String, String>): GroupSendReport {
        val msgId = UUID.randomUUID().toString().uppercase(java.util.Locale.US)
        val plaintext = GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = msgId, groupId = g.groupId, senderPublicKey = GroupMessageWire.SYSTEM_SENDER,
                body = "", timestampUnixMillis = System.currentTimeMillis(),
                systemMessageType = type, systemMessageData = data,
            )
        ).toByteArray(Charsets.UTF_8)
        return groupFanOut(g, msgId) { plaintext }
    }

    /** __GROUP_E2E_V2_2026_09_23__ `🔥REACTION🔥` in a group (spec §2.3). Applied locally first. */
    fun sendGroupReaction(groupId: String, messageId: String, emoji: String, adding: Boolean = true): GroupSendReport? {
        val g = groups.get(groupId) ?: return null
        val gid = GroupIdentity.canonicalGroupId(g.groupId)
        val local = messages.messages(gid).firstOrNull { GroupMessageWire.sameMessageId(it.id, messageId) } ?: return null
        val body = ReactionPayload.encode(
            messageId = local.id, emoji = emoji, senderPublicKey = address,
            isAdding = adding, atUnixMillis = System.currentTimeMillis(), senderName = displayName,
        )
        messages.setReaction(gid, local.id, address, if (adding) emoji else null)
        return sendGroupBody(g, body)
    }

    /** __GROUP_E2E_V2_2026_09_23__ `message_edited` (spec §2.4): our own messages only. */
    fun editGroupMessage(groupId: String, messageId: String, newText: String): GroupSendReport? {
        val g = groups.get(groupId) ?: return null
        val gid = GroupIdentity.canonicalGroupId(g.groupId)
        val local = messages.messages(gid).firstOrNull { GroupMessageWire.sameMessageId(it.id, messageId) } ?: return null
        if (!local.fromMe || ControlPrefix.isControl(newText)) return null
        messages.editContent(gid, local.id, newText, System.currentTimeMillis())
        return sendGroupSystem(
            g, GroupMessageWire.SYSTEM_EDITED,
            mapOf("actorPublicKey" to address, "editedMessageId" to local.id, "editedContent" to newText),
        )
    }

    /** __GROUP_E2E_V2_2026_09_23__ `message_deleted` (spec §2.4): ours, or any as an admin. */
    fun deleteGroupMessage(groupId: String, messageId: String): GroupSendReport? {
        val g = groups.get(groupId) ?: return null
        val gid = GroupIdentity.canonicalGroupId(g.groupId)
        val local = messages.messages(gid).firstOrNull { GroupMessageWire.sameMessageId(it.id, messageId) } ?: return null
        if (!local.fromMe && !g.isAdmin(address)) return null
        messages.deleteForEveryone(gid, local.id, System.currentTimeMillis())
        return sendGroupSystem(
            g, GroupMessageWire.SYSTEM_DELETED,
            mapOf("actorPublicKey" to address, "deletedMessageId" to local.id),
        )
    }

    /** __GROUP_E2E_V2_2026_09_23__ `⌨️TYPING⌨️` in a group (spec §2.3/§2.5): skipped over 20 members. */
    fun sendGroupTyping(groupId: String, typing: Boolean): GroupSendReport? {
        val g = groups.get(groupId) ?: return null
        if (g.members.size > 20) return null
        return sendGroupBody(
            g,
            TypingPayload.encode(
                senderPublicKey = address, isTyping = typing,
                atUnixMillis = System.currentTimeMillis(),
                groupId = GroupIdentity.canonicalGroupId(g.groupId), senderName = displayName,
            ),
        )
    }

    /**
     * __GROUP_E2E_V2_2026_09_23__ Group media (spec §4): the file is sealed ONCE (one file key,
     * one nonce, same ciphertext chunks), uploaded as one blob reservation PER MEMBER (the blob
     * store authorises exactly one recipient), and each member's `type:"group"` envelope carries
     * the `v2file` key JSON + `groupMessage` (media stripped). Over the blob cap ⇒ refused.
     * Never inline, never IPFS. No self copy (devsync brings media).
     */
    fun sendGroupFile(
        groupId: String,
        file: File,
        caption: String? = null,
        /** __VIDEO_NOTE_2026_09_24__ spec §2.B: flagged in the v2file JSON AND the GroupMessage. */
        videoNote: com.oshi.desktop.media.VideoNoteWire.Meta? = null,
    ): GroupSendReport? {
        val g = groups.get(groupId) ?: return null
        if (!config.isEnabledCached()) return null
        val bytes = mediaVault.openStream(file).use { it.readBytes() }   // sealed GIF or a user's own file
        if (bytes.isEmpty() || bytes.size > V2BlobClient.MAX_PLAINTEXT_BYTES) return null
        val mime = if (videoNote != null) com.oshi.desktop.media.VideoNoteWire.MIME else probeMime(file)
        val mediaType = MediaType.forMime(mime)
        val note = videoNote?.takeIf { mediaType == MediaType.VIDEO }
        val groupType = when (mediaType) {
            MediaType.IMAGE -> GroupMessageWire.GroupMediaType.PHOTO
            MediaType.VIDEO -> GroupMessageWire.GroupMediaType.VIDEO
            MediaType.AUDIO -> GroupMessageWire.GroupMediaType.AUDIO
            MediaType.CONTACT -> GroupMessageWire.GroupMediaType.CONTACT
            else -> GroupMessageWire.GroupMediaType.DOCUMENT
        }
        val msgId = UUID.randomUUID().toString().uppercase(java.util.Locale.US)
        val now = System.currentTimeMillis()
        val groupMessage = GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = msgId, groupId = g.groupId, senderPublicKey = address,
                body = "", timestampUnixMillis = now, mediaType = groupType,
                plaintextContent = caption?.takeIf { it.isNotEmpty() }, mediaFileName = file.name,
                videoNote = note,
            )
        )
        val enc = OSHICryptoV2.encryptFile(bytes, file.name, mime)
        val report = groupFanOut(g, msgId) { member ->
            if (!router.ensureSession(member)) return@groupFanOut null
            val blobId = blobs.uploadBlob(member, enc.chunks) ?: return@groupFanOut null
            V2FileKeyMessage(
                blobId = blobId, fileKey = enc.fileKey, fileNonce = enc.fileNonce,
                chunkCount = enc.chunks.size, manifest = enc.manifest, filename = file.name,
                mime = mime, mediaType = groupType.raw, groupMessage = groupMessage,
                // __VIDEO_NOTE_2026_09_24__ spec §2.B: in the key message AND in the GroupMessage.
                videoNote = if (note != null) true else null, durationMs = note?.wireDurationMs,
            ).toBytes()
        }
        messagePush.wakeGroup(report.reached, g.groupId, msgId)   // __DESKTOP_MESSAGE_PUSH_2026_09_23__ §2.5 media
        messages.append(
            Message(
                id = msgId,
                conversationId = GroupIdentity.canonicalGroupId(g.groupId),
                senderAddress = address,
                recipientAddress = g.groupId,
                fromMe = true,
                content = caption?.takeIf { it.isNotEmpty() } ?: file.name,
                mediaType = mediaType,
                mediaRef = file.absolutePath,
                sentAtMs = now,
                sentAtSource = TimestampSource.LOCAL_CLOCK,
                deliveryStatus = if (report.delivered) DeliveryStatus.SENT else DeliveryStatus.FAILED,
                transport = if (report.sent > 0) "relay-v2" else "none",
                videoNote = note != null,
                mediaDurationMs = note?.durationMs,
            )
        )
        groups.put(g.copy(lastActivityUnixMillis = now))
        return report
    }

    private fun bumpVersion(g: GroupDefinition): GroupDefinition = g.copy(
        // __GROUP_E2E_V2_2026_09_23__ spec §5.3: stateVersion is REQUIRED on every definition we
        // emit (local + 1, absent = 0). Receivers still skip the check when THEIR copy has none.
        stateVersion = (g.stateVersion ?: 0) + 1,
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
        storedSyncRefusal()?.let { return Result.failure(it) }
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

    /**
     * __ANDROID_EXACT_SYNC_2026_10_29__ The legacy `/api/sync` contact archive, emitted the
     * way the phones emit it — full records, `isBlocked` included.
     *
     * This used to push an ALIAS MAP ONLY, deliberately, so that a sync could never move a
     * block in either direction. That was a divergence from both phones, and the owner of
     * this project chose exact parity on 2026-10-29 after being shown what it costs:
     * blocked contacts are now omitted from the payload and every emitted row carries
     * `isBlocked=false`, so **a sync can unblock someone on the receiving device**. See
     * [ContactSyncRecord.exportAllAndroidExact] for the line-by-line mirror and
     * `PARITY.md` row 0.24.
     *
     * [ContactSyncRecord.assertBlocksRepresented] is deliberately NOT called here: its whole
     * purpose is to refuse this payload shape.
     */
    fun legacySyncPush(): Result<Unit> =
        storedSyncRefusal()?.let { Result.failure(it) }
            ?: run {
                val now = System.currentTimeMillis()
                legacySync.push(
                    SyncProtocol.LegacyKind.CONTACTS,
                    ContactSyncRecord.encodePayload(
                        ContactSyncRecord.exportAllAndroidExact(contacts, now),
                        now,
                        syncCursor.deviceId(),
                    ),
                )
            }

    /**
     * __DEVSYNC_DIRECT_2026_09_22__ Once direct device sync is on for this account, nothing is
     * uploaded to a stored-sync route any more (design D8): the V2 archive and the legacy
     * `/api/sync` mirror refuse locally, before any byte leaves.
     */
    private fun storedSyncRefusal(): Throwable? =
        if (runCatching { devSync.enabled }.getOrDefault(false))
            IllegalStateException("stored sync is off: this account uses direct device sync (More → Linked devices)")
        else null

    /**
     * __ANDROID_EXACT_SYNC_2026_10_29__ Apply the legacy contact archive the way the phones
     * apply it: last-writer-wins on the record's own stamp, block decisions included.
     *
     * This used to be gap-fill only — it never overwrote a local alias and never touched a
     * block. It now goes through [ContactSyncRecord.applyTo], whose rule 3 honours a record
     * that CARRIES a block decision, which after the push change above means an incoming
     * `isBlocked=false` under a winning stamp UNBLOCKS a locally blocked contact. That is
     * the phones' behaviour and it is the behaviour that was chosen; it is written down
     * here rather than discovered later.
     *
     * @return rows inserted + rows updated, as the CLI reports.
     */
    fun legacySyncPull(): Result<Int> =
        legacySync.pull(SyncProtocol.LegacyKind.CONTACTS).map { body ->
            if (body == null) 0
            else {
                val outcome = ContactSyncRecord.applyTo(
                    contacts,
                    ContactSyncRecord.parsePayload(body),
                    System.currentTimeMillis(),
                )
                outcome.inserted + outcome.updated
            }
        }

    // ------------------------------------------------------------ row 0.27, LoRa

    /**
     * __DESKTOP_LORA_MANUAL_LINK_2026_10_28__ The bindings OUTLIVE the process now.
     *
     * [com.oshi.desktop.lora.LoRaNodeBindings] had `snapshot()`/`restore()` and no caller
     * for either, so every binding died at exit. Survivable for a learned one, which the
     * next envelope re-proves; not for a manual link, which a person would otherwise have
     * to re-make on every launch.
     */
    /** __DESKTOP_RADIO_LANE_2026_10_28__ The two radio switches. See DesktopTransportPolicy. */
    private val transportPreferences = com.oshi.desktop.store.TransportPreferences(
        File(home, com.oshi.desktop.store.TransportPreferences.FILE_NAME)
    )

    /** What the Radio screen shows and writes. */
    fun transportPrefs(): com.oshi.desktop.store.TransportPreferences.Values = transportPreferences.load()

    fun setTransportPrefs(values: com.oshi.desktop.store.TransportPreferences.Values) {
        transportPreferences.save(values)
    }

    private val loraBindingStore = com.oshi.desktop.store.LoRaNodeBindingStore(
        File(home, com.oshi.desktop.store.LoRaNodeBindingStore.FILE_NAME),
        LocalDataKeys.derive(localDataRoot, LocalDataKeys.LORA_BINDINGS),
    )

    private val loraBindings = com.oshi.desktop.lora.LoRaNodeBindings().also {
        it.restore(loraBindingStore.load())
    }

    private val loraInbound = com.oshi.desktop.lora.LoRaInbound(loraBindings)

    /**
     * __RATCHET_IS_SHARED_SOURCE_2026_10_28__ PARITY.md row 0.16, answered.
     *
     * The legacy Double Ratchet — the phones' own file, compiled by this build, not a
     * second implementation of it. This is what turns an OSHI↔OSHI envelope over LoRa from
     * "reported and never opened" into a message.
     *
     * Sessions are loaded once here and persisted after every send and every receive, for
     * the reason [com.oshi.desktop.store.RatchetSessionStore] states: a chain that advanced
     * in memory but not on disk re-derives a message key that is already on the wire.
     */
    private val ratchetSessionStore = com.oshi.desktop.store.RatchetSessionStore(
        File(home, com.oshi.desktop.store.RatchetSessionStore.FILE_NAME),
        LocalDataKeys.derive(localDataRoot, LocalDataKeys.RATCHET_SESSIONS),
    )

    private val legacyEnvelope = com.oshi.desktop.crypto.LegacyRatchetEnvelope(
        ourPrivateKey = identity.identity.priv,
        ourPublicKeyBase64 = address,
    ).also { ratchetSessionStore.load(it.ratchet) }
    @Volatile private var lora: com.oshi.desktop.lora.LoRaLink? = null

    val loraAttached: Boolean get() = lora?.isConnected == true

    /** Which nodes the attached radio has heard — the header's "LoRa N". */
    private val loraRoster = com.oshi.desktop.lora.LoRaNodeRoster()

    /** Other LoRa nodes heard within Meshtastic's two-hour "online" window. */
    fun loraOnlineNodes(nowMs: Long = System.currentTimeMillis()): Int = loraRoster.onlineCount(nowMs)

    /** LAN mesh peers currently known to [mesh] — the header's mesh count. */
    val meshPeerCount: Int get() = mesh.peers().size

    /** The same peers by display name, for the list behind the header badge. */
    val meshPeerNames: List<String> get() = mesh.peers().map { it.displayName }

    /**
     * __PEERS_ARE_A_SCREEN_2026_10_28__ Every LAN peer, flattened for the Peers screen —
     * PARITY.md row 1.3's "no peers/mesh SCREEN".
     *
     * `connected` and `dialable` are deliberately separate facts. A peer that DIALLED US
     * has no dialable port — the socket's remote port is its ephemeral source port, not the
     * one it listens on, and `MeshNode` records PORT_UNKNOWN rather than guessing — so it
     * can reach us while we cannot re-open a connection to it. Collapsing the two would
     * mislead at exactly the moment someone opens this screen to find out why a message did
     * not arrive.
     */
    fun meshPeerRows(): List<com.oshi.desktop.ui.components.PeerRow> {
        val routes = mesh.routes()
        return mesh.peers().map { p ->
            com.oshi.desktop.ui.components.PeerRow(
                publicKey = p.publicKey,
                displayName = p.displayName,
                platform = p.platform,
                address = if (p.portIsDialable) "${p.host}:${p.port}" else p.host,
                connected = mesh.isConnectedTo(p.publicKey),
                dialable = p.portIsDialable,
                hopCount = routes[p.publicKey]?.hopCount,
            )
        }
    }

    /** __PEERS_ARE_A_SCREEN_2026_10_28__ Hang up on one peer. Not a block — see BlockPolicy. */
    fun meshDisconnect(publicKey: String): Boolean = mesh.disconnectPeer(publicKey)

    /** Is this machine announcing itself and looking for others? */
    val meshIsDiscovering: Boolean get() = mesh.isDiscovering

    /** Turn discovery on or off; returns the state after the call. */
    /** __MESH_OFF_IS_A_SENTENCE_2026_10_29__ Is the LAN mesh running in this process? */
    val meshRunning: Boolean get() = mesh.isRunning

    fun meshSetDiscovery(enabled: Boolean): Boolean = mesh.setDiscoveryEnabled(enabled)

    /** Dial a peer whose address discovery cannot see. Identity is still checked on connect. */
    fun meshConnectByHand(host: String, port: Int): Boolean {
        if (host.isBlank() || port !in 1..65_535) return false
        return runCatching { mesh.connectToPeer(host, port, expectedKey = null) }.isSuccess
    }

    /** The nodes behind the header's "LoRa N", named — the LoRa half of that same list. */
    fun loraOnlineNodeList(nowMs: Long = System.currentTimeMillis()): List<com.oshi.desktop.lora.LoRaNodeRoster.Node> =
        if (loraAttached) loraRoster.onlineNodes(nowMs) else emptyList()

    /**
     * OUR OWN radio's node number, or 0 before the handshake has delivered `my_info`.
     *
     * Exposed because "the reach badge excludes our own radio" is a claim about a specific
     * number, and a test that cannot name that number can only re-assert the roster's
     * implementation back at itself.
     */
    fun loraMyNodeNumber(): UInt = loraRoster.myNodeNumOrZero()

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
    fun loraAttach(host: String, port: Int = com.oshi.desktop.lora.LoRaAttach.TCP_PORT): Boolean {
        val endpoint = com.oshi.desktop.lora.LoRaAttach.tcpEndpoint(host, port)
        if (endpoint == null) {
            // Do not include the raw value here: this callback commonly feeds the terminal,
            // where a pasted control character could otherwise alter the diagnostic output.
            onLoRa("lora: refused invalid TCP endpoint")
            return false
        }
        loraDetach()
        val link = com.oshi.desktop.lora.LoRaLink(
            host = endpoint.host,
            port = endpoint.port,
            onFromRadio = { fromRadio ->
                loraTraffic.observe("←", fromRadio)
                loraRadioState.observeFromRadio(fromRadio)
                onFromRadio(fromRadio)
            },
            log = { log("lora: $it"); onLoRa(it) },
        )
        link.onToRadio = { loraTraffic.observe("→", it) }
        lora = link
        link.start()
        return true
    }

    /**
     * Attach to a Meshtastic node over **USB serial** — the transport a person has when the
     * radio is plugged into this machine, and the macOS/Linux counterpart of the phones' BLE.
     *
     * Same protobufs, same [com.oshi.desktop.lora.LoRaAttach.StreamFraming], same
     * [onFromRadio]: only the bytes' route into the process differs. See
     * [com.oshi.desktop.lora.LoRaSerial] for why this needs no dependency, and
     * [com.oshi.desktop.lora.LoRaChannel.Serial] for the open-then-configure order.
     *
     * The same limitation [loraAttach] prints applies here and is not softened by the
     * transport being nicer: stock Meshtastic TEXT is readable and quarantined, an
     * OSHI↔OSHI envelope is still sealed with the legacy Double Ratchet this client does
     * not implement (rows 0.16 / 0.27).
     */
    fun loraAttachSerial(device: String, baud: Int = com.oshi.desktop.lora.LoRaSerial.DEFAULT_BAUD): Boolean {
        if (!com.oshi.desktop.lora.LoRaSerial.isSupported()) {
            onLoRa("lora: serial attach needs macOS or Linux — use /lora attach <host> on Windows")
            return false
        }
        val path = com.oshi.desktop.lora.LoRaSerial.devicePath(device)
        if (path == null) {
            // As in loraAttach: never echo the rejected value into a sink that feeds a terminal.
            onLoRa("lora: refused invalid serial device path")
            return false
        }
        loraDetach()
        val link = com.oshi.desktop.lora.LoRaLink(
            channel = com.oshi.desktop.lora.LoRaChannel.Serial(path, baud),
            onFromRadio = { fromRadio ->
                loraTraffic.observe("←", fromRadio)
                loraRadioState.observeFromRadio(fromRadio)
                onFromRadio(fromRadio)
            },
            log = { log("lora: $it"); onLoRa(it) },
        )
        link.onToRadio = { loraTraffic.observe("→", it) }
        lora = link
        link.start()
        return true
    }

    /**
     * **One person, one conversation**: tell this client that a radio node is a contact.
     *
     * The answer to "my own Mac's radio made a second conversation". A node that only ever
     * sends stock Meshtastic text can never PROVE an OSHI identity — proof means an
     * envelope that decrypted, and there is no envelope on that lane — so without this the
     * `lora!<nodehex>` thread stays separate for ever.
     *
     * What the person takes on is in [com.oshi.desktop.lora.LoRaNodeBindings.link], and the
     * caller must show it: the text is NOT authenticated, so linking means text claiming
     * this node number lands in that contact's conversation on the strength of the node
     * number alone. Refused for a key that is not an identity key, for a node whose
     * evidence already contradicted itself, and — never silently — for a node the ratchet
     * has already bound to someone else.
     */
    fun loraLinkNode(node: UInt, address: String): com.oshi.desktop.lora.LoRaNodeBindings.LearnResult {
        val result = loraBindings.link(node, address, System.currentTimeMillis())
        if (result == com.oshi.desktop.lora.LoRaNodeBindings.LearnResult.BOUND) {
            loraBindingStore.save(loraBindings.snapshot())
        }
        return result
    }

    /**
     * Send MEDIA over the radio — end-to-end encrypted, opt-in, and tiny.
     *
     * __DESKTOP_LORA_MEDIA_2026_10_28__ The bytes do not ride the envelope's own fields:
     * the wire form strips the plaintext sidecar, so the media goes INSIDE the ciphertext
     * as the JSON wrapper both phones read ([com.oshi.desktop.lora.LoRaMediaWire]).
     *
     * Gated on the preference, which defaults OFF exactly as iOS's does. Refused above the
     * frame budget with the real number, never truncated — a truncated base64 string
     * decodes to nothing on the far side and the recipient sees an empty bubble with no
     * error anywhere.
     */
    fun loraSendMedia(
        recipient: String,
        bytes: ByteArray,
        type: com.oshi.desktop.store.MediaType,
        fileName: String? = null,
    ): Boolean {
        if (loraRefuseSend() != null) return false
        if (!transportPreferences.load().mediaOverLoRa) {
            // Recorded as a refusal too, so the pane can say WHICH of the five reasons it
            // was rather than only that something went wrong.
            loraLastRefusal = com.oshi.desktop.lora.LoRaSendRefusal.CARRIES_MEDIA
            onLoRa("lora: media over the radio is off — turn it on in Radio if you want it")
            return false
        }
        val ceiling = com.oshi.desktop.lora.LoRaMediaWire.maxMediaBytes()
        if (bytes.size > ceiling) {
            onLoRa(
                "lora: that is ${bytes.size} bytes and the radio carries about $ceiling — " +
                    "refused, not truncated. LoRa media is a thumbnail, not a photograph."
            )
            return false
        }
        val wrapper = com.oshi.desktop.lora.LoRaMediaWire.encode(
            com.oshi.desktop.lora.LoRaMediaWire.Media(type, bytes, fileName)
        )
        return loraSendOshiMessage(recipient, wrapper)
    }

    /** Undo a manual link; the thread goes back to `lora!<nodehex>`. Never drops proof. */
    fun loraUnlinkNode(node: UInt): Boolean {
        val removed = loraBindings.unlink(node)
        if (removed) loraBindingStore.save(loraBindings.snapshot())
        return removed
    }

    /** Node key (8 hex) → the identity a person attached to it. */
    fun loraManualLinks(): Map<String, String> = loraBindings.manualLinks()

    /**
     * The identity this node PROVED, by sending an envelope that decrypted — never one a
     * person asserted. Distinct from [loraLinkedContact] on purpose: the two must not be
     * conflated anywhere, least of all in a test.
     */
    fun loraProvedBinding(node: UInt): String? = loraBindings.boundKey(node)

    /** The identity this node's raw text is filed under, for a row in the UI. */
    fun loraLinkedContact(node: UInt): String? = loraBindings.manualKey(node)

    /**
     * Send an OSHI message over the radio — the SEND half of PARITY.md row 0.27.
     *
     * __DESKTOP_LORA_OSHI_SEND_2026_10_28__ With the receive half landed, this client could
     * read a message off the air and not answer it, which is a worse place to stop than
     * either end of it. This is the counterpart of iOS's
     * `MeshtasticManager.sendSecureMessage` (`OSHI/MeshtasticManager.swift:651`).
     *
     * ============================================================ WHAT GOES ON THE AIR
     *
     * The same envelope the relay and the LAN mesh carry — ratchet inside, static-static
     * HKDF + AES-GCM outside — serialised with [com.oshi.desktop.lora.LoRaSecureMessage]'s
     * wire form and chunked behind the 8-byte `"OM"` header onto portnum 256. Nothing here
     * is LoRa-specific except the chunking: a phone reassembles these frames and hands the
     * result to the same pipeline as any other mesh message.
     *
     * **It is BROADCAST**, as iOS broadcasts. Every OSHI radio in range receives the
     * ciphertext and each decides whether it is the addressee by comparing
     * `recipientPublicKey` to its own. That is a filter, not routing — confidentiality
     * rests entirely on the ratchet, because the Meshtastic channel underneath is the
     * default LongFast one whose key is public. `want_ack` is therefore false: Meshtastic
     * does not acknowledge a broadcast, so asking would raise a flag nobody answers.
     *
     * ============================================================ PERSIST BEFORE THE BYTES
     * LEAVE, AND REFUSE IF THAT FAILS
     *
     * `seal` advances the sending chain IN MEMORY. If the write does not land and the
     * process dies, the next send re-derives a message key that is already on the air — an
     * AES-256-GCM key reused across two plaintexts. So the save happens BEFORE a single
     * frame is written, and a failed save ABORTS the send rather than being logged past.
     * Android states the same rule at its own call site and deliberately does not catch it.
     *
     * @return false, with a reason on [onLoRa], when there is no radio, no identity key for
     *         the recipient, the ratchet state could not be persisted, or the envelope does
     *         not fit the 12-frame budget. Over the budget is REFUSED, never truncated.
     */
    @JvmOverloads
    fun loraSendOshiMessage(
        recipient: String,
        text: String,
        /** False when [sendPlaintext] already owns the row — it stores one for every lane. */
        storeRow: Boolean = true,
        /** The app-level id, so ONE logical message keeps ONE id across every transport. */
        messageId: String = UUID.randomUUID().toString(),
    ): Boolean {
        // __DESKTOP_LORA_REFUSAL_2026_10_29__ Before anything is sealed: a muted radio or
        // one with no region takes the frame and transmits nothing.
        if (loraRefuseSend() != null) return false
        val link = lora
        if (link == null || !link.isConnected) {
            onLoRa("lora: no radio attached")
            return false
        }
        if (text.isEmpty()) return false

        val recipientKey = com.oshi.desktop.crypto.LegacyRatchetEnvelope.canonicalKey(recipient)
        val sealed = try {
            legacyEnvelope.seal(text, recipientKey)
        } catch (e: Exception) {
            onLoRa("lora: could not seal for ${recipientKey.take(12)}…: ${e.message}")
            return false
        }

        // BEFORE the air. See the doc: a chain that advanced in memory but not on disk is
        // a key-reuse waiting for the next process start.
        try {
            ratchetSessionStore.save(legacyEnvelope.ratchet)
        } catch (e: Exception) {
            onLoRa("lora: REFUSING to send — ratchet state could not be saved (${e.message})")
            return false
        }

        val now = System.currentTimeMillis()
        val envelope = com.oshi.desktop.lora.LoRaSecureMessage(
            id = messageId,
            senderAddress = address,
            recipientAddress = recipientKey,
            encryptedContent = com.oshi.desktop.lora.LoRaEncryptedContent(
                ciphertext = sealed.getString("ciphertext"),
                signature = sealed.getString("signature"),
                senderPublicKey = address,
                unixMillis = now,
            ),
            unixMillis = now,
            isRead = false,
            deliveryStatus = com.oshi.desktop.lora.LoRaSecureMessage.STATUS_SENT,
            senderPublicKey = address,
            recipientPublicKey = recipientKey,
        )

        val msgId = com.oshi.desktop.lora.LoRaProto.randomPacketId()
        val frames = com.oshi.desktop.lora.LoRaFrame.frames(msgId, envelope.toWireJson())
        if (frames == null) {
            val bytes = envelope.toWireJson().size
            onLoRa(
                "lora: this message is $bytes bytes of envelope, over the " +
                    "${com.oshi.desktop.lora.LoRaFrame.MAX_SEND_BYTES}-byte radio budget " +
                    "(${com.oshi.desktop.lora.LoRaFrame.MAX_CHUNKS} frames) — refused, not truncated"
            )
            return false
        }

        var written = 0
        for (frame in frames) {
            val packet = com.oshi.desktop.lora.LoRaProto.toRadioPacket(
                to = com.oshi.desktop.lora.LoRaProto.BROADCAST_ADDR,
                portnum = com.oshi.desktop.lora.LoRaProto.PORT_OSHI,
                payload = frame,
                // Never on a broadcast. See the doc.
                wantAck = false,
                packetId = com.oshi.desktop.lora.LoRaProto.randomPacketId(),
            )
            if (!link.send(packet)) break
            written++
        }
        if (written != frames.size) {
            onLoRa("lora: only $written of ${frames.size} frames left this process — the message is incomplete on the air")
            return false
        }

        if (!storeRow) {
            onLoRa("OSHI message handed to the radio in ${frames.size} frame(s) — encrypted, broadcast, unacknowledged")
            return true
        }
        val stored = Message(
            id = messageId,
            conversationId = recipientKey,
            senderAddress = address,
            recipientAddress = recipientKey,
            fromMe = true,
            content = text,
            sentAtMs = now,
            sentAtSource = TimestampSource.LOCAL_CLOCK,
            // SENT, never DELIVERED: the bytes left this process over a broadcast nobody
            // acknowledges. Claiming delivery here is the mistake the phones already made
            // once — handing frames to a radio counted as delivery, so nothing ever fell
            // back to the internet.
            deliveryStatus = DeliveryStatus.SENT,
            transport = "lora",
        )
        messages.append(stored)
        onMessage(stored)
        onLoRa("OSHI message handed to the radio in ${frames.size} frame(s) — encrypted, broadcast, unacknowledged")
        return true
    }

    /**
     * Write the node's region, modem preset and hop limit — PARITY.md row 0.27's last gap.
     *
     * __DESKTOP_LORA_ADMIN_2026_10_28__ A node from the flasher boots at `region=UNSET` and
     * transmits NOTHING while reporting itself connected, so this is the step that makes a
     * new radio usable. It is also the step that can silence a working one: see
     * [com.oshi.desktop.lora.LoRaAdmin] for why every field is written explicitly and why
     * the AdminMessage field number is 34 and not 33.
     *
     * Addressed to OUR OWN node, never a peer. `want_ack` is true because this IS a unicast
     * — to the radio on the other end of the cable — and an acknowledgement is the only
     * sign the node took it.
     */
    fun loraSetRegion(
        region: com.oshi.desktop.lora.LoRaAdmin.Region,
        preset: com.oshi.desktop.lora.LoRaAdmin.ModemPreset = com.oshi.desktop.lora.LoRaAdmin.ModemPreset.LONG_FAST,
        hopLimit: Long = com.oshi.desktop.lora.LoRaAdmin.DEFAULT_HOP_LIMIT,
    ): Boolean {
        val frame = com.oshi.desktop.lora.LoRaAdmin.setConfigFrame(region, preset, hopLimit)
        if (frame == null) {
            onLoRa("lora: refusing to write region UNSET — that is the state this rescues a radio FROM")
            return false
        }
        return sendAdmin(frame, "region ${region.label}, ${preset.label}, hop limit $hopLimit")
    }

    /**
     * Put the node on a channel — "which mesh am I on".
     *
     * A blank name with the default key IS the stock LongFast channel, so blank is not an
     * error: it is how "put me back on the normal mesh" is reachable. A psk that is not a
     * key the firmware accepts is refused rather than silently replaced, because replacing
     * it would quietly move the radio onto a different mesh from the one asked for.
     */
    fun loraSetChannel(name: String, pskRaw: String = ""): Boolean {
        val psk = com.oshi.desktop.lora.LoRaAdmin.Channel.pskOrNull(pskRaw)
        if (psk == null) {
            onLoRa("lora: that is not a channel key — it must be base64 of 1, 16 or 32 bytes")
            return false
        }
        val clamped = com.oshi.desktop.lora.LoRaAdmin.Channel.clampName(name)
        val frame = com.oshi.desktop.lora.LoRaAdmin.setChannelFrame(clamped, psk)
        val shown = clamped.ifEmpty { com.oshi.desktop.lora.LoRaAdmin.Channel.DEFAULT_NAME }
        return sendAdmin(frame, "channel $shown")
    }

    /**
     * One admin frame to our own node.
     *
     * Refuses while the handshake has not produced `my_node_num`: a write addressed to node
     * 0 goes nowhere, and reporting success for it would be the worst possible answer on a
     * screen whose job is to rescue a silent radio.
     */
    /**
     * __DESKTOP_LORA_BEACON_FIXED_2026_10_28__ Tell the RADIO where it is, and let its own
     * PositionModule do the announcing.
     *
     * This replaces [loraBroadcastPosition] as the way a position reaches other nodes.
     * That function sent a POSITION_APP packet to the broadcast address — the mechanism
     * both phones shipped this morning — and it is accepted by the radio and goes nowhere:
     * measured with two ThinkNode M1s in one room, a text broadcast from the same radio
     * crossed in 3 seconds while ten position frames over four minutes produced nothing, on
     * a mesh whose receiver was reading 36 other nodes' positions fine. See
     * `LoRaAdmin.setFixedPositionFrame`.
     *
     * ⚠️ Unlike a broadcast this CHANGES THE NODE'S CONFIGURATION and persists on the
     * device: a radio told it is in Geneva stays in Geneva for every app that connects to
     * it afterwards. [loraClearFixedPosition] is the undo, and the pane's "Forget" calls it.
     */
    fun loraSetFixedPosition(latitude: Double, longitude: Double): Boolean {
        val frame = com.oshi.desktop.lora.LoRaAdmin.setFixedPositionFrame(latitude, longitude)
        if (frame == null) {
            onLoRa("lora: that is not a position on Earth — 0/0 included, which is what a radio with no lock reports")
            return false
        }
        return sendAdmin(frame, "fixed position %.5f, %.5f".format(latitude, longitude))
    }

    /**
     * __DESKTOP_LORA_SMART_BROADCAST_2026_10_28__ Write a COMPLETE `Config.position` back
     * to the node.
     *
     * Takes the whole payload because the firmware replaces the submessage wholesale and
     * protobuf omits defaults — the only safe way to change one setting is to read the
     * node's current config, rewrite one field of those exact bytes
     * ([com.oshi.desktop.lora.LoRaAdmin.withVarintField], which refuses anything it cannot
     * reproduce byte-for-byte) and send the result. Building a config from arguments is how
     * a radio ends up with `position_broadcast_secs = 0`.
     *
     * Deliberately not exposed in the UI. This is a diagnostic entry point for
     * `LiveSmartBroadcastBench`, and a screen that let someone write a partial config would
     * be the defect this signature exists to prevent.
     */
    fun loraSetPositionConfig(positionConfig: ByteArray): Boolean =
        sendAdmin(
            com.oshi.desktop.lora.LoRaAdmin.setPositionConfigFrame(positionConfig),
            "Config.position (${positionConfig.size} bytes, written whole)",
        )

    /**
     * __DESKTOP_LORA_REBOOT_2026_10_28__ Ask the attached radio to reboot.
     *
     * Diagnostic only — see [com.oshi.desktop.lora.LoRaAdmin.rebootFrame]. The node's own
     * position announcement is clamped to six hours while `fixed_position` is set, and a
     * fresh boot is the only point at which `PositionModule` sends one unprompted.
     */
    fun loraReboot(seconds: Int = 5): Boolean =
        sendAdmin(com.oshi.desktop.lora.LoRaAdmin.rebootFrame(seconds), "a reboot in ${seconds}s")

    /**
     * __DESKTOP_LORA_SET_OWNER_2026_10_29__ Name the attached radio — what both phones
     * have had and this client did not.
     *
     * Region and channel were settable here; the NAME was not, so a desktop owner's node
     * kept its factory label on every other client's list and on public mesh maps. Returns
     * false, with the reason logged, when the name is empty or no radio is attached.
     *
     * `shortName` is clamped to four BYTES by [com.oshi.desktop.lora.LoRaAdmin.clampShortName]
     * rather than four characters — the distinction Android had to fix after two emoji went
     * out as eight bytes.
     */
    fun loraSetOwner(longName: String, shortName: String): Boolean {
        val node = loraRoster.myNodeNumOrZero()
        if (node == 0u) {
            onLoRa("lora: the radio has not said which node it is yet — wait for the config burst")
            return false
        }
        val long = longName.trim()
        if (long.isEmpty()) {
            onLoRa("lora: a node needs a name")
            return false
        }
        val short = com.oshi.desktop.lora.LoRaAdmin.clampShortName(
            shortName.ifBlank { long },
        )
        if (short.isEmpty()) {
            onLoRa("lora: the short name came out empty — it must fit in four bytes")
            return false
        }
        return sendAdmin(
            com.oshi.desktop.lora.LoRaAdmin.setOwnerFrame(node, long, short),
            "the node's name to \"$long\" / \"$short\"",
        )
    }

    /**
     * Adopt a radio still wearing its factory default — iOS's `renameIfFactoryDefault`.
     *
     * Only renames a node whose announced long name looks untouched. A radio somebody
     * deliberately named is left alone, which is the whole point: this is a convenience for
     * a new node, not a claim on every node an OSHI user attaches.
     */
    fun loraAdoptIfFactoryDefault(): Boolean {
        val node = loraRoster.myNodeNumOrZero()
        if (node == 0u) return false
        val mine = loraOnlineNodeList().firstOrNull { it.num == node }
        val current = mine?.name?.trim().orEmpty()
        val looksFactory = current.isEmpty() ||
            Regex("^Meshtastic\\s+[0-9a-fA-F]{4}$").matches(current) ||
            Regex("^OSHI Mesh [0-9A-F]{4}$").matches(current)
        if (!looksFactory) {
            onLoRa("lora: \"$current\" is a name somebody chose — leaving it alone")
            return false
        }
        val adopted = com.oshi.desktop.lora.LoRaAdmin.adoptedName(node)
        return loraSetOwner(adopted, "OSHI")
    }

    /** Clear the node's stored fixed position — the undo for [loraSetFixedPosition]. */
    fun loraClearFixedPosition(): Boolean =
        sendAdmin(com.oshi.desktop.lora.LoRaAdmin.removeFixedPositionFrame(), "no fixed position")

    private fun sendAdmin(frame: ByteArray, what: String): Boolean {
        val link = lora
        if (link == null || !link.isConnected) {
            onLoRa("lora: no radio attached")
            return false
        }
        val me = loraRoster.myNodeNumOrZero()
        if (me == 0u) {
            onLoRa("lora: the radio has not said which node it is yet — wait for the config burst")
            return false
        }
        val packet = com.oshi.desktop.lora.LoRaProto.toRadioPacket(
            to = me,
            portnum = com.oshi.desktop.lora.LoRaAdmin.PORT_ADMIN,
            payload = frame,
            // A real unicast to our own node, so an ack is meaningful here — unlike the
            // broadcasts every other lane uses.
            wantAck = true,
            packetId = com.oshi.desktop.lora.LoRaProto.randomPacketId(),
        )
        val wrote = link.send(packet)
        onLoRa(if (wrote) "asked the radio to set $what" else "lora: write failed")
        return wrote
    }

    /** USB serial devices visible right now — what a picker offers. */
    fun loraSerialDevices(): List<com.oshi.desktop.lora.LoRaSerial.Device> =
        com.oshi.desktop.lora.LoRaSerial.listDevices()

    /**
     * Transmit stock Meshtastic text on portnum 1 — the lane every Meshtastic client reads.
     *
     * This is the send half of the interop lane, and the counterpart of iOS's
     * `MeshtasticManager.sendText` (`OSHI/MeshtasticManager.swift:556`). Until this existed
     * the desktop's radio was receive-only and `/lora` said so.
     *
     * **It is PLAINTEXT on a channel whose key is public**, which is what interop means:
     * the point is that a stock Meshtastic user with no OSHI can read it. So it is
     * deliberately NOT reachable from a conversation's composer — the caller is a person
     * who asked for a radio broadcast, not someone sending a message to a contact. The same
     * asymmetry the receive side already has: inbound interop text is quarantined under
     * `lora!<nodehex>` and flagged unverified, and nothing about sending changes that.
     *
     * Not chunked, by the same rule as iOS: a stock client has no reassembler, so a long
     * message would arrive as fragments. Over the limit is refused, not truncated — a
     * silently cut message is worse than a refused one.
     *
     * @param to a node number, or null to broadcast.
     * @return false when there is no attached radio or the text does not fit.
     */
    fun loraSendInteropText(text: String, to: UInt? = null): Boolean {
        // __DESKTOP_LORA_REFUSAL_2026_10_29__ A muted radio or one with no region accepts
        // every frame and transmits none; this used to report that as sent.
        if (loraRefuseSend() != null) return false
        val link = lora
        if (link == null || !link.isConnected) {
            onLoRa("lora: no radio attached")
            return false
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return false
        if (bytes.size > com.oshi.desktop.lora.LoRaProto.INTEROP_TEXT_LIMIT) {
            onLoRa("lora: text is ${bytes.size} bytes, the interop limit is ${com.oshi.desktop.lora.LoRaProto.INTEROP_TEXT_LIMIT}")
            return false
        }
        val target = to ?: com.oshi.desktop.lora.LoRaProto.BROADCAST_ADDR
        // want_ack only on a unicast: Meshtastic does not acknowledge a broadcast, so
        // asking raises a flag nobody answers (LoRaProto.toRadioPacket's doc).
        val packet = com.oshi.desktop.lora.LoRaProto.toRadioPacket(
            to = target,
            portnum = com.oshi.desktop.lora.LoRaProto.PORT_TEXT,
            payload = bytes,
            wantAck = target != com.oshi.desktop.lora.LoRaProto.BROADCAST_ADDR,
            packetId = com.oshi.desktop.lora.LoRaProto.randomPacketId(),
        )
        val wrote = link.send(packet)
        // "Wrote" is bytes leaving this process, never delivery — LoRaLink.send says so and
        // the wording here must not quietly upgrade it.
        onLoRa(
            if (wrote) "interop text handed to the radio (${bytes.size}B${if (to == null) ", broadcast" else ", node $target"}) — PLAINTEXT, not encrypted"
            else "lora: write failed"
        )
        return wrote
    }

    /**
     * __DESKTOP_LORA_BEACON_2026_10_28__ Put THIS machine's stated position on the air —
     * the send half of the position lane this client could only receive.
     *
     * A public, cleartext broadcast on the default LongFast channel, exactly as on the
     * phones, and the same consequence: every radio in range hears it, up to tens of
     * kilometres, including people the owner has never met and cannot enumerate. The
     * difference here is only the SOURCE — a position its owner typed rather than one a
     * sensor measured, which is Meshtastic's own `fixed_position` case for a node with no
     * GPS. See `LoRaFixedPosition`.
     *
     * No policy in this function. When to beacon is [com.oshi.desktop.lora.LoRaPositionBeacon]'s
     * decision — one function shared, byte for byte, with both phones — and this is only
     * the hands. The 0/0 and range guards are repeated anyway, because a sender that trusts
     * its caller is one refactor away from broadcasting the Gulf of Guinea.
     */
    fun loraBroadcastPosition(latitude: Double, longitude: Double): Boolean {
        if (loraRefuseSend() != null) return false
        val link = lora
        if (link == null || !link.isConnected) {
            onLoRa("lora: no radio attached")
            return false
        }
        if (latitude == 0.0 && longitude == 0.0) return false
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return false
        val payload = com.oshi.desktop.lora.LoRaProto.positionPayload(
            latitude, longitude, System.currentTimeMillis() / 1000,
        )
        val packet = com.oshi.desktop.lora.LoRaProto.toRadioPacket(
            to = com.oshi.desktop.lora.LoRaProto.BROADCAST_ADDR,
            portnum = com.oshi.desktop.lora.LoRaProto.PORT_POSITION,
            payload = payload,
            // Never on a broadcast — Meshtastic does not acknowledge one.
            wantAck = false,
            packetId = com.oshi.desktop.lora.LoRaProto.randomPacketId(),
        )
        val wrote = link.send(packet)
        // "Handed to the radio" is bytes leaving this process, not delivery. LoRaLink.send
        // says so and this wording must not quietly upgrade it.
        onLoRa(
            if (wrote) "position %.5f, %.5f handed to the radio → ALL — PLAINTEXT, not encrypted"
                .format(latitude, longitude)
            else "lora: position write failed",
        )
        return wrote
    }

    /**
     * One pass of the position beacon, driven by the caller's existing one-second tick.
     *
     * Returns the decision so a surface can say why nothing went out; sends nothing at all
     * unless the switch is on, a position is stored and a radio is attached.
     */
    fun loraPositionBeaconTick(nowMs: Long = System.currentTimeMillis()):
        com.oshi.desktop.lora.LoRaPositionBeacon.Decision =
        com.oshi.desktop.lora.LoRaPositionBeacon.tick(
            radioReady = lora?.isConnected == true,
            nowMs = nowMs,
            // __DESKTOP_LORA_BEACON_FIXED_2026_10_28__ The ADMIN path, not
            // [loraBroadcastPosition]. A POSITION_APP packet from a client to the broadcast
            // address is accepted by the radio and does not reach other nodes — measured,
            // see `LoRaAdmin.setFixedPositionFrame`. Telling the node where it is and
            // letting its PositionModule announce is what Meshtastic's own clients do.
            //
            // The policy still rate-limits, and that is no longer about airtime: this writes
            // the node's CONFIGURATION, so the 15-minute floor keeps a UI tick from
            // rewriting flash fifty times a minute. The radio owns the broadcast schedule.
            send = ::loraSetFixedPosition,
        )

    fun loraDetach() {
        lora?.stop()
        lora = null
        loraRoster.clear()
        // __DESKTOP_LORA_BEACON_2026_10_28__ Forget what was last beaconed. Without this,
        // detaching and re-attaching leaves `lastSentAtMs` set, so the first beacon of the
        // new session is suppressed as TOO_SOON — and with a fixed position, which never
        // moves, the node would then say nothing until the one-hour ceiling fired.
        com.oshi.desktop.lora.LoRaPositionBeacon.reset()
        // What the OLD node said about itself is not true of the next one.
        loraRadioState.clear()
        loraTraffic.clear()
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
        // Counts only — node_info/my_info are read for WHO was heard WHEN, nothing else.
        loraRoster.observeFromRadio(fromRadio, System.currentTimeMillis())
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
                val outcome = messages.append(stored)
                // Unlike an unopened OSHI envelope, this is deliberately persisted as an
                // unverified radio row. Publish it through the normal inbound callback so
                // the running desktop window refreshes, increments its unread counter and
                // may show its privacy-preserving local notification. A duplicate radio
                // retransmission must not create another alert.
                if (outcome != MessageStore.AppendOutcome.DUPLICATE) onMessage(stored)
                onLoRa("interop text from node ${d.nodeNum} (UNVERIFIED — not authenticated): ${d.text.take(200)}")
            }
            is com.oshi.desktop.lora.LoRaInbound.Decision.Envelope -> openLoRaEnvelope(d)
            is com.oshi.desktop.lora.LoRaInbound.Decision.Routing ->
                onLoRa("routing ack from node ${d.nodeNum}")
            com.oshi.desktop.lora.LoRaInbound.Decision.Ignored -> {}
        }
    }

    /**
     * An OSHI envelope off the radio — opened, filed, and used as PROOF of who sent it.
     *
     * __RATCHET_IS_SHARED_SOURCE_2026_10_28__ This branch used to be one log line saying
     * the message could not be read.
     *
     * ============================================================ THE BINDING IS MADE HERE
     * AND NOWHERE ELSE
     *
     * A node number is a firmware-assigned integer on a channel whose key is public, so it
     * proves nothing by itself. The ONE moment it becomes evidence is the moment an
     * envelope from that node DECRYPTS: only the holder of the sender's identity key could
     * have produced ciphertext this session opens. Android marks the same spot
     * (`ratchetProofCounter++`, "the ONLY place cryptographic proof of the sender exists").
     *
     * So [com.oshi.desktop.lora.LoRaNodeBindings.bind] is called AFTER the decrypt and
     * never before, and never on the interop lane. Its own conflict rules then apply — one
     * node claiming two identities, or two nodes claiming one, marks both ambiguous rather
     * than picking a winner.
     *
     * ============================================================ FAILURES ARE NOT ALIKE
     *
     * A replay is not a desync. A mesh floods and retries, so a second copy of a message is
     * routine; treating it as a broken session would throw away a session that is working
     * and lose the NEXT message — the "one message in two" both phones carry a named fix
     * for. `RatchetFailurePolicy` is the shared rule and it is consulted rather than
     * re-derived.
     */
    private fun openLoRaEnvelope(d: com.oshi.desktop.lora.LoRaInbound.Decision.Envelope) {
        openOshiEnvelope(d.message, transport = "lora", nodeNum = d.nodeNum) { onLoRa(it) }
    }

    /**
     * __MESH_IS_NO_LONGER_OPAQUE_2026_10_28__ An OSHI envelope off ANY offline transport.
     *
     * Extracted from the LoRa path because the LAN mesh carries the SAME `SecureMessage`
     * JSON — Android builds it in `crossPlatformMeshSecureMessage` and iOS in
     * `meshWireSafe()`, and `LoRaSecureMessage` already parses that shape. Two copies of
     * this would be two places to forget the proof rule below.
     *
     * @param nodeNum the LoRa node this arrived on, or null on the LAN mesh, where there is
     *   no node number to bind and nothing to learn from one.
     */
    private fun openOshiEnvelope(
        msg: com.oshi.desktop.lora.LoRaSecureMessage,
        transport: String,
        nodeNum: UInt?,
        report: (String) -> Unit,
    ) {
        // One spelling, for the same reason LegacyRatchetEnvelope canonicalises: this key
        // is the conversation id, so a base64url spelling off the wire would open a SECOND
        // thread with the same person — the very complaint the manual link exists to fix.
        val routedSender = com.oshi.desktop.crypto.LegacyRatchetEnvelope.canonicalKey(msg.senderPublicKey)
        if (routedSender.isEmpty()) {
            report("OSHI envelope carries no sender key — dropped")
            return
        }
        // The recipient filter IS the addressing on these lanes: both broadcast, so every
        // client in range sees every envelope and each decides whether it is the addressee.
        if (!com.oshi.desktop.lora.LoRaSecureMessage.isAddressedToMe(msg, address)) return
        val envelopeJson = org.json.JSONObject()
            .put("ciphertext", msg.encryptedContent.ciphertext)
            .put("signature", msg.encryptedContent.signature)
            .put("senderPublicKey", msg.encryptedContent.senderPublicKey)

        val plaintext = try {
            legacyEnvelope.open(envelopeJson, routedSender)
        } catch (e: com.oshi.desktop.crypto.LegacyRatchetEnvelope.ImpersonationException) {
            // Loud, never silent: this is a forgery attempt, not a glitch.
            log("$transport: REFUSED an envelope: ${e.message}")
            report("REFUSED an OSHI envelope: ${e.message}")
            return
        } catch (e: Exception) {
            val replay = !com.oshi.messenger.network.encryption.RatchetFailurePolicy.provesSessionIsBroken(e)
            report(
                if (replay) "an OSHI envelope was a duplicate the mesh re-flooded — ignored"
                else "an OSHI envelope did not decrypt: ${e.message}"
            )
            return
        }

        // Persist BEFORE anything else observable: the receiving chain has advanced.
        runCatching { ratchetSessionStore.save(legacyEnvelope.ratchet) }
            .onFailure { log("$transport: ratchet state NOT saved after receive: ${it.message}") }

        // PROOF, and only now — and only where there is a node number to bind. The LAN mesh
        // has none: its sender key rides the envelope, so there is nothing to learn.
        if (nodeNum != null) {
            val learned = loraBindings.bind(nodeNum, routedSender, System.currentTimeMillis())
            if (learned == com.oshi.desktop.lora.LoRaNodeBindings.LearnResult.BOUND) {
                runCatching { loraBindingStore.save(loraBindings.snapshot()) }
            }
        }

        // __DESKTOP_LORA_MEDIA_2026_10_28__ A decrypted payload may BE media: both phones
        // put it inside the ciphertext as a JSON wrapper rather than in the envelope's own
        // fields. Reading it here is what stops a photo arriving as a line of base64.
        val media = com.oshi.desktop.lora.LoRaMediaWire.decode(plaintext)
        val mediaRef = media?.let { m ->
            runCatching {
                // Same naming and the same SEALING WRITE as every other inbound media path:
                // `sealingStream` makes the file 0600 and encrypts as it writes, so the
                // plaintext never lands on disk even briefly.
                val safe = (m.fileName ?: "lora-media")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "file" }
                val out = File(mediaDir, "${msg.id.take(8)}-$safe")
                mediaVault.sealingStream(out).use { it.write(m.bytes) }
                out.absolutePath
            }.onFailure { report("media arrived but could not be stored: ${it.message}") }
                .getOrNull()
        }

        val stored = Message(
            id = msg.id.ifEmpty { UUID.randomUUID().toString() },
            // The identity, not `lora!<nodehex>`: this one IS authenticated, so it belongs
            // in the sender's conversation beside everything else they have sent.
            conversationId = routedSender,
            senderAddress = routedSender,
            recipientAddress = address,
            fromMe = false,
            // A media row carries no text: the wrapper JSON is not something to render.
            content = if (mediaRef != null) null else plaintext,
            mediaType = if (mediaRef != null) media?.type else null,
            mediaRef = mediaRef,
            sentAtMs = msg.unixMillis ?: System.currentTimeMillis(),
            sentAtSource = if (msg.unixMillis != null) TimestampSource.APPLE_EPOCH_SECONDS
            else TimestampSource.LOCAL_CLOCK,
            deliveryStatus = DeliveryStatus.DELIVERED,
            transport = transport,
        )
        val outcome = messages.append(stored)
        if (outcome != MessageStore.AppendOutcome.DUPLICATE) onMessage(stored)
        report("OSHI message decrypted over $transport (${plaintext.length} chars) — authenticated by the ratchet")
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
        // __DESKTOP_MESSAGE_PUSH_2026_09_23__ a scheduled message is a user message: wake the peer.
        if (outcome == SendOutcome.SENT && !ControlPrefix.suppressesPush(m.content)) messagePush.wakeDirect(m.recipient, m.id)
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
            // __BLOCKED_MEMBER_GROUPS_2026_09_24__ the ONE exception: a group STATE update (roster,
            // name, a member leaving) from a blocked member still reaches the authorizer, which
            // applies its own membership/admin rules — otherwise the roster here drifts from
            // everyone else's (iOS / Android keep processing group control from a blocked member
            // too). Their CONTENT stays dropped, 1:1 and group alike (owner decision 2026-09-24:
            // a blocked member's group messages are not stored — so not shown, not counted).
            if (inbound.groupId.isNullOrBlank() && ControlPrefix.match(inbound.text) == ControlPrefix.GROUP_UPDATE) {
                log("client: group update from a blocked contact ${inbound.from.take(12)}… — roster only")
                dispatch(inbound)
                return
            }
            log("client: dropped a message from a blocked contact ${inbound.from.take(12)}…")
            // Group traffic is not answered: being in a group with someone you blocked
            // is not them trying to reach you 1:1.
            if (inbound.groupId.isNullOrBlank()) noticeBlockedSender(inbound.from, inbound.text)
            return
        }
        dispatch(inbound)
    }

    /**
     * __BLOCKED_NOTICE_SWITCH_2026_09_23__ Tell a blocked peer, once a day at most, that
     * nothing they send arrives — parity with both phones. Rules and switch live in
     * [BlockedNoticeGate]. Bypasses [sendPlaintext] on purpose: that gate refuses every
     * send to a blocked key, and this is the one message that must reach one. No stored
     * row, no push, no self-copy.
     */
    internal fun noticeBlockedSender(peer: String, text: String?) {
        if (!blockedNotice.claim(peer, text, address)) return
        val sent = runCatching {
            router.sendText(peer, ControlPrefix.BLOCKED_NOTICE.toByteArray(Charsets.UTF_8), UUID.randomUUID().toString())
        }.getOrDefault(false)
        log("client: blocked notice to ${peer.take(12)}… ${if (sent) "sent" else "not sent"}")
    }

    /**
     * The post-block half of [receive] — see THE DISPATCH in the class doc for the order and
     * why it is that order. Internal so a test can drive one inbound plaintext without a
     * relay, a ratchet or a network.
     */
    internal fun dispatch(inbound: V2Inbound) {
        val text = inbound.text

        // 2. A group MESSAGE (content channel, spec §1/§3). The envelope's groupId is what routes
        //    it; the payload's own copy is a claim by the sender and is not trusted for routing.
        //    __GROUP_E2E_V2_2026_09_23__ checked FIRST: a `type:"group"` envelope is content only —
        //    a `📢GROUP_UPDATE📢` on it is dropped (vector `group_update_on_group_channel`), and a
        //    `v2file` key on it is GROUP media (§4), never 1:1 media.
        if (!inbound.groupId.isNullOrBlank()) {
            val key = V2FileKeyMessage.parse(text)
            if (key != null) receiveGroupMedia(inbound, key, com.oshi.desktop.media.VideoNoteWire.fromKey(key))
            else receiveGroupMessage(inbound)
            return
        }

        // 3. A group update (state channel): an ordinary 1:1 message carrying the sentinel.
        if (ControlPrefix.match(text) == ControlPrefix.GROUP_UPDATE) {
            val result = groupIngest.ingest(text, inbound.from)
            log("client: group update from ${inbound.from.take(12)}… → ${result.outcome} (${result.detail})")
            result.respondTo?.let { sendPlaintext(inbound.from, GroupUpdateWire.encodeDefinitionFramed(it), false) }
            // __GROUP_PARITY_2026_09_23__ an invite join changed the roster: every other member hears it.
            result.broadcast?.let { g ->
                val framed = GroupUpdateWire.encodeDefinitionFramed(g)
                for (m in GroupFanout.plan(g, address)) if (!GroupIdentity.sameIdentity(m, inbound.from)) sendPlaintext(m, framed, false)
            }
            contacts.seen(inbound.from, inbound.ts)
            onGroupEvent(result)
            // __GROUP_E2E_V2_2026_09_23__ spec §3 step 5/6: content that arrived before this
            // definition (unknown group, or a member added seconds ago) is replayed now.
            if (result.groupId != null &&
                (result.outcome == GroupIngest.Outcome.CREATED || result.outcome == GroupIngest.Outcome.UPDATED)
            ) replayHeldGroupContent(result.groupId)
            return
        }

        // 4. Media: a `{"kind":"v2file"}` key message. Parsed before the control catalog
        //    because it carries no sentinel at all and would otherwise read as prose.
        V2FileKeyMessage.parse(text)?.let { key ->
            receiveMedia(inbound, key, com.oshi.desktop.media.VideoNoteWire.fromKey(key))
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
                // __BLOCKED_BY_PEER_2026_09_24__ a receipt = the peer takes our traffic again.
                blockedByPeers.observe(inbound.from, text)
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
                // A blocked-contact notice deliberately has no body.  It must still become
                // a normal, localised row: otherwise the recipient sees the protocol marker
                // itself (or, worse, no explanation at all).  Do not send a delivery receipt
                // for it; the sender's emission is already rate-limited and this is a terminal
                // refusal, not a conversation message.
                if (event.prefix == ControlPrefix.BLOCKED_NOTICE) {
                    storeInbound(
                        inbound,
                        "🚫 " + t("chat.blocked_by_contact"),
                        // Phones' rule 3: the row is enough — the person is looking at the
                        // thread they just wrote in, an alert "you were blocked" helps no one.
                        suppressNotification = true,
                    )
                    // __BLOCKED_BY_PEER_2026_09_24__ from now on this peer is never dialled.
                    blockedByPeers.noteBlocked(inbound.from)
                    return
                }
                // __SHARED_NICKNAME_2026_09_22__ A profile update: the only thing this
                // client reads from it is the peer's shared nickname (`displayName`). It is
                // applied to the contact and NOTHING else happens — no row in MessageStore,
                // no `onMessage` (which is what raises the notification), no receipt. A
                // renamed peer is not news. Absent key (older builds) leaves the stored
                // nickname untouched; an explicit empty one clears it.
                if (event.prefix == ControlPrefix.PROFILE_UPDATE) {
                    contacts.seen(inbound.from, inbound.ts)
                    when (val nick = PeerNickname.readProfileUpdate(text)) {
                        is PeerNickname.Read.Set -> contacts.setSharedNickname(inbound.from, nick.name)
                        PeerNickname.Read.Clear -> contacts.setSharedNickname(inbound.from, null)
                        PeerNickname.Read.Absent -> Unit
                    }
                    onControl(inbound.from, event, outcome)
                    return
                }
                // __SHARED_NICKNAME_2026_09_22__ A peer asking for our profile (both phones
                // send this when they hold no profile for us). Answered with the same
                // payload a rename broadcasts, behind the same "have we written to them"
                // gate. Silent both ways; nothing stored.
                if (event.prefix == ControlPrefix.PROFILE_REQUEST) {
                    contacts.seen(inbound.from, inbound.ts)
                    if (mayBroadcastProfile && ownNickname != null && hasSentUserMessage(inbound.from)) {
                        runCatching { sendProfileUpdate(inbound.from) }
                    }
                    onControl(inbound.from, event, outcome)
                    return
                }
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
                // __GROUP_PARITY_2026_09_23__ A reply or forward is a USER message in an
                // envelope (`Kind.ENVELOPE`: "unwrap, never summarise"). Step 7 used to drop
                // it, so every reply and forward a phone sent in a 1:1 chat vanished on this
                // client. Stored as sent — the bubble unwraps it and draws the quote — and
                // treated like prose: receipt, notification, profile request.
                if (com.oshi.desktop.msg.ReplyEnvelope.unwrap(text) != null) {
                    storeInbound(inbound, text, suppressNotification = false)
                    if (deliveryReceiptsEnabled) {
                        sendPlaintext(inbound.from, DeliveryReceipt.encode(inbound.msgId), storeRow = false)
                    }
                    maybeRequestProfile(inbound.from)
                    return
                }
                // 6b. __DESKTOP_POLLS_2026_10_29__ Polls — the two sentinels this client
                //     has carried in its catalog since 2026-10-27 and acted on never.
                //
                //     Being in the catalog is what sent them HERE instead of into step 8's
                //     prose, and step 7 below then logged and dropped them. So an iPhone's
                //     poll did not arrive mangled; it did not arrive. These two branches are
                //     the whole of the difference.
                //
                //     A VOTE IS SILENT AND A POLL IS A ROW, which is both phones' own
                //     classification (`Kind.SILENT` vs `Kind.RENDERED`) and not a choice made
                //     here: a ballot is not an event to notify about, and the poll card is.
                if (PollPayload.isVote(text)) {
                    contacts.seen(inbound.from, inbound.ts)
                    val vote = PollPayload.decodeVote(text)
                    if (vote == null) {
                        log("client: unparseable \uD83D\uDCCAPOLL_VOTE\uD83D\uDCCA from ${inbound.from.take(12)}…")
                    } else {
                        // THE VOTER IS THE AUTHENTICATED SENDER, never a field in the JSON —
                        // the payload carries no voter precisely so that it cannot lie about
                        // one. `inbound.from` is the identity whose ratchet produced this
                        // plaintext, which is the same rule the LoRa node binding follows.
                        val changed = pollBallots.record(vote.pollId, inbound.from, vote.ballot)
                        // A duplicate is the COMMON case (the mesh redelivers the same vote
                        // routinely), so it must not look like news — and it does not: the
                        // repaint below comes from `onControl`, whose handler ends in
                        // `publish()`, and a `record` that returned false changed nothing for
                        // it to draw. Logged at the boundary so a vote that lost on timestamp
                        // is distinguishable from one that never arrived.
                        if (!changed) log("client: duplicate or stale ballot for poll ${vote.pollId.take(8)}…")
                    }
                    onControl(inbound.from, event, outcome)
                    return
                }
                // 6d. __DESKTOP_PROFILE_LINKS_2026_10_29__ A contact sharing or
                //     withdrawing their profile links. SILENT on both phones — somebody
                //     adding a GitHub URL is not an event — so this never stores a row and
                //     never notifies, and the only visible effect is in their profile.
                if (com.oshi.desktop.msg.ProfileLinksPayload.isVisibilityMessage(text)) {
                    contacts.seen(inbound.from, inbound.ts)
                    val v = com.oshi.desktop.msg.ProfileLinksPayload.decode(text)
                    if (v == null) {
                        log("client: unparseable \uD83D\uDD17LINKS_VISIBILITY\uD83D\uDD17 from ${inbound.from.take(12)}…")
                    } else if (!contactLinks.apply(inbound.from, v)) {
                        log("client: profile links from ${inbound.from.take(12)}… were unchanged")
                    }
                    onControl(inbound.from, event, outcome)
                    return
                }
                // 6c. __DESKTOP_OFFLINE_ONLY_2026_10_29__ The third drifted sentinel, and
                //     the one that is a PRIVACY PROMISE: a peer asking this conversation to
                //     stop using the relay. It fell to step 7 and was dropped, while the
                //     asking phone's UI waited for a confirmation that was never coming.
                if (com.oshi.desktop.msg.OfflineOnlyPayload.isRequest(text)) {
                    contacts.seen(inbound.from, inbound.ts)
                    val req = com.oshi.desktop.msg.OfflineOnlyPayload.decode(text)
                    if (req == null) {
                        log("client: unparseable \uD83D\uDD12OFFLINE_ONLY\uD83D\uDD12 from ${inbound.from.take(12)}…")
                        onControl(inbound.from, event, outcome)
                        return
                    }
                    if (req.isAck) {
                        // An ACK is never echoed — echoing one is the ping-pong the `ack`
                        // field exists to stop. It is counted only if it answers the request
                        // we are actually waiting on, matched on state AND stamp.
                        if (offlineOnly.answersCurrentRequest(req, inbound.from)) {
                            offlineOnly.noteConfirmation(inbound.from, inbound.from)
                        } else {
                            log("client: a stale offline-only ack from ${inbound.from.take(12)}… was not counted")
                        }
                        onControl(inbound.from, event, outcome)
                        return
                    }
                    // Honour it locally — that is the whole point of the request — and answer
                    // so their side can show us as agreed. The ack is attempted even when
                    // nothing changed (a redelivery): an ack that crosses with a duplicate is
                    // cheap, and a peer waiting for one is not.
                    offlineOnly.applyIncoming(req, inbound.from)
                    runCatching { sendPlaintext(inbound.from, req.acknowledgement().encode(), storeRow = false) }
                    // THE REQUEST IS A ROW, THE ACK IS NOT, and that asymmetry is iOS's:
                    // `handleOfflineOnlyPayload` returns false for a request so it renders
                    // and true for an ack so it is swallowed, because *"seeing when a
                    // conversation was taken off the relay, and by whom, is the point of it
                    // being rendered at all"* — while a rendered ack would print the same
                    // sentence a second time in the asker's thread.
                    //
                    // Stored as the LOCALISED sentence, not the payload: the catalogs already
                    // carry `offline_only.summary.{on,off}` in every language because they are
                    // extracted from the iOS tree, so there is no string to invent and nothing
                    // to print raw.
                    storeInbound(
                        inbound,
                        // Each key sits INSIDE its own `t(...)`: `CatalogAuditTest`'s suspect
                        // scan looks for a literal adjacent to a registered accessor, and
                        // `t(if (x) "a" else "b")` hides both literals from it. The audit
                        // caught this, which is the blind spot its TRAP 3 is named for.
                        "\uD83D\uDD12 " + (if (req.on) t("offline_only.summary.on") else t("offline_only.summary.off")),
                        // NOT suppressed, and this was wrong first: `suppressNotification`
                        // skips `onMessage` ENTIRELY (`:3558`), not just the alert, so the
                        // running window never learned the row existed and the switch in its
                        // header did not flip until something else repainted. iOS lets the
                        // request fall through to ordinary handling for the same reason it
                        // renders at all — a conversation being taken off the relay is news.
                        suppressNotification = false,
                    )
                    return
                }
                if (PollPayload.isPoll(text)) {
                    val poll = PollPayload.decodePoll(text)
                    if (poll == null) {
                        // A malformed poll is dropped rather than shown: there is no partial
                        // poll worth drawing, and `decodePoll` already refuses one with
                        // fewer than two surviving options.
                        log("client: unparseable \uD83D\uDCCAPOLL\uD83D\uDCCA from ${inbound.from.take(12)}…")
                        onControl(inbound.from, event, outcome)
                        return
                    }
                    // Stored with the payload AS IT ARRIVED, not with a rendered summary.
                    // The bubble re-decodes it every draw, so a vote arriving later changes
                    // the bars without the row being rewritten — which is the property that
                    // keeps the definition immutable.
                    storeInbound(inbound, text, suppressNotification = false)
                    if (deliveryReceiptsEnabled) {
                        sendPlaintext(inbound.from, DeliveryReceipt.encode(inbound.msgId), storeRow = false)
                    }
                    maybeRequestProfile(inbound.from)
                    return
                }
                // 7. A sentinel belonging to a row this client has not built (call signal,
                //    profile/wallpaper update). Known, not ours, and NOT rendered — the body
                //    is JSON and a user would see it raw.
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
        maybeRequestProfile(inbound.from)   // __SHARED_NICKNAME_2026_09_22__
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
        // __BLOCKED_BY_PEER_2026_09_24__ a real row (not the notice, not a silent ping) from the
        // peer = it writes to us again. Same rule as iOS `observeStoredInbound`.
        if (!suppressNotification) blockedByPeers.clear(inbound.from)
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
        // __GROUP_E2E_V2_2026_09_23__ spec §3 step 2: base64 GroupMessage or nothing — never
        // render bytes (a `📢GROUP_UPDATE📢` on the group channel lands here and is dropped).
        val payload = GroupMessageWire.decodeFromEnvelope(inbound.text)
        if (payload == null) {
            log("client: undecodable group message from ${inbound.from.take(12)}… — dropped")
            return
        }
        val group = admitGroupContent(inbound, payload) ?: return
        val gid = GroupIdentity.canonicalGroupId(group.groupId)
        if (payload.isSystem) {
            applyGroupSystem(inbound, group, payload)
            return
        }
        // §2.3 ephemeral / mutating bodies: never a bubble, never deduped as a message.
        val body = payload.body
        when (ControlPrefix.match(body)) {
            ControlPrefix.REACTION, ControlPrefix.REACTION_LEGACY,
            ControlPrefix.TYPING_IOS, ControlPrefix.TYPING_ANDROID -> {
                val (event, outcome) = control.apply(gid, inbound.from, body)
                contacts.seen(inbound.from, inbound.ts)
                // __GROUP_PARITY_2026_09_23__ a typing ping names its sender in the JSON; it is shown
                // under a name only when that is the member whose ratchet delivered it.
                if (event is ControlEvent.Typing && !GroupIdentity.sameIdentity(event.payload.senderPublicKey, inbound.from)) return
                onControl(gid, event, outcome)
                return
            }
            ControlPrefix.GROUP_UPDATE, ControlPrefix.ACTION, ControlPrefix.DELIVERY_RECEIPT,
            ControlPrefix.READ_RECEIPT, ControlPrefix.CALL_SIGNAL, ControlPrefix.SESSION_RESET -> {
                log("client: control payload on the group content channel from ${inbound.from.take(12)}… — dropped")
                return
            }
            else -> Unit
        }
        if (messages.messages(gid).any { GroupMessageWire.sameMessageId(it.id, payload.messageId) }) return
        // __MENTIONS_2026_09_23__ keep only current members whose `@name` is visible in the text.
        val admittedMentions = com.oshi.desktop.group.MentionWire.admit(
            body = com.oshi.desktop.msg.ReplyEnvelope.unwrap(body)?.content ?: body,
            mentions = payload.mentions,
            isMember = { k -> group.isMember(k) },
            sameKey = GroupIdentity::sameIdentity,
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
            mentions = admittedMentions,
        )
        val outcome = messages.append(stored)
        contacts.seen(inbound.from, inbound.ts)
        groups.put(group.copy(lastActivityUnixMillis = maxOf(group.lastActivityUnixMillis, payload.timestampUnixMillis)))
        if (outcome != MessageStore.AppendOutcome.DUPLICATE) onMessage(stored)
    }

    /**
     * __GROUP_E2E_V2_2026_09_23__ Spec §3 steps 3-6 for one decoded GroupMessage. Returns the group
     * when the content may be applied; null when it was dropped or HELD (unknown group, or a
     * sender not yet in our roster — replayed by [replayHeldGroupContent] when the definition
     * lands). Never creates a group, never adds a member.
     */
    private fun admitGroupContent(inbound: V2Inbound, payload: GroupMessageWire.GroupMessagePayload): GroupDefinition? {
        val gid = GroupIdentity.canonicalGroupId(inbound.groupId!!)
        if (!payload.groupId.equals(gid, ignoreCase = true)) {
            log("client: group message whose groupId differs from its envelope — dropped")
            return null
        }
        if (payload.isSystem) {
            val actor = payload.systemMessageData?.get("actorPublicKey")
            if (actor == null || !GroupIdentity.sameIdentity(actor, inbound.from)) {
                log("client: group system message whose actor is not the sender ${inbound.from.take(12)}… — dropped")
                return null
            }
        } else if (!GroupIdentity.sameIdentity(payload.senderPublicKey, inbound.from)) {
            log("client: group message whose author is not the sender ${inbound.from.take(12)}… — dropped")
            return null
        }
        val group = groups.get(gid)
        if (group == null) {
            holdGroupContent(gid, inbound, "a group we do not hold (yet)")
            return null
        }
        if (group.isEvicted(inbound.from)) {
            log("client: group message from an evicted member ${inbound.from.take(12)}… — dropped")
            return null
        }
        if (!group.isMember(inbound.from)) {
            holdGroupContent(gid, inbound, "a sender not (yet) in our roster")
            return null
        }
        return group
    }

    /** Spec §2.4: `message_edited` (author only) / `message_deleted` (author or current admin). */
    private fun applyGroupSystem(inbound: V2Inbound, group: GroupDefinition, payload: GroupMessageWire.GroupMessagePayload) {
        val gid = GroupIdentity.canonicalGroupId(group.groupId)
        val data = payload.systemMessageData.orEmpty()
        val at = payload.timestampUnixMillis
        when (payload.systemMessageType) {
            GroupMessageWire.SYSTEM_EDITED -> {
                val targetId = data["editedMessageId"] ?: return
                val text = data["editedContent"] ?: return
                val target = messages.messages(gid).firstOrNull { GroupMessageWire.sameMessageId(it.id, targetId) } ?: return
                if (!GroupIdentity.sameIdentity(target.senderAddress, inbound.from)) {
                    log("client: group edit of someone else's message by ${inbound.from.take(12)}… — refused")
                    return
                }
                if (target.isDeletedForEveryone || target.content == text) return
                messages.editContent(gid, target.id, text, at)
            }
            GroupMessageWire.SYSTEM_DELETED -> {
                val targetId = data["deletedMessageId"] ?: return
                val target = messages.messages(gid).firstOrNull { GroupMessageWire.sameMessageId(it.id, targetId) } ?: return
                if (!GroupIdentity.sameIdentity(target.senderAddress, inbound.from) && !group.isAdmin(inbound.from)) {
                    log("client: group delete by a non-author non-admin ${inbound.from.take(12)}… — refused")
                    return
                }
                if (target.isDeletedForEveryone) return
                messages.deleteForEveryone(gid, target.id, at)
            }
            else -> return
        }
        contacts.seen(inbound.from, inbound.ts)
        onGroupContentChanged(gid)
    }

    /** Called after a group edit/delete was applied, so a surface can redraw that thread. */
    @Volatile var onGroupContentChanged: (String) -> Unit = {}

    /**
     * __GROUP_E2E_V2_2026_09_23__ Group media (spec §4): the key message carries `groupMessage`;
     * the §3 checks run BEFORE any download, then the bytes are fetched from THIS member's blob
     * and sealed at rest like 1:1 media.
     */
    private fun receiveGroupMedia(
        inbound: V2Inbound,
        key: V2FileKeyMessage,
        videoNote: com.oshi.desktop.media.VideoNoteWire.Meta? = null,
    ) {
        val payload = key.groupMessage?.let { GroupMessageWire.decodeFromEnvelope(it) }
        if (payload == null || payload.isSystem) {
            log("client: group media key without a usable groupMessage from ${inbound.from.take(12)}… — dropped")
            return
        }
        val group = admitGroupContent(inbound, payload) ?: return
        val gid = GroupIdentity.canonicalGroupId(group.groupId)
        if (messages.messages(gid).any { GroupMessageWire.sameMessageId(it.id, payload.messageId) }) return
        val name = payload.mediaFileName ?: key.filename
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "file" }
        val out = File(mediaDir, "${payload.messageId.take(8)}-$safeName")
        val written = blobs.downloadAndDecryptToFile(
            blobId = key.blobId,
            chunkCount = key.chunkCount,
            fileKey = key.fileKey,
            fileNonce = key.fileNonce,
            manifest = key.manifest,
            out = out,
            openSink = mediaVault::sealingStream,
        )
        if (written == null) log("client: group media ${key.blobId.take(12)}… could not be fetched or decrypted")
        val mediaType = when (payload.mediaType ?: GroupMessageWire.GroupMediaType.fromRaw(key.mediaType)) {
            GroupMessageWire.GroupMediaType.PHOTO -> MediaType.IMAGE
            GroupMessageWire.GroupMediaType.VIDEO -> MediaType.VIDEO
            GroupMessageWire.GroupMediaType.AUDIO -> MediaType.AUDIO
            GroupMessageWire.GroupMediaType.CONTACT -> MediaType.CONTACT
            GroupMessageWire.GroupMediaType.DOCUMENT -> MediaType.DOCUMENT
            null -> MediaType.forMime(key.mime)
        }
        val stored = Message(
            id = payload.messageId,
            conversationId = gid,
            senderAddress = inbound.from,
            recipientAddress = gid,
            fromMe = false,
            content = payload.plaintextContent ?: name,
            mediaType = mediaType,
            mediaRef = if (written != null) out.absolutePath else null,
            sentAtMs = payload.timestampUnixMillis,
            sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            deliveryStatus = DeliveryStatus.DELIVERED,
            transport = "relay-v2",
            // __VIDEO_NOTE_2026_09_24__ spec rule 5: either location flags it (the key message's
            // copy was already merged with the GroupMessage's by `VideoNoteWire.fromKey`).
            videoNote = mediaType == MediaType.VIDEO && (videoNote ?: payload.videoNote) != null,
            mediaDurationMs = (videoNote?.durationMs ?: payload.videoNote?.durationMs)
                ?.takeIf { mediaType == MediaType.VIDEO },
        )
        val outcome = messages.append(stored)
        contacts.seen(inbound.from, inbound.ts)
        groups.put(group.copy(lastActivityUnixMillis = maxOf(group.lastActivityUnixMillis, payload.timestampUnixMillis)))
        if (outcome != MessageStore.AppendOutcome.DUPLICATE) onMessage(stored)
    }

    /** Spec §3 step 5: bounded (200 per group, 7 days), in memory; replayed when the definition lands. */
    private val heldGroupContent = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<Long, V2Inbound>>>()

    private fun holdGroupContent(gid: String, inbound: V2Inbound, why: String) {
        val list = heldGroupContent.getOrPut(gid) { java.util.Collections.synchronizedList(ArrayList()) }
        synchronized(list) {
            val cutoff = System.currentTimeMillis() - HELD_GROUP_CONTENT_TTL_MS
            list.removeAll { it.first < cutoff }
            if (list.size >= MAX_HELD_GROUP_CONTENT) list.removeAt(0)
            list += System.currentTimeMillis() to inbound
        }
        log("client: group content from ${inbound.from.take(12)}… held: $why (${gid.take(8)}…)")
    }

    private fun replayHeldGroupContent(groupId: String) {
        val gid = GroupIdentity.canonicalGroupId(groupId)
        val held = heldGroupContent.remove(gid) ?: return
        val cutoff = System.currentTimeMillis() - HELD_GROUP_CONTENT_TTL_MS
        val batch = synchronized(held) { held.filter { it.first >= cutoff }.map { it.second } }
            // __BLOCKED_MEMBER_GROUPS_2026_09_24__ held before the sender was blocked: still dropped.
            .filter { BlockPolicy.inbound(contacts, it.from) != BlockPolicy.Inbound.DROP_BLOCKED }
        for (inbound in batch) runCatching { dispatch(inbound) }
            .onFailure { log("client: replay of held group content failed: ${it.javaClass.simpleName}") }
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
    private fun receiveMedia(
        inbound: V2Inbound,
        key: V2FileKeyMessage,
        videoNote: com.oshi.desktop.media.VideoNoteWire.Meta? = null,
    ) {
        // __DESKTOP_TIMED_REVEAL_2026_10_29__ A TIMED PHOTO REVEAL, which this client parsed
        // and ignored.
        //
        // iOS sends such a photo as N standalone JPEGs ("layers"), smallest first: layer 0
        // arrives at once and the relay holds the rest on a schedule, so the picture SHARPENS.
        // The four fields that say so (`revealId`, `revealLayerIndex`, `revealLayerCount`,
        // `revealTotalMs`) have been in `V2FileKeyMessage` all along — the desktop COMPILES
        // that file as shared source, so it has been parsing them for free and acting on
        // none. The result: the same photograph appeared in the thread once per layer, each
        // copy sharper than the last.
        //
        // The security argument is worth repeating because it constrains the fix: a small
        // layer is small because it was DOWNSCALED AND RE-ENCODED, so a 48px JPEG contains
        // 48px of information and nothing more. Rendering a blurred copy of the full image
        // instead would be cosmetic — the whole picture would already be on this disk.
        val reveal = RevealLayer.of(key)
        if (reveal != null && reveal.layerIndex > 0) {
            applyRevealLayer(inbound, key, reveal)
            return
        }

        val safeName = key.filename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "file" }
        // Layer 0 is the CARRIER and must be findable by the layers that follow, so its row id
        // is the `revealId` rather than the envelope's — which is exactly what iOS does
        // (`messages.firstIndex(where: { $0.id == reveal.revealId })`).
        val rowId = reveal?.revealId ?: inbound.msgId
        val out = File(mediaDir, "${rowId.take(8)}-$safeName")
        val written = blobs.downloadAndDecryptToFile(
            blobId = key.blobId,
            chunkCount = key.chunkCount,
            fileKey = key.fileKey,
            fileNonce = key.fileNonce,
            manifest = key.manifest,
            out = out,
            // Re-sealed chunk by chunk under the local media key: the plaintext never lands.
            openSink = mediaVault::sealingStream,
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
            (MediaVault.lengthOf(out) ?: 0L) in 1..MAX_CONTACT_CARD_BYTES
        ) {
            runCatching {
                com.oshi.desktop.place.ContactCardPayload.decode(
                    String(mediaVault.readBytes(out, MAX_CONTACT_CARD_BYTES.toLong()), Charsets.UTF_8)
                )
            }.getOrNull()
        } else {
            if (written != null && key.mediaType == MediaType.CONTACT.wire) {
                log(
                    "client: an attachment claimed mediaType=contact at ${MediaVault.lengthOf(out)} bytes — " +
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
            id = rowId,
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
            // __VIDEO_NOTE_2026_09_24__ `VideoNoteWire.fromKey` already refused a flag on a non-video.
            videoNote = videoNote != null,
            mediaDurationMs = videoNote?.durationMs,
        )
        val outcome = messages.append(stored)
        contacts.seen(inbound.from, inbound.ts)
        blockedByPeers.clear(inbound.from)   // __BLOCKED_BY_PEER_2026_09_24__ media = a person writing
        if (outcome != MessageStore.AppendOutcome.DUPLICATE) onMessage(stored)
        if (deliveryReceiptsEnabled) {
            sendPlaintext(inbound.from, DeliveryReceipt.encode(inbound.msgId), storeRow = false)
        }
    }

    /**
     * __DESKTOP_TIMED_REVEAL_2026_10_29__ A later layer of a timed reveal: the same photo,
     * sharper, replacing the bytes of the row layer 0 created.
     *
     * Three rules, all iOS's:
     *  - **No new row.** The carrier is found by `revealId` and its media is replaced.
     *  - **A layer with no carrier is DROPPED**, not stored. iOS's words: rather than
     *    *"fabriquer une bulle orpheline qui montrerait une image floue sans fin"* — an
     *    orphan bubble showing a blurry image for ever, because the sharper layers have
     *    nowhere to land.
     *  - **No notification and no receipt.** Layer 0 already raised both; a photo sharpening
     *    is not four new messages, and alerting per layer would make the feature hostile.
     */
    private fun applyRevealLayer(inbound: V2Inbound, key: V2FileKeyMessage, reveal: RevealLayer) {
        // The carrier first, BEFORE the download: a layer whose row is gone is bytes we have
        // no use for, and fetching them would cost the airtime and the disk for nothing.
        if (messages.message(inbound.from, reveal.revealId) == null) {
            log("client: reveal layer ${reveal.layerIndex} with no carrier message — dropped")
            return
        }
        val safeName = key.filename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "file" }
        val out = File(mediaDir, "${reveal.revealId.take(8)}-L${reveal.layerIndex}-$safeName")
        val written = blobs.downloadAndDecryptToFile(
            blobId = key.blobId,
            chunkCount = key.chunkCount,
            fileKey = key.fileKey,
            fileNonce = key.fileNonce,
            manifest = key.manifest,
            out = out,
            openSink = mediaVault::sealingStream,
        )
        if (written == null) {
            log("client: reveal layer ${reveal.layerIndex} could not be fetched or decrypted")
            return
        }
        val updated = messages.replaceMedia(inbound.from, reveal.revealId, out.absolutePath)
        if (updated == null) {
            log("client: reveal layer ${reveal.layerIndex} could not be applied (row gone or hidden)")
            return
        }
        contacts.seen(inbound.from, inbound.ts)
        log("client: reveal layer ${reveal.layerIndex}/${reveal.layerCount - 1} applied")
    }

    /**
     * The four reveal fields, validated together — iOS's `RevealWirePlan`.
     *
     * Validated as a GROUP because they are only meaningful together: an index without a
     * count cannot say whether it is the last layer, and a count of 1 is not a reveal. The
     * bounds are iOS's (`count >= 2, count <= 64`): 64 layers of one photograph is already
     * absurd, and the ceiling is what stops a peer-chosen number deciding how many blobs this
     * client will fetch for one message.
     */
    private data class RevealLayer(val revealId: String, val layerIndex: Int, val layerCount: Int) {
        companion object {
            fun of(key: V2FileKeyMessage): RevealLayer? {
                val id = key.revealId?.takeIf { it.isNotBlank() } ?: return null
                val index = key.revealLayerIndex ?: return null
                val count = key.revealLayerCount ?: return null
                if (index < 0 || count < 2 || count > 64 || index >= count) return null
                return RevealLayer(id, index, count)
            }
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

    fun history(peerAddress: String): List<Message> = messages.messages(peerAddress).filterNot { it.hiddenLocally }

    /** __GROUP_PARITY_2026_09_23__ delete one message on this device only, 1:1 or group (iOS "Delete for me"). */
    fun deleteMessageForMe(conversationId: String, messageId: String): Boolean {
        val conv = groups.get(conversationId)?.let { GroupIdentity.canonicalGroupId(it.groupId) } ?: conversationId
        val m = messages.messages(conv).firstOrNull { GroupMessageWire.sameMessageId(it.id, messageId) } ?: return false
        return messages.hideLocally(conv, m.id) != null
    }

    /**
     * __GROUP_PARITY_2026_09_23__ iOS `deleteGroup` — what an ADMIN's "Delete group" does on iOS
     * (`GroupMessaging.swift:2394`, reached from `GroupViews.swift:4823`): the group and its
     * messages are removed from THIS device, and nothing is sent — the other members keep the
     * group. A leave is also recorded for own-device sync, so a member's later broadcast (whose
     * roster still names us) does not bring it back here; iOS has no such tombstone.
     */
    fun deleteGroupLocally(groupId: String): Boolean {
        val g = groups.get(groupId) ?: return false
        runCatching { devSync.noteLocalLeave(g.groupId, g.name) }
        messages.deleteConversation(GroupIdentity.canonicalGroupId(g.groupId))
        return groups.delete(g.groupId)
    }

    /**
     * __GROUP_PARITY_2026_09_23__ iOS `BlockedContactsManager.blockGroup` (local): the composer is
     * closed with a banner and no notification is raised (`PushNotificationManager.swift:183`).
     * Messages still arrive and are stored, as on iOS. Nothing is sent to anyone.
     */
    /**
     * __DESKTOP_REPORT_2026_09_23__ File a report about a contact (address) or a group
     * (group id), optionally blocking in the same gesture — the phones' "Also block"
     * toggle. Blocking here is the SAME [block] / [setGroupBlocked] every other surface
     * uses; reporting never leaves a group by itself (iOS/Android parity).
     */
    fun report(
        subject: com.oshi.desktop.net.V2ReportClient.Subject,
        subjectId: String,
        reason: com.oshi.desktop.net.V2ReportClient.Reason,
        details: String,
        alsoBlock: Boolean,
    ): com.oshi.desktop.net.V2ReportClient.Report {
        val filed = reports.file(subject, subjectId, reason, details)
        if (alsoBlock) {
            when (subject) {
                com.oshi.desktop.net.V2ReportClient.Subject.CONTACT -> block(subjectId)
                com.oshi.desktop.net.V2ReportClient.Subject.GROUP -> setGroupBlocked(subjectId, true)
            }
        }
        return filed
    }

    fun setGroupBlocked(groupId: String, blocked: Boolean): GroupDefinition? {
        groups.get(groupId) ?: return null
        groups.setBlocked(groupId, blocked)
        return groups.get(groupId)
    }

    fun isGroupBlocked(groupId: String): Boolean = groups.isBlocked(groupId)

    /**
     * __GROUP_PARITY_2026_09_23__ Set the group description (wire key `description`, iOS
     * `groupDescription`, GROUP_E2E_V2_SPEC §5.1). Same permission as the name. Phones on a
     * release older than that spec ignore the key; an absent key never erases a stored one, so a
     * description can be changed but not cleared across devices (iOS `swift:1072` has the same rule).
     */
    fun setGroupDescription(groupId: String, description: String): GroupDefinition? {
        val g = groups.get(groupId) ?: return null
        if (!canEditGroupInfo(groupId)) return null
        val text = description.trim().take(500)
        if (text == g.description.orEmpty()) return g
        val updated = bumpVersion(g.copy(description = text.ifEmpty { null }))
        groups.put(updated)
        broadcastGroupUpdate(updated, GroupUpdateWire.encodeDefinitionFramed(updated))
        return updated
    }

    /**
     * __GROUP_PARITY_2026_09_23__ Pin ([messageId]) or unpin (null) — iOS `pinMessage` /
     * `unpinMessage` (`GroupMessaging.swift:1471-1505`): `pinnedMessageId` + `pinnedBy` in the
     * full definition, permission `canPinMessages` (= the name/picture rule). Unpinning sends a
     * definition WITHOUT the keys, which is how iOS unpins too.
     */
    fun setGroupPin(groupId: String, messageId: String?): GroupDefinition? {
        val g = groups.get(groupId) ?: return null
        if (!canEditGroupInfo(groupId)) return null
        val gid = GroupIdentity.canonicalGroupId(g.groupId)
        val pinId = messageId?.let { id ->
            messages.messages(gid).firstOrNull { GroupMessageWire.sameMessageId(it.id, id) }?.id?.uppercase(java.util.Locale.US)
                ?: return null
        }
        val updated = bumpVersion(g.copy(pinnedMessageId = pinId, pinnedBy = pinId?.let { address }))
        groups.put(updated)
        broadcastGroupUpdate(updated, GroupUpdateWire.encodeDefinitionFramed(updated))
        return updated
    }

    /**
     * __DESKTOP_PROFILE_LINKS_2026_10_29__ Share our own links with a contact, or withdraw
     * them.
     *
     * Withdrawing sends `visible:false` and NO links, which is iOS's own encoder
     * (`links: visible ? myLinks : nil`) and the only sane shape: a withdrawal must not hand
     * over the very strings it is withdrawing.
     *
     * What it cannot do is take back what was already delivered, and the caller should say so
     * rather than imply otherwise — the peer holds those strings on their disk. This asks
     * them to stop showing them.
     */
    fun setProfileLinksVisible(
        peerAddress: String,
        visible: Boolean,
        links: com.oshi.desktop.msg.ProfileLinksPayload.Links,
    ): SendOutcome = sendPlaintext(
        peerAddress,
        com.oshi.desktop.msg.ProfileLinksPayload.encode(visible, links),
        storeRow = false,
    )

    /**
     * __DESKTOP_OFFLINE_ONLY_2026_10_29__ Restrict a conversation to the mesh and the radio,
     * or lift the restriction.
     *
     * **The local state is written FIRST and unconditionally**, and that order is the whole
     * feature rather than a convenience. The promise this switch makes is that nothing this
     * device sends over that conversation touches the relay; if the write waited on the
     * request reaching the peer, then a failed send would leave the restriction off while the
     * person believed it was on. A request nobody received is a weaker promise than intended;
     * a restriction nobody applied is a broken one.
     *
     * The peer is then ASKED. There is no way to make their client refuse the internet, so
     * what comes back is a confirmation to display — [OfflineOnlyStore.confirmedCount] — and
     * a count below the other-participant count is the honest state, not a bug.
     *
     * Note the request itself travels over whatever lane is still available, which after
     * turning the switch ON excludes the relay for this conversation. That is correct and
     * worth stating: with no radio and no mesh peer, turning it on means the PEER may never
     * hear about it, and the local restriction still holds. The switch protects this device
     * first and negotiates second.
     */
    fun setOfflineOnly(peerAddress: String, on: Boolean): SendOutcome {
        val atUnix = System.currentTimeMillis() / 1000.0
        // Local first. See the note above.
        offlineOnly.set(on, peerAddress, atUnix)
        val req = com.oshi.desktop.msg.OfflineOnlyPayload.Request(on = on, atUnix = atUnix)
        return sendPlaintext(peerAddress, req.encode(), storeRow = false)
    }

    /**
     * __DESKTOP_POLLS_2026_10_29__ Send a poll into a 1:1 conversation.
     *
     * [PollPayload.sanitize] is the single definition of "sendable", so this refuses exactly
     * what the composer would refuse — iOS's reason for putting it in one place is that a
     * Send button and the wire must never disagree.
     *
     * The poll is stored locally as the payload itself, exactly as the inbound path stores
     * one: the bubble decodes it on every draw, which is what lets a ballot arriving later
     * move the bars without the row being rewritten.
     *
     * @return null when the question or the options are not sendable — not a transport
     *   failure, and distinguished from one so a caller can say which happened.
     */
    fun sendPoll(
        peerAddress: String,
        question: String,
        options: List<String>,
        allowsMultipleAnswers: Boolean = false,
        isAnonymous: Boolean = false,
        closesAtUnix: Double? = null,
    ): SendOutcome? {
        val (q, opts) = PollPayload.sanitize(question, options) ?: return null
        val poll = PollPayload.Poll(
            id = UUID.randomUUID().toString(),
            question = q,
            options = opts,
            allowsMultipleAnswers = allowsMultipleAnswers,
            isAnonymous = isAnonymous,
            // UNIX SECONDS, as a Double. The whole payload uses this epoch and iOS carries a
            // comment with the 978 307 200 s trap in it; a millisecond value here would read
            // as a poll created in the year 56 000.
            createdAtUnix = System.currentTimeMillis() / 1000.0,
            closesAtUnix = closesAtUnix,
            creatorPublicKey = address,
        )
        val wire = poll.encode()
        val outcome = sendPlaintext(peerAddress, wire, storeRow = true)
        return outcome
    }

    /**
     * Cast or change our ballot on a poll, and tell the peer.
     *
     * [picks] EMPTY is a retraction, not a no-op — both phones treat it as a real ballot that
     * has to beat the earlier one on timestamp, so it is sent rather than swallowed.
     *
     * Applied locally whatever the network said, which is [reactToMessage]'s rule and both
     * phones' order: a ballot the user pressed is local state first, and the peer converges
     * on the next successful send. The local record goes through the SAME [PollTally.record]
     * an inbound vote uses, so our own ballot obeys the same later-wins rule as everyone
     * else's — a separate "mine" field would be a second source of truth to disagree with.
     */
    fun votePoll(peerAddress: String, pollId: String, picks: List<Int>): SendOutcome {
        if (pollId.isEmpty()) return SendOutcome.NO_V2_PATH
        val atUnix = System.currentTimeMillis() / 1000.0
        val vote = PollPayload.Vote(pollId = pollId, picks = picks.distinct().sorted(), atUnix = atUnix)
        val outcome = sendPlaintext(peerAddress, vote.encode(), storeRow = false)
        if (outcome != SendOutcome.BLOCKED) pollBallots.record(pollId, address, vote.ballot)
        return outcome
    }

    /**
     * __DESKTOP_DIRECT_PIN_2026_10_29__ Pin ([messageId]) or unpin (null) a message in a 1:1
     * conversation — iOS `pinMessageAndSync` / `unpinMessageAndSync`
     * (`MessageManager.swift:9000-9012`), Android `MessageRepository.togglePinMessage`
     * (`:7399`). This is the SYNCING variant because it is the only one either phone's UI
     * calls: `ChatView.swift:5675-5677` wires the context menu straight to the *AndSync*
     * pair, and a pin that stayed on this machine would disagree with the peer's banner for
     * ever with nothing to resolve it.
     *
     * ## Three places this deliberately differs from [setGroupPin]
     *
     *  - **No permission check.** A group pin needs `canEditGroupInfo`; a 1:1 has no admin,
     *    and both phones let either party pin. [PinnedMessageStore] has the receivers.
     *  - **No ownership check.** Unlike [editMessage] and [deleteMessage], which refuse a
     *    target that is not ours (C-MSG-4), a pin may name the PEER's message — that is the
     *    ordinary case, since what you want to keep in view is usually something they said.
     *  - **No id uppercasing.** `setGroupPin` normalises to upper case because the group
     *    definition's `pinnedMessageId` comes from `UUID.uuidString`. Here the id stays as
     *    this client stored it, and every comparison is case-insensitive instead, which is
     *    what both phones do for a 1:1.
     *
     * Local state is written whatever the network said, matching [reactToMessage]'s rule and
     * both phones' order (`togglePinMessage` writes the map before it sends): a pin the user
     * pressed is local state first, and the peer converges on the next successful send. A
     * BLOCKED send is the one case that changes nothing.
     */
    fun pinDirectMessage(peerAddress: String, messageId: String?): SendOutcome {
        val target = messageId?.let {
            messages.message(peerAddress, it) ?: return SendOutcome.NO_V2_PATH
        }
        // Unpin names the message being unpinned, as both phones' payloads do; with no pin
        // on record there is nothing to name and nothing to tell the peer.
        val named = target?.id ?: pinnedMessages.get(peerAddress) ?: return SendOutcome.NO_V2_PATH
        val payload = MessageActionPayload.encode(
            actionType = if (messageId == null) MessageActionPayload.ActionType.UNPIN_MESSAGE
            else MessageActionPayload.ActionType.PIN_MESSAGE,
            targetMessageId = named,
            newContent = null,
            atUnixMillis = System.currentTimeMillis(),
        )
        val outcome = sendPlaintext(peerAddress, payload, storeRow = false)
        if (outcome != SendOutcome.BLOCKED) pinnedMessages.set(peerAddress, target?.id)
        return outcome
    }

    /**
     * __GROUP_PARITY_2026_09_23__ Forward a message to a contact — iOS `forwardGroupMessage`
     * (`GroupViews.swift:5305`): a 1:1 send of `➡️FORWARDED➡️{originalSenderName, content,
     * isForwarded, forwardCount}`; forwarding a forward increments the count and keeps the
     * first sender's name (`MessageActionsManager.swift:292`).
     */
    fun forwardMessage(fromConversation: String, messageId: String, toPeer: String): SendOutcome? {
        val conv = groups.get(fromConversation)?.let { GroupIdentity.canonicalGroupId(it.groupId) } ?: fromConversation
        val m = messages.messages(conv).firstOrNull { GroupMessageWire.sameMessageId(it.id, messageId) } ?: return null
        if (m.isDeletedForEveryone || m.hiddenLocally || m.content.isNullOrBlank()) return null
        val senderName = if (m.fromMe) ownNickname else contacts.get(m.senderAddress)?.label(m.senderAddress.take(8))
        val body = com.oshi.desktop.msg.ReplyEnvelope.forward(m.content!!, senderName)
        if (BlockPolicy.outgoingText(contacts, toPeer) == BlockPolicy.Outbound.REFUSE_BLOCKED) return SendOutcome.BLOCKED
        return sendPlaintext(toPeer, body, storeRow = true)
    }

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
        vault.delete(OWN_NICKNAME_ACCOUNT)   // __SHARED_NICKNAME_2026_09_22__ the name goes with the account
        routerState.clear()
        syncCursor.reset()
        return receipt
    }

    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ A message ANOTHER device of this account sent
     * (CLIENT_SPEC.md §3.6), stored as an OUTGOING row with status `sent`: no notification (a
     * `fromMe` row never raises one), no receipt, no control processing. Deduped by msgId —
     * devsync may bring the same row later. Control payloads and media keys never become rows
     * here; a media message's bytes arrive through devsync with its row.
     */
    internal fun storeSentCopy(copy: com.oshi.desktop.net.SentCopy) {
        val row = when (copy.conversationType) {
            com.oshi.desktop.net.SentCopy.TYPE_1TO1 -> {
                val peer = copy.peer ?: return
                val text = copy.message
                if (ControlPrefix.isControl(text) || V2FileKeyMessage.parse(text) != null) return
                Message(
                    id = copy.msgId, conversationId = peer, senderAddress = address, recipientAddress = peer,
                    fromMe = true, content = text, sentAtMs = copy.sentAtMs ?: System.currentTimeMillis(),
                    sentAtSource = if (copy.sentAtMs != null) TimestampSource.ISO8601 else TimestampSource.LOCAL_CLOCK,
                    deliveryStatus = DeliveryStatus.SENT, transport = "self-copy",
                )
            }
            com.oshi.desktop.net.SentCopy.TYPE_GROUP -> {
                val gid = GroupIdentity.canonicalGroupId(copy.groupId ?: return)
                val group = groups.get(gid) ?: run { log("client: sent-copy for a group we do not hold (${gid.take(12)}…)"); return }
                val payload = GroupMessageWire.decodeFromEnvelope(copy.message) ?: return
                // __GROUP_E2E_V2_2026_09_23__ spec §7.2: §3 steps 2-3, our own authorship, no
                // command processing; the row id is the GroupMessage `id` (dedup key everywhere).
                if (!payload.groupId.equals(gid, ignoreCase = true)) return
                if (payload.isSystem || !GroupIdentity.sameIdentity(payload.senderPublicKey, address)) return
                if (ControlPrefix.isControl(payload.body) && ControlPrefix.match(payload.body) != ControlPrefix.REPLY) return
                if (messages.messages(gid).any { GroupMessageWire.sameMessageId(it.id, payload.messageId) }) return
                groups.put(group.copy(lastActivityUnixMillis = maxOf(group.lastActivityUnixMillis, payload.timestampUnixMillis)))
                Message(
                    id = payload.messageId, conversationId = gid, senderAddress = address, recipientAddress = group.groupId,
                    fromMe = true, content = payload.body, sentAtMs = payload.timestampUnixMillis,
                    sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
                    deliveryStatus = DeliveryStatus.SENT, transport = "self-copy",
                    mentions = com.oshi.desktop.group.MentionWire.admit(
                        com.oshi.desktop.msg.ReplyEnvelope.unwrap(payload.body)?.content ?: payload.body,
                        payload.mentions, { k -> group.isMember(k) }, GroupIdentity::sameIdentity,
                    ),
                )
            }
            else -> return
        }
        if (messages.messages(row.conversationId).any { it.id.equals(row.id, ignoreCase = true) }) return
        if (messages.append(row) != MessageStore.AppendOutcome.DUPLICATE) onMessage(row)
    }

    companion object {
        /** How often the poll loop re-asks whether the ACCOUNT bundle needs a refill. */
        const val REPUBLISH_CHECK_MS = 60 * 60 * 1000L

        /** __GROUP_E2E_V2_2026_09_23__ spec §3 step 5 bounds for held group content. */
        const val MAX_HELD_GROUP_CONTENT = 200
        const val HELD_GROUP_CONTENT_TTL_MS = 7L * 24 * 3600 * 1000

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

        /** __SHARED_NICKNAME_2026_09_22__ Vault entry holding this account's own nickname (UTF-8). */
        const val OWN_NICKNAME_ACCOUNT = "profile.nickname"

        fun defaultDisplayName(): String =
            System.getenv("OSHI_NAME")?.takeIf { it.isNotBlank() }
                ?: "${System.getProperty("user.name") ?: "OSHI"} (${System.getProperty("os.name")})"
    }
}
