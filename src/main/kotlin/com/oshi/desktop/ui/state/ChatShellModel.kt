package com.oshi.desktop.ui.state

import com.oshi.messenger.util.CashuPayCommand

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.app.short
import com.oshi.desktop.app.stamp
import com.oshi.desktop.group.GroupIdentity
import com.oshi.desktop.i18n.t
import com.oshi.desktop.lora.LoRaNodeBindings
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.ControlEvent
import com.oshi.desktop.msg.ControlPayloadRouter
import com.oshi.desktop.msg.TypingPayload
import com.oshi.desktop.pairing.ContactQr
import com.oshi.desktop.scheduled.ScheduledMessageRunner
import com.oshi.desktop.store.ContactStore
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.MediaType
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The whole state of the desktop window — PARITY.md row 1.1, the first vertical slice.
 *
 * ============================================================ WHY THIS FILE HAS NO UI IN IT
 *
 * There is not one `androidx.compose` import below, and there must never be one. Two
 * reasons, and the second is the load-bearing one:
 *
 *  1. A test that needs a display cannot run on a headless CI runner, and this project's
 *     Working rule 3 is that a guard which has not been watched failing is not a guard. A
 *     guard living inside a `@Composable` could only ever be watched failing on a machine
 *     with a screen, which is not where this project's Windows and Linux evidence has to
 *     come from.
 *  2. Everything a window could LIE about is decided here, not in the drawing code. Whether
 *     a send left the machine, whether a conversation is one the ratchet vouched for,
 *     whether a composer may be enabled at all — those are the claims that matter, and they
 *     are computed in plain Kotlin so a JUnit test can hold them to account. The composables
 *     in [com.oshi.desktop.ui] render strings this file produced; they choose nothing.
 *
 * So [ChatShellModelTest] drives a REAL [OshiClient] against a REAL in-process relay, with
 * a direct executor, and never opens a window. Which also means: **the drawing code is not
 * covered by anything.** Said out loud here so nobody reads a green suite as a tested UI.
 *
 * ============================================================ WHAT IT REFUSES TO SHOW
 *
 * A conversation log is not a homogeneous thing on this client, and pretending it is would
 * be the single most misleading thing this window could do. Four kinds of row can land in
 * [com.oshi.desktop.store.MessageStore], and only ONE of them is a message the Double
 * Ratchet authenticated:
 *
 *  - **direct** — a 1:1 V2 conversation. Ratcheted, authenticated, and the only kind whose
 *    composer is enabled.
 *  - **group** — PARITY.md row 0.17, partial, with three sub-rows blocked. It has a working
 *    fan-out send in [OshiClient.sendGroupText]; it is not wired here, and the composer says
 *    so rather than being quietly absent.
 *  - **bot** — `bot!<groupId>`, PARITY.md row 0.26. **This lane has no encryption at all.**
 *    A composer here would put a plaintext-to-the-server send behind the same text box as an
 *    end-to-end encrypted one, which is exactly the confusion the store's `bot!` prefix
 *    exists to prevent. Refused, with the reason on screen.
 *  - **lora** — `lora!<nodehex>`, PARITY.md row 0.27. Stock Meshtastic text, unauthenticated
 *    by construction and flagged `unverified`. There is no verified identity here to answer.
 *
 * Hiding the last three would be its own kind of lie — they ARE in the store, and a list
 * that silently omits rows is a list nobody can reconcile with `/chats`. So they are shown,
 * labelled by kind, and their composers are disabled with the reason spelled out.
 *
 * ============================================================ WHAT IT REFUSES TO CLAIM
 *
 * **A send that did not leave is never drawn as sent.** [OshiClient.send] writes the local
 * row whatever happens — a message the user typed does not vanish because the network
 * refused it — and puts the truth in [DeliveryStatus]. This model surfaces that status on
 * every outgoing row and puts the outcome in [ShellState.notice] in words, because "the
 * bubble appeared" is what a user reads as success and it is not evidence of anything.
 *
 * **Reachability is UNKNOWN until it has been measured.** [OshiClient.canReach] is a network
 * call (it may fetch a prekey bundle), so it runs on [worker], and until it answers the
 * pane says the check has not been run — never "online", never a green dot. There is no
 * presence protocol on this client and a green dot would be inventing one.
 *
 * **A control payload typed into the composer is refused and the text is KEPT.**
 * [OshiClient.send] rejects a body that is itself a row-0.18 sentinel and stores nothing, so
 * clearing the box would destroy what the user typed to no purpose.
 *
 * ============================================================ THREADING
 *
 * Inbound delivery happens on `OshiClient`'s poll thread (see its class note), and the
 * window draws on the AWT thread. Every mutation below therefore takes [lock], recomputes
 * one immutable [ShellState], and publishes it to [onChange] — the renderer never reads a
 * store and never sees a half-applied change. Anything that can block (a send, a
 * reachability probe) is handed to [worker] OUTSIDE the lock, so the drawing thread is never
 * the thread waiting on the relay. Tests pass a direct executor and get determinism for
 * free.
 */
