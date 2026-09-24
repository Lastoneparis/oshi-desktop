package com.oshi.desktop.ui.state

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.mail.MailClient
import com.oshi.desktop.mail.MailCrypto
import com.oshi.desktop.mail.MailMime
import com.oshi.desktop.pairing.QrImageDecoder
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The whole state of the OSHI Mail screen — the mail counterpart of [ChatShellModel].
 *
 * Same discipline as that file and for the same reason: no `androidx.compose` import
 * below, ever. Everything a mail window could get wrong — whether a folder's unread badge
 * counts what it claims to, whether a draft that never got typed into gets saved as an
 * empty row, whether a decrypted attachment ever gets handed to the wrong composer — is
 * decided here in plain Kotlin, where a JUnit test on a headless runner can hold it to
 * account. [com.oshi.desktop.ui.components.MailPane] renders what this file produces; it
 * chooses nothing.
 *
 * ============================================================ THREADING
 *
 * Every [MailClient] call is a blocking HTTP round trip (see [com.oshi.desktop.net.V2Http]'s
 * class note — this is the JDK client, synchronous by design). So every command that talks
 * to the network runs on [worker], never on the calling thread, and every mutation to
 * [state] happens under [lock] and is published as one immutable snapshot — the renderer
 * never observes a half-updated screen. Tests pass a direct executor for determinism.
 *
 * ============================================================ WHAT "ZERO ACCESS" MEANS HERE
 *
 * [MailClient.mailPrivateKey] (via [client]) never leaves this process. Every decrypted
 * body this model holds — [bodyCache], [openMail], search results — is plaintext living in
 * this JVM's heap for the life of the window, exactly as it is on iOS and Android. There is
 * no server-side plaintext to fall back on if any of this is wrong, which is why
 * [com.oshi.desktop.mail.MailCryptoTest]'s frozen vector is the test that matters most in
 * this whole feature.
 */
