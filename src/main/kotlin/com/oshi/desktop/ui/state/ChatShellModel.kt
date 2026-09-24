package com.oshi.desktop.ui.state

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.app.short
import com.oshi.desktop.app.stamp
import com.oshi.desktop.group.GroupIdentity
import com.oshi.desktop.i18n.dt
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
    private val onIncomingNotification: () -> Unit = {},
) : AutoCloseable {

    // ------------------------------------------------------------------ mutable core

    private val lock = Any()
    private val unread = HashMap<String, Int>()
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
    /** __GROUP_PARITY_2026_09_23__ the message the next send replies to, and in which conversation. */
    private var replyTarget: Pair<String, com.oshi.desktop.msg.ReplyEnvelope.Quote>? = null
    /** __GROUP_PARITY_2026_09_23__ group id → member key → typing-until (epoch ms). */
    private val groupTyping = HashMap<String, HashMap<String, Long>>()
    private var busy: Boolean = false
    private var notice: Notice? = null
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
    }

    // ------------------------------------------------------------------ commands

    /** Re-read the stores and republish. Cheap enough to call on every change. */
    fun refresh() = publish()

    /** The reach badge as of [nowMs]: mesh peers (drops held a few seconds) and LoRa nodes. */
    fun networkBadge(nowMs: Long): NetworkBadge {
        val attached = client.loraAttached
        val nodes = if (attached) client.loraOnlineNodes(nowMs) else 0
        val peers = client.meshPeerCount
        return synchronized(lock) { badgeTracker.observe(peers, attached, nodes, nowMs) }
    }

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
    fun setReceiptPrivacy(delivery: Boolean? = null, read: Boolean? = null) {
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
                    onSuccess = { Notice("Archived ${it.accepted.size} contact record(s); server head is seq ${it.maxSeq}.", Severity.OK) },
                    onFailure = { Notice("Could not archive contacts: ${it.message ?: it.javaClass.simpleName}", Severity.ERROR) },
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
                            dt("desktop.export.done", r.messages, r.file.path) + " " +
                                dt("desktop.export.done.partial", r.unreadableConversations),
                            Severity.ERROR,
                        ) else Notice(dt("desktop.export.done", r.messages, r.file.path), Severity.OK)
                    },
                    onFailure = { Notice(dt("desktop.export.failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
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
                        val invalid = if (r.invalid > 0) " " + dt("desktop.import.invalid", r.invalid) else ""
                        Notice(
                            t("import.messages_imported", r.imported) + " " + dt("desktop.import.alreadyPresent", r.alreadyPresent) + invalid,
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
                            "Applied ${it.applied}; skipped ${it.skippedUndecryptable} undecryptable, " +
                                "${it.skippedStale} stale and ${it.skippedOwn} own record(s). Cursor: ${it.lastSeq}/${it.headMaxSeq}.",
                            Severity.OK,
                        )
                    },
                    onFailure = { Notice("Could not pull the archive: ${it.message ?: it.javaClass.simpleName}", Severity.ERROR) },
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
    fun select(id: String?) {
        val kind = id?.let { kindOf(it) }
        var stopTyping: String? = null
        synchronized(lock) {
            stopTyping = typingTarget
            typingTarget = null
            selectedId = id
            pane = Pane.CONVERSATION
            draftText = ""
            replyTarget = null
            notice = null
            reach = Reach.UNKNOWN
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
        val (target, text) = synchronized(lock) {
            val id = selectedId
            val composer = composerFor(id)
            when {
                id == null -> return
                !composer.enabled -> {
                    notice = Notice(composer.disabledReason ?: "This conversation cannot be sent to.", Severity.ERROR)
                    null to ""
                }
                busy -> null to ""
                draftText.isBlank() -> {
                    notice = Notice("Nothing to send.", Severity.INFO)
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
                    notice = Notice("Send failed: ${e.javaClass.simpleName}: ${e.message}", Severity.ERROR)
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
     * GROUPS ARE REFUSED, and that is a client fact rather than a window one: there is no
     * group media fan-out on this client at all — `sendGroupText` carries text — so an attach
     * button that appeared to work in a group would upload a blob nobody receives.
     */
    fun attach(file: File, mediaType: MediaType? = null, discardAfterSend: Boolean = false) {
        val target = synchronized(lock) {
            val id = selectedId
            val composer = composerFor(id)
            when {
                id == null -> return
                busy -> return
                !composer.attachEnabled -> {
                    notice = Notice(
                        composer.attachDisabledReason ?: "This conversation takes no attachments.",
                        Severity.ERROR,
                    )
                    null
                }
                !file.isFile -> {
                    notice = Notice("Not a readable file: ${file.path}", Severity.ERROR)
                    null
                }
                else -> { busy = true; id }
            }
        }
        if (target == null) { publish(); return }

        val group = kindOf(target) == ConversationKind.GROUP
        worker.execute {
            val result = try {
                // __GROUP_E2E_V2_2026_09_23__ group media: one sealed file, one blob per member (spec §4).
                if (group) client.sendGroupFile(target, file)?.let(::groupSendResult)
                    ?: SendResult(dt("desktop.group.media.refused"), Severity.ERROR, keepDraft = false)
                else directSendResult(client.sendFile(target, file, mediaType))
            } catch (e: Exception) {
                if (discardAfterSend) runCatching { file.delete() }
                synchronized(lock) {
                    busy = false
                    notice = Notice("Attach failed: ${e.javaClass.simpleName}: ${e.message}", Severity.ERROR)
                }
                publish()
                return@execute
            }
            synchronized(lock) {
                busy = false
                notice = Notice(
                    if (result.severity == Severity.OK)
                        "Sent ${file.name} (${file.length()} bytes), encrypted before it left."
                    else result.text,
                    result.severity,
                )
            }
            if (discardAfterSend) runCatching { file.delete() }
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
    fun startConversation(raw: String) {
        // __GROUP_PARITY_2026_09_23__ a pasted or scanned GROUP invite joins the group instead.
        if (com.oshi.desktop.group.GroupInvite.parse(raw) != null) {
            val (outcome, gid) = client.joinGroupFromInvite(raw)
            if (gid != null && outcome != OshiClient.JoinOutcome.NO_INVITER) select(gid)
            synchronized(lock) {
                destination = Destination.MESSAGES
                notice = when (outcome) {
                    OshiClient.JoinOutcome.ALREADY_MEMBER -> Notice("You are already in this group.", Severity.INFO)
                    OshiClient.JoinOutcome.REQUESTED -> Notice("Join request sent to the person who invited you. The group fills in when they answer.", Severity.OK)
                    OshiClient.JoinOutcome.NO_INVITER -> Notice("This is an old-style group link with no inviter; ask for a new invite link.", Severity.ERROR)
                    OshiClient.JoinOutcome.NOT_AN_INVITE -> Notice("That is not a group invite.", Severity.ERROR)
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
                        "Added ${short(scan.address)}. Nothing was sent and the relay was not told.",
                        Severity.OK,
                    )
                }
                publish()
            }
            is ContactQr.Scan.Rejected -> {
                synchronized(lock) {
                    notice = Notice("That code was not usable: ${scan.detail}", Severity.ERROR)
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
                    onFailure = { Notice(dt("desktop.nickname.failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
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
                    onSuccess = { u -> if (u == null) Notice(dt("desktop.nickname.pull.none"), Severity.INFO) else nicknameNotice(u) },
                    onFailure = { Notice(dt("desktop.nickname.sync.failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
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
                    onSuccess = { Notice(dt("desktop.nickname.pushed"), Severity.OK) },
                    onFailure = { Notice(dt("desktop.nickname.sync.failed", it.message ?: it.javaClass.simpleName), Severity.ERROR) },
                )
            }
            publish()
        }
    }

    private fun nicknameNotice(u: OshiClient.NicknameUpdate): Notice = when {
        !u.changed -> Notice(dt("desktop.nickname.unchanged"), Severity.INFO)
        u.withheld -> Notice(dt("desktop.nickname.withheld"), Severity.INFO)
        u.nickname == null -> Notice(dt("desktop.nickname.cleared", u.sent, u.eligible), Severity.OK)
        else -> Notice(dt("desktop.nickname.saved", u.sent, u.eligible), Severity.OK)
    }

    fun renameContact(address: String, name: String?) {
        client.contacts.setDisplayName(address, name?.trim()?.takeIf { it.isNotBlank() })
        publish()
    }

    /** PARITY.md row 0.21 — blocked peers are WITHHELD from the list, never deleted. */
    fun setBlocked(address: String, blocked: Boolean) {
        if (blocked) client.block(address) else client.unblock(address)
        synchronized(lock) {
            if (blocked && selectedId == address) selectedId = null
            notice = Notice(
                if (blocked) "Blocked ${short(address)}. Their conversation is hidden, not deleted."
                else "Unblocked ${short(address)}.",
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
                if (verified) "Safety number verified for ${short(address)}."
                else "That safety QR does not match ${short(address)}. Verify the identity before continuing.",
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
            synchronized(lock) { notice = Notice("A group needs a name.", Severity.ERROR) }
            publish()
            return
        }
        if (recipients.isEmpty()) {
            synchronized(lock) { notice = Notice("Choose at least one contact for the group.", Severity.ERROR) }
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
                        "Created ${group.name} locally. Group definitions were sent to ${recipients.size} contact(s).",
                        Severity.OK,
                    )
                }.onFailure { error ->
                    notice = Notice("Could not create the group: ${error.message ?: error.javaClass.simpleName}", Severity.ERROR)
                }
            }
            publish()
        }
    }

    /** Rename only a group this account still administers; the client re-checks authority. */
    fun renameGroup(groupId: String, name: String) {
        val nextName = name.trim()
        if (nextName.isBlank()) {
            synchronized(lock) { notice = Notice("A group needs a name.", Severity.ERROR) }
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
                    updated.isFailure -> Notice("Could not rename the group: ${updated.exceptionOrNull()?.message ?: "network error"}", Severity.ERROR)
                    updated.getOrNull() == null -> Notice("This account is no longer a group admin.", Severity.ERROR)
                    else -> Notice("Renamed the group locally. The update was sent to its members.", Severity.OK)
                }
            }
            publish()
        }
    }

    /** Add a known, unblocked contact only; the client repeats the admin check at the send site. */
    fun addGroupMember(groupId: String, memberAddress: String) {
        val contact = client.contacts.get(memberAddress)
        if (contact == null || contact.blocked) {
            synchronized(lock) { notice = Notice("Only known, unblocked contacts can be added to a group.", Severity.ERROR) }
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
                    updated.isFailure -> Notice("Could not add the member: ${updated.exceptionOrNull()?.message ?: "network error"}", Severity.ERROR)
                    updated.getOrNull() == null -> Notice("This account is no longer a group admin.", Severity.ERROR)
                    else -> Notice("Added ${contact.label(short(memberAddress))} to the group.", Severity.OK)
                }
            }
            publish()
        }
    }

    /** Removing this account is deliberately not offered: it could strand the local group view. */
    fun removeGroupMember(groupId: String, memberAddress: String) {
        if (GroupIdentity.sameIdentity(memberAddress, client.address)) {
            synchronized(lock) { notice = Notice("Leave a group from the REPL; this window cannot safely remove its own admin.", Severity.ERROR) }
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
                    updated.isFailure -> Notice("Could not remove the member: ${updated.exceptionOrNull()?.message ?: "network error"}", Severity.ERROR)
                    updated.getOrNull() == null -> Notice("This account is no longer a group admin.", Severity.ERROR)
                    else -> Notice("Removed ${short(memberAddress)} from the group.", Severity.OK)
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
                    updated.isFailure -> Notice("Could not change the member role: ${updated.exceptionOrNull()?.message ?: "network error"}", Severity.ERROR)
                    updated.getOrNull() == null -> Notice("That role change is not permitted for this group.", Severity.ERROR)
                    else -> Notice("${if (isAdmin) "Promoted" else "Demoted"} ${short(memberAddress)} ${if (isAdmin) "to" else "from"} admin.", Severity.OK)
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
                    cancelled.isFailure -> Notice("Could not cancel the scheduled message: ${cancelled.exceptionOrNull()?.message ?: "storage error"}", Severity.ERROR)
                    cancelled.getOrNull() == true -> Notice("Scheduled message cancelled. It remains listed as cancelled.", Severity.OK)
                    else -> Notice("That scheduled message is no longer pending and cannot be cancelled.", Severity.ERROR)
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
                synchronized(lock) { notice = Notice("Choose a group this account belongs to for a scheduled message.", Severity.ERROR) }
                publish()
                return
            }
            !isGroup && (contact == null || contact.blocked) -> {
                synchronized(lock) { notice = Notice("Choose a known, unblocked contact for a scheduled message.", Severity.ERROR) }
                publish()
                return
            }
            body.isEmpty() -> {
                synchronized(lock) { notice = Notice("A scheduled message needs text.", Severity.ERROR) }
                publish()
                return
            }
            delayMs <= 0L -> {
                synchronized(lock) { notice = Notice("Choose a future delivery time.", Severity.ERROR) }
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
                    queued.isFailure -> Notice("Could not schedule the message: ${queued.exceptionOrNull()?.message ?: "storage error"}", Severity.ERROR)
                    else -> Notice("Scheduled for ${stamp(queued.getOrThrow().scheduledAtMs)} on this machine.", Severity.OK)
                }
            }
            publish()
        }
    }

    /** The store permits edits only while pending; never change a sent or cancelled record. */
    fun editScheduled(id: String, content: String) {
        val body = content.trim()
        if (body.isEmpty()) {
            synchronized(lock) { notice = Notice("A scheduled message needs text.", Severity.ERROR) }
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
                    edited.isFailure -> Notice("Could not edit the scheduled message: ${edited.exceptionOrNull()?.message ?: "storage error"}", Severity.ERROR)
                    edited.getOrNull() == true -> Notice("Scheduled message updated.", Severity.OK)
                    else -> Notice("That scheduled message is no longer pending and cannot be edited.", Severity.ERROR)
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
            synchronized(lock) { notice = Notice("Reactions are available only in direct conversations.", Severity.ERROR) }
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
                    outcome.isFailure -> Notice("Could not send the reaction: ${outcome.exceptionOrNull()?.message ?: "network error"}", Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.BLOCKED -> Notice("That contact is blocked; the reaction was not applied.", Severity.ERROR)
                    else -> Notice(
                        if (outcome.getOrThrow() == OshiClient.SendOutcome.SENT) "Reaction added locally and sent."
                        else "Reaction added locally; it was not delivered yet.",
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
            synchronized(lock) { notice = Notice("A message cannot be empty.", Severity.ERROR) }
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

    fun setGroupDescription(groupId: String, text: String) = runGroupInfoChange("description") { client.setGroupDescription(groupId, text) }

    fun pinMessage(messageId: String?) {
        val gid = groupSelected() ?: return
        runGroupInfoChange("pin") { client.setGroupPin(gid, messageId) }
    }

    fun setGroupBlocked(groupId: String, blocked: Boolean) = runGroupInfoChange("block") { client.setGroupBlocked(groupId, blocked) }

    /** iOS: an admin "deletes" the group from this device; a member leaves. */
    fun deleteGroup(groupId: String) {
        worker.execute {
            val ok = runCatching { client.deleteGroupLocally(groupId) }.getOrDefault(false)
            synchronized(lock) {
                if (ok && selectedId == groupId) selectedId = null
                notice = if (ok) Notice(t("conversation.deleted"), Severity.OK) else Notice("The group could not be deleted.", Severity.ERROR)
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
                    OshiClient.SendOutcome.BLOCKED -> Notice("That contact is blocked.", Severity.ERROR)
                    null -> Notice("That message cannot be forwarded.", Severity.ERROR)
                    else -> Notice(t("forward.failed"), Severity.ERROR)
                }
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
                    outcome.isFailure -> Notice("Could not change the group $what: ${outcome.exceptionOrNull()?.message ?: "error"}", Severity.ERROR)
                    outcome.getOrNull() == null -> Notice(
                        if (what == "picture") "Not changed: only an admin can edit this group, or the file is not a picture." else "Not changed.",
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
                    outcome.isFailure -> Notice(dt("desktop.group.action.failed", outcome.exceptionOrNull()?.message ?: "network error"), Severity.ERROR)
                    outcome.getOrNull() == null -> Notice(dt("desktop.group.action.refused"), Severity.ERROR)
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
                notice = if (left.getOrNull() == true) Notice(dt("desktop.group.left"), Severity.OK)
                else Notice(dt("desktop.group.action.failed", left.exceptionOrNull()?.message ?: "not a member"), Severity.ERROR)
            }
            publish()
        }
    }

    private fun runDirectMessageAction(action: String, send: (String) -> OshiClient.SendOutcome) {
        val target = synchronized(lock) { selectedId?.takeIf { kindOf(it) == ConversationKind.DIRECT } }
        if (target == null) {
            synchronized(lock) { notice = Notice("Message actions are available only in direct conversations.", Severity.ERROR) }
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
                    outcome.isFailure -> Notice("Could not $action the message: ${outcome.exceptionOrNull()?.message ?: "network error"}", Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.BLOCKED -> Notice("That contact is blocked; the message was not changed.", Severity.ERROR)
                    outcome.getOrNull() == OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD -> Notice("Only messages you sent can be $action${if (action == "delete") "d" else "ed"}.", Severity.ERROR)
                    else -> Notice(
                        if (outcome.getOrThrow() == OshiClient.SendOutcome.SENT) "Message $action${if (action == "delete") "d" else "ed"} and sent."
                        else "Message $action${if (action == "delete") "d" else "ed"} locally; it was not delivered yet.",
                        Severity.OK,
                    )
                }
            }
            publish()
        }
    }

    /** Called on the poll thread for every inbound message. See THREADING. */
    fun onInbound(m: Message) {
        val shouldNotify: Boolean
        synchronized(lock) {
            val unseen = !m.fromMe && m.conversationId != selectedId
            if (unseen) {
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
                // __BLOCKED_NOTIF_2026_09_23__ a blocked person stays silent inside a group
                // too. `senderAddress` is the authenticated envelope sender, never a name.
                !com.oshi.desktop.block.BlockPolicy.isBlocked(client.contacts, m.senderAddress)
        }
        publish()
        // Deliberately after durable storage (which OshiClient completed before this
        // callback) and after the UI state update. A broken shell notification is never a
        // delivery failure, and it contains no decrypted text or sender metadata.
        if (shouldNotify) onIncomingNotification()
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
            ConversationRow(
                id = summary.conversationId,
                label = labelFor(summary.conversationId, kind),
                kind = kind,
                messageCount = summary.messageCount,
                lastActivityMs = summary.lastActivityMs,
                lastActivity = stamp(summary.lastActivityMs),
                preview = previewOf(summary.lastMessage),
                unread = unread[summary.conversationId] ?: 0,
                mentionedYou = summary.conversationId in mentionedYou,
                pictureBase64 = if (kind == ConversationKind.GROUP) client.groups.get(summary.conversationId)?.groupPictureBase64 else null,
            )
        }
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
                groupPictureBase64 = group?.groupPictureBase64,
                groupInviteLink = group?.let { client.groupInviteLink(id) },
                groupDescription = group?.description,
                groupBlocked = group != null && client.isGroupBlocked(id),
                groupPinned = group?.pinnedMessageId?.let { pid ->
                    client.history(id).firstOrNull { com.oshi.desktop.group.GroupMessageWire.sameMessageId(it.id, pid) }?.let { m ->
                        QuoteRow(m.id, if (m.fromMe) "me" else senderLabel(m.senderAddress),
                            com.oshi.desktop.msg.ReplyEnvelope.quotableText(m.content, m.mediaType?.wire))
                    }
                },
                forwardTargets = if (kind == ConversationKind.DIRECT || kind == ConversationKind.GROUP) {
                    client.contacts.all().filterNot { it.blocked || it.address == id }
                        .map { GroupCandidateRow(it.address, it.label(short(it.address))) }
                } else emptyList(),
                typingNames = if (group == null) emptyList() else synchronized(lock) {
                    val now = System.currentTimeMillis()
                    groupTyping[id].orEmpty().filter { (k, until) -> until > now && !com.oshi.desktop.group.GroupIdentity.sameIdentity(k, client.address) }
                        .keys.map(::senderLabel).sorted()
                },
                groupCanEditInfo = group != null && client.canEditGroupInfo(id),
                groupMuted = group?.isMuted == true,
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
                verified = kind == ConversationKind.DIRECT &&
                    client.contacts.get(id)?.verification == ContactStore.VerificationState.VERIFIED,
                reach = if (kind == ConversationKind.DIRECT) reach else Reach.NOT_APPLICABLE,
                reachLabel = reachLabel(if (kind == ConversationKind.DIRECT) reach else Reach.NOT_APPLICABLE),
                peerTyping = kind == ConversationKind.DIRECT && (typingUntilMs[id] ?: 0L) > System.currentTimeMillis(),
                messages = client.history(id).map { row(it) },
                composer = composerFor(id),
                // __BOT_E2E_2026_09_23__ BOT_SEAL_SPEC.md §3: say how the LATEST bot post was
                // protected. Rows stored before bot-seal-v1 carry no bot transport = legacy.
                botSealing = if (kind != ConversationKind.BOT) null else
                    client.history(id).lastOrNull { !it.fromMe }?.let {
                        com.oshi.desktop.bot.BotEnvelope.Sealing.fromTransport(it.transport)
                            ?: com.oshi.desktop.bot.BotEnvelope.Sealing.NONE
                    },
            )
        }
        return ShellState(
            selfAddress = client.address,
            selfAddressShort = short(client.address),
            displayName = client.displayName,
            relayUrl = client.serverUrl,
            gateOpen = client.config.isEnabledCached(),
            deliveryReceiptsEnabled = client.deliveryReceiptsEnabled,
            readReceiptsEnabled = client.readReceiptsEnabled,
            syncNotice = syncNotice,
            backupNotice = backupNotice,
            ownNickname = client.ownNickname,
            nicknameBroadcasts = client.mayBroadcastProfile,
            profileNotice = profileNotice,
            replyingTo = replyTarget?.takeIf { it.first == id }?.second?.let(::quoteRow),
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
            busy = busy,
            notice = notice,
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
            m.isDeletedForEveryone -> "(deleted)"
            // __DESKTOP_CALL_UI_2026_09_23__ a call-history row carries a KEY; draw it localized.
            com.oshi.desktop.msg.CallSummary.isCallSummary(m.content) -> com.oshi.desktop.msg.CallSummary.render(m.content!!) { t(it) }
            env != null -> env.content
            m.mediaRef != null -> m.content.orEmpty()
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
        failed = m.fromMe && m.deliveryStatus == DeliveryStatus.FAILED,
        edited = m.editedAtMs != null,
        deleted = m.isDeletedForEveryone,
        reactions = m.reactions.keys.sorted().joinToString(""),
    )
    }

    private fun previewOf(m: Message?): String = when {
        m == null -> ""
        m.isDeletedForEveryone -> "(deleted)"
        m.hiddenLocally -> ""
        com.oshi.desktop.msg.ReplyEnvelope.isWrapped(m.content) ->
            com.oshi.desktop.msg.ReplyEnvelope.unwrap(m.content)?.content ?: m.content.orEmpty()
        com.oshi.desktop.msg.CallSummary.isCallSummary(m.content) -> com.oshi.desktop.msg.CallSummary.render(m.content!!) { t(it) }
        m.mediaRef != null -> "[${m.mediaType?.wire ?: "file"}] " + m.content.orEmpty()
        else -> m.content.orEmpty()
    }.replace('\n', ' ').take(80)

    private fun labelFor(id: String, kind: ConversationKind): String = when (kind) {
        ConversationKind.GROUP -> client.groups.get(id)?.name ?: short(id)
        ConversationKind.BOT -> "bot: " + id.removePrefix(BOT_PREFIX)
        ConversationKind.LORA -> "radio: " + id.removePrefix(LoRaNodeBindings.FALLBACK_PREFIX)
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
        id == null -> ComposerState(false, "No conversation is open.", false, null)
        busy -> ComposerState(false, "Working…", false, null)
        else -> when (kindOf(id)) {
            ConversationKind.DIRECT -> ComposerState(true, null, true, null)
            // PARITY.md row 0.17 is partial and the partial part is RECEIVE (three sub-rows
            // blocked: an Android peer's group envelope, membership changes, and the legacy
            // lane). The SEND half is the fan-out this calls, and it is the same code path
            // the REPL has always used — so a disabled composer here was the window being
            // behind the client, not the client being unable.
            // __GROUP_E2E_V2_2026_09_23__ group media now fans out (OshiClient.sendGroupFile).
            ConversationKind.GROUP -> if (client.isGroupBlocked(id)) {
                // __GROUP_PARITY_2026_09_23__ iOS closes the composer of a blocked group behind a banner.
                ComposerState(false, t("group.blocked_message"), false, t("group.blocked_message"))
            } else ComposerState(true, null, true, null)
            ConversationKind.BOT -> ComposerState(
                false,
                OshiClient.BOT_CHANNEL_IS_PLAINTEXT + " — so this window will not put it behind the " +
                    "same text box as an end-to-end encrypted conversation. `/bot send` in the REPL " +
                    "prints that warning on every send.",
                false,
                null,
            )
            ConversationKind.LORA -> ComposerState(
                false,
                "Stock Meshtastic text, quarantined and flagged unverified (PARITY.md row 0.27). " +
                    "Nothing ratcheted it and no peer authenticated it, so there is no verified " +
                    "identity here to reply to.",
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
            "REFUSED — that text is itself an OSHI control payload. Nothing was sent and nothing " +
                "was stored; your text is still in the box.",
            Severity.ERROR,
            keepDraft = true,
        )
        r.recipients == 0 -> SendResult(
            "NOT SENT — this group has nobody in it but you.",
            Severity.ERROR,
            keepDraft = false,
        )
        r.sent == 0 -> SendResult(
            "NOT DELIVERED — 0 of ${r.recipients} members took it. None of them has a V2 path." +
                blockedTail(r),
            Severity.ERROR,
            keepDraft = false,
        )
        r.sent < r.recipients -> SendResult(
            "PARTIAL — ${r.sent} of ${r.recipients} members took it. The rest have published no " +
                "prekey bundle, so V2 cannot reach them." + blockedTail(r),
            Severity.ERROR,
            keepDraft = false,
        )
        else -> SendResult(
            "Sent to all ${r.recipients} members." + blockedTail(r),
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
        else " ${r.skippedBlocked.size} blocked member(s) were skipped, which is not a failure.") +
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
            "NOT SENT — that contact is blocked. The message was not written to the log either."
        OshiClient.SendOutcome.NO_V2_PATH ->
            "NOT DELIVERED — that peer has published no prekey bundle, so V2 cannot reach them. " +
                "The message is in your log, marked failed."
        OshiClient.SendOutcome.GATE_CLOSED ->
            "NOT SENT — the V2 rollout gate is closed for this account. " +
                "The message is in your log, marked failed."
        OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD ->
            "REFUSED — that text is itself an OSHI control payload (a receipt, typing, reaction " +
                "or edit sentinel). Sending it would hand a peer a message-editing primitive. " +
                "Nothing was sent and nothing was stored; your text is still in the box."
    }

    private fun severityOf(o: OshiClient.SendOutcome): Severity =
        if (o == OshiClient.SendOutcome.SENT) Severity.OK else Severity.ERROR

    private fun reachLabel(r: Reach): String = when (r) {
        Reach.UNKNOWN -> "reachability not checked"
        Reach.CHECKING -> "checking reachability…"
        Reach.REACHABLE -> "V2 path available"
        Reach.NO_BUNDLE -> "no published prekey bundle — V2 cannot reach them"
        Reach.ERROR -> "reachability check failed"
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
enum class Pane { CONVERSATION, ACCOUNT, LIMITS, COVERT, MAIL, SCHEDULED, DEVICES }

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
    /** __MENTIONS_2026_09_23__ an unseen message in this group mentions you. */
    val mentionedYou: Boolean = false,
    /** __GROUP_PARITY_2026_09_23__ a group's picture, drawn instead of the monogram. */
    val pictureBase64: String? = null,
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
)

/** A reply's quote block, and the composer's "replying to" banner. */
data class QuoteRow(val messageId: String, val who: String, val text: String)

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
    /** Notifications off for this group on this device (local only). */
    val groupMuted: Boolean = false,
    /** iOS-format invite link, shown with its QR code. */
    val groupInviteLink: String? = null,
    val groupDescription: String? = null,
    /** Blocked on this device: composer closed behind a banner, no notifications. */
    val groupBlocked: Boolean = false,
    /** The pinned message, when the definition names one we hold. */
    val groupPinned: QuoteRow? = null,
    /** Group members typing right now (display names). */
    val typingNames: List<String> = emptyList(),
    /** __GROUP_PARITY_2026_09_23__ contacts a message may be forwarded to. */
    val forwardTargets: List<GroupCandidateRow> = emptyList(),
    /** Empty for direct, bot and radio threads; roster data comes only from a stored definition. */
    val groupMembers: List<GroupMemberRow>,
    /** Known unblocked contacts not in this group; candidates are never inferred from messages. */
    val groupCandidates: List<GroupCandidateRow>,
    /** PARITY.md row 0.20. Blank for anything the ratchet did not vouch for. */
    val safetyNumber: String,
    val verified: Boolean,
    val reach: Reach,
    val reachLabel: String,
    /** Ephemeral peer state, auto-cleared after [TYPING_VISIBLE_MS]. */
    val peerTyping: Boolean,
    val messages: List<MessageRow>,
    val composer: ComposerState,
    /** __BOT_E2E_2026_09_23__ Bot threads only: how the latest post was protected; null elsewhere. */
    val botSealing: com.oshi.desktop.bot.BotEnvelope.Sealing? = null,
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
)

data class GroupCandidateRow(val address: String, val label: String)

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

data class ShellState(
    val selfAddress: String,
    val selfAddressShort: String,
    val displayName: String,
    val relayUrl: String,
    val gateOpen: Boolean,
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
) {
    companion object {
        fun empty(address: String, name: String) = ShellState(
            selfAddress = address,
            selfAddressShort = short(address),
            displayName = name,
            relayUrl = "",
            gateOpen = false,
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