class ChatShellModel(
    private val client: OshiClient,
    private val worker: Executor = defaultWorker(),
    /** UI-only local alert; persistence and relay acknowledgement never depend on it. */
    private val onIncomingNotification: (sender: String?, preview: String?) -> Unit = { _, _ -> },
    /**
     * __DESKTOP_PREVIEW_PARITY_2026_10_27__ Whether the shell notification may carry
     * the sender and the text. Read at notify time, not captured once, so flipping
     * the switch takes effect on the next message rather than the next launch.
     * Default false keeps the guarantee in the comment at the call site intact.
     */
    private val notificationPreviewEnabled: () -> Boolean = { false },
    /**
     * __HIDDEN_WINDOW_NOTIFY_2026_10_29__ Is the window on screen RIGHT NOW?
     *
     * A message in the conversation the user is looking at must not pop a notification —
     * that is the phones' rule and it was this object's too. But the desktop has a state
     * no phone has: the window hidden to the tray with a conversation still selected.
     * The close button hides rather than quits (row 2.3), so this is the ordinary way to
     * put OSHI away. In that state the old rule read "selected, therefore seen" and the
     * message arrived with **no notification and no unread badge** — the one chat the
     * user is most likely to be waiting on was the one chat that could not tell them.
     * Row 2.3 recorded it under "Not verified" as a rule rather than as the defect it is.
     *
     * Read at notify time, like [notificationPreviewEnabled], so hiding the window takes
     * effect on the next message rather than the next launch. Defaults to `true`, which
     * keeps every headless caller and test on the previous behaviour.
     */
    private val windowVisible: () -> Boolean = { true },
) : AutoCloseable {

    // ------------------------------------------------------------------ mutable core

    private val lock = Any()
    private val unread = HashMap<String, Int>()
    /** __UNREAD_DIVIDER_2027__ [ThreadView.unreadAtOpen] for the selected conversation. */
    private var unreadAtOpen = 0
    /** __MENTIONS_2026_09_23__ conversations with an unseen `@you`; cleared when opened. */
    private val mentionedYou = HashSet<String>()
    /** __MENTIONS_2026_09_23__ members picked from the `@` picker for the current draft, per group. */
    private val pickedMentions = HashMap<String, MutableList<com.oshi.desktop.group.MentionWire.Mention>>()

    /** The composer's `@` picker chose [m] for the open conversation's draft. */
    fun pickMention(m: com.oshi.desktop.group.MentionWire.Mention) {
        synchronized(lock) {
            val id = selectedId ?: return
            pickedMentions.getOrPut(id) { mutableListOf() }.add(m)
        }
    }
    private var selectedId: String? = null
    private var destination: Destination = Destination.MESSAGES
    private var pane: Pane = Pane.CONVERSATION
    private var draftText: String = ""
    /** __DRAFT_CLEAR_RACE_2026_11_18__ bumped when a text send settles; see [ShellState.sendSerial]. */
    private var sendSerial: Int = 0
    /** __GROUP_PARITY_2026_09_23__ the message the next send replies to, and in which conversation. */
    private var replyTarget: Pair<String, com.oshi.desktop.msg.ReplyEnvelope.Quote>? = null
    /**
     * __GROUP_COMMENTS_2026_11_21__ GROUP_INFO_SETTINGS_SPEC §2.12.6: the comment thread open beside
     * the group (group id, post id), its own draft and the comment it replies to. Closed when
     * the conversation changes.
     */
    private var commentsFor: Pair<String, String>? = null
    private var commentDraft: String = ""
    private var commentReplyTo: String? = null
    private var commentBusy: Boolean = false
    /** __GROUP_PARITY_2026_09_23__ group id → member key → typing-until (epoch ms). */
    private val groupTyping = HashMap<String, HashMap<String, Long>>()
    private var busy: Boolean = false
    private var notice: Notice? = null
    /** __GROUP_INFO_SETTINGS_2026_11_20__ the public-link confirmation, and the link editor's state. */
    private var publicJoin: PublicJoinState? = null
    private var linkBusy: Boolean = false
    private var linkResult: LinkResult? = null
    /** Account-pane outcome for the non-destructive V2 archive actions. */
    private var syncNotice: Notice? = null
    /** More-pane outcome for the encrypted `.oshiexport` export/import. */
    private var backupNotice: Notice? = null
    /** __SHARED_NICKNAME_2026_09_22__ More-pane outcome for the nickname field. */
    private var profileNotice: Notice? = null
    private var reach: Reach = Reach.UNKNOWN
    /** Direct-peer typing is transient UI state; control payloads are never stored as rows. */
    private val typingUntilMs = HashMap<String, Long>()
    private val typingExpiry: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "oshi-typing-expiry").apply { isDaemon = true }
    }

    /** Set by the renderer. Called on whichever thread caused the change — see THREADING. */
    var onChange: (ShellState) -> Unit = {}

    @Volatile
    var state: ShellState = ShellState.empty(client.address, client.displayName)
        private set

    private var previousOnMessage: ((Message) -> Unit)? = null
    private var previousOnScheduledRun: ((ScheduledMessageRunner.DueRun) -> Unit)? = null
    private var previousOnControl: ((String, ControlEvent, ControlPayloadRouter.Outcome) -> Unit)? = null
    /** The peer currently receiving an ephemeral typing ping, if any. Never persisted. */
    private var typingTarget: String? = null

    // __LIVE_SHARE_COUNTER_2026_09_22__ / __TOOLBAR_DRIFT_2026_09_22__ — the header's two
    // indicators. Read on a UI tick, never folded into [ShellState]: both depend on the clock
    // (a share ends, a node ages out, a peer drop outlasts its grace) and rebuilding the whole
    // state every second to notice would re-read every store for nothing.
    private val incomingShares = IncomingLiveShares()
    private val badgeTracker = NetworkBadgeTracker()
    private var previousOnPlace: ((String, com.oshi.desktop.place.PlaceEvent) -> Unit)? = null

    // ------------------------------------------------------------------ lifecycle

    /**
     * Subscribe to live inbound messages.
     *
     * The existing callbacks are CHAINED, not replaced. `OshiClient` exposes exactly one
     * callback slot for each event, and a window that silently unhooks whatever was there is
     * a window that breaks the REPL the moment somebody runs both in one process — which the
     * `--ui` entry point does not do today and which is precisely the kind of assumption that
     * stops being true without anyone noticing.
     */
    fun attach() {
        synchronized(lock) {
            if (previousOnMessage != null) return
            previousOnMessage = client.onMessage
            previousOnScheduledRun = client.onScheduledRun
            previousOnControl = client.onControl
        }
        client.onMessage = { m ->
            previousOnMessage?.invoke(m)
            onInbound(m)
        }
        client.onScheduledRun = { run ->
            previousOnScheduledRun?.invoke(run)
            // A run changes the persisted local queue. Rebuild rather than attempting to
            // guess which entries transitioned, including deferred and failed entries.
            refresh()
        }
        client.onControl = { peer, event, outcome ->
            previousOnControl?.invoke(peer, event, outcome)
            onControl(peer, event)
        }
        previousOnPlace = client.onPlace
        client.onPlace = { from, event ->
            previousOnPlace?.invoke(from, event)
            onPlace(from, event)
        }
        runCatching { client.devSync.onRemoteRead { conv, upTo -> onRemoteRead(conv, upTo) } }
        // __GROUP_E2E_V2_2026_09_23__ a roster/name change or a group edit/delete must repaint too.
        val previousGroupEvent = client.onGroupEvent
        client.onGroupEvent = { r -> previousGroupEvent(r); publish() }
        val previousGroupContent = client.onGroupContentChanged
        client.onGroupContentChanged = { gid -> previousGroupContent(gid); publish() }
        // __GROUP_RESEND_2026_11_25__ "Sent to X of Y" moves as the resend queue reaches members.
        val previousDelivery = client.onGroupDeliveryChanged
        client.onGroupDeliveryChanged = { previousDelivery(); publish() }
        // __GROUP_COMMENTS_2026_11_21__ a comment repaints (counts, open thread) and notifies only
        // when the client says so (§2.12.5: a reply to my comment, or a mention of me).
        val previousComment = client.onComment
        client.onComment = { m, notify -> previousComment(m, notify); onComment(m, notify) }
        client.onLoRaStatusChanged = { publish() }
        // __PROFILE_PHOTOS_DESKTOP_2027__ a contact's new picture repaints the list and header.
        client.photos.addChangeListener { publish() }
        com.oshi.desktop.store.PrivacyPreferences.addChangeListener(photoPrivacyListener)
        // __VIEW_ONCE_DESKTOP_2027__ anything opened before a quit or crash is destroyed now.
        worker.execute { if (runCatching { client.wipeOpenedViewOnce() }.getOrDefault(0) > 0) publish() }
        refresh()
    }

    override fun close() {
        val callbacks = synchronized(lock) {
            val old = Triple(previousOnMessage, previousOnScheduledRun, previousOnControl)
            previousOnMessage = null
            previousOnScheduledRun = null
            previousOnControl = null
            old
        }
        callbacks.first?.let { client.onMessage = it }
        callbacks.second?.let { client.onScheduledRun = it }
        callbacks.third?.let { client.onControl = it }
        previousOnPlace?.let { client.onPlace = it }
        previousOnPlace = null
        typingExpiry.shutdownNow()
        com.oshi.desktop.store.PrivacyPreferences.removeChangeListener(photoPrivacyListener)
    }

    /**
     * __MERGE_AB_PROFILE_PHOTO_PRIVACY__ Profile Photo switched: tell the contacts again, so
     * the ones holding our picture drop it (OFF) or get it back (ON). Only when there IS a
     * picture — with none, the next profile update says the same thing anyway.
     */
    private val photoPrivacyListener: (com.oshi.desktop.store.PrivacyPreferences.Values, com.oshi.desktop.store.PrivacyPreferences.Values) -> Unit =
        { old, new ->
            if (old.profilePhotoVisible != new.profilePhotoVisible) worker.execute {
                runCatching {
                    if (client.ownPhotoBase64 != null) client.broadcastProfile(clear = client.ownNickname == null)
                }
            }
        }

    // ------------------------------------------------------------------ commands

    /** Re-read the stores and republish. Cheap enough to call on every change. */
    fun refresh() = publish()

    /** __GROUP_RESEND_2026_11_25__ "Retry now" on a group message some members have not received. */
    fun retryGroupDelivery(messageId: String) = client.retryGroupDelivery(messageId)

    /** The reach badge as of [nowMs]: mesh peers (drops held a few seconds) and LoRa nodes. */
    fun networkBadge(nowMs: Long): NetworkBadge {
        val attached = client.loraAttached
        val nodes = if (attached) client.loraOnlineNodes(nowMs) else 0
        val peers = client.meshPeerCount
        return synchronized(lock) { badgeTracker.observe(peers, attached, nodes, nowMs) }
    }

    /**
     * Who is reachable as of [nowMs] — read when the reach badge is CLICKED, never on the
     * badge's own tick. See [MeshReach].
     */
    fun meshReach(nowMs: Long = System.currentTimeMillis()): MeshReach = MeshReach(
        lanPeers = client.meshPeerNames,
        loraNodes = client.loraOnlineNodeList(nowMs).map {
            MeshReach.LoRaNode(
                name = it.name,
                // The same `!%08x` a Meshtastic node, the stock app and OSHI's own LoRa
                // conversation keys all spell a node with. A decimal node number here and a hex
                // one in the thread title reads as two different radios.
                id = "!%08x".format(it.num.toLong()),
                lastHeardMs = it.lastHeardMs,
            )
        },
    )

    /**
     * Contacts sharing their live location WITH this desktop as of [nowMs]. The desktop
     * cannot share its own (no GPS, PARITY.md row 0.19), so this is the only direction.
     */
    fun incomingLiveShares(nowMs: Long): List<IncomingShare> =
        incomingShares.active(nowMs) { from -> synchronized(lock) { labelFor(from, kindOf(from)) } }

    private fun onPlace(from: String, event: com.oshi.desktop.place.PlaceEvent) {
        if (event !is com.oshi.desktop.place.PlaceEvent.Location) return
        val sessionId = event.payload.sessionId ?: return
        val live = when (event.state) {
            com.oshi.desktop.place.LiveState.LIVE -> true
            com.oshi.desktop.place.LiveState.STOPPED, com.oshi.desktop.place.LiveState.EXPIRED -> false
            else -> return // a one-time pin, or a share with no bound — not counted
        }
        // The tracker's PINNED expiry, the one row 0.19 renders against — never the ping's own.
        val end = client.places.tracker().session(sessionId)?.pinnedExpiryMs
            ?: event.payload.expiresAtMs ?: return
        incomingShares.observe(from, sessionId, live, end)
    }

    fun show(target: Pane) {
        synchronized(lock) { pane = target }
        publish()
    }

    /** Privacy controls are enforced in [OshiClient] at each send site. */
    fun setReceiptPrivacy(delivery: Boolean? = null, read: Boolean? = null, typing: Boolean? = null) {
        typing?.let { client.typingIndicatorsEnabled = it }
        delivery?.let { client.deliveryReceiptsEnabled = it }
        read?.let { client.readReceiptsEnabled = it }
        publish()
    }

    /** Archive the local contact book for this identity's other devices. */
    fun syncPushContacts() {
        synchronized(lock) { busy = true; syncNotice = null }
        publish()
        worker.execute {
            val result = client.syncPushContacts()
            synchronized(lock) {
                busy = false
                syncNotice = result.fold(
                    onSuccess = { Notice(t("conv.n.sync_archived", it.accepted.size, it.maxSeq), Severity.OK) },
                    onFailure = { Notice(t("conv.n.sync_archive_failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    /**
     * __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Write the whole history to [target] as an
     * encrypted `.oshiexport` (account key; no plaintext ever touches the disk).
     */
    fun exportMessages(target: File) {
        synchronized(lock) { busy = true; backupNotice = null }
        publish()
        worker.execute {
            val result = runCatching { com.oshi.desktop.app.MessageBackup(client).export(target) }
            synchronized(lock) {
                busy = false
                backupNotice = result.fold(
                    onSuccess = { r ->
                        if (r.unreadableConversations > 0) Notice(
                            t("desktop.export.done", r.messages, r.file.path) + " " +
                                t("desktop.export.done.partial", r.unreadableConversations),
                            Severity.ERROR,
                        ) else Notice(t("desktop.export.done", r.messages, r.file.path), Severity.OK)
                    },
                    onFailure = { Notice(t("desktop.export.failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    /**
     * __CALL_LOG_AT_REST_2026_09_23__ "Share call diagnostics": decrypt the sealed call log,
     * apply the cross-platform allow-list redaction (iOS `redact(logs:)`, byte-identical) and
     * write it to [target]. On the worker: decrypting an hour of call log is not UI work.
     */
    fun exportCallDiagnostics(target: File) {
        synchronized(lock) { busy = true; backupNotice = null }
        publish()
        worker.execute {
            val result = runCatching { com.oshi.desktop.diag.DesktopCallLog.exportTo(target) }
            synchronized(lock) {
                busy = false
                backupNotice = result.fold(
                    onSuccess = { f ->
                        if (f == null) Notice(t("desktop.calldiag.empty"), Severity.ERROR)
                        else Notice(t("desktop.calldiag.done", f.path), Severity.OK)
                    },
                    onFailure = { Notice(t("desktop.calldiag.failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    /**
     * Merge an `.oshiexport` made by this account on any desktop. Additive and silent:
     * existing messages are kept as they are and nothing is replayed (no receipts, no
     * notifications); the conversation list is rebuilt from the store afterwards.
     */
    fun importMessages(source: File) {
        synchronized(lock) { busy = true; backupNotice = null }
        publish()
        worker.execute {
            val result = runCatching { com.oshi.desktop.app.MessageBackup(client).import(source) }
            synchronized(lock) {
                busy = false
                backupNotice = result.fold(
                    onSuccess = { r ->
                        val invalid = if (r.invalid > 0) " " + t("desktop.import.invalid", r.invalid) else ""
                        Notice(
                            t("import.messages_imported", r.imported) + " " + t("desktop.import.alreadyPresent", r.alreadyPresent) + invalid,
                            if (r.invalid > 0) Severity.INFO else Severity.OK,
                        )
                    },
                    onFailure = {
                        val reason = (it as? com.oshi.desktop.store.MessageExportException)?.userMessage()
                            ?: (it.message ?: it.javaClass.simpleName)
                        Notice(reason, Severity.ERROR)
                    },
                )
            }
            publish()
        }
    }

    /** Pull and apply the encrypted archive, without acknowledging deletion to the server. */
    fun syncPull() {
        synchronized(lock) { busy = true; syncNotice = null }
        publish()
        worker.execute {
            val result = client.syncPull()
            synchronized(lock) {
                busy = false
                syncNotice = result.fold(
                    onSuccess = {
                        Notice(
                            t("conv.n.sync_pulled", it.applied, it.skippedUndecryptable, it.skippedStale, it.skippedOwn, it.lastSeq, it.headMaxSeq),
                            Severity.OK,
                        )
                    },
                    onFailure = { Notice(t("conv.n.sync_pull_failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    /**
     * Switch destination — the five-way control the shipped app carries at the top.
     *
     * It changes NOTHING about the conversation that is open. A user who walks to Places and
     * back expects the thread they were reading to still be there, and `selectedId` is left
     * alone precisely so that walking away is not a way to lose your place. The one thing it
     * does reset is [notice]: an outcome line belongs to the screen that produced it, and a
     * "sent" banner still sitting there after a trip through Settings is a stale claim.
     */
    fun go(target: Destination) {
        synchronized(lock) {
            destination = target
            notice = null
        }
        publish()
    }

    /**
     * Open a conversation.
     *
     * Clears its unread count — the user is looking at it — and kicks off a reachability
     * probe on [worker], never inline: this is the one action in the window that can touch
     * the network, and a list click that blocks on a bundle fetch is a list click that
     * freezes the window on a slow relay.
     */
    /**
     * __LINUX_NOTIFICATION_CLICK_2026_11_21__ The conversation the latest notification was about,
     * so clicking that notification opens it (notify-send's default action on Linux).
     */
    @Volatile var lastNotifiedConversationId: String? = null
        private set

    fun select(id: String?) {
        val kind = id?.let { kindOf(it) }
        var stopTyping: String? = null
        val leaving = synchronized(lock) { selectedId }
        // __VIEW_ONCE_DESKTOP_2027__ leaving a conversation destroys what was opened in it.
        if (leaving != null && leaving != id) worker.execute { runCatching { client.wipeOpenedViewOnce(leaving) }; publish() }
        synchronized(lock) {
            stopTyping = typingTarget
            typingTarget = null
            selectedId = id
            pane = Pane.CONVERSATION
            draftText = ""
            replyTarget = null
            if (commentsFor?.first != id) { commentsFor = null; commentDraft = ""; commentReplyTo = null }
            notice = null
            reach = Reach.UNKNOWN
            unreadAtOpen = if (id != null) unread[id] ?: 0 else 0
            if (id != null) { unread.remove(id); mentionedYou.remove(id) }
        }
        publish()
        stopTyping?.takeIf { it != id }?.let { emitTyping(it, false) }
        if (id != null && kind == ConversationKind.DIRECT) probeReach(id)
        if (id != null && kind == ConversationKind.DIRECT && client.messages.messages(id).any { !it.fromMe }) {
            worker.execute { runCatching { client.sendReadReceipt(id) } }
        }
        // __DEVSYNC_READ_STATE_2026_09_23__ opened = read here; own-device sync carries it (READ_STATE).
        if (id != null) worker.execute { runCatching { client.devSync.noteRead(id) } }
    }

    /**
     * __DEVSYNC_READ_STATE_2026_09_23__ Another device of this account read [conversationId] up to
     * [readUpToMs]: only what arrived after that stays unread here. Never emits a receipt.
     */
    private fun onRemoteRead(conversationId: String, readUpToMs: Long) {
        val newer = runCatching { client.messages.messages(conversationId).count { !it.fromMe && it.sentAtMs > readUpToMs } }.getOrNull() ?: return
        val changed = synchronized(lock) {
            val cur = unread[conversationId] ?: return@synchronized false
            if (newer == 0) unread.remove(conversationId) else unread[conversationId] = minOf(cur, newer)
            unread[conversationId] != cur
        }
        if (changed) publish()
    }

    fun draft(text: String) {
        synchronized(lock) {
            draftText = text
            notice = null
        }
        publish()
    }

    /** Called by the Compose-local editor without publishing on every keystroke. */
    fun typingChanged(conversationId: String, hasText: Boolean) {
        val next = synchronized(lock) {
            val kind = kindOf(conversationId)
            if (selectedId != conversationId || (kind != ConversationKind.DIRECT && kind != ConversationKind.GROUP)) return
            val wasTyping = typingTarget == conversationId
            when {
                hasText && !wasTyping -> { typingTarget = conversationId; true }
                !hasText && wasTyping -> { typingTarget = null; false }
                else -> return
            }
        }
        emitTyping(conversationId, next)
    }

    private fun emitTyping(peer: String, typing: Boolean) {
        // Typing is ephemeral: it never freezes the editor or creates a message-status claim.
        // __GROUP_E2E_V2_2026_09_23__ a group gets the group typing fan-out (skipped over 20 members).
        // __TYPING_PRIVACY_2027__ switched off: nothing leaves, neither start nor stop.
        if (!client.typingIndicatorsEnabled) return
        val group = kindOf(peer) == ConversationKind.GROUP
        worker.execute { runCatching { if (group) client.sendGroupTyping(peer, typing) else client.sendTyping(peer, typing) } }
    }

    /**
     * Send the draft to the open conversation.
     *
     * Returns immediately; the outcome lands in [ShellState.notice] when [worker] gets to
     * it. [ShellState.busy] is true in between, and the composer is disabled while it is —
     * a second click during a slow relay round trip would send the same text twice.
     */
    fun send() {
        var stopTyping: String? = null
        // __CASHU_2026_10_24__ `/pay <token> [public]`, decided before anything
        // is queued. Two refusals, the same two as on the phones:
        //   * nothing that fails to decode goes on the wire — a typo in a
        //     pasted token is money thrown away, and neither side ever learns
        //     why;
        //   * a bearer token into a GROUP needs the word `public`, because
        //     everyone who can read the thread can spend it and the first to
        //     redeem wins.
        // A refusal keeps the draft: a token is tedious to paste twice, and the
        // user has to see it to fix it.
        synchronized(lock) {
            val id = selectedId
            if (id != null && CashuPayCommand.handles(draftText)) {
                val isGroup = kindOf(id) == ConversationKind.GROUP
                when (val outcome = CashuPayCommand.parse(draftText, isGroup)) {
                    is CashuPayCommand.Outcome.Send -> draftText = outcome.token.raw
                    is CashuPayCommand.Outcome.NeedsPublicConfirm -> {
                        notice = Notice(
                            // The IDENTIFIER iOS uses, not the English sentence:
                            // this catalogue is extracted from the iOS tree, so a
                            // key the desktop invents exists in no locale at all —
                            // English included, which is the worst case because a
                            // locale-versus-English parity test then finds nothing
                            // wrong. And one literal, never a concatenation: the
                            // extractor would read half a key.
                            t("cashu_pay_public_confirm", outcome.summary),
                            Severity.ERROR,
                        )
                        publish(); return
                    }
                    CashuPayCommand.Outcome.Usage -> {
                        notice = Notice(t("cashu_pay_usage"), Severity.INFO)
                        publish(); return
                    }
                    CashuPayCommand.Outcome.NotAToken -> {
                        notice = Notice(t("cashu_pay_not_token"), Severity.ERROR)
                        publish(); return
                    }
                    CashuPayCommand.Outcome.Invalid -> {
                        notice = Notice(t("cashu_pay_invalid"), Severity.ERROR)
                        publish(); return
                    }
                }
            }
        }
        val (target, text) = synchronized(lock) {
            val id = selectedId
            val composer = composerFor(id)
            when {
                id == null -> return
                !composer.enabled -> {
                    notice = Notice(composer.disabledReason ?: t("conv.n.cannot_send"), Severity.ERROR)
                    null to ""
                }
                busy -> null to ""
                draftText.isBlank() -> {
                    notice = Notice(t("conv.n.nothing_to_send"), Severity.INFO)
                    null to ""
                }
                else -> {
                    busy = true
                    stopTyping = typingTarget
                    typingTarget = null
                    id to draftText
                }
            }
        }
        if (target == null) { publish(); return }

        stopTyping?.let { emitTyping(it, false) }

        val group = kindOf(target) == ConversationKind.GROUP
        val quote = synchronized(lock) { replyTarget?.takeIf { it.first == target }?.second }
        val mentions = synchronized(lock) { pickedMentions.remove(target)?.toList().orEmpty() }
        worker.execute {
            val result = try {
                if (group) groupSendResult(client.sendGroupText(target, text, quote, mentions))
                else directSendResult(client.send(target, text, quote))
            } catch (e: Exception) {
                synchronized(lock) {
                    busy = false
                    notice = Notice(t("conv.n.send_failed", "${e.javaClass.simpleName}: ${e.message}"), Severity.ERROR)
                }
                publish()
                return@execute
            }
            synchronized(lock) {
                busy = false
                notice = Notice(result.text, result.severity)
                // A refused control payload is the one outcome that stored NOTHING — both
                // send paths return before reaching the store — so clearing the box would
                // delete what the user typed and leave no trace of it anywhere.
                if (!result.keepDraft) { draftText = ""; replyTarget = null }
                sendSerial++
            }
            publish()
        }
    }

    /**
     * __GROUP_PARITY_2026_09_23__ Reply to [messageId] in the open conversation (1:1 or group),
     * as iOS's swipe-to-reply does (`GroupViews.swift:5285`): the quote carries the
     * UNWRAPPED text, never a sentinel's JSON, truncated at 180.
     */
    fun beginReply(messageId: String) {
        synchronized(lock) {
            val id = selectedId ?: return
            val m = client.messages.messages(id).firstOrNull { it.id == messageId } ?: return
            if (m.isDeletedForEveryone) return
            replyTarget = id to com.oshi.desktop.msg.ReplyEnvelope.Quote(
                originalMessageId = m.id,
                originalSenderKey = if (m.fromMe) client.address else m.senderAddress,
                originalText = com.oshi.desktop.msg.ReplyEnvelope.quotableText(m.content, m.mediaType?.wire),
                originalTimestampMs = m.sentAtMs,
                originalMediaType = m.mediaType?.wire,
            )
        }
        publish()
    }

    fun cancelReply() {
        synchronized(lock) { replyTarget = null }
        publish()
    }

    /** Who a sender is, as THIS device names them: me, our contact alias, their nickname, or a short key. */
    private fun senderLabel(key: String): String =
        if (com.oshi.desktop.group.GroupIdentity.sameIdentity(key, client.address)) "me"
        else client.contacts.get(key)?.label(short(key)) ?: short(key)

    private fun quoteRow(q: com.oshi.desktop.msg.ReplyEnvelope.Quote): QuoteRow = QuoteRow(
        messageId = q.originalMessageId,
        who = if (q.originalSenderKey.isBlank()) "" else senderLabel(q.originalSenderKey),
        text = q.originalText.ifBlank { q.originalMediaType?.let { "[$it]" }.orEmpty() },
    )

    /**
     * Send an attachment to the open conversation — PARITY.md rows 0.15 and 0.12.
     *
     * The path this takes is `OshiClient.sendFile`, the same one `/sendfile` takes, which is
     * the path that has been watched crossing to a shipped Android handset over the live
     * relay. Nothing about the encryption, the chunking or the blob upload is re-decided
     * here; this is a file chooser wired to a tested call.
     *
     * GROUPS GO THROUGH `OshiClient.sendGroupFile` (GROUP_E2E_V2_SPEC §4). This comment used
     * to say groups were REFUSED because there was no group media fan-out on this client at
     * all; that stopped being true when `sendGroupFile` landed, and a comment describing a
     * gap the code closed sends the next reader to write what is already there. The fan-out
     * is the shape Android sends: the bytes are sealed ONCE and PUT into one blob reservation
     * PER MEMBER — the blob store authorises exactly one recipient, so N uploads of the same
     * ciphertext is the design, not a workaround — and each member's `type:"group"` envelope
     * carries the `v2file` key JSON plus `groupMessage`. Over the blob cap ⇒ nobody.
     */
    fun attach(
        file: File,
        mediaType: MediaType? = null,
        discardAfterSend: Boolean = false,
        /** __VIDEO_NOTE_2026_09_24__ send as a round video note (the file is a sealed MP4 kept for our own bubble). */
        videoNote: com.oshi.desktop.media.VideoNoteWire.Meta? = null,
        /** __VIEW_ONCE_DESKTOP_2027__ send it view-once (1:1 photo/video/voice only; ignored elsewhere). */
        viewOnce: Boolean = false,
    ) {
        val target = synchronized(lock) {
            val id = selectedId
            val composer = composerFor(id)
            when {
                id == null -> return
                busy -> return
                !composer.attachEnabled -> {
                    notice = Notice(
                        composer.attachDisabledReason ?: t("conv.n.no_attachments"),
                        Severity.ERROR,
                    )
                    null
                }
                !file.isFile -> {
                    notice = Notice(t("conv.n.not_readable_file", file.path), Severity.ERROR)
                    null
                }
                else -> { busy = true; id }
            }
        }
        if (target == null) { publish(); return }

        val group = kindOf(target) == ConversationKind.GROUP
        worker.execute {
            val sentBytes = file.length()
            // __OWN_VOICE_NOTE_BUBBLE_2026_11_21__ A file made only to be sent (a voice note, a marked-up
            // camera photo) lives in `media-tmp/` and is deleted after the send — but the outgoing row
            // keeps the path it was sent from as its `localRef`, so our OWN bubble became "the decrypted
            // file is not at that path any more" the moment it left (seen in the running app, 21/11),
            // and a retry after a failed upload had nothing to resend. Keep a SEALED copy in `media/`
            // (as a received attachment is kept) and send from it; only the plaintext temp is deleted.
            val source = if (discardAfterSend) keepSealedCopy(file) ?: file else file
            val result = try {
                // __GROUP_E2E_V2_2026_09_23__ group media: one sealed file, one blob per member (spec §4).
                if (group) client.sendGroupFile(target, source, videoNote = videoNote)?.let(::groupSendResult)
                    ?: SendResult(t("desktop.group.media.refused"), Severity.ERROR, keepDraft = false)
                else directSendResult(client.sendFile(target, source, mediaType, videoNote, viewOnce = viewOnce))
            } catch (e: Exception) {
                if (discardAfterSend) { runCatching { file.delete() }; if (source != file) runCatching { source.delete() } }
                synchronized(lock) {
                    busy = false
                    notice = Notice(t("conv.n.attach_failed", "${e.javaClass.simpleName}: ${e.message}"), Severity.ERROR)
                }
                publish()
                return@execute
            }
            synchronized(lock) {
                busy = false
                notice = Notice(
                    if (result.severity == Severity.OK)
                        t("conv.n.file_sent", file.name, com.oshi.desktop.ui.components.humanBytes(sentBytes))
                    else result.text,
                    result.severity,
                )
            }
            if (discardAfterSend) runCatching { file.delete() }
            publish()
        }
    }

    /**
     * __OWN_VOICE_NOTE_BUBBLE_2026_11_21__ [file] sealed into `media/sent/<random>/<same name>` (the
     * name is what travels as the attachment's filename, so it is kept), or null when it cannot be
     * written — then the send goes ahead from the temp file as before.
     */
    private fun keepSealedCopy(file: File): File? = runCatching {
        val out = File(File(File(client.mediaDir, "sent"), java.util.UUID.randomUUID().toString().take(12)), file.name)
        client.mediaVault.sealingStream(out).use { sink -> file.inputStream().use { it.copyTo(sink) } }
        out
    }.getOrNull()

    /**
     * Send one contact INTO the open conversation — PARITY.md row 0.19's third payload.
     *
     * __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ `OshiClient.sendContactCard` has existed
     * since the media path landed and was reachable only from the console, so this window
     * could DISPLAY a received card (`MediaPresentation` draws "Contact card",
     * `MediaGalleryPane` a 👤) and had no way to send one. iOS attaches one from its media
     * picker (`MediaPickerView.swift:815`, `ChatView.swift:6159`).
     *
     * Not to be confused with iOS's `shareContact()`, which builds share text and a QR for
     * the system share sheet — the window already has that, as a per-contact QR in More and
     * Copy address. This is the other thing: a card that travels as media inside OSHI.
     *
     * DIRECT conversations only, and that is a real limit rather than an oversight:
     * `sendContactCard` addresses one peer and there is no group fan-out for it, so the
     * control is not offered where it cannot work.
     */
    fun sendContactCard(cardAddress: String) {
        val target = synchronized(lock) {
            val id = selectedId
            val composer = composerFor(id)
            when {
                id == null -> return
                busy -> return
                !composer.attachEnabled -> {
                    notice = Notice(
                        composer.attachDisabledReason ?: t("conv.n.no_attachments"),
                        Severity.ERROR,
                    )
                    null
                }
                kindOf(id) == ConversationKind.GROUP -> {
                    notice = Notice(t("conv.n.card_one_person"), Severity.ERROR)
                    null
                }
                cardAddress == id -> {
                    // Sending someone their own card is not an error the client would catch,
                    // and it is never what was meant.
                    notice = Notice(t("conv.n.card_own"), Severity.ERROR)
                    null
                }
                else -> { busy = true; id }
            }
        }
        if (target == null) { publish(); return }

        val name = client.contacts.get(cardAddress)?.displayName ?: cardAddress.take(12)
        worker.execute {
            val result = try {
                directSendResult(client.sendContactCard(target, cardAddress))
            } catch (e: Exception) {
                synchronized(lock) {
                    busy = false
                    notice = Notice(t("conv.n.card_failed", "${e.javaClass.simpleName}: ${e.message}"), Severity.ERROR)
                }
                publish()
                return@execute
            }
            synchronized(lock) {
                busy = false
                notice = Notice(
                    if (result.severity == Severity.OK) t("conv.n.card_sent", name) else result.text,
                    result.severity,
                )
            }
            publish()
        }
    }

    /**
     * __DESKTOP_1_6_PARITY__ "Ping a place" into the open 1:1 — the point chosen on the map.
     * Direct conversations only for now: a group fan-out of a place (iOS offers one in its
     * group composer) has not been built or checked against Android's group renderer.
     */
    fun sendLocationPing(latitude: Double, longitude: Double, address: String?) {
        val target = synchronized(lock) {
            val id = selectedId
            if (id == null || busy || kindOf(id) != ConversationKind.DIRECT || !composerFor(id).enabled) null
            else { busy = true; id }
        } ?: return
        publish()
        worker.execute {
            val outcome = runCatching { client.sendLocationPing(target, latitude, longitude, address) }
            synchronized(lock) {
                busy = false
                notice = outcome.fold(
                    { o -> directSendResult(o).let { r -> Notice(if (r.severity == Severity.OK) t("location.shared") else r.text, r.severity) } },
                    { e -> Notice(t("conv.error.network") + " (" + e.javaClass.simpleName + ")", Severity.ERROR) },
                )
            }
            publish()
        }
    }

    /**
     * __DESKTOP_1_6_PARITY__ The safety check-in of the open 1:1: start (`on`, with the
     * interval), confirm (`ok`, "I'm alive") or stop (`off`). The client already sent all
     * three from the REPL (`/alive`); this is the window's control for it.
     */
    fun sendAliveCheckIn(kind: com.oshi.desktop.msg.AliveCheckInPayload.Kind, intervalSeconds: Long) {
        val target = synchronized(lock) {
            val id = selectedId
            if (id == null || busy || kindOf(id) != ConversationKind.DIRECT || !composerFor(id).enabled) null
            else { busy = true; id }
        } ?: return
        publish()
        worker.execute {
            val outcome = runCatching { client.sendAliveCheckIn(target, kind, intervalSeconds) }
            synchronized(lock) {
                busy = false
                notice = outcome.fold(
                    { o ->
                        directSendResult(o).let { r ->
                            Notice(
                                if (r.severity != Severity.OK) r.text else when (kind) {
                                    com.oshi.desktop.msg.AliveCheckInPayload.Kind.ENABLED -> t("alive.summary.started")
                                    com.oshi.desktop.msg.AliveCheckInPayload.Kind.DISABLED -> t("alive.summary.stopped")
                                    com.oshi.desktop.msg.AliveCheckInPayload.Kind.CONFIRMED -> t("alive.summary.confirmed")
                                },
                                r.severity,
                            )
                        }
                    },
                    { e -> Notice(t("conv.error.network") + " (" + e.javaClass.simpleName + ")", Severity.ERROR) },
                )
            }
            publish()
        }
    }

    /**
     * Open a conversation with a pasted OSHI code — the window's half of pairing.
     *
     * There is no camera here and there is not going to be one (`DesktopLimits.MISSING`), so
     * the input is a string the user pasted: a bare key, an `oshi://` deep link, or the
     * bracketed share text the phones produce. [ContactQr.parse] is what decides, it is the
     * same parser `/scan` uses, and its REJECTIONS are surfaced verbatim rather than
     * flattened into "invalid code" — "that is your own address" and "that is not valid
     * base64" send a user to two completely different places.
     *
     * A contact is RECORDED (`contacts.seen`) before the conversation opens, so the sidebar
     * has a name to draw rather than a truncated key. No message is sent, nothing is
     * published to the relay, and the peer is not told: adding someone here is a local act.
     */
    /**
     * __DESKTOP_GROUP_DISCOVERY_2027__ Join a public group heard on the mesh, then open it —
     * the same outcomes, in the same words, as joining from an invite link.
     */
    fun joinPublicGroup(groupId: String) {
        val (outcome, gid) = client.joinPublicGroup(groupId)
        if (gid != null && outcome != OshiClient.JoinOutcome.NO_INVITER && outcome != OshiClient.JoinOutcome.NOT_AN_INVITE) select(gid)
        synchronized(lock) {
            destination = Destination.MESSAGES
            pane = Pane.CONVERSATION
            notice = when (outcome) {
                OshiClient.JoinOutcome.ALREADY_MEMBER -> Notice(t("conv.n.already_member"), Severity.INFO)
                OshiClient.JoinOutcome.REQUESTED -> Notice(t("conv.n.join_requested"), Severity.OK)
                OshiClient.JoinOutcome.NO_INVITER -> Notice(t("conv.n.join_old_link"), Severity.ERROR)
                OshiClient.JoinOutcome.NOT_AN_INVITE -> Notice(t("pc.groups.discover.gone"), Severity.ERROR)
            }
        }
        publish()
    }

    fun startConversation(raw: String) {
        // __GROUP_INFO_SETTINGS_2026_11_20__ a PUBLIC group link (…/g/<handle>): looked up, then
        // confirmed in a dialog — never joined on paste alone (GROUP_INFO_SETTINGS_SPEC §3.1).
        if (com.oshi.desktop.group.GroupPublicLink.parse(raw) != null) { openPublicLink(raw); return }
        // __GROUP_PARITY_2026_09_23__ a pasted or scanned GROUP invite joins the group instead.
        if (com.oshi.desktop.group.GroupInvite.parse(raw) != null) {
            val (outcome, gid) = client.joinGroupFromInvite(raw)
            if (gid != null && outcome != OshiClient.JoinOutcome.NO_INVITER) select(gid)
            synchronized(lock) {
                destination = Destination.MESSAGES
                notice = when (outcome) {
                    OshiClient.JoinOutcome.ALREADY_MEMBER -> Notice(t("conv.n.already_member"), Severity.INFO)
                    OshiClient.JoinOutcome.REQUESTED -> Notice(t("conv.n.join_requested"), Severity.OK)
                    OshiClient.JoinOutcome.NO_INVITER -> Notice(t("conv.n.join_old_link"), Severity.ERROR)
                    OshiClient.JoinOutcome.NOT_AN_INVITE -> Notice(t("conv.n.not_invite"), Severity.ERROR)
                }
            }
            publish()
            return
        }
        when (val scan = client.scanContact(raw)) {
            is ContactQr.Scan.Contact -> {
                client.contacts.seen(scan.address, System.currentTimeMillis())
                // ORDER MATTERS, and getting it wrong costs the user the only confirmation
                // this action produces: `select` clears `notice` — correctly, since an
                // outcome line belongs to the conversation that produced it — so setting
                // the notice first and selecting after publishes a state with no notice in
                // it at all. Caught by a test and never by looking, because the
                // conversation still opened and nothing appeared to be wrong.
                select(scan.address)
                synchronized(lock) {
                    destination = Destination.MESSAGES
                    notice = Notice(
                        t("conv.n.contact_added", short(scan.address)),
                        Severity.OK,
                    )
                }
                publish()
            }
            is ContactQr.Scan.Rejected -> {
                synchronized(lock) {
                    notice = Notice(t("conv.n.code_unusable", scan.detail), Severity.ERROR)
                }
                publish()
            }
        }
    }

    /** Rename a contact locally. The peer is not told; nothing leaves this machine. */
    /**
     * __SHARED_NICKNAME_2026_09_22__ Set or remove THIS account's nickname and tell every
     * eligible contact (silently). The broadcast is network I/O, so it runs on [worker].
     */
    fun setOwnNickname(name: String?) {
        synchronized(lock) { busy = true; profileNotice = null }
        publish()
        worker.execute {
            val result = runCatching { client.setOwnNickname(name) }
            synchronized(lock) {
                busy = false
                profileNotice = result.fold(
                    onSuccess = { u -> nicknameNotice(u) },
                    onFailure = { Notice(t("desktop.nickname.failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    /** Adopt the nickname from the phones' encrypted profile archive (same account only). */
    fun pullNicknameFromPhone() {
        synchronized(lock) { busy = true; profileNotice = null }
        publish()
        worker.execute {
            val result = client.legacySyncPullNickname()
            synchronized(lock) {
                busy = false
                profileNotice = result.fold(
                    onSuccess = { u -> if (u == null) Notice(t("desktop.nickname.pull.none"), Severity.INFO) else nicknameNotice(u) },
                    onFailure = { Notice(t("desktop.nickname.sync.failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    /** Write our nickname into that archive, keeping the phone's avatar and links. */
    fun pushNicknameToPhone() {
        synchronized(lock) { busy = true; profileNotice = null }
        publish()
        worker.execute {
            val result = client.legacySyncPushNickname()
            synchronized(lock) {
                busy = false
                profileNotice = result.fold(
                    onSuccess = { Notice(t("desktop.nickname.pushed"), Severity.OK) },
                    onFailure = { Notice(t("desktop.nickname.sync.failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    private fun nicknameNotice(u: OshiClient.NicknameUpdate): Notice = when {
        !u.changed -> Notice(t("desktop.nickname.unchanged"), Severity.INFO)
        u.withheld -> Notice(t("desktop.nickname.withheld"), Severity.INFO)
        u.nickname == null -> Notice(t("desktop.nickname.cleared", u.sent, u.eligible), Severity.OK)
        else -> Notice(t("desktop.nickname.saved", u.sent, u.eligible), Severity.OK)
    }

    fun renameContact(address: String, name: String?) {
        client.contacts.setDisplayName(address, name?.trim()?.takeIf { it.isNotBlank() })
        publish()
    }

    /** PARITY.md row 0.21 — blocked peers are WITHHELD from the list, never deleted. */
    fun setBlocked(address: String, blocked: Boolean, keepOpen: Boolean = false) {
        if (blocked) client.block(address) else client.unblock(address)
        synchronized(lock) {
            // __CONVERSATION_BLOCK_2027__ blocking from INSIDE the conversation keeps it on
            // screen behind the "Contact Blocked" banner, with Unblock one click away — the
            // phones' order. From the contacts list the thread closes, as before.
            if (blocked && selectedId == address && !keepOpen) selectedId = null
            notice = Notice(
                if (blocked) t("conv.n.blocked", short(address))
                else t("conv.n.unblocked", short(address)),
                Severity.OK,
            )
        }
        publish()
    }

    /**
     * Records an explicit safety-number comparison. The payload is the peer's QR data, not
     * the displayed digits: [SafetyNumber.verify] compares it byte-for-byte with the two
     * identity keys this client is actually using. A mismatch is deliberately recorded as a
     * changed safety number rather than leaving an old "verified" badge behind.
     */
    fun verifySafetyNumber(address: String, payload: String): Boolean {
        val verified = client.verifySafetyNumber(address, payload.trim())
        synchronized(lock) {
            notice = Notice(
                if (verified) t("conv.n.verified", short(address))
                else t("conv.n.verify_mismatch", short(address)),
                if (verified) Severity.OK else Severity.ERROR,
            )
        }
        publish()
        return verified
    }

    /**
     * Create a group from contacts the window already knows. Group-definition delivery is a
     * network operation, so this mirrors send(): it never runs on Compose's thread and it
     * reports only that the local group was created, not that every invitation arrived.
     */
    fun createGroup(name: String, members: List<String>) {
        val groupName = name.trim()
        val recipients = members.map(String::trim).filter(String::isNotBlank).distinct()
        if (groupName.isBlank()) {
            synchronized(lock) { notice = Notice(t("conv.n.group_needs_name"), Severity.ERROR) }
            publish()
            return
        }
        if (recipients.isEmpty()) {
            synchronized(lock) { notice = Notice(t("conv.n.group_needs_member"), Severity.ERROR) }
            publish()
            return
        }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val result = runCatching { client.createGroup(groupName, recipients) }
            synchronized(lock) {
                busy = false
                result.onSuccess { group ->
                    selectedId = group.groupId
                    destination = Destination.MESSAGES
                    pane = Pane.CONVERSATION
                    notice = Notice(
                        t("conv.n.group_created", group.name, recipients.size),
                        Severity.OK,
                    )
                }.onFailure { error ->
                    notice = Notice(t("conv.n.group_create_failed", error.message ?: error.javaClass.simpleName), Severity.ERROR)
                }
            }
            publish()
        }
    }

    /** Rename only a group this account still administers; the client re-checks authority. */
    fun renameGroup(groupId: String, name: String) {
        val nextName = name.trim()
        if (nextName.isBlank()) {
            synchronized(lock) { notice = Notice(t("conv.n.group_needs_name"), Severity.ERROR) }
            publish()
            return
        }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val updated = runCatching { client.renameGroup(groupId, nextName) }
            synchronized(lock) {
                busy = false
                notice = when {
                    updated.isFailure -> Notice(t("conv.n.failed", updated.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    updated.getOrNull() == null -> Notice(t("conv.n.not_admin"), Severity.ERROR)
                    else -> Notice(t("conv.n.group_renamed"), Severity.OK)
                }
            }
            publish()
        }
    }

    /** Add a known, unblocked contact only; the client repeats the admin check at the send site. */
    fun addGroupMember(groupId: String, memberAddress: String) {
        val contact = client.contacts.get(memberAddress)
        if (contact == null || contact.blocked) {
            synchronized(lock) { notice = Notice(t("conv.n.add_known_only"), Severity.ERROR) }
            publish()
            return
        }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val updated = runCatching { client.addGroupMember(groupId, memberAddress) }
            synchronized(lock) {
                busy = false
                notice = when {
                    updated.isFailure -> Notice(t("conv.n.failed", updated.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    updated.getOrNull() == null -> Notice(t("conv.n.not_admin"), Severity.ERROR)
                    else -> Notice(t("conv.n.member_added", contact.label(short(memberAddress))), Severity.OK)
                }
            }
            publish()
        }
    }

    /** Removing this account is deliberately not offered: it could strand the local group view. */
    fun removeGroupMember(groupId: String, memberAddress: String) {
        if (GroupIdentity.sameIdentity(memberAddress, client.address)) {
            synchronized(lock) { notice = Notice(t("conv.n.remove_self"), Severity.ERROR) }
            publish()
            return
        }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val updated = runCatching { client.removeGroupMember(groupId, memberAddress) }
            synchronized(lock) {
                busy = false
                notice = when {
                    updated.isFailure -> Notice(t("conv.n.failed", updated.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    updated.getOrNull() == null -> Notice(t("conv.n.not_admin"), Severity.ERROR)
                    else -> Notice(t("conv.n.member_removed", short(memberAddress)), Severity.OK)
                }
            }
            publish()
        }
    }

    /** The client owns the authority and creator-role guards; this layer owns the outcome. */
    fun setGroupMemberAdmin(groupId: String, memberAddress: String, isAdmin: Boolean) {
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val updated = runCatching { client.setGroupMemberAdmin(groupId, memberAddress, isAdmin) }
            synchronized(lock) {
                busy = false
                notice = when {
                    updated.isFailure -> Notice(t("conv.n.failed", updated.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    updated.getOrNull() == null -> Notice(t("conv.n.role_refused"), Severity.ERROR)
                    else -> Notice(if (isAdmin) t("conv.n.promoted", short(memberAddress)) else t("conv.n.demoted", short(memberAddress)), Severity.OK)
                }
            }
            publish()
        }
    }

    /** Cancellation is a status transition, never deletion: the queue retains its audit trail. */
    fun cancelScheduled(id: String) {
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val cancelled = runCatching { client.scheduler.cancel(id) }
            synchronized(lock) {
                busy = false
                notice = when {
                    cancelled.isFailure -> Notice(t("desktop.sched.cancel_failed", cancelled.exceptionOrNull()?.message ?: t("desktop.sched.storage_error")), Severity.ERROR)
                    cancelled.getOrNull() == true -> Notice(t("desktop.sched.cancelled"), Severity.OK)
                    else -> Notice(t("desktop.sched.cancel_not_pending"), Severity.ERROR)
                }
            }
            publish()
        }
    }

    /** Queue only to a known, unblocked direct contact; a schedule is never a silent contact add. */
    fun scheduleMessage(recipient: String, content: String, delayMs: Long, isGroup: Boolean = false) {
        val contact = client.contacts.get(recipient)
        val body = content.trim()
        when {
            isGroup && (client.groups.get(recipient)?.isMember(client.address) != true) -> {
                synchronized(lock) { notice = Notice(t("desktop.sched.need_group"), Severity.ERROR) }
                publish()
                return
            }
            !isGroup && (contact == null || contact.blocked) -> {
                synchronized(lock) { notice = Notice(t("desktop.sched.need_contact"), Severity.ERROR) }
                publish()
                return
            }
            body.isEmpty() -> {
                synchronized(lock) { notice = Notice(t("desktop.sched.need_text"), Severity.ERROR) }
                publish()
                return
            }
            delayMs <= 0L -> {
                synchronized(lock) { notice = Notice(t("desktop.sched.need_future"), Severity.ERROR) }
                publish()
                return
            }
        }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val queued = runCatching { client.scheduler.schedule(recipient, body, System.currentTimeMillis() + delayMs, isGroup = isGroup) }
            synchronized(lock) {
                busy = false
                notice = when {
                    queued.isFailure -> Notice(t("desktop.sched.schedule_failed", queued.exceptionOrNull()?.message ?: t("desktop.sched.storage_error")), Severity.ERROR)
                    else -> Notice(t("desktop.sched.scheduled", stamp(queued.getOrThrow().scheduledAtMs)), Severity.OK)
                }
            }
            publish()
        }
    }

    /** The store permits edits only while pending; never change a sent or cancelled record. */
    fun editScheduled(id: String, content: String) {
        val body = content.trim()
        if (body.isEmpty()) {
            synchronized(lock) { notice = Notice(t("desktop.sched.need_text"), Severity.ERROR) }
            publish()
            return
        }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val edited = runCatching { client.scheduled.editContent(id, body) }
            synchronized(lock) {
                busy = false
                notice = when {
                    edited.isFailure -> Notice(t("desktop.sched.edit_failed", edited.exceptionOrNull()?.message ?: t("desktop.sched.storage_error")), Severity.ERROR)
                    edited.getOrNull() == true -> Notice(t("desktop.sched.updated"), Severity.OK)
                    else -> Notice(t("desktop.sched.edit_not_pending"), Severity.ERROR)
                }
            }
            publish()
        }
    }

    /** React only in a selected direct thread; the client applies its existing local-first rule. */
    fun react(messageId: String, emoji: String) {
        groupSelected()?.let { gid -> return runGroupMessageAction { client.sendGroupReaction(gid, messageId, emoji) } }
        val target = synchronized(lock) { selectedId?.takeIf { kindOf(it) == ConversationKind.DIRECT } }
        if (target == null) {
            synchronized(lock) { notice = Notice(t("conv.n.reactions_unavailable"), Severity.ERROR) }
            publish()
            return
        }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val outcome = runCatching { client.sendReaction(target, messageId, emoji) }
            synchronized(lock) {
                busy = false
                notice = when {
                    outcome.isFailure -> Notice(t("conv.n.failed", outcome.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.BLOCKED -> Notice(t("chat.contact_blocked_message"), Severity.ERROR)
                    else -> Notice(
                        if (outcome.getOrThrow() == OshiClient.SendOutcome.SENT) t("conv.n.reaction_sent")
                        else t("conv.n.reaction_pending"),
                        Severity.OK,
                    )
                }
            }
            publish()
        }
    }

    /** Edit one of our direct messages; [OshiClient] repeats the ownership check at the boundary. */
    fun editMessage(messageId: String, content: String) {
        val body = content.trim()
        if (body.isEmpty()) {
            synchronized(lock) { notice = Notice(t("conv.n.empty_message"), Severity.ERROR) }
            publish()
            return
        }
        groupSelected()?.let { gid -> return runGroupMessageAction { client.editGroupMessage(gid, messageId, body) } }
        runDirectMessageAction("edit") { peer -> client.editMessage(peer, messageId, body) }
    }

    /** Delete one of our direct messages for everyone, after the bubble's confirmation step. */
    fun deleteMessage(messageId: String) {
        groupSelected()?.let { gid -> return runGroupMessageAction { client.deleteGroupMessage(gid, messageId) } }
        runDirectMessageAction("delete") { peer -> client.deleteMessage(peer, messageId) }
    }

    /** __GROUP_PARITY_2026_09_23__ set the open group's picture from an image file (iOS rule: see `OshiClient.canEditGroupInfo`). */
    fun setGroupPicture(groupId: String, file: File) = runGroupInfoChange("picture") {
        client.setGroupPicture(groupId, file.readBytes())
    }

    fun removeGroupPicture(groupId: String) = runGroupInfoChange("picture") { client.setGroupPicture(groupId, null) }

    fun setGroupMuted(groupId: String, muted: Boolean) = runGroupInfoChange("mute") { client.setGroupMuted(groupId, muted) }

    /**
     * __CONVERSATION_PIN_MUTE_2027__ Mute any conversation: a group through its definition
     * (the existing local flag, so the two can never disagree), anything else here.
     */
    fun setConversationMuted(id: String, muted: Boolean) {
        if (kindOf(id) == ConversationKind.GROUP) return setGroupMuted(id, muted)
        client.conversationPrefs.setMuted(id, muted)
        publish()
    }

    /** __CONVERSATION_PIN_MUTE_2027__ Pin a conversation to the top of the list, or unpin it. */
    fun setConversationPinned(id: String, pinned: Boolean) {
        client.conversationPrefs.setPinned(id, pinned)
        publish()
    }

    /** __CONVERSATION_UNREAD_2027__ "Mark as read" from the list without opening the thread. */
    /**
     * __DELETE_CONVERSATION_2027__ The phones' "Delete conversation" (iOS swipe / header menu
     * `alert.delete_conversation`, Android's long-press): this device's copy of the history is
     * removed — the log file, not just the view. The contact stays, nothing is sent, and the
     * other side keeps their copy. Groups keep their own leave/delete path.
     */
    fun deleteConversation(id: String) {
        synchronized(lock) {
            if (selectedId == id) selectedId = null
            unread.remove(id); mentionedYou.remove(id)
        }
        runCatching { client.messages.deleteConversation(id) }
        publish()
    }

    /**
     * __SEARCH_MESSAGE_TEXT_2027__ Conversations whose MESSAGES contain [query] — the phones'
     * "Search messages and contacts". The list filter alone only saw names and the last
     * preview, so a word said three days ago could not be found from the list. Read off the
     * local store only; called off the UI thread.
     */
    fun conversationsContaining(query: String): Set<String> {
        val q = query.trim()
        if (q.length < 2) return emptySet()
        val ids = synchronized(lock) { state.conversations.map { it.id } }
        return ids.filterTo(LinkedHashSet()) { id ->
            runCatching { client.history(id).any { m -> m.content?.contains(q, ignoreCase = true) == true } }.getOrDefault(false)
        }
    }

    fun markConversationRead(id: String) {
        synchronized(lock) { unread.remove(id); mentionedYou.remove(id) }
        publish()
    }

    /**
     * __CONVERSATION_VERIFY_2027__ "Mark as Verified" after comparing the numbers out loud —
     * the phones' `safety.mark_verified` ("Marks this contact as verified after you compare
     * numbers"). Recorded as a manual verification; un-marking returns to unverified. The
     * QR route ([verifySafetyNumber]) stays the stronger one and is offered beside it.
     */
    fun setContactVerified(address: String, verified: Boolean) {
        client.contacts.setVerification(
            address,
            if (verified) ContactStore.VerificationState.VERIFIED else ContactStore.VerificationState.UNVERIFIED,
        )
        synchronized(lock) {
            notice = Notice(if (verified) t("conv.verify.done") else t("conv.verify.undone"), Severity.OK)
        }
        publish()
    }

    /**
     * __CONVERSATION_EXPORT_2027__ Write ONE conversation to [target] as readable text
     * (Android's "Export as Text"). Unlike the account backup this file is NOT encrypted,
     * and the window says so before it asks for a place to save it.
     */
    fun exportConversation(id: String, target: File) {
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val result = runCatching {
                val kind = kindOf(id)
                val lines = ConversationTextExport.render(
                    title = labelFor(id, kind),
                    rows = client.history(id).filterNot { it.hiddenLocally }.map { m ->
                        ConversationTextExport.Line(
                            atMs = m.sentAtMs,
                            who = if (m.fromMe) t("conv.export.me") else senderLabel(m.senderAddress),
                            text = when {
                                m.isDeletedForEveryone -> t("message.deleted_for_everyone")
                                m.mediaRef != null -> "[" + (m.content?.takeIf { it.isNotBlank() } ?: (m.mediaType?.wire ?: "file")) + "]"
                                else -> previewOfFull(m)
                            },
                        )
                    },
                )
                target.writeText(lines, Charsets.UTF_8)
                client.history(id).size
            }
            synchronized(lock) {
                busy = false
                notice = result.fold(
                    onSuccess = { Notice(t("conv.export.done", target.name), Severity.OK) },
                    onFailure = { Notice(t("export.failed") + ": " + (it.message ?: it.javaClass.simpleName), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    fun setGroupDescription(groupId: String, text: String) = runGroupInfoChange("description") { client.setGroupDescription(groupId, text) }

    /**
     * Pin ([messageId]) or unpin (null) in the open conversation.
     *
     * __DESKTOP_DIRECT_PIN_2026_10_29__ The `?: return` on the group id used to end this
     * function, so pressing Pin in a 1:1 did nothing. It was not a dead control — the pane
     * correctly offered the item only in groups — it was a missing feature, and the one
     * client of the three that had it: iOS and Android both send a 1:1 pin and apply an
     * inbound one. See [OshiClient.pinDirectMessage].
     */
    fun pinMessage(messageId: String?) {
        groupSelected()?.let { gid -> return runGroupInfoChange("pin") { client.setGroupPin(gid, messageId) } }
        runDirectPin(messageId)
    }

    /**
     * The 1:1 half of [pinMessage], with its own wording.
     *
     * [runDirectMessageAction] would have fitted the shape and not the words: it builds its
     * notice as `"Message " + action + "ed"`, which spells "pined"; its
     * `REFUSED_CONTROL_PAYLOAD` line says *"Only messages you sent can be …"*, which is the
     * opposite of the pin rule; and it reports anything that is not `SENT` as a local
     * success, so [OshiClient.pinDirectMessage]'s `NO_V2_PATH` — a message this device does
     * not hold — would have been announced as a pin that happened. Reusing it would have
     * been three wrong sentences in exchange for four saved lines.
     */
    private fun runDirectPin(messageId: String?) {
        val target = synchronized(lock) { selectedId?.takeIf { kindOf(it) == ConversationKind.DIRECT } } ?: return
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val outcome = runCatching { client.pinDirectMessage(target, messageId) }
            val verb = if (messageId == null) "unpinned" else "pinned"
            synchronized(lock) {
                busy = false
                notice = when {
                    outcome.isFailure ->
                        Notice(t("conv.n.failed", outcome.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.BLOCKED ->
                        Notice(t("chat.contact_blocked_message"), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.NO_V2_PATH ->
                        Notice(t("conv.n.message_missing"), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.SENT ->
                        Notice(if (messageId == null) t("conv.n.unpinned") else t("conv.n.pinned"), Severity.OK)
                    // Pinned here, not yet delivered. Said plainly: both phones show their
                    // own banner immediately too, so the two ends can disagree until the
                    // next successful send, and a user who is told nothing cannot know that.
                    else -> Notice(if (messageId == null) t("conv.n.unpinned_pending") else t("conv.n.pinned_pending"), Severity.OK)
                }
            }
            publish()
        }
    }

    fun setGroupBlocked(groupId: String, blocked: Boolean) = runGroupInfoChange("block") { client.setGroupBlocked(groupId, blocked) }

    /** iOS: an admin "deletes" the group from this device; a member leaves. */
    fun deleteGroup(groupId: String) {
        worker.execute {
            val ok = runCatching { client.deleteGroupLocally(groupId) }.getOrDefault(false)
            synchronized(lock) {
                if (ok && selectedId == groupId) selectedId = null
                notice = if (ok) Notice(t("conversation.deleted"), Severity.OK) else Notice(t("conv.n.group_delete_failed"), Severity.ERROR)
            }
            publish()
        }
    }

    /** "Delete for me" — this device only. */
    fun deleteMessageForMe(messageId: String) {
        val id = synchronized(lock) { selectedId } ?: return
        worker.execute {
            runCatching { client.deleteMessageForMe(id, messageId) }
            publish()
        }
    }

    /** Forward one message of the open conversation to a contact (iOS `forwardGroupMessage`). */
    fun forwardMessage(messageId: String, toPeer: String) {
        val from = synchronized(lock) { selectedId } ?: return
        worker.execute {
            val outcome = runCatching { client.forwardMessage(from, messageId, toPeer) }.getOrNull()
            synchronized(lock) {
                notice = when (outcome) {
                    OshiClient.SendOutcome.SENT -> Notice(t("forward.success"), Severity.OK)
                    OshiClient.SendOutcome.BLOCKED -> Notice(t("chat.contact_blocked_message"), Severity.ERROR)
                    null -> Notice(t("conv.n.cannot_forward"), Severity.ERROR)
                    else -> Notice(t("forward.failed"), Severity.ERROR)
                }
            }
            publish()
        }
    }

    /**
     * __FORWARD_MANY_DESKTOP_2027__ Forward one message to several contacts at once. Each is
     * its own 1:1 `➡️FORWARDED➡️` send — the wire the phones read — so a recipient sees exactly
     * what a single forward from an iPhone looks like. The notice counts what really left.
     */
    fun forwardMessageToMany(messageId: String, peers: List<String>) {
        val from = synchronized(lock) { selectedId } ?: return
        val targets = peers.distinct().take(ForwardSelection.MAX_RECIPIENTS)
        if (targets.isEmpty()) return
        if (targets.size == 1) return forwardMessage(messageId, targets.single())
        worker.execute {
            val outcomes = targets.map { runCatching { client.forwardMessage(from, messageId, it) }.getOrNull() }
            val sent = outcomes.count { it == OshiClient.SendOutcome.SENT }
            synchronized(lock) {
                notice = when {
                    outcomes.all { it == null } -> Notice(t("conv.n.cannot_forward"), Severity.ERROR)
                    sent == targets.size -> Notice(t("conv.fa.forward.sent_many", sent), Severity.OK)
                    sent == 0 -> Notice(t("forward.failed"), Severity.ERROR)
                    else -> Notice(t("conv.fa.forward.sent_partial", sent, targets.size), Severity.ERROR)
                }
            }
            publish()
        }
    }

    /**
     * __REPLY_PRIVATELY_DESKTOP_2027__ "Reply privately": open the 1:1 with the member who sent
     * [messageId] in the open group, with the composer already quoting it.
     *
     * No phone has this action, so nothing new goes on the wire: the reply is an ordinary 1:1
     * `💬REPLY💬` whose `replyTo` carries the group message's id, sender and text (≤ 180) —
     * exactly what iOS's `MessageWithReply` and Android read, and they draw the quote from
     * `originalText` when the id is not in that chat, which is the case here.
     */
    fun beginPrivateReply(messageId: String) {
        val (member, quote) = synchronized(lock) {
            val gid = selectedId ?: return
            if (kindOf(gid) != ConversationKind.GROUP) return
            val m = client.messages.messages(gid).firstOrNull { com.oshi.desktop.group.GroupMessageWire.sameMessageId(it.id, messageId) } ?: return
            if (m.fromMe || m.isDeletedForEveryone || com.oshi.desktop.group.GroupIdentity.sameIdentity(m.senderAddress, client.address)) return
            m.senderAddress to com.oshi.desktop.msg.ReplyEnvelope.Quote(
                originalMessageId = m.id,
                originalSenderKey = m.senderAddress,
                originalText = com.oshi.desktop.msg.ReplyEnvelope.quotableText(m.content, m.mediaType?.wire),
                originalTimestampMs = m.sentAtMs,
                originalMediaType = m.mediaType?.wire,
            )
        }
        if (client.contacts.get(member)?.blocked == true) {
            synchronized(lock) { notice = Notice(t("chat.contact_blocked_message"), Severity.ERROR) }
            publish(); return
        }
        client.contacts.seen(member, System.currentTimeMillis())
        select(member)
        synchronized(lock) { replyTarget = member to quote; destination = Destination.MESSAGES }
        publish()
    }

    /**
     * __MERGE_AB_MEMBER_MENU__ The member menu's "Reply privately": [beginPrivateReply] without a
     * quote — open (or start) the 1:1 with [member]. Same guards: not ourselves, not blocked.
     */
    fun replyPrivatelyToMember(member: String) {
        if (member.isBlank() || com.oshi.desktop.group.GroupIdentity.sameIdentity(member, client.address)) return
        if (client.contacts.get(member)?.blocked == true) {
            synchronized(lock) { notice = Notice(t("chat.contact_blocked_message"), Severity.ERROR) }
            publish(); return
        }
        client.contacts.seen(member, System.currentTimeMillis())
        select(member)
        synchronized(lock) { destination = Destination.MESSAGES }
        publish()
    }

    /** __VIEW_ONCE_DESKTOP_2027__ the recipient opened a view-once attachment of the open conversation. */
    fun openViewOnce(messageId: String) {
        val id = synchronized(lock) { selectedId } ?: return
        worker.execute { if (runCatching { client.markViewOnceOpened(id, messageId) }.getOrDefault(false)) publish() }
    }

    /**
     * __PROFILE_PHOTOS_DESKTOP_2027__ Set (a file) or remove (null) this account's picture and
     * tell the contacts — the phones' `setProfileImage` + broadcast.
     */
    fun setOwnPhoto(file: File?) {
        synchronized(lock) { busy = true; profileNotice = null }
        publish()
        worker.execute {
            val result = runCatching { client.setOwnPhoto(file?.readBytes()) }
            synchronized(lock) {
                busy = false
                profileNotice = result.fold(
                    onSuccess = { u ->
                        when {
                            !u.ok -> Notice(t("conv.fa.photo.unreadable"), Severity.ERROR)
                            u.withheld -> Notice(t("desktop.nickname.withheld"), Severity.INFO)
                            file == null -> Notice(t("conv.fa.photo.removed", u.sent, u.eligible), Severity.OK)
                            else -> Notice(t("conv.fa.photo.saved", u.sent, u.eligible), Severity.OK)
                        }
                    },
                    onFailure = { Notice(t("conv.fa.photo.unreadable"), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    /**
     * __DESKTOP_REPORT_2026_09_23__ "Signaler" a contact or a group. Off the UI thread (it
     * is a network POST); the notice says what REALLY happened — sent, saved for retry, or
     * refused — in the phones' own words (`report.*` strings, 34 languages).
     */
    fun report(
        conversationId: String,
        isGroup: Boolean,
        reason: com.oshi.desktop.net.V2ReportClient.Reason,
        details: String,
        alsoBlock: Boolean,
    ) {
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val outcome = runCatching {
                client.report(
                    if (isGroup) com.oshi.desktop.net.V2ReportClient.Subject.GROUP else com.oshi.desktop.net.V2ReportClient.Subject.CONTACT,
                    conversationId, reason, details, alsoBlock,
                )
            }
            synchronized(lock) {
                busy = false
                val state = outcome.getOrNull()?.state
                notice = when (state) {
                    com.oshi.desktop.net.V2ReportClient.State.SENT ->
                        Notice(t("report.sent_title") + " — " + t("report.sent_message"), Severity.OK)
                    com.oshi.desktop.net.V2ReportClient.State.REJECTED ->
                        Notice(t("conv.n.report_refused", outcome.getOrNull()?.lastError ?: "4xx"), Severity.ERROR)
                    else -> Notice(t("report.queued_title") + " — " + t("report.queued_message"), Severity.OK)
                }
                if (alsoBlock && !isGroup && selectedId == conversationId) selectedId = null
            }
            refresh()
        }
    }

    // ================================================================ __GROUP_INFO_SETTINGS_2026_11_20__

    private fun groupSettingsView(g: com.oshi.desktop.group.GroupDefinition): GroupSettingsView {
        val admin = g.isAdmin(client.address)
        val s = g.effectiveSettings
        fun memberRow(m: com.oshi.desktop.group.GroupMember) = GroupMemberRow(
            address = m.publicKey,
            label = client.contacts.get(m.publicKey)?.label(m.alias?.takeIf { it.isNotBlank() } ?: short(m.publicKey))
                ?: m.alias?.takeIf { it.isNotBlank() } ?: short(m.publicKey),
            isAdmin = m.isAdmin,
            isCreator = GroupIdentity.sameIdentity(m.publicKey, g.adminPublicKey),
            self = GroupIdentity.sameIdentity(m.publicKey, client.address),
            blocked = client.contacts.isBlocked(m.publicKey),
        )
        val nameOf: (String) -> String = { key ->
            if (GroupIdentity.sameIdentity(key, client.address)) t("gs_you")
            else g.members.firstOrNull { GroupIdentity.sameIdentity(it.publicKey, key) }?.let { memberRow(it).label }
                ?: client.contacts.get(key)?.label(short(key)) ?: short(key)
        }
        return GroupSettingsView(
            isAdmin = admin,
            type = g.type,
            settings = s,
            publicLink = s.handle?.takeIf { s.isPublic }?.let(com.oshi.desktop.group.GroupPublicLink::link),
            canPost = g.mayPost(client.address),
            dissolved = g.isDissolved,
            admins = g.members.filter { it.isAdmin }.map(::memberRow)
                .sortedWith(compareByDescending<GroupMemberRow> { it.isCreator }.thenBy { it.label.lowercase() }),
            banned = if (!admin) emptyList() else g.evictedMemberKeys.map { BannedRow(it, nameOf(it)) },
            events = if (!admin) emptyList() else client.groupEvents(g.groupId).asReversed().map { e ->
                EventRow(eventSentence(e, nameOf(e.actor), nameOf), stamp(e.atMs))
            },
            welcomeShown = client.groupWelcomeShown(g.groupId),
            linkBusy = linkBusy,
            linkResult = linkResult,
        )
    }

    fun setGroupType(groupId: String, type: com.oshi.desktop.group.GroupType) = runGroupInfoChange("type") { client.setGroupType(groupId, type) }
    fun setGroupPostPolicy(groupId: String, p: com.oshi.desktop.group.GroupSettings.PostPolicy) = runGroupInfoChange("post") { client.setGroupPostPolicy(groupId, p) }
    fun setGroupReactions(groupId: String, mode: com.oshi.desktop.group.GroupSettings.ReactionMode, allowed: List<String>) =
        runGroupInfoChange("reactions") { client.setGroupReactions(groupId, mode, allowed) }
    fun setGroupWelcome(groupId: String, text: String) = runGroupInfoChange("welcome") { client.setGroupWelcome(groupId, text) }
    fun setGroupAdminDMs(groupId: String, on: Boolean) = runGroupInfoChange("dms") { client.setGroupAdminDMs(groupId, on) }
    /** __GROUP_COMMENTS_2026_11_21__ §2.12.1 the Comments switch. */
    fun setGroupComments(groupId: String, on: Boolean) = runGroupInfoChange("comments") { client.setGroupComments(groupId, on) }
    fun resetGroupInviteLink(groupId: String) = runGroupInfoChange("link") { client.resetGroupInviteLink(groupId) }
    fun unbanGroupMember(groupId: String, key: String) = runGroupInfoChange("unban") { client.unbanGroupMember(groupId, key) }
    fun setGroupPrivate(groupId: String) {
        synchronized(lock) { linkResult = null }
        runGroupInfoChange("link") { client.setGroupPrivate(groupId) }
    }

    /** §4.3 Public + handle: the registry first, the broadcast only on success. Network: on the worker. */
    fun setGroupPublic(groupId: String, handle: String, showPreview: Boolean) {
        synchronized(lock) { linkBusy = true; linkResult = null }
        publish()
        worker.execute {
            val r = runCatching { client.setGroupPublic(groupId, handle, showPreview) }.getOrDefault(OshiClient.PublicLinkOutcome.FAILED)
            synchronized(lock) {
                linkBusy = false
                linkResult = when (r) {
                    OshiClient.PublicLinkOutcome.OK -> LinkResult.SAVED
                    OshiClient.PublicLinkOutcome.TAKEN -> LinkResult.TAKEN
                    OshiClient.PublicLinkOutcome.INVALID -> LinkResult.INVALID
                    OshiClient.PublicLinkOutcome.FAILED, OshiClient.PublicLinkOutcome.NOT_ADMIN -> LinkResult.FAILED
                }
            }
            refresh()
        }
    }

    /** The link editor closed: its last answer is not news any more. */
    fun clearGroupLinkResult() { synchronized(lock) { linkResult = null }; publish() }

    /** §2.9 "Delete for everyone" (admin). */
    fun dissolveGroup(groupId: String) {
        worker.execute {
            val ok = runCatching { client.dissolveGroup(groupId) }.getOrDefault(false)
            synchronized(lock) {
                if (ok && selectedId == groupId) selectedId = null
                notice = if (ok) Notice(t("conversation.deleted"), Severity.OK) else Notice(t("conv.n.group_delete_failed"), Severity.ERROR)
            }
            refresh()
        }
    }

    /** §2.9 "Delete for me": leave (members are told), then the group and its messages go from here. */
    fun deleteGroupForMe(groupId: String) {
        worker.execute {
            val ok = runCatching { client.deleteGroupForMe(groupId) }.getOrDefault(false)
            synchronized(lock) {
                if (ok && selectedId == groupId) selectedId = null
                notice = if (ok) Notice(t("conversation.deleted"), Severity.OK) else Notice(t("conv.n.group_delete_failed"), Severity.ERROR)
            }
            refresh()
        }
    }

    /** §3.1: resolve a public link, then wait for the person's answer in [ShellState.publicJoin]. */
    fun openPublicLink(raw: String) {
        val parsed = com.oshi.desktop.group.GroupPublicLink.parse(raw)
        if (parsed !is com.oshi.desktop.group.GroupPublicLink.Parsed.Handle) {
            synchronized(lock) { publicJoin = PublicJoinState.Problem(null, t("gs_link_invalid")) }
            publish(); return
        }
        synchronized(lock) { publicJoin = PublicJoinState.Loading(parsed.handle); destination = Destination.MESSAGES }
        publish()
        worker.execute {
            val r = runCatching { client.lookupPublicGroup(raw) }.getOrDefault(OshiClient.PublicLookup.Failed)
            synchronized(lock) {
                if (publicJoin?.handle != parsed.handle) return@synchronized   // dismissed meanwhile
                publicJoin = when (r) {
                    is OshiClient.PublicLookup.Found -> PublicJoinState.Found(
                        parsed.handle, r.result.preview?.name, r.result.preview?.description, r.result.preview?.memberCount, r.result,
                    )
                    OshiClient.PublicLookup.NotFound -> PublicJoinState.Problem(parsed.handle, t("gs_link_not_found"))
                    OshiClient.PublicLookup.Invalid -> PublicJoinState.Problem(parsed.handle, t("gs_link_invalid"))
                    OshiClient.PublicLookup.Failed -> PublicJoinState.Problem(parsed.handle, t("desktop.group.settings.lookup_failed"))
                }
            }
            publish()
        }
    }

    fun dismissPublicJoin() { synchronized(lock) { publicJoin = null }; publish() }

    /** The person said Join: only now does anything reach the group's admins. */
    fun confirmPublicJoin() {
        val found = synchronized(lock) { (publicJoin as? PublicJoinState.Found).also { publicJoin = null } } ?: return publish()
        publish()
        worker.execute {
            val sent = runCatching { client.requestPublicJoin(found.result) }.getOrDefault(0)
            synchronized(lock) {
                notice = if (sent > 0) Notice(t("gs_join_request_sent"), Severity.OK)
                else Notice(t("desktop.group.settings.lookup_failed"), Severity.ERROR)
            }
            publish()
        }
    }

    private fun runGroupInfoChange(what: String, change: () -> com.oshi.desktop.group.GroupDefinition?) {
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val outcome = runCatching(change)
            synchronized(lock) {
                busy = false
                notice = when {
                    outcome.isFailure -> Notice(t("conv.n.group_change_failed", outcome.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    outcome.getOrNull() == null -> Notice(
                        if (what == "picture") t("conv.n.group_picture_refused") else t("conv.n.not_changed"),
                        Severity.ERROR,
                    )
                    else -> null
                }
            }
            refresh()
        }
    }

    private fun groupSelected(): String? =
        synchronized(lock) { selectedId?.takeIf { kindOf(it) == ConversationKind.GROUP } }

    /**
     * __GROUP_E2E_V2_2026_09_23__ A group reaction / edit / delete (GROUP_E2E_V2_SPEC §2.3-2.4):
     * applied locally, fanned out pairwise. Null from the client = not permitted (not ours, not admin).
     */
    private fun runGroupMessageAction(send: () -> OshiClient.GroupSendReport?) {
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val outcome = runCatching { send() }
            synchronized(lock) {
                busy = false
                notice = when {
                    outcome.isFailure -> Notice(t("desktop.group.action.failed", outcome.exceptionOrNull()?.message ?: "network error"), Severity.ERROR)
                    outcome.getOrNull() == null -> Notice(t("desktop.group.action.refused"), Severity.ERROR)
                    else -> groupSendResult(outcome.getOrThrow()!!).let { Notice(it.text, it.severity) }
                }
            }
            publish()
        }
    }

    /** __GROUP_E2E_V2_2026_09_23__ Leave a group: `member_removed` naming ourselves, then forget it here. */
    fun leaveGroup(groupId: String) {
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val left = runCatching { client.leaveGroup(groupId) }
            synchronized(lock) {
                busy = false
                if (left.getOrNull() == true && selectedId == groupId) selectedId = null
                notice = if (left.getOrNull() == true) Notice(t("desktop.group.left"), Severity.OK)
                else Notice(t("desktop.group.action.failed", left.exceptionOrNull()?.message ?: "not a member"), Severity.ERROR)
            }
            publish()
        }
    }

    private fun runDirectMessageAction(action: String, send: (String) -> OshiClient.SendOutcome) {
        val target = synchronized(lock) { selectedId?.takeIf { kindOf(it) == ConversationKind.DIRECT } }
        if (target == null) {
            synchronized(lock) { notice = Notice(t("conv.n.actions_unavailable"), Severity.ERROR) }
            publish()
            return
        }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val outcome = runCatching { send(target) }
            synchronized(lock) {
                busy = false
                notice = when {
                    outcome.isFailure -> Notice(
                        (outcome.exceptionOrNull()?.message ?: t("conv.error.network")).let {
                            if (action == "delete") t("conv.n.delete_failed", it) else t("conv.n.edit_failed", it)
                        },
                        Severity.ERROR,
                    )
                    outcome.getOrNull() == OshiClient.SendOutcome.BLOCKED -> Notice(t("chat.contact_blocked_message"), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD -> Notice(if (action == "delete") t("conv.n.delete_own_only") else t("conv.n.edit_own_only"), Severity.ERROR)
                    else -> Notice(
                        if (outcome.getOrThrow() == OshiClient.SendOutcome.SENT) (if (action == "delete") t("conv.n.deleted_sent") else t("conv.n.edited_sent"))
                        else (if (action == "delete") t("conv.n.deleted_pending") else t("conv.n.edited_pending")),
                        Severity.OK,
                    )
                }
            }
            publish()
        }
    }

    /**
     * __HIDDEN_WINDOW_NOTIFY_2026_10_29__ The window came back. Whatever arrived in the
     * open conversation while it was away has now been seen, so its badge goes.
     *
     * Deliberately NOT a call to [select]: that clears `draftText`, so reopening the
     * window would eat a half-typed message. This touches the badge and nothing else.
     * It also does not send a read receipt — `select` owns that, and firing one from a
     * window-visibility change would put an outward-facing signal on a gesture the user
     * may not think of as opening the chat. That asymmetry is named here rather than
     * left to be discovered.
     */
    fun onWindowShown() {
        val changed = synchronized(lock) {
            val id = selectedId ?: return@synchronized false
            val had = unread.remove(id) != null
            had or mentionedYou.remove(id)
        }
        if (changed) publish()
    }

    /** Called on the poll thread for every inbound message. See THREADING. */
    fun onInbound(m: Message) {
        val shouldNotify: Boolean
        synchronized(lock) {
            // __HIDDEN_WINDOW_NOTIFY_2026_10_29__ "selected" only means "seen" while the
            // window is actually on screen. Hidden to the tray, every conversation is unseen.
            val unseen = !m.fromMe && (m.conversationId != selectedId || !windowVisible())
            // A BLOCKED group's messages are kept (iOS parity) but must not grow a badge the
            // user asked never to hear from again; a muted one still counts, quietly.
            if (unseen && !client.isGroupBlocked(m.conversationId)) {
                unread[m.conversationId] = (unread[m.conversationId] ?: 0) + 1
            }
            // __MENTIONS_2026_09_23__ an admitted `@you` breaks through mute (never through a block).
            val mentionsMe = !m.fromMe && com.oshi.desktop.group.MentionWire.mentions(
                client.address, m.mentions, com.oshi.desktop.group.GroupIdentity::sameIdentity,
            )
            if (unseen && mentionsMe) mentionedYou.add(m.conversationId)
            // __GROUP_PARITY_2026_09_23__ a muted group still counts unread, it just stays quiet.
            shouldNotify = unseen && (mentionsMe || !client.isGroupMuted(m.conversationId)) &&
                !client.isGroupBlocked(m.conversationId) &&
                // __CONVERSATION_PIN_MUTE_2027__ a muted 1:1 still counts unread, quietly.
                !client.conversationPrefs.isMuted(m.conversationId)
        }
        publish()
        // Deliberately after durable storage (which OshiClient completed before this
        // callback) and after the UI state update. A broken shell notification is never a
        // delivery failure, and it contains no decrypted text or sender metadata.
        if (shouldNotify) {
            lastNotifiedConversationId = m.conversationId
            // __DESKTOP_PREVIEW_PARITY_2026_10_27__ The text leaves this object ONLY
            // with the switch on, and never for a control payload or a message its
            // sender revoked — a poll envelope quoted into notification history would
            // be both unreadable and permanent.
            // `content` is nullable: a media-only row, and a row the sender revoked,
            // both reach here with nothing to quote. Those notify generically.
            val text = m.content?.takeIf { it.isNotBlank() }
            val previewable = notificationPreviewEnabled() &&
                !m.isDeletedForEveryone &&
                text != null &&
                !com.oshi.desktop.msg.ControlPrefix.isControl(text)
            // __DESKTOP_1_6_PARITY__ a place or a check-in has words of its own (the list's).
            val readable = if (notificationPreviewEnabled() && !m.isDeletedForEveryone && text != null && !previewable)
                ListPreview.of(m) { "" }.takeIf { it.kind == ListPreview.Kind.LOCATION || it.kind == ListPreview.Kind.ALIVE }?.text
            else null
            if (previewable) {
                onIncomingNotification(senderLabel(m.senderAddress), text)
            } else if (readable != null) {
                onIncomingNotification(senderLabel(m.senderAddress), readable)
            } else {
                onIncomingNotification(null, null)
            }
        }
    }

    /**
     * __GROUP_COMMENTS_2026_11_21__ A stored comment (poll thread). Never an unread badge, never
     * the group's notification: only "<name> replied to your comment" when [notify].
     */
    private fun onComment(m: Message, notify: Boolean) {
        publish()
        if (!notify || client.conversationPrefs.isMuted(m.conversationId)) return
        val open = synchronized(lock) { commentsFor?.let { it.first == m.conversationId } == true && windowVisible() }
        if (open) return
        lastNotifiedConversationId = m.conversationId
        if (notificationPreviewEnabled()) {
            onIncomingNotification(labelFor(m.conversationId, ConversationKind.GROUP), t("cm_notif_reply", senderLabel(m.senderAddress)))
        } else onIncomingNotification(null, null)
    }

    /** §2.12.6 open the comments of [postId] in the selected group. */
    fun openComments(postId: String) {
        synchronized(lock) {
            val gid = selectedId?.takeIf { kindOf(it) == ConversationKind.GROUP } ?: return
            if (commentsFor != gid to postId) { commentDraft = ""; commentReplyTo = null }
            commentsFor = gid to postId
        }
        publish()
    }

    fun closeComments() {
        synchronized(lock) { commentsFor = null; commentDraft = ""; commentReplyTo = null }
        publish()
    }

    fun setCommentDraft(text: String) {
        synchronized(lock) { commentDraft = text }
        publish()
    }

    /** Reply to one comment of the open thread ("Replying to <name>" above the composer). */
    fun beginCommentReply(commentId: String) {
        synchronized(lock) { if (commentsFor != null) commentReplyTo = commentId }
        publish()
    }

    fun cancelCommentReply() {
        synchronized(lock) { commentReplyTo = null }
        publish()
    }

    /** Send the comment draft to the open thread. The draft stays on any refusal. */
    fun sendComment() {
        val job = synchronized(lock) {
            val target = commentsFor ?: return
            if (commentBusy || commentDraft.isBlank()) return
            commentBusy = true
            Triple(target, commentDraft, commentReplyTo)
        }
        publish()
        val (target, text, replyTo) = job
        worker.execute {
            val r = runCatching { client.sendGroupComment(target.first, target.second, text, replyTo) }
            synchronized(lock) {
                commentBusy = false
                val outcome = r.getOrNull()?.outcome
                when (outcome) {
                    OshiClient.CommentSendResult.Outcome.SENT -> { commentDraft = ""; commentReplyTo = null }
                    OshiClient.CommentSendResult.Outcome.NOT_DELIVERED -> {
                        commentDraft = ""; commentReplyTo = null
                        notice = groupSendResult(r.getOrThrow().report!!).let { Notice(it.text, it.severity) }
                    }
                    OshiClient.CommentSendResult.Outcome.TOO_FAST -> notice = Notice(t("cm_too_fast"), Severity.ERROR)
                    OshiClient.CommentSendResult.Outcome.EMPTY -> notice = Notice(t("conv.n.nothing_to_send"), Severity.INFO)
                    OshiClient.CommentSendResult.Outcome.NOT_ALLOWED -> notice = Notice(t("cm_disabled"), Severity.ERROR)
                    null -> notice = Notice(t("conv.n.send_failed", r.exceptionOrNull()?.message ?: ""), Severity.ERROR)
                }
            }
            publish()
        }
    }

    /** §2.12.6 the open thread, already decided: post, comments, composer state. */
    private fun commentThreadView(group: com.oshi.desktop.group.GroupDefinition, postId: String): CommentThreadView {
        val gid = group.groupId
        val all = client.messages.messages(gid)
        val post = all.firstOrNull { com.oshi.desktop.group.GroupMessageWire.sameMessageId(it.id, postId) }
            ?.takeIf { it.commentOn == null && !it.isDeletedForEveryone && !it.hiddenLocally }
        val comments = if (post == null) emptyList() else client.comments(gid, post.id).map { commentRow(it) }
        val state = when {
            post == null -> CommentThreadView.State.POST_UNAVAILABLE
            !group.effectiveSettings.commentsActive || group.isDissolved || !group.isMember(client.address) -> CommentThreadView.State.DISABLED
            else -> CommentThreadView.State.OPEN
        }
        val replying = commentReplyTo?.let { rid -> all.firstOrNull { com.oshi.desktop.group.GroupMessageWire.sameMessageId(it.id, rid) && it.commentOn != null } }
        return CommentThreadView(
            postId = postId,
            post = post?.let { row(it) },
            comments = comments,
            state = state,
            draft = commentDraft,
            busy = commentBusy,
            replyingTo = replying?.let {
                QuoteRow(it.id, if (it.fromMe) t("common.you") else senderLabel(it.senderAddress),
                    com.oshi.desktop.msg.ReplyEnvelope.quotableText(it.content, null))
            },
            canModerate = group.isAdmin(client.address),
        )
    }

    /** A comment's row: a quote of the POST itself is not drawn inside its own thread (§2.12.2). */
    private fun commentRow(m: Message): MessageRow {
        val r = row(m)
        val q = r.quote ?: return r
        return if (m.commentOn != null && q.messageId.equals(m.commentOn, ignoreCase = true)) r.copy(quote = null) else r
    }

    /** Control payloads mutate the store silently; typing additionally drives a short-lived hint. */
    private fun onControl(peer: String, event: ControlEvent) {
        // __GROUP_PARITY_2026_09_23__ in a group, WHO is typing (the sender is checked against the
        // authenticated envelope in OshiClient before this is called).
        if (event is ControlEvent.Typing && kindOf(peer) == ConversationKind.GROUP) {
            val who = event.payload.senderPublicKey
            val expiresAt = System.currentTimeMillis() + TYPING_VISIBLE_MS
            synchronized(lock) {
                val m = groupTyping.getOrPut(peer) { HashMap() }
                if (event.payload.isTyping) m[who] = expiresAt else m.remove(who)
            }
            if (event.payload.isTyping) typingExpiry.schedule({ publish() }, TYPING_VISIBLE_MS + 50, TimeUnit.MILLISECONDS)
            publish()
            return
        }
        if (event is ControlEvent.Typing) {
            val expiresAt = System.currentTimeMillis() + TYPING_VISIBLE_MS
            if (event.payload.isTyping) {
                synchronized(lock) { typingUntilMs[peer] = expiresAt }
                typingExpiry.schedule({ expireTyping(peer, expiresAt) }, TYPING_VISIBLE_MS, TimeUnit.MILLISECONDS)
            } else {
                synchronized(lock) { typingUntilMs.remove(peer) }
            }
        }
        // Reactions/actions/receipts updated a row in the store too. Rebuild the selected
        // thread so an inbound control never waits for an unrelated message to repaint.
        publish()
    }

    private fun expireTyping(peer: String, expectedExpiry: Long) {
        val expired = synchronized(lock) {
            if (typingUntilMs[peer] != expectedExpiry) false else {
                typingUntilMs.remove(peer)
                true
            }
        }
        if (expired) publish()
    }

    // ------------------------------------------------------------------ reachability

    private fun probeReach(id: String) {
        synchronized(lock) { reach = Reach.CHECKING }
        publish()
        worker.execute {
            val result = try {
                if (client.canReach(id)) Reach.REACHABLE else Reach.NO_BUNDLE
            } catch (e: Exception) {
                Reach.ERROR
            }
            synchronized(lock) { if (selectedId == id) reach = result }
            publish()
        }
    }

    // ------------------------------------------------------------------ state assembly

    /**
     * Build a snapshot and publish it — atomically with respect to other publishers.
     *
     * THE ASSIGNMENT IS INSIDE THE LOCK, and it was not. `build()` was atomic and the
     * publication that followed it was not, so two threads could interleave as:
     *
     *   poll thread    builds S1 (an inbound message arrived)     … descheduled here
     *   worker thread  builds S2 (send finished: draft cleared, "sent" notice), publishes
     *   poll thread    resumes, assigns S1 — and the window REVERTS
     *
     * The user watches the "sent" confirmation vanish and the text they just sent
     * reappear in the composer, and it stays that way until something else happens to
     * trigger a publish. Building under the lock and assigning outside it protects the
     * snapshot's internal consistency and nothing about its ordering.
     *
     * `onChange` stays OUTSIDE the lock deliberately: it re-enters Compose, and holding a
     * model lock across a recomposition is how a UI deadlocks against its own state.
     */
    private fun publish() {
        val next = synchronized(lock) {
            val built = build()
            state = built
            built
        }
        onChange(next)
    }

    private fun build(): ShellState {
        // conversations(), NOT conversationsIncludingBlocked(). PARITY.md row 0.21: a
        // blocked peer is WITHHELD from the conversation list and never deleted. The
        // "including blocked" reader exists for `/blocked`, which says what it is showing;
        // a window that used it would silently un-hide everyone the user blocked.
        // A newly created or newly received group has a definition before it has a message.
        // The message store alone would hide it until somebody speaks, turning creation into
        // an apparently failed action. Merge definition-only groups into the same list, with
        // no fabricated message or preview.
        val messageSummaries = client.conversations()
        val summaryById = messageSummaries.associateBy { it.conversationId }
        val summaries = messageSummaries + client.groups.all()
            .filter { it.groupId !in summaryById }
            .map { com.oshi.desktop.store.MessageStore.ConversationSummary(it.groupId, 0, null, it.lastActivityUnixMillis) }
        val rows = summaries.sortedByDescending { it.lastActivityMs }.map { summary ->
            val kind = kindOf(summary.conversationId)
            val groupDef = if (kind == ConversationKind.GROUP) client.groups.get(summary.conversationId) else null
            val summaryPreview = listPreviewOf(summary.lastMessage)
            ConversationRow(
                id = summary.conversationId,
                label = labelFor(summary.conversationId, kind),
                kind = kind,
                messageCount = summary.messageCount,
                lastActivityMs = summary.lastActivityMs,
                lastActivity = stamp(summary.lastActivityMs),
                preview = summaryPreview.text,
                previewKind = summaryPreview.kind,
                unread = unread[summary.conversationId] ?: 0,
                mentionedYou = summary.conversationId in mentionedYou,
                pictureBase64 = groupDef?.groupPictureBase64
                    ?: if (kind == ConversationKind.DIRECT) client.photos.get(summary.conversationId) else null,
                groupType = groupDef?.type,
                pinned = client.conversationPrefs.isPinned(summary.conversationId),
                muted = groupDef?.isMuted == true || client.conversationPrefs.isMuted(summary.conversationId),
            )
        }.let { list -> com.oshi.desktop.store.ConversationPreferences.pinnedFirst(list, client.conversationPrefs.pinnedIds()) { it.id } }
        val id = selectedId
        val thread = if (id == null) null else {
            val kind = kindOf(id)
            val group = if (kind == ConversationKind.GROUP) client.groups.get(id) else null
            ThreadView(
                conversationId = id,
                title = labelFor(id, kind),
                address = id,
                kind = kind,
                groupAdmin = group?.isAdmin(client.address) == true,
                // __PROFILE_PHOTOS_DESKTOP_2027__ a 1:1 shows the contact's own picture in the same slot.
                groupPictureBase64 = group?.groupPictureBase64
                    ?: if (kind == ConversationKind.DIRECT) client.photos.get(id) else null,
                groupInviteLink = group?.let { client.groupInviteLink(id) },
                groupDescription = group?.description,
                groupBlocked = group != null && client.isGroupBlocked(id),
                // __DESKTOP_DIRECT_PIN_2026_10_29__ One pinned row for either kind. A group's
                // pin lives in its definition; a 1:1's lives in `client.pinnedMessages`. The
                // id match is case-insensitive on both branches, because an iPhone's pin
                // targets an id it rendered from `UUID.uuidString`.
                pinned = (group?.pinnedMessageId ?: client.pinnedMessages.get(id))?.let { pid ->
                    client.history(id).firstOrNull { com.oshi.desktop.group.GroupMessageWire.sameMessageId(it.id, pid) }?.let { m ->
                        QuoteRow(m.id, if (m.fromMe) "me" else senderLabel(m.senderAddress),
                            com.oshi.desktop.msg.ReplyEnvelope.quotableText(m.content, m.mediaType?.wire))
                    }
                },
                // A group pin needs the info-editing permission; in a 1:1 either party may
                // pin, which is both phones' rule. Nothing else may pin at all.
                // __DESKTOP_OFFLINE_ONLY_2026_10_29__
                offlineOnly = client.offlineOnly.isOfflineOnly(id),
                alive = if (kind == ConversationKind.DIRECT) AliveState.from(client.history(id)) else AliveState.NONE,
                offlineOnlyConfirmed = if (!client.offlineOnly.isOfflineOnly(id)) null else
                    com.oshi.desktop.msg.OfflineOnlyPayload.confirmationSummary(
                        confirmed = client.offlineOnly.confirmedCount(id),
                        // A 1:1 has exactly one other participant; a group has its roster
                        // minus us. We are never counted — our own compliance is not in doubt.
                        otherMemberCount = if (kind == ConversationKind.GROUP)
                            group?.members?.count { !com.oshi.desktop.group.GroupIdentity.sameIdentity(it.publicKey, client.address) } ?: 0
                        else 1,
                    ),
                pinCanToggle = when (kind) {
                    ConversationKind.GROUP -> group != null && client.canEditGroupInfo(id)
                    ConversationKind.DIRECT -> true
                    else -> false
                },
                // __DESKTOP_MEDIA_GALLERY_2026_10_29__ Newest first, and a deleted or
                // locally hidden row contributes nothing — the gallery shows what the thread
                // would show, never more.
                media = client.history(id).asReversed().mapNotNull { m ->
                    val ref = m.mediaRef ?: return@mapNotNull null
                    if (m.isDeletedForEveryone || m.hiddenLocally) return@mapNotNull null
                    // A VIEW-ONCE ATTACHMENT IS NEVER IN THE GALLERY, revealed or not.
                    //
                    // The weaker rule — hide it only while still sealed — was written first
                    // and is wrong twice over: the reveal state lives in the window, not
                    // here, so this layer cannot even ask; and "view once" means once, so a
                    // grid that lists it afterwards is a second look by another route. A
                    // gallery that quietly re-opens what the bubble spent effort sealing is
                    // worse than no gallery.
                    if (m.isViewOnce) return@mapNotNull null
                    MediaItemRow(
                        messageId = m.id,
                        path = ref,
                        kind = m.mediaType ?: com.oshi.desktop.store.MediaType.DOCUMENT,
                        // The SENDER's filename, not the path's. `receiveMedia` writes the
                        // file as "<msgId8>-<name>" so two attachments called photo.jpg
                        // cannot collide on disk — an internal detail that has no business
                        // in a grid a person reads. `content` is where the real name is
                        // (`receiveMedia` sets it to `key.filename`); the path's name is
                        // only the fallback for a row that has none.
                        // __FILENAME_FORMAT_CHARS_2026_11_20__ cleaned like the bubble's: a raw
                        // "invoice\u202Egpj.exe" displayed here as "invoiceexe.jpg".
                        fileName = m.content?.takeIf { it.isNotBlank() }?.let { com.oshi.desktop.store.SafeFileName.sanitize(it) } ?: java.io.File(ref).name,
                        stamp = stamp(m.sentAtMs),
                        fromMe = m.fromMe,
                    )
                },
                links = client.history(id).asReversed().flatMap { m ->
                    if (m.isDeletedForEveryone || m.hiddenLocally || m.mediaRef != null) return@flatMap emptyList()
                    val text = com.oshi.desktop.msg.ReplyEnvelope.unwrap(m.content)?.content ?: m.content
                    if (text == null || com.oshi.desktop.msg.ControlPrefix.isControl(text)) return@flatMap emptyList()
                    GalleryCategories.links(text).map { url ->
                        LinkItemRow("${m.id}#$url", url, GalleryCategories.host(url), stamp(m.sentAtMs), m.fromMe)
                    }
                },
                forwardTargets = if (kind == ConversationKind.DIRECT || kind == ConversationKind.GROUP) {
                    client.contacts.all().filterNot { it.blocked || it.address == id }
                        .map { GroupCandidateRow(it.address, it.label(short(it.address)), client.photos.get(it.address)) }
                } else emptyList(),
                typingNames = if (group == null) emptyList() else synchronized(lock) {
                    val now = System.currentTimeMillis()
                    groupTyping[id].orEmpty().filter { (k, until) -> until > now && !com.oshi.desktop.group.GroupIdentity.sameIdentity(k, client.address) }
                        .keys.map(::senderLabel).sorted()
                },
                groupCanEditInfo = group != null && client.canEditGroupInfo(id),
                groupMuted = group?.isMuted == true || (group == null && client.conversationPrefs.isMuted(id)),
                conversationPinned = client.conversationPrefs.isPinned(id),
                contactBlocked = kind == ConversationKind.DIRECT && client.contacts.get(id)?.blocked == true,
                groupMembers = group?.members?.map { member ->
                    GroupMemberRow(
                        address = member.publicKey,
                        // __GROUP_PARITY_2026_09_23__ our alias, then the member's own group alias
                        // (iOS `GroupMentions.displayName` order), then a short key.
                        label = client.contacts.get(member.publicKey)?.label(member.alias?.takeIf { it.isNotBlank() } ?: short(member.publicKey))
                            ?: member.alias?.takeIf { it.isNotBlank() } ?: short(member.publicKey),
                        isAdmin = member.isAdmin,
                        isCreator = GroupIdentity.sameIdentity(member.publicKey, group.adminPublicKey),
                        self = member.publicKey == client.address,
                        blocked = client.contacts.isBlocked(member.publicKey),
                    )
                }.orEmpty(),
                groupCandidates = group?.let { definition ->
                    client.contacts.all()
                        .filterNot { contact -> contact.blocked || definition.members.any { GroupIdentity.sameIdentity(it.publicKey, contact.address) } }
                        .map { contact -> GroupCandidateRow(contact.address, contact.label(short(contact.address))) }
                }.orEmpty(),
                // A safety number is a function of two IDENTITY keys, so it means nothing
                // for a group (many), a bot (none) or a LoRa node (unauthenticated). Drawing
                // one anyway would offer a verification ritual that verifies nothing.
                safetyNumber = if (kind == ConversationKind.DIRECT) client.safetyNumber(id) else "",
                safetyQr = if (kind == ConversationKind.DIRECT) com.oshi.desktop.crypto.SafetyNumber.qrPayload(client.address, id) else "",
                verified = kind == ConversationKind.DIRECT &&
                    client.contacts.get(id)?.verification == ContactStore.VerificationState.VERIFIED,
                reach = if (kind == ConversationKind.DIRECT) reach else Reach.NOT_APPLICABLE,
                reachLabel = reachLabel(if (kind == ConversationKind.DIRECT) reach else Reach.NOT_APPLICABLE),
                peerTyping = kind == ConversationKind.DIRECT && (typingUntilMs[id] ?: 0L) > System.currentTimeMillis(),
                messages = client.history(id).let { rows ->
                    // __GROUP_COMMENTS_2026_11_21__ §2.12.6: a bar under every post of an Active group.
                    val active = group?.effectiveSettings?.commentsActive == true && !group.isDissolved
                    val counts = if (active) client.commentCounts(id) else emptyMap()
                    rows.map { m ->
                        val r = row(m)
                        if (active && !m.isDeletedForEveryone && group!!.isAdmin(m.senderAddress)) {
                            r.copy(comments = counts[m.id.uppercase(java.util.Locale.ROOT)] ?: 0)
                        } else r
                    }
                },
                commentThread = commentsFor?.takeIf { it.first == id && group != null }?.let { commentThreadView(group!!, it.second) },
                composer = composerFor(id),
                // __BOT_E2E_2026_09_23__ BOT_SEAL_SPEC.md §3: say how the LATEST bot post was
                // protected. Rows stored before bot-seal-v1 carry no bot transport = legacy.
                botSealing = if (kind != ConversationKind.BOT) null else
                    client.history(id).lastOrNull { !it.fromMe }?.let {
                        com.oshi.desktop.bot.BotEnvelope.Sealing.fromTransport(it.transport)
                            ?: com.oshi.desktop.bot.BotEnvelope.Sealing.NONE
                    },
                unreadAtOpen = unreadAtOpen,
                groupSettings = group?.let { groupSettingsView(it) },
            )
        }
        return ShellState(
            selfAddress = client.address,
            selfAddressShort = short(client.address),
            displayName = client.displayName,
            relayUrl = client.serverUrl,
            gateOpen = client.config.isEnabledCached(),
            gateAnswered = client.config.everAnswered,
            deliveryReceiptsEnabled = client.deliveryReceiptsEnabled,
            readReceiptsEnabled = client.readReceiptsEnabled,
            typingIndicatorsEnabled = client.typingIndicatorsEnabled,
            syncNotice = syncNotice,
            backupNotice = backupNotice,
            ownNickname = client.ownNickname,
            nicknameBroadcasts = client.mayBroadcastProfile,
            profileNotice = profileNotice,
            replyingTo = replyTarget?.takeIf { it.first == id }?.second?.let(::quoteRow),
            selfPictureBase64 = client.ownPhotoBase64,
            destination = destination,
            // `all()`, not `visible()`: this list is where blocking is UNDONE, and a blocked
            // peer filtered out of the only screen carrying an unblock control would be a
            // one-way door. The row says which ones are blocked; the list shows everyone.
            contacts = client.contacts.all().map { c ->
                ContactRow(
                    address = c.address,
                    addressShort = short(c.address),
                    label = c.label(short(c.address)),
                    alias = c.displayName?.takeIf { it.isNotBlank() },
                    sharedNickname = c.sharedNickname,
                    verified = c.verification == ContactStore.VerificationState.VERIFIED,
                    blocked = c.blocked,
                    safetyNumber = client.safetyNumber(c.address),
                    pictureBase64 = client.photos.get(c.address),
                )
            },
            scheduled = client.scheduled.all().map { scheduled ->
                ScheduledRow(
                    id = scheduled.id,
                    recipient = scheduled.recipient,
                    content = scheduled.content,
                    due = stamp(scheduled.scheduledAtMs),
                    status = scheduled.status.iosWire,
                    group = scheduled.isGroup,
                )
            },
            shareText = ContactQr.shareText(client.address),
            pane = pane,
            conversations = rows,
            selectedId = id,
            thread = thread,
            draft = draftText,
            sendSerial = sendSerial,
            busy = busy,
            notice = notice,
            publicJoin = publicJoin,
        )
    }

    private fun row(m: Message): MessageRow {
        // __GROUP_PARITY_2026_09_23__ reply / forward envelopes are unwrapped, never drawn as JSON.
        val env = if (m.isDeletedForEveryone) null else com.oshi.desktop.msg.ReplyEnvelope.unwrap(m.content)
        return MessageRow(
        id = m.id,
        fromMe = m.fromMe,
        who = if (m.fromMe) "me" else senderLabel(m.senderAddress),
        // The DELETE flag is read BEFORE the content, not after. A row deleted for everyone
        // can still carry a body — the store keeps the record and the router nulls the text,
        // but nothing in the type system says a caller could not hand it both — and a
        // renderer that reached for `content` first would draw the message its sender
        // revoked. PARITY.md row 0.18 is the authority for the delete existing at all; this
        // is about never rendering past it.
        body = when {
            m.isDeletedForEveryone -> t("message.deleted_for_everyone")
            // __DESKTOP_CALL_UI_2026_09_23__ a call-history row carries a KEY; draw it localized.
            com.oshi.desktop.msg.CallSummary.isCallSummary(m.content) -> com.oshi.desktop.msg.CallSummary.render(m.content!!) { t(it) }
            env != null -> env.content
            // __VIDEO_NOTE_2026_09_24__ a round note has no caption box (spec §4): its `content`
            // is the informative filename, which the circle must not print under itself.
            m.videoNote && m.mediaType == com.oshi.desktop.store.MediaType.VIDEO &&
                (m.content == null || m.content.endsWith(".mp4", ignoreCase = true)) -> ""
            m.mediaRef != null -> m.content.orEmpty()
            // __DESKTOP_POLLS_2026_10_29__ The card draws the question, so the body must be
            // empty rather than the payload. Before polls were decoded this `when` fell
            // through to `content`, which is why a stored poll printed as raw JSON — and that
            // is exactly what would happen again if `poll` were drawn and this line were not.
            com.oshi.desktop.msg.PollPayload.isPoll(m.content) -> ""
            // __DESKTOP_1_6_PARITY__ a place draws a map card; a check-in its sentence.
            com.oshi.desktop.place.LocationPayload.decode(m.content.orEmpty()) != null -> ""
            com.oshi.desktop.msg.AliveCheckInPayload.isEvent(m.content.orEmpty()) -> "\uD83D\uDC93 " + ListPreview.of(m) { "" }.text
            else -> m.content ?: "(deleted)"
        },
        quote = env?.quote?.let(::quoteRow),
        forwardedFrom = env?.forwardedFrom,
        // __MENTIONS_2026_09_23__ admitted targets; the bubble finds their `@name` in `body`.
        mentions = if (m.isDeletedForEveryone) emptyList() else m.mentions,
        // The bytes are on disk (PARITY.md row 0.15) and this window does not open them.
        // Naming the file and the path is a true statement; a thumbnail would be a promise.
        attachment = if (m.isDeletedForEveryone || m.mediaRef == null) null
        else "[${m.mediaType?.wire ?: "file"}] ${m.mediaRef}",
        stamp = stamp(m.sentAtMs),
        // Only outgoing rows carry a status. An inbound message's DeliveryStatus describes
        // OUR receipt bookkeeping, and painting it beside a bubble somebody else sent reads
        // as a claim about THEIR delivery, which this client knows nothing about.
        status = if (m.fromMe) m.deliveryStatus.wire else null,
        // __GROUP_RESEND_2026_11_25__ not "Sent" while group members are still owed it.
        partialDelivery = if (!m.fromMe) null else client.groupDeliveryOf(m.id)?.let { e ->
            PartialDeliveryRow(
                label = t("group.sent_partial", e.delivered, e.total),
                detail = (if (e.gaveUp) t("group.sent_partial.gave_up", e.pending.size) else t("group.sent_partial.body", e.pending.size)) +
                    "\n" + e.pending.map { senderLabel(it) }.sorted().take(20).joinToString(", "),
                gaveUp = e.gaveUp,
            )
        },
        failed = m.fromMe && m.deliveryStatus == DeliveryStatus.FAILED,
        coverWaiting = m.fromMe && m.deliveryStatus == DeliveryStatus.PENDING &&
            m.transport == com.oshi.desktop.app.CoverOutbox.TRANSPORT_WAITING,
        restrictedNetwork = !m.fromMe && m.restrictedNetwork,
        // __LORA_AIRTIME_HINT_2026_11_18__ an OSHI Mesh radio has held it (QUEUED) for over a minute.
        awaitingAirtime = m.fromMe && m.transport == "lora" && m.id in client.loraAwaitingAirtime(),
        edited = m.editedAtMs != null,
        deleted = m.isDeletedForEveryone,
        reactions = m.reactions.keys.sorted().joinToString(""),
        reactionKeys = m.reactions.keys.sorted(),
        // __DESKTOP_POLLS_2026_10_29__ Decoded on every publish; see [PollCardRow].
        poll = if (m.isDeletedForEveryone) null else pollCard(m.content),
        location = if (m.isDeletedForEveryone) null else com.oshi.desktop.place.LocationPayload.decode(m.content.orEmpty())?.let { p ->
            LocationCardRow(p.latitude, p.longitude, p.address?.takeIf { it.isNotBlank() }, p.isLive && p.liveState(System.currentTimeMillis()) == com.oshi.desktop.place.LiveState.LIVE,
                p.remainingMs(System.currentTimeMillis()))
        },
        videoNote = !m.isDeletedForEveryone && m.videoNote && m.mediaType == com.oshi.desktop.store.MediaType.VIDEO,
        mediaDurationMs = m.mediaDurationMs,
        sentAtMs = m.sentAtMs,
        senderAddress = if (m.fromMe) "" else m.senderAddress,
        reactors = if (m.isDeletedForEveryone) emptyList() else Reactors.groups(
            m.reactions,
            isMe = { com.oshi.desktop.group.GroupIdentity.sameIdentity(it, client.address) },
            label = ::senderLabel,
            you = t("common.you"),
        ),
        viewOnce = m.isViewOnce,
        viewOnceOpened = m.viewOnceOpened,
    )
    }

    /**
     * __DESKTOP_OFFLINE_ONLY_2026_10_29__ Take the open conversation off the relay, or put it
     * back.
     *
     * The notice is deliberately specific about the asymmetry, because the switch behaves
     * differently in each direction and a user who is not told will read the ON case as a
     * failure: turning it ON cannot announce itself over the relay it just banned, so with no
     * radio and no mesh peer the restriction holds here and the other side is simply never
     * told. Turning it OFF travels, because the restriction is lifted before the request is
     * sent.
     */
    fun setOfflineOnly(on: Boolean) {
        val conv = synchronized(lock) { selectedId } ?: return
        if (kindOf(conv) != ConversationKind.DIRECT) {
            synchronized(lock) { notice = Notice(t("conv.n.direct_only"), Severity.ERROR) }
            publish()
            return
        }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            val outcome = runCatching { client.setOfflineOnly(conv, on) }
            synchronized(lock) {
                busy = false
                notice = when {
                    outcome.isFailure ->
                        Notice(t("conv.n.change_failed", outcome.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.SENT ->
                        Notice(if (on) t("offline_only.summary.on") else t("offline_only.summary.off"), Severity.OK)
                    on ->
                        // Not a failure. See the note above.
                        Notice(
                            t("offline_only.summary.on") + " " + t("conv.n.offline_on_untold"),
                            Severity.OK,
                        )
                    else -> Notice(t("offline_only.summary.off") + " " + t("conv.n.offline_off_untold"), Severity.OK)
                }
            }
            publish()
        }
    }

    /**
     * __DESKTOP_POLL_COMPOSER_2026_10_29__ Create a poll in the open 1:1 conversation.
     *
     * The validity question is NOT re-asked here. [OshiClient.sendPoll] returns null when
     * `PollPayload.sanitize` refuses, and that null is reported as a refusal rather than as a
     * network error — the two send a user to completely different places, and collapsing them
     * is what makes a composer feel broken.
     */
    fun createPoll(question: String, options: List<String>, multi: Boolean, anon: Boolean, closesInSeconds: Long? = null) {
        val conv = synchronized(lock) { selectedId } ?: return
        val kind = kindOf(conv)
        if (kind != ConversationKind.DIRECT && kind != ConversationKind.GROUP) return
        val closesAt = closesInSeconds?.let { System.currentTimeMillis() / 1000.0 + it }
        synchronized(lock) { busy = true; notice = null }
        publish()
        worker.execute {
            // __GROUP_POLLS_2027__ a group poll is a group message; a 1:1 poll a direct one.
            val outcome = runCatching {
                if (kind == ConversationKind.GROUP) {
                    client.sendGroupPoll(conv, question, options, allowsMultipleAnswers = multi, isAnonymous = anon, closesAtUnix = closesAt)
                        ?.let { if (it.delivered) OshiClient.SendOutcome.SENT else OshiClient.SendOutcome.NO_V2_PATH }
                } else {
                    client.sendPoll(conv, question, options, allowsMultipleAnswers = multi, isAnonymous = anon, closesAtUnix = closesAt)
                }
            }
            synchronized(lock) {
                busy = false
                notice = when {
                    outcome.isFailure ->
                        Notice(t("conv.poll.failed", outcome.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    // null is sanitize refusing, NOT a transport failure.
                    outcome.getOrNull() == null -> Notice(t("conv.poll.invalid"), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.BLOCKED -> Notice(t("conv.poll.blocked"), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.SENT -> Notice(t("conv.poll.sent"), Severity.OK)
                    else -> Notice(t("conv.poll.pending"), Severity.OK)
                }
            }
            publish()
        }
    }

    /**
     * Build the card for a row whose payload is a poll, or null.
     *
     * The tally is read from the client on every call, which is what makes a late ballot show
     * up without the message row changing. A poll whose payload no longer decodes returns
     * null and the bubble falls back to an ordinary (empty-bodied) row rather than throwing
     * out of a draw.
     */
    private fun pollCard(content: String?): PollCardRow? {
        val poll = com.oshi.desktop.msg.PollPayload.decodePoll(content) ?: return null
        val r = client.pollBallots.results(poll, client.address)
        return PollCardRow(
            pollId = poll.id,
            question = poll.question,
            options = poll.options.mapIndexed { i, label ->
                PollOptionRow(
                    label = label,
                    count = r.counts.getOrElse(i) { 0 },
                    percent = r.percent(i),
                    mine = i in r.myPicks,
                )
            },
            voterCount = r.voterCount,
            allowsMultipleAnswers = poll.allowsMultipleAnswers,
            isAnonymous = poll.isAnonymous,
            closed = poll.isClosed(System.currentTimeMillis() / 1000.0),
        )
    }

    /**
     * Vote on a poll in the open conversation, or retract by picking what we already picked.
     *
     * The toggle rule is the phones': in a single-answer poll a new pick REPLACES the old
     * one; in a multiple-answer poll a pick is added, and picking it again removes it. An
     * empty result is a retraction and is sent as one, because both platforms treat an empty
     * ballot as a real ballot that has to beat the earlier one on time.
     */
    fun votePoll(messageId: String, optionIndex: Int) {
        val conv = synchronized(lock) { selectedId } ?: return
        val kind = kindOf(conv)
        if (kind != ConversationKind.DIRECT && kind != ConversationKind.GROUP) return
        val row = client.history(conv).firstOrNull { it.id == messageId } ?: return
        val poll = com.oshi.desktop.msg.PollPayload.decodePoll(row.content) ?: return
        if (poll.isClosed(System.currentTimeMillis() / 1000.0)) {
            synchronized(lock) { notice = Notice(t("conv.poll.closed_notice"), Severity.ERROR) }
            publish()
            return
        }
        if (!poll.isValidOptionIndex(optionIndex)) return
        val current = client.pollBallots.results(poll, client.address).myPicks
        val next = when {
            !poll.allowsMultipleAnswers -> if (current == setOf(optionIndex)) emptySet() else setOf(optionIndex)
            optionIndex in current -> current - optionIndex
            else -> current + optionIndex
        }
        worker.execute {
            val outcome = runCatching {
                if (kind == ConversationKind.GROUP) {
                    // __GROUP_POLLS_2027__ fanned out as an ephemeral ballot; recorded first.
                    client.voteGroupPoll(conv, poll.id, next.toList().sorted())
                        ?.let { if (it.delivered) OshiClient.SendOutcome.SENT else OshiClient.SendOutcome.NO_V2_PATH }
                        ?: OshiClient.SendOutcome.NO_V2_PATH
                } else client.votePoll(conv, poll.id, next.toList().sorted())
            }
            synchronized(lock) {
                notice = when {
                    outcome.isFailure ->
                        Notice(t("conv.vote.failed", outcome.exceptionOrNull()?.message ?: t("conv.error.network")), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.BLOCKED -> Notice(t("conv.vote.blocked"), Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.SENT -> null
                    // Counted here, not yet delivered — said plainly, as the pin does.
                    else -> Notice(t("conv.vote.pending"), Severity.OK)
                }
            }
            publish()
        }
    }

    /** The export's text for one row: the same decoding as the list preview, untruncated. */
    private fun previewOfFull(m: Message): String = when {
        com.oshi.desktop.msg.ReplyEnvelope.isWrapped(m.content) ->
            com.oshi.desktop.msg.ReplyEnvelope.unwrap(m.content)?.content ?: m.content.orEmpty()
        com.oshi.desktop.msg.CallSummary.isCallSummary(m.content) -> com.oshi.desktop.msg.CallSummary.render(m.content!!) { t(it) }
        com.oshi.desktop.place.LocationPayload.decode(m.content.orEmpty()) != null ||
            com.oshi.desktop.msg.AliveCheckInPayload.isEvent(m.content.orEmpty()) -> ListPreview.of(m) { "" }.let { p ->
                val loc = com.oshi.desktop.place.LocationPayload.decode(m.content.orEmpty())
                if (loc != null) "\uD83D\uDCCD " + p.text + " (" + "%.5f, %.5f".format(java.util.Locale.ROOT, loc.latitude, loc.longitude) + ")"
                else "\uD83D\uDC93 " + p.text
            }
        com.oshi.desktop.msg.PollPayload.isPoll(m.content) -> com.oshi.desktop.msg.PollPayload.decodePoll(m.content)?.let { p ->
            "\uD83D\uDCCA " + p.question + p.options.joinToString("") { "\n  - $it" }
        } ?: ""
        else -> com.oshi.desktop.msg.InlineMarkup.stripped(m.content.orEmpty())
    }

    /**
     * __DESKTOP_1_6_PARITY__ The list's line for the last message: a kind (drawn as a glyph) and
     * a text. Media, voice notes, polls, places and check-ins are decided in [ListPreview]; only
     * prose reaches the lambda, which unwraps replies, renders call rows and strips markup.
     */
    private fun listPreviewOf(m: Message?): ListPreview = ListPreview.of(m) { msg ->
        when {
            com.oshi.desktop.msg.ReplyEnvelope.isWrapped(msg.content) ->
                com.oshi.desktop.msg.ReplyEnvelope.unwrap(msg.content)?.content ?: msg.content.orEmpty()
            com.oshi.desktop.msg.CallSummary.isCallSummary(msg.content) -> com.oshi.desktop.msg.CallSummary.render(msg.content!!) { t(it) }
            // __DESKTOP_INLINE_MARKUP_2026_10_29__ The list cannot style, so it must not show
            // the markers either.
            else -> com.oshi.desktop.msg.InlineMarkup.stripped(msg.content.orEmpty())
        }
    }

    private fun labelFor(id: String, kind: ConversationKind): String = when (kind) {
        ConversationKind.GROUP -> client.groups.get(id)?.name ?: short(id)
        ConversationKind.BOT -> t("conv.label.bot", id.removePrefix(BOT_PREFIX))
        ConversationKind.LORA -> t("conv.label.radio", id.removePrefix(LoRaNodeBindings.FALLBACK_PREFIX))
        ConversationKind.DIRECT -> client.contacts.get(id)?.label(short(id)) ?: short(id)
    }

    private fun kindOf(id: String): ConversationKind = when {
        id.startsWith(BOT_PREFIX) -> ConversationKind.BOT
        id.startsWith(LoRaNodeBindings.FALLBACK_PREFIX) -> ConversationKind.LORA
        client.groups.get(id) != null -> ConversationKind.GROUP
        else -> ConversationKind.DIRECT
    }

    /**
     * Whether the composer may send, whether it may attach, and why not.
     *
     * **The two gates are separate and they disagree on exactly one kind.** A group takes
     * text — `sendGroupText` fans one message out over N pairwise ratchet sessions and it is
     * the same call `/group send` makes — and takes NO attachment, because there is no group
     * media fan-out anywhere in this client. Folding both into one boolean is how a window
     * ends up with a paperclip that uploads a blob no member can fetch.
     */
    private fun composerFor(id: String?): ComposerState = when {
        id == null -> ComposerState(false, t("conv.composer.no_conversation"), false, null)
        busy -> ComposerState(false, t("conv.composer.working"), false, null)
        else -> when (kindOf(id)) {
            // __CONVERSATION_BLOCK_2027__ a blocked peer: nothing is sent, the banner says why.
            ConversationKind.DIRECT -> if (client.contacts.get(id)?.blocked == true)
                ComposerState(false, t("chat.contact_blocked_message"), false, t("chat.contact_blocked_message"))
            else ComposerState(true, null, true, null)
            // PARITY.md row 0.17 is partial and the partial part is RECEIVE (three sub-rows
            // blocked: an Android peer's group envelope, membership changes, and the legacy
            // lane). The SEND half is the fan-out this calls, and it is the same code path
            // the REPL has always used — so a disabled composer here was the window being
            // behind the client, not the client being unable.
            // __GROUP_E2E_V2_2026_09_23__ group media now fans out (OshiClient.sendGroupFile).
            ConversationKind.GROUP -> if (client.isGroupBlocked(id)) {
                // __GROUP_PARITY_2026_09_23__ iOS closes the composer of a blocked group behind a banner.
                ComposerState(false, t("group.blocked_message"), false, t("group.blocked_message"))
            } else if (client.groups.get(id)?.isDissolved == true) {
                // __GROUP_INFO_SETTINGS_2026_11_20__ §2.9: read-only after "Delete for everyone".
                ComposerState(false, t("gs_dissolved_banner"), false, t("gs_dissolved_banner"))
            } else if (client.groups.get(id)?.let { !client.mayPostInGroup(id) } == true) {
                // §2.4 channel mode: the muted banner in place of the composer.
                ComposerState(false, t("gs_only_admins_banner"), false, t("gs_only_admins_banner"))
            } else ComposerState(true, null, true, null)
            ConversationKind.BOT -> ComposerState(
                false,
                // OshiClient.BOT_CHANNEL_IS_PLAINTEXT is the console's sentence; this is the same
                // warning in the reader's language (ChatShellModelTest pins that it says so).
                t("conv.composer.bot"),
                false,
                null,
            )
            ConversationKind.LORA -> ComposerState(
                false,
                t("conv.composer.lora"),
                false,
                null,
            )
        }
    }

    // ------------------------------------------------------------------ send outcomes

    /** One shape for both send paths, so the composer has one thing to react to. */
    private data class SendResult(val text: String, val severity: Severity, val keepDraft: Boolean)

    private fun directSendResult(o: OshiClient.SendOutcome) = SendResult(
        outcomeText(o),
        severityOf(o),
        keepDraft = o == OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD,
    )

    /**
     * A group send is PARTIAL by nature and the number is the whole story.
     *
     * `sendGroupText` reports how many legs left out of how many recipients, because a
     * fan-out over N ratchet sessions can succeed for some peers and fail for others — a
     * member who has published no prekey bundle simply has no V2 path. Reporting that as
     * "sent" would be the exact lie this model exists to prevent, so anything short of
     * everyone is drawn as a failure carrying both numbers.
     */
    private fun groupSendResult(r: OshiClient.GroupSendReport): SendResult = when {
        r.refusedControlPayload -> SendResult(
            t("conv.send.refused_short"),
            Severity.ERROR,
            keepDraft = true,
        )
        r.recipients == 0 -> SendResult(
            t("conv.send.group_empty"),
            Severity.ERROR,
            keepDraft = false,
        )
        r.sent == 0 -> SendResult(
            t("conv.send.group_none", r.recipients) + blockedTail(r),
            Severity.ERROR,
            keepDraft = false,
        )
        r.sent < r.recipients -> SendResult(
            t("conv.send.group_partial", r.sent, r.recipients) + blockedTail(r),
            Severity.ERROR,
            keepDraft = false,
        )
        else -> SendResult(
            t("conv.send.group_all", r.recipients) + blockedTail(r),
            Severity.OK,
            keepDraft = false,
        )
    }

    /**
     * A blocked member is SKIPPED by the fan-out and is not a failure.
     *
     * Leaving it out of the count with no word would make a group of five look like a group
     * of four that half-worked. Saying it is the difference between "the network failed you"
     * and "you asked for this".
     */
    private fun blockedTail(r: OshiClient.GroupSendReport): String =
        (if (r.skippedBlocked.isEmpty()) ""
        else " " + t("conv.send.blocked_skipped", r.skippedBlocked.size)) +
            mustUpdateTail(r)

    /**
     * __GROUP_E2E_V2_2026_09_23__ GROUP_E2E_V2_SPEC §6: a member with no v2 path receives NOTHING
     * (there is no legacy fallback), and the group says who, in the user's language.
     */
    private fun mustUpdateTail(r: OshiClient.GroupSendReport): String =
        r.unreachable.joinToString("") { key ->
            " " + t("group.member_must_update", client.contacts.get(key)?.label(short(key)) ?: short(key))
        }

    private fun outcomeText(o: OshiClient.SendOutcome): String = when (o) {
        // The one string this window shares with the REPL, through the same catalog key,
        // so the two surfaces cannot end up disagreeing about what "sent" is called.
        OshiClient.SendOutcome.SENT -> t("messages.sent_successfully")
        OshiClient.SendOutcome.BLOCKED ->
            t("conv.send.blocked")
        // __DESKTOP_SAY_WHICH_FAILURE_2026_10_29__ BLAME THE RIGHT PARTY.
        //
        // Both of these outcomes are ALSO what a client returns when the relay simply did not
        // answer — a bundle fetch that timed out is indistinguishable, at this layer, from a
        // peer who never published one. The old wording told the user that their contact had
        // not set OSHI up, or that they were outside a rollout, and neither is actionable
        // even when true. `V2ConfigGate.serverUnreachable` is the one bit that tells them
        // apart, and it is checked FIRST.
        OshiClient.SendOutcome.NO_V2_PATH ->
            if (client.config.serverUnreachable)
                t("conv.send.server_down_keys")
            else
                t("conv.send.no_keys")
        OshiClient.SendOutcome.GATE_CLOSED ->
            if (client.config.serverUnreachable)
                t("conv.send.server_down")
            else if (!client.config.everAnswered)
                t("conv.send.no_answer_yet")
            else
                t("conv.send.gate_closed")
        OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD ->
            t("conv.send.refused_control")
        // __PRIVACY_DISGUISE_2026_11_18__ the phones' bubble wording; the notice adds the why.
        OshiClient.SendOutcome.WAITING_FOR_COVER -> t("desktop.cover.waiting.notice")
    }

    private fun severityOf(o: OshiClient.SendOutcome): Severity = when (o) {
        OshiClient.SendOutcome.SENT -> Severity.OK
        OshiClient.SendOutcome.WAITING_FOR_COVER -> Severity.INFO
        else -> Severity.ERROR
    }

    private fun reachLabel(r: Reach): String = when (r) {
        Reach.UNKNOWN -> t("conv.reach.unknown")
        Reach.CHECKING -> t("conv.reach.checking")
        Reach.REACHABLE -> t("conv.reach.reachable")
        Reach.NO_BUNDLE -> t("conv.reach.no_keys")
        Reach.ERROR -> t("conv.reach.error")
        Reach.NOT_APPLICABLE -> ""
    }

    companion object {
        /** `OshiClient` files bot posts under this. Never a peer conversation — row 0.26. */
        const val BOT_PREFIX = "bot!"

        private fun defaultWorker(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "oshi-ui-worker").apply { isDaemon = true }
        }
    }
}

// ---------------------------------------------------------------------------- the state

/**
 * The five destinations the shipped macOS window carries across its top.
 *
 * This is the phone's `MainTabView` order, kept because a user moving between the two should
 * not have to re-learn where anything is — but it is NOT a tab bar: nothing here is anchored
 * to an icon position, there is no badge on a glyph, and the detail pane beside it is a
 * desktop split view rather than a pushed navigation stack.
 *
 * `PLACES` is the phone's `Map` tab and is deliberately not called Map. PARITY.md row 0.19
 * is unambiguous that this client has no GPS, no navigation and no live location, and the
 * pane says so on screen; what it DOES have is the offline POI and street reader, which is
 * a real, shipped capability and the only thing that destination offers.
 */
enum class Destination { MESSAGES, AI, NEW, PLACES, MORE }

/** DEVICES: __DEVSYNC_DIRECT_2026_09_22__ More → Linked devices (direct own-device sync). */
enum class Pane { CONVERSATION, ACCOUNT, LIMITS, COVERT, MAIL, SCHEDULED, DEVICES, LORA, PEERS, DELETE_ACCOUNT, DISGUISE, DISCOVER_GROUPS }

enum class ConversationKind { DIRECT, GROUP, BOT, LORA }

enum class Reach { UNKNOWN, CHECKING, REACHABLE, NO_BUNDLE, ERROR, NOT_APPLICABLE }

enum class Severity { OK, INFO, ERROR }

data class Notice(val text: String, val severity: Severity)

data class ComposerState(
    val enabled: Boolean,
    val disabledReason: String?,
    val attachEnabled: Boolean,
    val attachDisabledReason: String?,
)

data class ConversationRow(
    val id: String,
    val label: String,
    val kind: ConversationKind,
    val messageCount: Int,
    val lastActivityMs: Long,
    val lastActivity: String,
    val preview: String,
    val unread: Int,
    /** __DESKTOP_1_6_PARITY__ what [preview] describes, drawn as a glyph before it. */
    val previewKind: ListPreview.Kind = ListPreview.Kind.TEXT,
    /** __MENTIONS_2026_09_23__ an unseen message in this group mentions you. */
    val mentionedYou: Boolean = false,
    /** __GROUP_PARITY_2026_09_23__ a group's picture, drawn instead of the monogram. */
    val pictureBase64: String? = null,
    /** __MERGED_CHATS_2026_11_18__ a group's type, for the Admin / Collaborative / Public sub-filter. */
    val groupType: com.oshi.desktop.group.GroupType? = null,
    /** __CONVERSATION_PIN_MUTE_2027__ pinned to the top of the list (this machine only). */
    val pinned: Boolean = false,
    /** Notifications off: a muted group, or a muted 1:1 (this machine only). */
    val muted: Boolean = false,
)

/**
 * __DESKTOP_POLLS_2026_10_29__ A poll as the bubble draws it — the definition from the
 * message payload, the numbers from [com.oshi.desktop.store.PollTally].
 *
 * Built fresh on every publish rather than stored, which is what keeps the definition
 * immutable: a ballot arriving later changes these numbers without the message row being
 * rewritten. iOS records the reason it matters — a bubble that mutates the payload it was
 * parsed from can no longer be re-parsed.
 */
data class PollCardRow(
    val pollId: String,
    val question: String,
    /** Option label, its vote count, and its rounded share. Index IS the option index. */
    val options: List<PollOptionRow>,
    /** Distinct people with a non-empty ballot — NOT the sum of the counts in a multi poll. */
    val voterCount: Int,
    val allowsMultipleAnswers: Boolean,
    val isAnonymous: Boolean,
    /** Closed polls still show their results; the options stop being clickable. */
    val closed: Boolean,
)

data class PollOptionRow(
    val label: String,
    val count: Int,
    val percent: Int,
    /** This is one of OUR picks. */
    val mine: Boolean,
)

/**
 * __DESKTOP_MEDIA_GALLERY_2026_10_29__ One attachment in the conversation's gallery.
 *
 * Built from the same rows the thread draws, so there is no second source of truth about
 * what a conversation contains. A SEALED view-once row is excluded at the source: the
 * gallery must not become the surface that shows what the bubble is still hiding.
 */
data class MediaItemRow(
    val messageId: String,
    val path: String,
    val kind: com.oshi.desktop.store.MediaType,
    val fileName: String,
    val stamp: String,
    val fromMe: Boolean,
)

data class MessageRow(
    val id: String,
    val fromMe: Boolean,
    val who: String,
    val body: String,
    val attachment: String?,
    val stamp: String,
    val status: String?,
    val failed: Boolean,
    val edited: Boolean,
    val deleted: Boolean,
    val reactions: String,
    /** __GROUP_PARITY_2026_09_23__ the message this one replies to, when it is a reply. */
    val quote: QuoteRow? = null,
    /** Non-null for a forwarded message (may be blank: the sender was not named). */
    val forwardedFrom: String? = null,
    /** __MENTIONS_2026_09_23__ `@name` targets, highlighted in the bubble (MentionWire). */
    val mentions: List<com.oshi.desktop.group.MentionWire.Mention> = emptyList(),
    /** __VIDEO_NOTE_2026_09_24__ draw [attachment] as a round video note (VIDEO_NOTE_SPEC §4). */
    /** __DESKTOP_POLLS_2026_10_29__ non-null when this row's payload is a `📊POLL📊`. */
    val poll: PollCardRow? = null,
    /** __DESKTOP_1_6_PARITY__ a shared place, drawn as a map card instead of a body. */
    val location: LocationCardRow? = null,
    val videoNote: Boolean = false,
    /** The note's announced duration, when the sender stated one; else read from the file. */
    val mediaDurationMs: Long? = null,
    /**
     * __DESKTOP_THREAD_SEARCH_2026_10_29__ When this was sent, in epoch millis.
     *
     * The row already carries [stamp], but that is a FORMATTED string in the viewer's locale
     * and a search must not parse it back: a date filter built on "14:05" or "2/3" would be
     * comparing presentation, and would mean something different in another locale.
     */
    val sentAtMs: Long = 0L,
    /**
     * __OSHI_SMILE_REACTION_2026_11_09__ The reaction keys, one per element, in the order
     * [reactions] joins them. The bubble needs them SEPARATE: in the joined string a "⭕"
     * reaction next to a "😊" one is the byte sequence of the OSHI reaction, and only the
     * exact key `⭕😊` may be drawn as the OSHI mark.
     */
    val reactionKeys: List<String> = emptyList(),
    /** __PRIVACY_DISGUISE_2026_11_18__ PENDING because it waits for the disguised connection. */
    val coverWaiting: Boolean = false,
    /** __RESTRICTED_NETWORK_HINT_2026_11_18__ inbound, sent through a restricted network (RN-HINT v1). */
    val restrictedNetwork: Boolean = false,
    /** __LORA_AIRTIME_HINT_2026_11_18__ "Waiting for radio airtime (legal duty-cycle limit)". */
    val awaitingAirtime: Boolean = false,
    /** __REPLY_PRIVATELY_DESKTOP_2027__ who sent it (identity key); blank for our own rows. */
    val senderAddress: String = "",
    /** __WHO_REACTED_DESKTOP_2027__ each reaction with the people who chose it. */
    val reactors: List<ReactorGroup> = emptyList(),
    /** __VIEW_ONCE_DESKTOP_2027__ a view-once attachment, and whether we (the recipient) opened it. */
    val viewOnce: Boolean = false,
    val viewOnceOpened: Boolean = false,
    /**
     * __GROUP_COMMENTS_2026_11_21__ GROUP_INFO_SETTINGS_SPEC §2.12.6: non-null under a POST of a
     * group whose comments are Active — the number of stored, non-deleted comments (0 = "Leave a
     * comment"). Null everywhere else (no bar).
     */
    val comments: Int? = null,
    /** __GROUP_RESEND_2026_11_25__ an own group message some members have not received yet. */
    val partialDelivery: PartialDeliveryRow? = null,
)

/** __GROUP_RESEND_2026_11_25__ "Sent to X of Y" + who is still waiting (tooltip) — click = Retry now. */
data class PartialDeliveryRow(val label: String, val detail: String, val gaveUp: Boolean)

/**
 * __GROUP_COMMENTS_2026_11_21__ The comment thread of one post, as the side panel draws it.
 * [post] null = the post is gone ([State.POST_UNAVAILABLE]).
 */
data class CommentThreadView(
    val postId: String,
    val post: MessageRow?,
    val comments: List<MessageRow>,
    val state: State,
    val draft: String,
    val busy: Boolean,
    val replyingTo: QuoteRow?,
    /** Admins delete any comment (§2.12.4 D); everyone else their own. */
    val canModerate: Boolean,
) {
    enum class State { OPEN, DISABLED, POST_UNAVAILABLE }
}

/** A reply's quote block, and the composer's "replying to" banner. */
data class QuoteRow(val messageId: String, val who: String, val text: String)

/** __DESKTOP_1_6_PARITY__ A `📍LOCATION📍` message as its bubble draws it. */
data class LocationCardRow(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    /** A live share that has not ended or expired. */
    val live: Boolean = false,
    val remainingMs: Long? = null,
)

data class ThreadView(
    val conversationId: String,
    val title: String,
    val address: String,
    val kind: ConversationKind,
    /** Group controls are drawn only when this account is an administrator in this roster. */
    val groupAdmin: Boolean,
    /** __GROUP_PARITY_2026_09_23__ the group picture (JPEG, base64), when the definition carries one. */
    val groupPictureBase64: String? = null,
    /** iOS `canChangeGroupPicture`: admins, or any member of a non-admin-only group. */
    val groupCanEditInfo: Boolean = false,
    /**
     * Notifications off for this conversation on this device (local only). Named for the
     * group switch it started as; __CONVERSATION_PIN_MUTE_2027__ a 1:1 mute sets it too.
     */
    val groupMuted: Boolean = false,
    /** __CONVERSATION_PIN_MUTE_2027__ this conversation is pinned to the top of the list. */
    val conversationPinned: Boolean = false,
    /** A 1:1 whose peer this account blocked: composer closed behind a banner. */
    val contactBlocked: Boolean = false,
    /** iOS-format invite link, shown with its QR code. */
    val groupInviteLink: String? = null,
    val groupDescription: String? = null,
    /** Blocked on this device: composer closed behind a banner, no notifications. */
    val groupBlocked: Boolean = false,
    /**
     * The pinned message of this conversation, when we hold the row it names.
     *
     * __DESKTOP_DIRECT_PIN_2026_10_29__ This was `groupPinned` and read only a group
     * definition's `pinnedMessageId`, so a 1:1 pin — which both phones send and apply over
     * `🔧ACTION🔧` — had nowhere to appear even once the router stored it. One field for
     * both kinds, because a pin is a property of a conversation, not of a group.
     */
    val pinned: QuoteRow? = null,
    /** True when the open conversation lets THIS user pin and unpin. See [pinned]. */
    val pinCanToggle: Boolean = false,
    /**
     * __DESKTOP_OFFLINE_ONLY_2026_10_29__ This conversation is restricted to the mesh and the
     * radio. Nothing this device sends in it goes over the relay.
     */
    val offlineOnly: Boolean = false,
    /** __DESKTOP_1_6_PARITY__ the safety check-in of a 1:1, read off its 💓ALIVE💓 rows. */
    val alive: AliveState = AliveState.NONE,
    /**
     * How many OTHER participants have confirmed the restriction, out of how many there are.
     *
     * Shown rather than assumed: nothing can make another client refuse the internet, so a
     * confirmed count below the total is the honest state of the conversation. Null when the
     * conversation is not restricted and there is nothing to report.
     */
    val offlineOnlyConfirmed: Pair<Int, Int>? = null,
    /** Group members typing right now (display names). */
    val typingNames: List<String> = emptyList(),
    /** __GROUP_PARITY_2026_09_23__ contacts a message may be forwarded to. */
    val forwardTargets: List<GroupCandidateRow> = emptyList(),
    /**
     * __DESKTOP_MEDIA_GALLERY_2026_10_29__ Every attachment in this conversation, newest
     * first — iOS's `MediaGalleryView`, Android's "Media Gallery" menu item.
     */
    val media: List<MediaItemRow> = emptyList(),
    /** __GALLERY_BY_TYPE_DESKTOP_2027__ links found in the conversation's messages, newest first. */
    val links: List<LinkItemRow> = emptyList(),
    /** Empty for direct, bot and radio threads; roster data comes only from a stored definition. */
    val groupMembers: List<GroupMemberRow>,
    /** Known unblocked contacts not in this group; candidates are never inferred from messages. */
    val groupCandidates: List<GroupCandidateRow>,
    /** PARITY.md row 0.20. Blank for anything the ratchet did not vouch for. */
    val safetyNumber: String,
    /** __CONVERSATION_VERIFY_2027__ the QR a phone scans to verify this pair (`SafetyNumber.qrPayload`). */
    val safetyQr: String = "",
    val verified: Boolean,
    val reach: Reach,
    val reachLabel: String,
    /** Ephemeral peer state, auto-cleared after [TYPING_VISIBLE_MS]. */
    val peerTyping: Boolean,
    val messages: List<MessageRow>,
    val composer: ComposerState,
    /** __BOT_E2E_2026_09_23__ Bot threads only: how the latest post was protected; null elsewhere. */
    val botSealing: com.oshi.desktop.bot.BotEnvelope.Sealing? = null,
    /**
     * __UNREAD_DIVIDER_2027__ How many messages were unread when this conversation was
     * opened — the phones' "Unread messages" divider (`chat_unread_divider`) sits above them.
     * Fixed for the visit: reading does not move it, reopening the conversation clears it.
     */
    val unreadAtOpen: Int = 0,
    /** __GROUP_INFO_SETTINGS_2026_11_20__ GROUP_INFO_SETTINGS_SPEC §5, groups only. */
    val groupSettings: GroupSettingsView? = null,
    /** __GROUP_COMMENTS_2026_11_21__ the comment thread open beside this group, if any. */
    val commentThread: CommentThreadView? = null,
)

private const val TYPING_VISIBLE_MS = 5_000L

/** A group roster row is identity metadata, not a presence signal. */
data class GroupMemberRow(
    val address: String,
    val label: String,
    val isAdmin: Boolean,
    /** Creator role is permanent, as it anchors the roster's administrator set. */
    val isCreator: Boolean,
    val self: Boolean,
    /** __GROUP_MEMBER_MODERATION_2027__ this member is blocked (their group messages are dropped). */
    val blocked: Boolean = false,
)

data class GroupCandidateRow(
    val address: String,
    val label: String,
    /** __PROFILE_PHOTOS_DESKTOP_2027__ the contact's own picture, when they shared one. */
    val pictureBase64: String? = null,
)

/** One person this account knows about, as the contacts and blocked lists draw them. */
data class ContactRow(
    val address: String,
    val addressShort: String,
    val label: String,
    val verified: Boolean,
    val blocked: Boolean,
    val safetyNumber: String,
    /** __SHARED_NICKNAME_2026_09_22__ OUR alias for them — what the rename field edits. */
    val alias: String? = null,
    /** What they call themselves (their profile update). Shown under the name when an alias hides it. */
    val sharedNickname: String? = null,
    /** __PROFILE_PHOTOS_DESKTOP_2027__ the picture from their profile update. */
    val pictureBase64: String? = null,
)

/** Persisted local delivery intent; due time is the local clock, never a relay promise. */
data class ScheduledRow(
    val id: String,
    val recipient: String,
    val content: String,
    val due: String,
    val status: String,
    val group: Boolean,
)

/**
 * __GATE_NOT_YET_IS_NOT_NO_2026_10_29__ What a surface should SAY about the V2 gate.
 *
 * Four places read `gateOpen` as a boolean and each one independently told a client that
 * had not finished asking that its ACCOUNT was refused: the REPL's send outcome, the
 * window's send notice, the chip under the user's own name, and the account pane someone
 * opens precisely to find out. They were fixed one at a time, over two hours, because
 * nothing named the three states in one place. This does.
 *
 * [ASKING] is not a failure and must not be painted as one: on this relay `/v2/config`
 * has been measured at 9-25 s, so it is the state every launch passes through.
 */
enum class GateDisplay { OPEN, ASKING, CLOSED }

/** Pure: the gate's verdict AND whether it has one yet. */
fun gateDisplay(gateOpen: Boolean, gateAnswered: Boolean): GateDisplay = when {
    gateOpen -> GateDisplay.OPEN
    !gateAnswered -> GateDisplay.ASKING
    else -> GateDisplay.CLOSED
}

data class ShellState(
    val selfAddress: String,
    val selfAddressShort: String,
    val displayName: String,
    val relayUrl: String,
    val gateOpen: Boolean,
    /**
     * __GATE_NOT_YET_IS_NOT_NO_2026_10_29__ Has the gate had an ANSWER yet? [gateOpen] is
     * false before the first `/v2/config` reply and after one that said no, and the chip
     * painted both of them red with "sends refused" — a verdict about the account, shown
     * to someone whose client had simply not finished asking.
     */
    val gateAnswered: Boolean,
    val deliveryReceiptsEnabled: Boolean,
    val readReceiptsEnabled: Boolean,
    val syncNotice: Notice?,
    val destination: Destination,
    val contacts: List<ContactRow>,
    val scheduled: List<ScheduledRow>,
    val shareText: String,
    val pane: Pane,
    val conversations: List<ConversationRow>,
    val selectedId: String?,
    val thread: ThreadView?,
    val draft: String,
    /**
     * __DRAFT_CLEAR_RACE_2026_11_18__ Increments each time a text send settles. The window
     * used to clear the box on the busy true → false EDGE alone; against a fast relay (a local
     * one under test: send + publish in a few ms) both states can land between two frames, the
     * edge is never observed, and the sent text stays in the composer. Seen on the Xvfb run.
     */
    val sendSerial: Int = 0,
    val busy: Boolean,
    val notice: Notice?,
    /** Outcome of the last `.oshiexport` export/import, shown in the More pane. */
    val backupNotice: Notice? = null,
    /** __SHARED_NICKNAME_2026_09_22__ The nickname set for this account (null = none). */
    val ownNickname: String? = null,
    /** Outcome of the last nickname save / archive sync. */
    val profileNotice: Notice? = null,
    /** False on an identity shared with a phone: the nickname is kept local (see `OshiClient.mayBroadcastProfile`). */
    val nicknameBroadcasts: Boolean = true,
    /** __GROUP_PARITY_2026_09_23__ the message the draft replies to (open conversation only). */
    val replyingTo: QuoteRow? = null,
    /** __TYPING_PRIVACY_2027__ */
    val typingIndicatorsEnabled: Boolean = true,
    /** __PROFILE_PHOTOS_DESKTOP_2027__ this account's own picture (base64 JPEG), null = monogram. */
    val selfPictureBase64: String? = null,
    /** __GROUP_INFO_SETTINGS_2026_11_20__ a public link awaiting confirmation (GROUP_INFO_SETTINGS_SPEC §3.1). */
    val publicJoin: PublicJoinState? = null,
) {
    companion object {
        fun empty(address: String, name: String) = ShellState(
            selfAddress = address,
            selfAddressShort = short(address),
            displayName = name,
            relayUrl = "",
            gateOpen = false,
            gateAnswered = false,
            deliveryReceiptsEnabled = true,
            readReceiptsEnabled = true,
            syncNotice = null,
            destination = Destination.MESSAGES,
            contacts = emptyList(),
            scheduled = emptyList(),
            shareText = "",
            pane = Pane.CONVERSATION,
            conversations = emptyList(),
            selectedId = null,
            thread = null,
            draft = "",
            busy = false,
            notice = null,
        )
    }
}
