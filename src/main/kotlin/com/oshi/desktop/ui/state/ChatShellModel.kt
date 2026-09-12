package com.oshi.desktop.ui.state

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.app.short
import com.oshi.desktop.app.stamp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.lora.LoRaNodeBindings
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.pairing.ContactQr
import com.oshi.desktop.store.ContactStore
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.Message
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
) : AutoCloseable {

    // ------------------------------------------------------------------ mutable core

    private val lock = Any()
    private val unread = HashMap<String, Int>()
    private var selectedId: String? = null
    private var destination: Destination = Destination.MESSAGES
    private var pane: Pane = Pane.CONVERSATION
    private var draftText: String = ""
    private var busy: Boolean = false
    private var notice: Notice? = null
    private var reach: Reach = Reach.UNKNOWN

    /** Set by the renderer. Called on whichever thread caused the change — see THREADING. */
    var onChange: (ShellState) -> Unit = {}

    @Volatile
    var state: ShellState = ShellState.empty(client.address, client.displayName)
        private set

    private var previousOnMessage: ((Message) -> Unit)? = null

    // ------------------------------------------------------------------ lifecycle

    /**
     * Subscribe to live inbound messages.
     *
     * The existing `onMessage` is CHAINED, not replaced. `OshiClient` exposes exactly one
     * callback slot, and a window that silently unhooks whatever was there is a window that
     * breaks the REPL the moment somebody runs both in one process — which the `--ui` entry
     * point does not do today and which is precisely the kind of assumption that stops being
     * true without anyone noticing.
     */
    fun attach() {
        synchronized(lock) {
            if (previousOnMessage != null) return
            previousOnMessage = client.onMessage
        }
        client.onMessage = { m ->
            previousOnMessage?.invoke(m)
            onInbound(m)
        }
        refresh()
    }

    override fun close() {
        val prior = synchronized(lock) { previousOnMessage.also { previousOnMessage = null } }
        if (prior != null) client.onMessage = prior
    }

    // ------------------------------------------------------------------ commands

    /** Re-read the stores and republish. Cheap enough to call on every change. */
    fun refresh() = publish()

    fun show(target: Pane) {
        synchronized(lock) { pane = target }
        publish()
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
        synchronized(lock) {
            selectedId = id
            pane = Pane.CONVERSATION
            draftText = ""
            notice = null
            reach = Reach.UNKNOWN
            if (id != null) unread.remove(id)
        }
        publish()
        if (id != null && kind == ConversationKind.DIRECT) probeReach(id)
    }

    fun draft(text: String) {
        synchronized(lock) {
            draftText = text
            notice = null
        }
        publish()
    }

    /**
     * Send the draft to the open conversation.
     *
     * Returns immediately; the outcome lands in [ShellState.notice] when [worker] gets to
     * it. [ShellState.busy] is true in between, and the composer is disabled while it is —
     * a second click during a slow relay round trip would send the same text twice.
     */
    fun send() {
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
                    id to draftText
                }
            }
        }
        if (target == null) { publish(); return }

        val group = kindOf(target) == ConversationKind.GROUP
        worker.execute {
            val result = try {
                if (group) groupSendResult(client.sendGroupText(target, text))
                else directSendResult(client.send(target, text))
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
                if (!result.keepDraft) draftText = ""
            }
            publish()
        }
    }

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
    fun attach(file: File) {
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

        worker.execute {
            val result = try {
                directSendResult(client.sendFile(target, file))
            } catch (e: Exception) {
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

    /** Called on the poll thread for every inbound message. See THREADING. */
    fun onInbound(m: Message) {
        synchronized(lock) {
            if (m.conversationId != selectedId) {
                unread[m.conversationId] = (unread[m.conversationId] ?: 0) + 1
            }
        }
        publish()
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
        val rows = client.conversations().map { summary ->
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
            )
        }
        val id = selectedId
        val thread = if (id == null) null else {
            val kind = kindOf(id)
            ThreadView(
                conversationId = id,
                title = labelFor(id, kind),
                address = id,
                kind = kind,
                // A safety number is a function of two IDENTITY keys, so it means nothing
                // for a group (many), a bot (none) or a LoRa node (unauthenticated). Drawing
                // one anyway would offer a verification ritual that verifies nothing.
                safetyNumber = if (kind == ConversationKind.DIRECT) client.safetyNumber(id) else "",
                verified = kind == ConversationKind.DIRECT &&
                    client.contacts.get(id)?.verification == ContactStore.VerificationState.VERIFIED,
                reach = if (kind == ConversationKind.DIRECT) reach else Reach.NOT_APPLICABLE,
                reachLabel = reachLabel(if (kind == ConversationKind.DIRECT) reach else Reach.NOT_APPLICABLE),
                messages = client.history(id).map { row(it) },
                composer = composerFor(id),
            )
        }
        return ShellState(
            selfAddress = client.address,
            selfAddressShort = short(client.address),
            displayName = client.displayName,
            relayUrl = client.serverUrl,
            gateOpen = client.config.isEnabledCached(),
            destination = destination,
            // `all()`, not `visible()`: this list is where blocking is UNDONE, and a blocked
            // peer filtered out of the only screen carrying an unblock control would be a
            // one-way door. The row says which ones are blocked; the list shows everyone.
            contacts = client.contacts.all().map { c ->
                ContactRow(
                    address = c.address,
                    addressShort = short(c.address),
                    label = c.displayName?.takeIf { it.isNotBlank() } ?: short(c.address),
                    verified = c.verification == ContactStore.VerificationState.VERIFIED,
                    blocked = c.blocked,
                    safetyNumber = client.safetyNumber(c.address),
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

    private fun row(m: Message): MessageRow = MessageRow(
        id = m.id,
        fromMe = m.fromMe,
        who = if (m.fromMe) "me" else short(m.senderAddress),
        // The DELETE flag is read BEFORE the content, not after. A row deleted for everyone
        // can still carry a body — the store keeps the record and the router nulls the text,
        // but nothing in the type system says a caller could not hand it both — and a
        // renderer that reached for `content` first would draw the message its sender
        // revoked. PARITY.md row 0.18 is the authority for the delete existing at all; this
        // is about never rendering past it.
        body = when {
            m.isDeletedForEveryone -> "(deleted)"
            m.mediaRef != null -> m.content.orEmpty()
            else -> m.content ?: "(deleted)"
        },
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

    private fun previewOf(m: Message?): String = when {
        m == null -> ""
        m.isDeletedForEveryone -> "(deleted)"
        m.mediaRef != null -> "[${m.mediaType?.wire ?: "file"}] " + m.content.orEmpty()
        else -> m.content.orEmpty()
    }.replace('\n', ' ').take(80)

    private fun labelFor(id: String, kind: ConversationKind): String = when (kind) {
        ConversationKind.GROUP -> client.groups.get(id)?.name ?: short(id)
        ConversationKind.BOT -> "bot: " + id.removePrefix(BOT_PREFIX)
        ConversationKind.LORA -> "radio: " + id.removePrefix(LoRaNodeBindings.FALLBACK_PREFIX)
        ConversationKind.DIRECT -> client.contacts.get(id)?.displayName?.takeIf { it.isNotBlank() } ?: short(id)
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
            ConversationKind.GROUP -> ComposerState(
                true,
                null,
                false,
                "There is no group media fan-out on this client — `sendGroupText` carries text " +
                    "and nothing else. A file sent here would be uploaded and fetched by nobody.",
            )
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
        if (r.skippedBlocked.isEmpty()) ""
        else " ${r.skippedBlocked.size} blocked member(s) were skipped, which is not a failure."

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

enum class Pane { CONVERSATION, ACCOUNT, LIMITS, COVERT }

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
)

data class ThreadView(
    val conversationId: String,
    val title: String,
    val address: String,
    val kind: ConversationKind,
    /** PARITY.md row 0.20. Blank for anything the ratchet did not vouch for. */
    val safetyNumber: String,
    val verified: Boolean,
    val reach: Reach,
    val reachLabel: String,
    val messages: List<MessageRow>,
    val composer: ComposerState,
)

/** One person this account knows about, as the contacts and blocked lists draw them. */
data class ContactRow(
    val address: String,
    val addressShort: String,
    val label: String,
    val verified: Boolean,
    val blocked: Boolean,
    val safetyNumber: String,
)

data class ShellState(
    val selfAddress: String,
    val selfAddressShort: String,
    val displayName: String,
    val relayUrl: String,
    val gateOpen: Boolean,
    val destination: Destination,
    val contacts: List<ContactRow>,
    val shareText: String,
    val pane: Pane,
    val conversations: List<ConversationRow>,
    val selectedId: String?,
    val thread: ThreadView?,
    val draft: String,
    val busy: Boolean,
    val notice: Notice?,
) {
    companion object {
        fun empty(address: String, name: String) = ShellState(
            selfAddress = address,
            selfAddressShort = short(address),
            displayName = name,
            relayUrl = "",
            gateOpen = false,
            destination = Destination.MESSAGES,
            contacts = emptyList(),
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