class MailModel(
    private val mail: MailClient,
    private val worker: Executor = defaultWorker(),
) {
    constructor(client: OshiClient) : this(MailClient(client.identity, client.vault, client.serverUrl))

    data class OpenMail(
        val id: String,
        val folder: String,
        val subject: String,
        val from: String,
        val to: String,
        val date: String,
        val body: String,
        val attachments: List<MailMime.Attachment>,
        val messageId: String?,
    )

    data class DraftContent(
        val id: String?, val to: String, val cc: String, val subject: String, val body: String,
        val inReplyTo: String? = null,
        val attachments: List<MailClient.OutgoingAttachment> = emptyList(),
        val composerId: String = java.util.UUID.randomUUID().toString(),
    )

    data class MailUiState(
        val loading: Boolean = true,
        val hasMailbox: Boolean = false,
        val account: MailClient.Account? = null,
        val messages: List<MailClient.MailSummary> = emptyList(),
        val folder: String = FOLDER_INBOX,
        val searchQuery: String = "",
        val deepSearch: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        val busy: String? = null,
        val open: OpenMail? = null,
        val editingDraft: DraftContent? = null,
        val drive: List<MailClient.DriveFile> = emptyList(),
        val webSessions: List<MailClient.WebSession> = emptyList(),
        val contacts: List<Pair<String, String>> = emptyList(),
        /** id -> "subject\nfrom\nbody", for messages decrypted this session — deep search only. */
        val bodyCache: Map<String, String> = emptyMap(),
    ) {
        /** What the folder sidebar draws, filtered and text-searched. */
        val visibleMessages: List<MailClient.MailSummary> get() {
            val inFolder = messages.filter { it.folder == folder }
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) return inFolder
            return inFolder.filter { matchesQuery(it, q) }
        }

        /** Inbox shows unread; every other folder shows its total — never a fake permanent 0. */
        fun badgeCount(forFolder: String): Int {
            val rows = messages.filter { it.folder == forFolder }
            return if (forFolder == FOLDER_INBOX) rows.count { !it.seen } else rows.size
        }

        /**
         * Mail autocomplete is local-only: contacts came from envelopes this client already
         * decrypted. Only the token after the last comma is searched, as on iOS, and an
         * address already chosen in an earlier token is never offered again.
         */
        fun recipientSuggestions(recipients: String): List<Pair<String, String>> {
            val token = recipients.substringAfterLast(',').trim()
            if (token.isEmpty()) return emptyList()
            val already = recipients.substringBeforeLast(',', "")
                .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val query = token.lowercase()
            return contacts.filter { (name, address) ->
                address.lowercase() !in already && (address.lowercase().contains(query) || name.lowercase().contains(query))
            }.take(5)
        }

        /** Replace just the active comma-delimited recipient token with [address]. */
        fun applyRecipientSuggestion(recipients: String, address: String): String {
            val prefix = recipients.substringBeforeLast(',', "")
            return if (',' in recipients) "${prefix.trimEnd()}, $address" else address
        }

        private fun matchesQuery(m: MailClient.MailSummary, q: String): Boolean {
            val env = m.envelope
            if (env != null && (env.subject.lowercase().contains(q) || env.from.lowercase().contains(q) ||
                    env.to.lowercase().contains(q))
            ) return true
            // "Deep" also matches bodies already decrypted THIS SESSION — never a bulk
            // fetch-and-search, which is what makes server-side search structurally
            // impossible on a zero-access mailbox in the first place.
            if (deepSearch) return bodyCache[m.id]?.lowercase()?.contains(q) == true
            return false
        }
    }

    companion object {
        const val FOLDER_INBOX = "inbox"
        const val FOLDER_ARCHIVE = "archive"
        const val FOLDER_DRAFT = "draft"
        const val FOLDER_SENT = "sent"
        const val FOLDER_TRASH = "trash"
        val FOLDERS = listOf(FOLDER_INBOX, FOLDER_ARCHIVE, FOLDER_DRAFT, FOLDER_SENT, FOLDER_TRASH)

        private fun defaultWorker(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "oshi-mail-worker").apply { isDaemon = true }
        }
    }

    // ------------------------------------------------------------------ mutable core

    private val lock = Any()
    private var current = MailUiState()

    /**
     * The app shell observes startup refreshes while [MailPane] observes the visible
     * mailbox. These must be independent: a single replaceable callback made whichever
     * mounted last silently stop the other from receiving unread changes.
     */
    private val observers = LinkedHashSet<(MailUiState) -> Unit>()

    @Volatile
    var state: MailUiState = current
        private set

    private fun mutate(f: MailUiState.() -> MailUiState) {
        synchronized(lock) {
            current = current.f()
            state = current
            observers.toList().forEach { it(current) }
        }
    }

    /** Observe a consistent initial snapshot and all later mutations; returns an unsubscribe. */
    fun observe(observer: (MailUiState) -> Unit): () -> Unit {
        synchronized(lock) {
            observers += observer
            observer(current)
        }
        return { synchronized(lock) { observers -= observer } }
    }

    // ------------------------------------------------------------------ lifecycle / account

    fun refresh() {
        worker.execute {
            mail.account().fold(
                onSuccess = { acc ->
                    // On the first refresh, account and inbox are one coherent mailbox
                    // snapshot. Publishing an empty list between them made the UI say
                    // "No messages" before it had asked the server for messages at all.
                    mutate { copy(hasMailbox = true, account = acc, error = null) }
                    loadInbox()
                },
                onFailure = { e ->
                    // A 404 here is "no mailbox claimed yet", not a fault — everything else
                    // is a real error and gets shown on the claim screen so it is not silent.
                    val notFound = (e as? MailClient.MailException)?.status == 404
                    mutate { copy(loading = false, hasMailbox = if (notFound) false else hasMailbox, error = if (notFound) null else e.message) }
                },
            )
        }
    }

    private fun loadInbox() {
        mail.inbox().fold(
            onSuccess = { result ->
                mutate { copy(loading = false, messages = result.messages) }
                mutate { copy(contacts = harvestContacts(result.messages)) }
            },
            onFailure = { e -> mutate { copy(loading = false, error = e.message) } },
        )
    }

    private fun harvestContacts(messages: List<MailClient.MailSummary>): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>() // address(lower) -> display name
        for (m in messages) {
            val env = m.envelope ?: continue
            for (field in listOf(env.from, env.to)) {
                for (part in field.split(",")) {
                    val addr = MailMime.emailAddress(part).lowercase()
                    if (addr.isBlank() || !addr.contains('@')) continue
                    val name = MailMime.displayName(part)
                    out.putIfAbsent(addr, name)
                }
            }
        }
        return out.map { it.value to it.key }
    }

    fun isAvailable(localpart: String, onResult: (Boolean?) -> Unit) {
        worker.execute { onResult(mail.isAvailable(localpart).getOrNull()) }
    }

    fun claim(localpart: String, onDone: (String?) -> Unit) {
        worker.execute {
            mail.register(localpart).fold(
                onSuccess = { refresh(); onDone(null) },
                onFailure = { e -> onDone(e.message ?: "could not create that address") },
            )
        }
    }

    fun setFolder(folder: String) = mutate { copy(folder = folder, searchQuery = "") }

    fun setSearchQuery(q: String) = mutate { copy(searchQuery = q) }

    fun toggleDeepSearch() = mutate { copy(deepSearch = !deepSearch) }

    fun dismissNotice() = mutate { copy(notice = null, error = null) }

    // ------------------------------------------------------------------ reading

    fun open(id: String) {
        val row = state.messages.find { it.id == id } ?: return
        if (row.folder == FOLDER_DRAFT) { openDraftForEdit(id); return }
        mutate { copy(busy = "Decrypting…") }
        worker.execute {
            mail.message(id).fold(
                onSuccess = { raw ->
                    val parsed = MailMime.parse(raw)
                    val openMail = OpenMail(
                        id = id,
                        folder = row.folder,
                        subject = parsed.subject.ifEmpty { "(no subject)" },
                        from = parsed.from.ifEmpty { row.envelope?.from.orEmpty() },
                        to = parsed.to.ifEmpty { row.envelope?.to.orEmpty() },
                        date = parsed.date.ifEmpty { row.receivedAt },
                        body = parsed.text.ifEmpty { "(empty message)" },
                        attachments = parsed.attachments,
                        messageId = parsed.headers["message-id"],
                    )
                    mutate {
                        copy(
                            busy = null, open = openMail,
                            bodyCache = bodyCache + (id to "${parsed.subject}\n${parsed.from}\n${parsed.text}"),
                        )
                    }
                    if (row.folder == FOLDER_INBOX) {
                        worker.execute { mail.markSeen(id, true); loadInbox() }
                    }
                },
                onFailure = { e -> mutate { copy(busy = null, error = e.message) } },
            )
        }
    }

    fun closeOpen() = mutate { copy(open = null) }

    /** First call moves it to Trash; a second call (already in Trash) destroys it — the
     *  caller gates the second call behind a confirmation, same as Android/iOS. */
    fun delete(id: String) {
        worker.execute {
            val row = state.messages.find { it.id == id }
            mail.delete(id, permanent = row?.folder == FOLDER_TRASH).fold(
                onSuccess = { mutate { copy(open = null) }; loadInboxNow() },
                onFailure = { e -> mutate { copy(error = e.message ?: "Operation failed") } },
            )
        }
    }

    fun archive(id: String) = move(id, FOLDER_ARCHIVE)
    fun restore(id: String) = move(id, FOLDER_INBOX)

    private fun move(id: String, folder: String) {
        worker.execute {
            mail.move(id, folder).fold(
                onSuccess = { mutate { copy(open = null) }; loadInboxNow() },
                onFailure = { e -> mutate { copy(error = e.message) } },
            )
        }
    }

    fun markUnread(id: String) {
        worker.execute {
            mail.markSeen(id, false).fold(
                onSuccess = { mutate { copy(open = null) }; loadInboxNow() },
                onFailure = { e -> mutate { copy(error = e.message ?: "Operation failed") } },
            )
        }
    }

    private fun loadInboxNow() = loadInbox()

    // ------------------------------------------------------------------ composing / sending

    /** @param draftIdToRemove the draft this send replaces, if the composer was opened from one. */
    fun send(
        to: List<String>,
        cc: List<String>,
        subject: String,
        text: String,
        inReplyTo: String?,
        attachments: List<MailClient.OutgoingAttachment>,
        from: String? = null,
        draftIdToRemove: String?,
        onDone: (String?) -> Unit,
    ) {
        val sendingDraft = state.editingDraft
        mutate { copy(busy = "Sending…") }
        worker.execute {
            mail.send(to, subject, text, cc, from = from, inReplyTo = inReplyTo, attachments = attachments).fold(
                onSuccess = {
                    draftIdToRemove?.let { id -> mail.delete(id, permanent = true).onFailure { e ->
                        mutate { copy(error = "Message sent, but its draft could not be removed: ${e.message}") }
                    } }
                    mutate { copy(busy = null, editingDraft = if (editingDraft === sendingDraft) null else editingDraft) }
                    loadInboxNow()
                    onDone(null)
                },
                onFailure = { e ->
                    mutate { copy(busy = null) }
                    onDone(
                        if (e is MailClient.MailException && e.code == "send-quota-exceeded")
                            "You have used all of this month's 150 messages."
                        else e.message ?: "Could not send message",
                    )
                },
            )
        }
    }

    // ------------------------------------------------------------------ drafts (sealed to our own key)

    fun openDraftForEdit(id: String) {
        val row = state.messages.find { it.id == id }
        mutate { copy(busy = "Decrypting…") }
        worker.execute {
            mail.message(id).fold(
                onSuccess = { raw ->
                    mutate {
                        copy(
                            busy = null,
                            editingDraft = DraftContent(
                                id = id,
                                to = row?.envelope?.to.orEmpty(),
                                cc = row?.envelope?.cc.orEmpty(),
                                subject = row?.envelope?.subject.orEmpty(),
                                body = String(raw, Charsets.UTF_8),
                                inReplyTo = row?.envelope?.inReplyTo,
                            ),
                        )
                    }
                },
                onFailure = { e -> mutate { copy(busy = null, error = e.message) } },
            )
        }
    }

    fun newDraft() = mutate { if (editingDraft == null) copy(editingDraft = DraftContent(null, "", "", "", "")) else this }

    /** Opens the composer pre-filled to reply to an [OpenMail] the reader has open. */
    fun replyDraftFrom(open: OpenMail) {
        val quoted = "\n\nOn ${open.date}, ${open.from} wrote:\n" + open.body.lines().joinToString("\n") { "> $it" }
        mutate {
            copy(
                editingDraft = DraftContent(
                    id = null,
                    to = MailMime.emailAddress(open.from),
                    cc = "",
                    subject = if (open.subject.startsWith("Re:", ignoreCase = true)) open.subject else "Re: ${open.subject}",
                    body = quoted,
                    inReplyTo = open.messageId,
                ),
            )
        }
    }

    fun editDraft(draft: DraftContent) = mutate { copy(editingDraft = draft) }

    fun reply(message: OpenMail) = replyDraftFrom(message)

    fun forward(message: OpenMail) = editDraft(DraftContent(null, "", "", "Fwd: " + message.subject.removePrefix("Fwd: "), "\n\nFrom: ${message.from}\nTo: ${message.to}\nDate: ${message.date}\n\n${message.body}", attachments = message.attachments.map { MailClient.OutgoingAttachment(it.filename, it.mimeType, it.bytes) }))

    fun attachFile(file: File) {
        val draft = state.editingDraft ?: return
        worker.execute {
            runCatching {
                require(file.length() <= MailClient.MAX_ATTACHMENT_TOTAL_BYTES) { "Attachment is too large" }
                val bytes = file.inputStream().use { it.readNBytes(MailClient.MAX_ATTACHMENT_TOTAL_BYTES + 1) }
                val attachment = MailClient.OutgoingAttachment(file.name, java.nio.file.Files.probeContentType(file.toPath()) ?: "application/octet-stream", bytes)
                mutate {
                    val active = editingDraft ?: return@mutate this
                    if (active.composerId != draft.composerId) return@mutate this
                    require(active.attachments.sumOf { it.bytes.size.toLong() } + bytes.size <= MailClient.MAX_ATTACHMENT_TOTAL_BYTES) { "Attachments exceed 18 MiB" }
                    copy(editingDraft = active.copy(attachments = active.attachments + attachment))
                }
            }.onFailure { e -> mutate { copy(error = e.message ?: "Could not attach file") } }
        }
    }

    fun saveAttachment(attachment: MailMime.Attachment, target: File, onDone: (String?) -> Unit) {
        worker.execute { onDone(runCatching { target.writeBytes(attachment.bytes) }.exceptionOrNull()?.let { it.message ?: "Could not save attachment" }) }
    }

    /**
     * Seals a received attachment to THIS mailbox's own mail key and stores it in the
     * encrypted drive — the same zero-access path a file picked in the drive uses (the
     * server bills the size and never learns the name or the bytes). Mirrors iOS
     * `MailView.saveAttachmentToDrive` and Android's equivalent: the body is sealed here,
     * the name is sealed inside [MailClient.uploadToDrive].
     */
    fun saveAttachmentToDrive(attachment: MailMime.Attachment, onDone: (String?) -> Unit) {
        mutate { copy(busy = "Saving to drive…") }
        worker.execute {
            runCatching {
                val sealedBody = MailCrypto.seal(attachment.bytes, mail.mailPublicKey())
                mail.uploadToDrive(sealedBody, attachment.filename) { p ->
                    mutate { copy(busy = "Saving to drive ${(p * 100).toInt()}%") }
                }.getOrThrow()
            }.fold(
                onSuccess = { mutate { copy(busy = null) }; refresh(); onDone(null) },
                onFailure = { e -> mutate { copy(busy = null) }; onDone(e.message ?: "Could not save to drive") },
            )
        }
    }

    fun clearEditingDraft() = mutate { copy(editingDraft = null) }

    /**
     * Seals and autosaves the composer, reusing [id] so repeated autosaves edit the same
     * draft instead of minting a new one on every keystroke pause. Call this from a
     * debounce timer in the UI layer — this method itself does not debounce.
     */
    fun autosaveDraft(id: String?, to: String, cc: String, subject: String, body: String, onSaved: (String?) -> Unit) {
        if (to.isBlank() && cc.isBlank() && subject.isBlank() && body.isBlank()) { onSaved(id); return }
        // A delayed UI autosave can complete after a discard/new-composer action. Only the
        // composer that asked may receive the persisted id needed for its next edit.
        val requestedComposerId = state.editingDraft?.composerId
        val inReplyTo = state.editingDraft?.inReplyTo
        worker.execute {
            val envelope = JSONObject()
                .put("from", state.account?.address.orEmpty())
                .put("to", to)
                .put("cc", cc)
                .put("inReplyTo", inReplyTo)
                .put("subject", subject)
                .put("date", java.time.Instant.now().toString())
                .toString().toByteArray(Charsets.UTF_8)
            mail.saveDraft(id, envelope, body.toByteArray(Charsets.UTF_8)).fold(
                onSuccess = { newId ->
                    val persistedId = newId.ifEmpty { id }
                    if (persistedId != null && requestedComposerId != null) mutate {
                        val active = editingDraft
                        if (active?.composerId == requestedComposerId && active.id != persistedId)
                            copy(editingDraft = active.copy(id = persistedId))
                        else this
                    }
                    loadInboxNow()
                    onSaved(persistedId)
                },
                onFailure = { e -> mutate { copy(error = e.message ?: "Could not save draft") }; onSaved(null) },
            )
        }
    }

    fun deleteDraft(id: String) {
        worker.execute {
            mail.delete(id, permanent = true).fold(
                onSuccess = { mutate { copy(editingDraft = null) }; loadInboxNow() },
                onFailure = { e -> mutate { copy(error = e.message ?: "Operation failed") } },
            )
        }
    }

    // ------------------------------------------------------------------ aliases

    fun addAlias(alias: String, onDone: (String?) -> Unit) {
        worker.execute {
            mail.addAlias(alias).fold(
                onSuccess = { refresh(); onDone(null) },
                onFailure = { e -> onDone(e.message) },
            )
        }
    }

    fun removeAlias(alias: String) {
        worker.execute { mail.removeAlias(alias).fold(onSuccess = { refresh() }, onFailure = { e -> mutate { copy(error = e.message ?: "Operation failed") } }) }
    }

    /** Public-key-derived alias suggestion; safe to render and identical across devices. */
    fun keyBasedAliasLocalpart(): String = mail.keyBasedLocalpart

    // ---------------------------------------------------------- browser key sharing

    /** Looks up the browser's ephemeral public key; no private mail material is sent here. */
    fun inspectBrowserPairing(code: String, onDone: (MailClient.PairingRequest?, String?) -> Unit) {
        worker.execute {
            mail.inspectPairing(code).fold(
                onSuccess = { onDone(it, null) },
                onFailure = { e -> onDone(null, e.message ?: "Could not find that browser") },
            )
        }
    }

    /**
     * Opens a saved browser-pairing QR off the UI thread. A contact/recovery QR must never
     * be treated as permission to share a mail key, hence the protocol-specific prefix.
     */
    fun inspectBrowserPairingQr(image: File, onDone: (MailClient.PairingRequest?, String?) -> Unit) {
        worker.execute {
            val payload = QrImageDecoder.decode(image).getOrElse { error ->
                onDone(null, error.message ?: "Could not scan this QR image")
                return@execute
            }
            val prefix = "OSHIMAIL:PAIR:"
            if (!payload.startsWith(prefix)) {
                onDone(null, "That QR code is not a browser mail pairing request")
                return@execute
            }
            mail.inspectPairing(payload.removePrefix(prefix)).fold(
                onSuccess = { onDone(it, null) },
                onFailure = { e -> onDone(null, e.message ?: "Could not find that browser") },
            )
        }
    }

    /** The claim seals the private key to the inspected browser key inside [MailClient]. */
    fun approveBrowserPairing(pairing: MailClient.PairingRequest, label: String, onDone: (String?, String?) -> Unit) {
        worker.execute {
            mail.approvePairing(pairing, label).fold(
                onSuccess = { address -> loadBrowserSessions(); onDone(address, null) },
                onFailure = { e -> onDone(null, e.message ?: "Could not approve that browser") },
            )
        }
    }

    fun loadBrowserSessions() {
        worker.execute {
            mail.webSessions().fold(
                onSuccess = { sessions -> mutate { copy(webSessions = sessions) } },
                onFailure = { e -> mutate { copy(error = e.message ?: "Could not load browser sessions") } },
            )
        }
    }

    fun revokeBrowserSession(id: String, onDone: (String?) -> Unit = {}) {
        worker.execute {
            mail.revokeWebSession(id).fold(
                onSuccess = { loadBrowserSessions(); onDone(null) },
                onFailure = { e -> onDone(e.message ?: "Could not sign out that browser") },
            )
        }
    }

    // ------------------------------------------------------------------ drive

    fun loadDrive() {
        mutate { copy(busy = "Decrypting names…") }
        worker.execute {
            mail.driveFiles().fold(
                onSuccess = { files -> mutate { copy(busy = null, drive = files) } },
                onFailure = { e -> mutate { copy(busy = null, error = e.message) } },
            )
        }
    }

    /**
     * Encrypts and uploads a local file. Ciphertext leaves the device; the server never
     * sees the plaintext bytes OR the filename (that rides inside the sealed metadata too).
     */
    fun uploadFile(file: File, onDone: (String?) -> Unit) {
        mutate { copy(busy = "Encrypting…") }
        worker.execute {
            val plain = try { file.readBytes() } catch (e: Exception) {
                mutate { copy(busy = null) }; onDone(e.message); return@execute
            }
            val sealed = MailCrypto.seal(plain, mail.mailPublicKey())
            mail.uploadToDrive(sealed, file.name) { p -> mutate { copy(busy = "Uploading ${(p * 100).toInt()}%") } }
                .fold(
                    onSuccess = { mutate { copy(busy = null) }; loadDrive(); refresh(); onDone(null) },
                    onFailure = { e -> mutate { copy(busy = null) }; onDone(e.message) },
                )
        }
    }

    fun downloadFile(id: String, target: File, onDone: (String?) -> Unit) {
        mutate { copy(busy = "Decrypting…") }
        worker.execute {
            mail.downloadFromDrive(id).fold(
                onSuccess = { bytes ->
                    runCatching { target.writeBytes(bytes) }
                        .fold(
                            onSuccess = { mutate { copy(busy = null) }; onDone(null) },
                            onFailure = { e -> mutate { copy(busy = null) }; onDone(e.message) },
                        )
                },
                onFailure = { e -> mutate { copy(busy = null) }; onDone(e.message) },
            )
        }
    }

    fun deleteDriveFile(id: String) {
        worker.execute { mail.deleteFromDrive(id).fold(onSuccess = { loadDrive(); refresh() }, onFailure = { e -> mutate { copy(error = e.message ?: "Operation failed") } }) }
    }

    fun renameDriveFile(id: String, newName: String, onDone: (String?) -> Unit) {
        val clean = newName.trim()
        if (clean.isEmpty()) { onDone("A file needs a name."); return }
        worker.execute {
            mail.renameDriveFile(id, clean).fold(
                onSuccess = { loadDrive(); onDone(null) },
                onFailure = { e -> onDone(e.message) },
            )
        }
    }

    /**
     * Decrypts a drive file and appends it to the composer that requested it. The download
     * can outlive a keystroke, a discarded draft, or a new composer, so only the matching
     * [DraftContent.composerId] may receive its bytes.
     */
    fun attachDriveFile(file: MailClient.DriveFile, onDone: (String?) -> Unit) {
        val requestedBy = state.editingDraft ?: return onDone("No draft is open")
        mutate { copy(busy = "Decrypting…") }
        worker.execute {
            mail.downloadFromDrive(file.id).fold(
                onSuccess = { bytes ->
                    runCatching {
                        require(bytes.size <= MailClient.MAX_ATTACHMENT_TOTAL_BYTES) { "Attachment is too large" }
                        var attached = false
                        mutate {
                            val active = editingDraft ?: return@mutate this
                            if (active.composerId != requestedBy.composerId) return@mutate this
                            require(active.attachments.sumOf { it.bytes.size.toLong() } + bytes.size <= MailClient.MAX_ATTACHMENT_TOTAL_BYTES) {
                                "Attachments exceed 18 MiB"
                            }
                            attached = true
                            copy(editingDraft = active.copy(attachments = active.attachments +
                                MailClient.OutgoingAttachment(file.name ?: "file", "application/octet-stream", bytes)))
                        }
                        require(attached) { "The draft changed before the Drive file finished downloading" }
                    }.fold(
                        onSuccess = { mutate { copy(busy = null) }; onDone(null) },
                        onFailure = { e -> mutate { copy(busy = null) }; onDone(e.message ?: "Could not attach Drive file") },
                    )
                },
                onFailure = { e -> mutate { copy(busy = null) }; onDone(e.message ?: "Could not download Drive file") },
            )
        }
    }
}

/**
 * Decides whether a refreshed mailbox contains mail that arrived after this process began
 * observing it. It deliberately tracks all ids, not just unread ids: marking an existing
 * row unread is a local action, not a new-mail notification.
 */
internal class MailArrivalTracker {
    private var knownIds: Set<String>? = null

    fun onSnapshot(snapshot: MailModel.MailUiState): Boolean {
        if (snapshot.loading || !snapshot.hasMailbox) return false
        val ids = snapshot.messages.mapTo(LinkedHashSet()) { it.id }
        val prior = knownIds
        knownIds = ids
        return prior != null && snapshot.messages.any {
            it.id !in prior && it.folder == MailModel.FOLDER_INBOX && !it.seen
        }
    }
}
