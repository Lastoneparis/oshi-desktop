package com.oshi.desktop.store

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Conversations and messages, durable across a restart — PARITY.md row 0.13.
 *
 * WHAT THIS IS A PORT OF. There is no single upstream file: Android keeps `Message`/
 * `Contact` in Room (`data/model/Message.kt`) and iOS keeps `SecureMessage` in an
 * in-memory array persisted by `MessageManager` (`MessageManager.swift:7951`). This
 * store mirrors the FIELDS both sides actually carry — content, media pointer, delivery
 * status, edit/delete, view-once, reply, reactions — without either engine: no SQLite
 * driver, no ORM, just the JDK and `org.json`, per PLAN.md's "no new dependency without
 * a reason that survives being written down."
 *
 * ============================================================ FORMAT
 *
 * One APPEND-ONLY file per conversation, newline-delimited JSON, one message per line:
 *
 *     <dataDir>/messages/<sha256-hex(conversationId)>.jsonl
 *
 * Why hash the conversation id into the filename rather than use it directly: a
 * conversation id is typically a standard-base64 X25519 address, which contains `/`
 * (a path separator) and is 44 characters of mixed case that two of the three target
 * filesystems (Windows, and macOS in its default case-insensitive mode) do not treat
 * the same way two Linux filesystems would. Hashing sidesteps all three platform quirks
 * in one move instead of writing a bespoke escaper — and it is emphatically NOT the
 * base64url-in-an-identity hazard PLAN.md §4.1 warns about: that rule is about the wire,
 * and a filename on this machine never reaches it. The conversation id itself is still
 * recorded, in full, inside every record — so the mapping is recoverable by reading a
 * single line, not by remembering a scheme.
 *
 * WHY APPEND-ONLY, AND NOT [AtomicFile] LIKE [KeyVault]/[SessionStore]/[PrekeyStore].
 * Every other store in this package holds at most a few kilobytes and is safe to
 * rewrite wholesale on every change — that is exactly what `AtomicFile.write` (in
 * `SecretStore.kt`) is for, and this file reuses it for [ContactStore], where the
 * "small, bounded, rewrite it all" assumption actually holds (a contact list is bounded
 * by a person's social graph, not by how long they have used the app).
 *
 * A conversation is not bounded like that. A rewrite-on-every-message design costs
 * O(existing history) per NEW message, which makes a five-thousand-message conversation
 * roughly five thousand times more expensive to append to than a five-message one. This
 * store instead opens each file `O_APPEND` and writes exactly one line: the OS appends
 * without touching a single byte that was already on disk, so the cost of a message is
 * the cost of that message, not the cost of the whole conversation. [MessageStoreTest]
 * proves this directly — the bytes before the append point are BYTE-IDENTICAL before
 * and after appending message five thousand, and there is exactly one file on disk.
 *
 * DURABILITY. Each append is one `write()` of one line followed by `FileDescriptor.sync()`
 * — the record is on stable storage before the call returns. A crash mid-write can still
 * leave a torn final line (a `write()` of more than a filesystem block is not atomic
 * against a power loss or a `kill -9`), so the reader treats a line that fails to parse
 * as "this one record does not exist" and keeps going — never as "the file is corrupt,
 * discard everything after it," and never as a thrown exception that would make an
 * otherwise-intact conversation unreadable. This is a DELIBERATELY different answer than
 * [KeyVault], which throws on a damaged file rather than silently reading it as empty:
 * a vault that quietly "recovers" as empty invites a fresh identity to be minted over an
 * existing one, which is unrecoverable. A damaged message row has no equivalent
 * catastrophe on the other side — losing one line out of five thousand to a torn write
 * is strictly better than refusing to show any of them, so this store degrades instead
 * of throwing. [MessageStoreTest] proves the specific case: history written before a
 * simulated crash survives reading it back after one.
 *
 * DEDUP ACROSS TRANSPORTS. The relay and the mesh can both deliver the same logical
 * message under the same `msgId` — a phone that is on the same LAN and also reachable
 * through the relay will see exactly this. [append] treats a second arrival whose fields
 * are otherwise identical to the stored record as a no-op: no line is written at all, so
 * a duplicate delivery costs nothing on disk and the conversation still reads back with
 * that message appearing exactly once. A record that differs (content, delivery status,
 * a reaction, an edit) under the SAME id is not a duplicate, it is an update, and is
 * appended as a new line — the latest line for a given id wins when the log is folded
 * into the current state on load. That fold is what keeps updates cheap too: marking a
 * message read costs one short line, not a rewrite of the message it is about.
 *
 * ORDERING. A message's position in the file is arrival order at THIS node, which is not
 * the same as when it was sent — the mesh and the relay race, and the loser can still
 * arrive after the winner's reply. [messages] therefore never returns file order: it
 * sorts by each message's own [Message.sentAtMs], with the append sequence as a stable
 * tiebreaker for two messages that claim the exact same millisecond. A conversation that
 * received B before A (because A's path was slower) still reads back A, then B.
 *
 * ============================================================ TIMESTAMPS (PLAN.md §4.2)
 *
 * This format is LOCAL ONLY: it is never transmitted, and the reader and the writer are
 * this same class in the same process, so the Swift/Kotlin interop hazards in PLAN.md §4
 * (key order, strict decoders refusing an unknown field) simply do not apply to it — there
 * is no second implementation to disagree with. What DOES carry over is the timestamp
 * discipline in §4.2: **four** different encodings are live on the wire (relay millis,
 * legacy-IPFS seconds, Apple-epoch seconds, ISO-8601 strings), and mixing one up does not
 * always throw — sending millis where Apple-epoch is expected quietly decodes to the year
 * 58,000. Whoever calls [append] is responsible for having already converted the payload's
 * own timestamp into a single unambiguous instant (`sentAtMs`, Unix epoch milliseconds);
 * this store does not guess from a field name or a magnitude on its own. What it does do
 * is keep [Message.sentAtSource] alongside the instant — provenance for debugging a bad
 * conversion after the fact — and apply the same `>1e10`-shaped magnitude guard both
 * platforms already carry (`Message.init`), so a value that is obviously an un-converted
 * epoch (seconds instead of millis, or the reverse) is rejected at the door instead of
 * silently corrupting this conversation's order forever.
 *
 * ============================================================ THREAT MODEL
 *
 * Production records are AES-256-GCM encrypted individually. The history key is a distinct
 * 32-byte random value in [KeyVault], whose master key stays in the OS key store (or the
 * explicit passphrase fallback). A fresh random 96-bit nonce is generated for every record;
 * the filename is authenticated as additional data so an encrypted line cannot be copied to
 * another conversation file and still open. Random nonces avoid the permanent AES-GCM failure
 * mode of reusing a counter after a deleted/recreated conversation.
 *
 * Older plaintext journals are accepted only long enough to migrate them. Once all parseable
 * rows have been loaded, the migration rewrites that one journal atomically as encrypted
 * records. A torn or malformed legacy row follows the existing recovery rule (lose that row,
 * keep the rest); an encrypted row with a bad tag is never treated as plaintext or empty.
 */
class MessageStore(
    private val baseDir: File = DesktopPaths.file("messages"),
    private val atRestKey: ByteArray? = null,
) {

    init {
        DesktopPaths.ensurePrivateDir(baseDir)
        require(atRestKey == null || atRestKey.size == MessageLogCipher.KEY_BYTES) {
            "message history key must be ${MessageLogCipher.KEY_BYTES} bytes"
        }
    }

    enum class AppendOutcome { INSERTED, UPDATED, DUPLICATE }

    data class ConversationSummary(
        val conversationId: String,
        val messageCount: Int,
        val lastMessage: Message?,
        val lastActivityMs: Long,
    )

    private val cache = HashMap<String, ConversationLog>()

    /**
     * Insert or update one message. A second arrival of the same [Message.id] whose
     * other fields are unchanged costs no I/O at all (see the class doc for why).
     */
    @Synchronized
    fun append(message: Message): AppendOutcome =
        logFor(message.conversationId).append(message).also { if (it != AppendOutcome.DUPLICATE) notifyChanged(message.conversationId) }

    /**
     * __DEVSYNC_LIVE_UPGRADE_2026_09_23__ Told after every [append] that changed something
     * (insert, status, edit, delete, reaction): own-device sync mirrors it within ~150 ms instead
     * of at the next poll. Called under the store lock: listeners must only hand off (never block,
     * never call back into this store).
     */
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()
    fun addChangeListener(l: (conversationId: String) -> Unit) { changeListeners.add(l) }
    fun removeChangeListener(l: (conversationId: String) -> Unit) { changeListeners.remove(l) }
    private fun notifyChanged(conversationId: String) = changeListeners.forEach { runCatching { it(conversationId) } }

    /** Everything in one conversation, oldest first by [Message.sentAtMs] (see class doc). */
    @Synchronized
    fun messages(conversationId: String): List<Message> = logFor(conversationId).ordered()

    @Synchronized
    fun message(conversationId: String, id: String): Message? = logFor(conversationId).byId(id)

    /** Every conversation this node has any history for, most recently active first. */
    @Synchronized
    fun conversations(): List<ConversationSummary> {
        val files = baseDir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: emptyArray()
        return files.mapNotNull { f -> peekConversationId(f) }
            .distinct()
            .map { cid -> logFor(cid).summary() }
            .sortedByDescending { it.lastActivityMs }
    }

    /**
     * __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Every stored message, every conversation —
     * INCLUDING blocked peers, bots and radio threads, because an export is a backup, not a
     * view. [unreadableFiles] counts journals whose conversation id could not be decoded at
     * all (wrong history key, or every row damaged): they are NOT in [messages], and a caller
     * must say so rather than present the export as complete.
     */
    data class Snapshot(val messages: List<Message>, val unreadableFiles: Int)

    @Synchronized
    fun snapshot(): Snapshot {
        val files = baseDir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: emptyArray()
        var unreadable = 0
        val ids = LinkedHashSet<String>()
        for (f in files.sortedBy { it.name }) {
            val cid = peekConversationId(f)
            if (cid == null) unreadable++ else ids += cid
        }
        return Snapshot(ids.flatMap { logFor(it).ordered() }, unreadable)
    }

    data class ImportCounts(val inserted: Int, val alreadyPresent: Int)

    /**
     * Merge imported messages, de-duplicated by (conversation, id). A message this store
     * already holds is LEFT AS IT IS — the local copy may carry a newer status, edit or
     * reaction than the export, and an import must never roll it back. Nothing here sends a
     * receipt, raises a notification or calls any callback: [MessageStore] has none, and the
     * caller is expected to refresh its view afterwards.
     */
    @Synchronized
    fun importMissing(incoming: List<Message>): ImportCounts {
        var inserted = 0
        var present = 0
        for (m in incoming) {
            val log = logFor(m.conversationId)
            if (log.byId(m.id) != null) { present++; continue }
            log.append(m)
            inserted++
        }
        return ImportCounts(inserted, present)
    }

    /** Account/conversation deletion: the file is gone, not merely emptied in memory. */
    @Synchronized
    fun deleteConversation(conversationId: String) {
        cache.remove(conversationId)
        idByPath.remove(fileFor(conversationId).absolutePath)
        fileFor(conversationId).delete()
    }

    /** Where a conversation's log lives, exposed for tests and for a future "export chat." */
    fun fileNameFor(conversationId: String): String = hash(conversationId) + SUFFIX

    // -------------------------------------------------------------------------- mutators

    /**
     * Advance delivery status, never backward and never resurrect a `FAILED` message —
     * the exact rule iOS's `DeliveryStatus.advanced(to:)` enforces
     * (`MessageManager.swift:8090`), copied here so a delayed relay receipt cannot
     * un-read a message the user already opened.
     */
    @Synchronized
    fun advanceDeliveryStatus(conversationId: String, id: String, to: DeliveryStatus): Message? {
        val current = message(conversationId, id) ?: return null
        val advanced = current.deliveryStatus.advanced(to)
        if (advanced == current.deliveryStatus) return current
        val updated = current.copy(deliveryStatus = advanced)
        append(updated)
        return updated
    }

    @Synchronized
    fun editContent(conversationId: String, id: String, newContent: String, editedAtMs: Long): Message? {
        val current = message(conversationId, id) ?: return null
        if (current.isDeletedForEveryone) return current // a tombstone cannot be edited back to life
        val updated = current.copy(content = newContent, editedAtMs = editedAtMs, editedContent = newContent)
        append(updated)
        return updated
    }

    @Synchronized
    fun deleteForEveryone(conversationId: String, id: String, atMs: Long): Message? {
        val current = message(conversationId, id) ?: return null
        val updated = current.copy(
            content = null, mediaType = null, mediaRef = null,
            isDeletedForEveryone = true, editedAtMs = atMs,
        )
        append(updated)
        return updated
    }

    /** __GROUP_PARITY_2026_09_23__ delete one message on this device only. */
    @Synchronized
    fun hideLocally(conversationId: String, id: String): Message? {
        val current = message(conversationId, id) ?: return null
        if (current.hiddenLocally) return current
        val updated = current.copy(content = null, mediaRef = null, hiddenLocally = true)
        append(updated)
        return updated
    }

    @Synchronized
    fun setReaction(conversationId: String, id: String, senderAddress: String, emoji: String?): Message? {
        val current = message(conversationId, id) ?: return null
        val next = HashMap<String, Set<String>>()
        current.reactions.forEach { (k, v) -> next[k] = v - senderAddress }
        if (emoji != null) next[emoji] = (next[emoji] ?: emptySet()) + senderAddress
        val updated = current.copy(reactions = next.filterValues { it.isNotEmpty() })
        append(updated)
        return updated
    }

    // ---------------------------------------------------------------------------- plumbing

    private fun logFor(conversationId: String): ConversationLog =
        cache.getOrPut(conversationId) { ConversationLog(conversationId, fileFor(conversationId)).apply { loadFromDisk() } }

    private fun fileFor(conversationId: String): File = File(baseDir, fileNameFor(conversationId))

    /** Cheap discovery for [conversations]: read only as far as the first valid line. */
    /**
     * Path → conversation id, cached for the life of the store.
     *
     * Sound because the mapping is IMMUTABLE, not merely stable: the file name IS
     * `hash(conversationId)` ([fileNameFor]), so one name can only ever belong to one
     * conversation. A file deleted and recreated for the same conversation hashes to the
     * same name and carries the same id; a different conversation cannot land on that
     * name without a SHA-256 collision.
     *
     * Worth caching because [conversations] is called on every inbound message and every
     * selection change in the window, and without this each call opened and read EVERY
     * conversation file to recover the id the filename deliberately hides. That is a
     * directory scan plus N file opens, and `ChatShellModel.build()` performs it while
     * holding its lock.
     */
    private val idByPath = HashMap<String, String>()

    private fun peekConversationId(file: File): String? {
        idByPath[file.absolutePath]?.let { return it }
        file.bufferedReader(Charsets.UTF_8).use { r ->
            var line = r.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    decodeLine(file, line)?.let { decoded ->
                        runCatching { JSONObject(decoded.plaintext).getString(MessageStoreConst.F_CONVERSATION) }.getOrNull()
                    }?.let {
                        idByPath[file.absolutePath] = it
                        return it
                    }
                }
                line = r.readLine()
            }
        }
        return null
    }

    private fun hash(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }

    companion object {
        private const val SUFFIX = ".jsonl"
        const val HISTORY_KEY_ACCOUNT = "message-history-key-v1"
    }

    private data class DecodedLine(val plaintext: String, val wasEncrypted: Boolean)

    /** `null` means corrupt/tampered, never "no history". */
    private fun decodeLine(file: File, line: String): DecodedLine? {
        if (!MessageLogCipher.isEnvelope(line)) return DecodedLine(line, wasEncrypted = false)
        val key = atRestKey ?: return null
        return MessageLogCipher.open(key, file.name, line)?.let { DecodedLine(it, wasEncrypted = true) }
    }

    private fun encodeLine(file: File, plaintext: String): String =
        atRestKey?.let { MessageLogCipher.seal(it, file.name, plaintext) } ?: plaintext

    // -------------------------------------------------------------------- per-conversation log

    private inner class ConversationLog(val conversationId: String, val file: File) {
        // insertion order preserved, but read order is decided by ordered() — this map
        // is "current state per id," not "display order."
        private val byId = LinkedHashMap<String, Message>()
        private var nextSeq = 0L

        fun loadFromDisk() {
            byId.clear(); nextSeq = 0L
            if (!file.isFile) return
            var legacyRowsSeen = false
            var sealedRowsLost = 0
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    // A line that fails to parse is either a torn write (only ever the
                    // LAST line — every append is a single write() of one whole line) or
                    // genuine corruption of one record. Either way: drop that one record,
                    // keep everything else. See the class doc for why this differs from
                    // KeyVault's "throw, never silently read as empty."
                    val decoded = decodeLine(file, line)
                    if (decoded == null) {
                        if (MessageLogCipher.isEnvelope(line)) sealedRowsLost++
                        continue
                    }
                    if (!decoded.wasEncrypted) legacyRowsSeen = true
                    val record = runCatching { Message.fromJson(JSONObject(decoded.plaintext)) }.getOrNull() ?: continue
                    apply(record)
                }
            }
            // __AT_REST_MIGRATION_HARDENING_2026_09_22__ Migrate only when EVERY sealed row
            // in the file opened. A rewrite folds the file down to what was readable, so a
            // mixed journal holding rows sealed under another history key (a restored vault,
            // a copied profile) would have had those rows destroyed by the "upgrade". The
            // file is left exactly as it is instead; appends still go out encrypted.
            if (legacyRowsSeen && atRestKey != null && sealedRowsLost == 0) rewriteEncrypted()
        }

        private fun apply(m: Message) {
            byId[m.id] = m
            if (m.localSeq >= nextSeq) nextSeq = m.localSeq + 1
        }

        fun append(message: Message): AppendOutcome {
            require(message.conversationId == conversationId) {
                "message ${message.id} carries conversationId=${message.conversationId}, " +
                    "not $conversationId — routed to the wrong log"
            }
            val existing = byId[message.id]
            if (existing != null && existing.isDuplicateOf(message)) return AppendOutcome.DUPLICATE
            // __GROUP_PARITY_2026_09_23__ deleted for me: a later copy (redelivery, edit, reaction)
            // neither resurrects it nor raises a notification.
            if (existing?.hiddenLocally == true && !message.hiddenLocally) return AppendOutcome.DUPLICATE

            // A row deleted for me stays deleted for me, whatever copy arrives later.
            val stamped = message.copy(localSeq = nextSeq, hiddenLocally = message.hiddenLocally || existing?.hiddenLocally == true)
            val line = stamped.toJson().toString()
            appendLineDurable(file, encodeLine(file, line))
            apply(stamped)
            return if (existing == null) AppendOutcome.INSERTED else AppendOutcome.UPDATED
        }

        fun ordered(): List<Message> = byId.values.sortedWith(compareBy({ it.sentAtMs }, { it.localSeq }))

        fun byId(id: String): Message? = byId[id]

        fun summary(): ConversationSummary {
            val ordered = ordered()
            return ConversationSummary(
                conversationId = conversationId,
                messageCount = ordered.size,
                lastMessage = ordered.lastOrNull(),
                lastActivityMs = ordered.lastOrNull()?.sentAtMs ?: 0L,
            )
        }

        /**
         * One-time plaintext upgrade: WRITE NEW, VERIFY, THEN REPLACE OLD.
         *
         * The sealed journal goes to a temp file beside the original and is fsynced; it is
         * then read back and every row decrypted and compared with what was loaded. Only a
         * copy that reproduces the loaded state exactly is atomically moved over the
         * plaintext file. Any failure leaves the plaintext journal untouched (it is retried
         * on the next open) — never an empty or half-written replacement.
         */
        private fun rewriteEncrypted() {
            val records = byId.values.toList()
            if (records.isEmpty()) return
            val encrypted = buildString {
                for (record in records) {
                    append(encodeLine(file, record.toJson().toString())).append('\n')
                }
            }.toByteArray(Charsets.UTF_8)
            val parent = file.parentFile ?: File(".")
            val tmp = File.createTempFile(file.name, ".migrating", parent)
            try {
                DesktopPaths.makePrivate(tmp)
                FileOutputStream(tmp).use { fos -> fos.write(encrypted); fos.fd.sync() }
                val readBack = tmp.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
                    if (!MessageLogCipher.isEnvelope(line)) return
                    val plain = decodeLine(file, line) ?: return
                    Message.fromJson(JSONObject(plain.plaintext))
                }
                if (readBack != records) return
                try {
                    java.nio.file.Files.move(
                        tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                DesktopPaths.makePrivate(file)
            } catch (_: Exception) {
                // Leave the original journal in place; the next open retries the upgrade.
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}

/** Small, line-oriented AEAD envelope for [MessageStore]; not a wire protocol. */
private object MessageLogCipher {
    const val KEY_BYTES = 32
    private const val VERSION = 1
    private const val NONCE_BYTES = 12

    fun isEnvelope(line: String): Boolean = runCatching {
        val o = JSONObject(line)
        o.optInt("v", -1) == VERSION && o.has("nonce") && o.has("ct")
    }.getOrDefault(false)

    fun seal(key: ByteArray, fileName: String, plaintext: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(fileName))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("v", VERSION)
            .put("nonce", Base64.getEncoder().encodeToString(nonce))
            .put("ct", Base64.getEncoder().encodeToString(ct))
            .toString()
    }

    fun open(key: ByteArray, fileName: String, envelope: String): String? = runCatching {
        val o = JSONObject(envelope)
        if (o.optInt("v", -1) != VERSION) return null
        val nonce = Base64.getDecoder().decode(o.getString("nonce"))
        require(nonce.size == NONCE_BYTES) { "invalid message-history nonce" }
        val ct = Base64.getDecoder().decode(o.getString("ct"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(fileName))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private fun aad(fileName: String): ByteArray =
        "oshi-message-history-v1:$fileName".toByteArray(Charsets.UTF_8)
}

/**
 * Append exactly one line, durably, without touching any byte already on disk.
 *
 * `FileOutputStream(file, true)` opens `O_APPEND`: the OS positions every write at the
 * file's current end atomically with respect to other writers on this same machine, so
 * this is safe even if a future version of this store adds a background compaction pass
 * running in another thread. `FileDescriptor.sync()` forces the write to stable storage
 * before returning — without it, a message could be reported as stored and then vanish
 * on a power loss, which is a durability claim this store's whole design exists to make.
 *
 * Deliberately NOT [AtomicFile]: that helper's temp-file-then-rename pattern rewrites
 * the ENTIRE target every call, which is the exact per-message-costs-the-whole-history
 * behaviour this file is structured to avoid. See the class doc's FORMAT section.
 */
internal fun appendLineDurable(file: File, line: String) {
    val parent = file.parentFile ?: File(".")
    DesktopPaths.ensurePrivateDir(parent)
    val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
    FileOutputStream(file, true).use { fos ->
        fos.write(bytes)
        fos.fd.sync()
    }
    DesktopPaths.makePrivate(file)
}

// ==================================================================================== Message

/**
 * One message, folded to its current state (edits, status advances and reactions all
 * mutate the same record id — see [MessageStore]'s dedup/update rule).
 *
 * Fields are the union of what Android's `Message` entity (`data/model/Message.kt`) and
 * iOS's `SecureMessage` (`MessageManager.swift:7951`) actually carry, minus the media
 * bytes themselves (row 0.15, not this row) and minus the encrypted-content blob (this
 * store only ever holds what has already been decrypted — see the threat-model note).
 */
data class Message(
    /** The relay/mesh `msgId` — the cross-transport dedup key (PLAN.md, `V2MessageRouter`). */
    val id: String,
    val conversationId: String,
    val senderAddress: String,
    val recipientAddress: String,
    val fromMe: Boolean,
    /** Plaintext. Null for a message deleted-for-everyone (content is gone, the row is not). */
    val content: String?,
    val mediaType: MediaType? = null,
    /** A pointer into row 0.15's blob store, not the bytes. Format decided there. */
    val mediaRef: String? = null,
    val replyToId: String? = null,
    /** Normalised Unix-epoch-milliseconds instant — see the class doc's TIMESTAMPS section. */
    val sentAtMs: Long,
    val sentAtSource: TimestampSource,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.PENDING,
    /** Local-only provenance: which transport delivered THIS copy. Never on the wire. */
    val transport: String = "unknown",
    val editedAtMs: Long? = null,
    val editedContent: String? = null,
    val isDeletedForEveryone: Boolean = false,
    val isViewOnce: Boolean = false,
    val viewOnceOpened: Boolean = false,
    /** emoji -> senders. Empty map, never null, so callers never null-check it. */
    val reactions: Map<String, Set<String>> = emptyMap(),
    /**
     * __GROUP_PARITY_2026_09_23__ "Delete for me" (iOS `deleteGroupMessage`, local only). The row
     * is KEPT so a redelivery of the same id is still recognised as a duplicate and does not
     * come back; readers that draw a conversation skip it.
     */
    val hiddenLocally: Boolean = false,
    /** __MENTIONS_2026_09_23__ admitted `@name` targets of a group message (see `MentionWire`). */
    val mentions: List<com.oshi.desktop.group.MentionWire.Mention> = emptyList(),
    /**
     * Append-order sequence within its conversation, assigned by [MessageStore] — the
     * ordering tiebreaker for two messages that claim the identical [sentAtMs]. Not
     * meaningful across conversations, and callers should not set it themselves; [copy]
     * exists so [MessageStore] can stamp it without a mutable var.
     */
    val localSeq: Long = -1,
) {
    init {
        require(id.isNotBlank()) { "message id must not be blank" }
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(senderAddress.isNotBlank()) { "senderAddress must not be blank" }
        require(recipientAddress.isNotBlank()) { "recipientAddress must not be blank" }
        // PLAN.md §4.2's magnitude guard, applied at the storage boundary: a value
        // outside a plausible calendar range is almost certainly an un-normalised
        // epoch-SECONDS or Apple-epoch value that never got converted, not a real date.
        // Catching it here, loudly, beats discovering it as a conversation whose order
        // silently drifted to "the year 58,000."
        require(sentAtMs in MIN_PLAUSIBLE_MS..MAX_PLAUSIBLE_MS) {
            "sentAtMs=$sentAtMs (source=$sentAtSource) is outside a plausible calendar " +
                "range [2000-01-01, 2100-01-01] — an un-converted epoch, most likely"
        }
    }

    /**
     * Same logical message, for dedup purposes: every field that describes the message
     * itself, ignoring [localSeq] (assigned per-store, not part of the message) and
     * [transport] (which transport happened to deliver THIS copy is not part of what the
     * message IS — two transports racing the same `msgId` disagree on that field by
     * definition and must still be recognised as the same message).
     */
    fun isDuplicateOf(other: Message): Boolean =
        id == other.id && conversationId == other.conversationId &&
            senderAddress == other.senderAddress && recipientAddress == other.recipientAddress &&
            fromMe == other.fromMe && content == other.content && mediaType == other.mediaType &&
            mediaRef == other.mediaRef && replyToId == other.replyToId && sentAtMs == other.sentAtMs &&
            sentAtSource == other.sentAtSource && deliveryStatus == other.deliveryStatus &&
            editedAtMs == other.editedAtMs && editedContent == other.editedContent &&
            isDeletedForEveryone == other.isDeletedForEveryone && isViewOnce == other.isViewOnce &&
            viewOnceOpened == other.viewOnceOpened && reactions == other.reactions &&
            mentions == other.mentions &&
            (hiddenLocally || !other.hiddenLocally)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put(MessageStoreConst.F_CONVERSATION, conversationId)
        put("senderAddress", senderAddress)
        put("recipientAddress", recipientAddress)
        put("fromMe", fromMe)
        content?.let { put("content", it) }
        mediaType?.let { put("mediaType", it.wire) }
        mediaRef?.let { put("mediaRef", it) }
        replyToId?.let { put("replyToId", it) }
        put("sentAtMs", sentAtMs)
        put("sentAtSource", sentAtSource.wire)
        put("deliveryStatus", deliveryStatus.wire)
        put("transport", transport)
        editedAtMs?.let { put("editedAtMs", it) }
        editedContent?.let { put("editedContent", it) }
        if (isDeletedForEveryone) put("isDeletedForEveryone", true)
        if (isViewOnce) put("isViewOnce", true)
        if (viewOnceOpened) put("viewOnceOpened", true)
        if (hiddenLocally) put("hiddenLocally", true)
        com.oshi.desktop.group.MentionWire.render(mentions)?.let { put("mentions", JSONArray(it)) }
        if (reactions.isNotEmpty()) {
            put("reactions", JSONObject().apply {
                for ((emoji, senders) in reactions.toSortedMap()) {
                    put(emoji, JSONArray(senders.sorted()))
                }
            })
        }
        put("localSeq", localSeq)
    }

    companion object {
        const val MIN_PLAUSIBLE_MS = 946_684_800_000L  // 2000-01-01T00:00:00Z
        const val MAX_PLAUSIBLE_MS = 4_102_444_800_000L // 2100-01-01T00:00:00Z

        fun fromJson(o: JSONObject): Message = Message(
            id = o.getString("id"),
            conversationId = o.getString(MessageStoreConst.F_CONVERSATION),
            senderAddress = o.getString("senderAddress"),
            recipientAddress = o.getString("recipientAddress"),
            fromMe = o.getBoolean("fromMe"),
            content = if (o.has("content")) o.getString("content") else null,
            mediaType = if (o.has("mediaType")) MediaType.fromWire(o.getString("mediaType")) else null,
            mediaRef = o.optString("mediaRef", "").ifEmpty { null },
            replyToId = o.optString("replyToId", "").ifEmpty { null },
            sentAtMs = o.getLong("sentAtMs"),
            sentAtSource = TimestampSource.fromWire(o.optString("sentAtSource", "")),
            deliveryStatus = DeliveryStatus.fromWire(o.optString("deliveryStatus", "")),
            transport = o.optString("transport", "unknown"),
            editedAtMs = if (o.has("editedAtMs")) o.getLong("editedAtMs") else null,
            editedContent = o.optString("editedContent", "").ifEmpty { null },
            isDeletedForEveryone = o.optBoolean("isDeletedForEveryone", false),
            isViewOnce = o.optBoolean("isViewOnce", false),
            viewOnceOpened = o.optBoolean("viewOnceOpened", false),
            hiddenLocally = o.optBoolean("hiddenLocally", false),
            mentions = com.oshi.desktop.group.MentionWire.parse(o.opt("mentions")),
            reactions = o.optJSONObject("reactions")?.let { r ->
                val out = LinkedHashMap<String, Set<String>>()
                for (emoji in r.keys()) {
                    val arr = r.getJSONArray(emoji)
                    out[emoji] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                }
                out
            } ?: emptyMap(),
            localSeq = o.optLong("localSeq", -1),
        )
    }
}

/** Package-visible constant shared between [Message] and [MessageStore] without exposing it publicly. */
internal object MessageStoreConst {
    const val F_CONVERSATION = "conversationId"
}

/**
 * Which of PLAN.md §4.2's four wire encodings produced this instant, kept for
 * debugging a bad conversion after the fact — never re-derived from this tag, always
 * from [Message.sentAtMs] itself. Unknown values degrade to [UNKNOWN] rather than
 * throwing (PLAN.md §4.8): this is our own local bookkeeping, but the codec still
 * follows the house rule so a future extra source never costs a stored message.
 */
enum class TimestampSource(val wire: String) {
    RELAY_ENVELOPE_MS("relay-v2-ms"),
    LEGACY_IPFS_SECONDS("legacy-ipfs-s"),
    APPLE_EPOCH_SECONDS("apple-epoch-s"),
    ISO8601("iso8601"),
    MESH_LOCAL("mesh-local"),
    LOCAL_CLOCK("local-clock"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String): TimestampSource = entries.firstOrNull { it.wire == raw } ?: UNKNOWN
    }
}

/**
 * Two enums exist upstream and disagree with each other (1:1 `image|video|audio|contact|
 * document` vs group `photo|video|audio|document|contact`, PLAN.md §4.8) — this store
 * does not take sides. It keeps whatever string it was given, mapped to a KNOWN variant
 * where recognised and to [UNKNOWN] otherwise, so an unfamiliar spelling degrades
 * instead of throwing and taking the message with it.
 */
enum class MediaType(val wire: String) {
    IMAGE("image"), VIDEO("video"), AUDIO("audio"), DOCUMENT("document"),
    CONTACT("contact"), LOCATION("location"), UNKNOWN("unknown");

    companion object {
        /**
         * `"photo"` is not a typo and not this client's invention: Android accepts it as
         * an alias for `image` on the inbound path (`MessageRepository.mediaTypeFromV2:4125`)
         * while every emitter in both trees writes `image`. Accepting what a peer might
         * send costs nothing; refusing it would turn one shipped alias into an UNKNOWN
         * attachment with no renderer.
         */
        fun fromWire(raw: String): MediaType =
            // __GIF_PACK_2026_09_23__ `gif` is what iOS (HEAD, `MediaManager.gifWireType`) stamps
            // on a GIF; Android maps it to IMAGE too (`MediaType.fromWireLabel`). The bubble
            // animates from the bytes (`GIF8` header), never from this label.
            if (raw == "photo" || raw == "gif") IMAGE else entries.firstOrNull { it.wire == raw } ?: UNKNOWN

        /**
         * The type a MIME string implies, for the two places the wire does not say.
         *
         * **This is not a convenience default — it is the classification both phones
         * already perform, and getting it wrong is visible to the user.** Android reads
         * the `mediaType` field FIRST and only falls back to the MIME
         * (`MessageRepository.mediaTypeFromV2:4123-4139`); iOS does the same through
         * `MediaManager.MediaType(rawValue:)` with `v2Mime` behind it. So a sender that
         * labels a JPEG `document` gets a document row on the receiver no matter how
         * correct its MIME is — the MIME is never consulted once the field is present.
         * That is exactly how this client shipped every photo, video and voice note it
         * ever sent, and the receiving phone drew a file attachment for each one.
         *
         * The order matters: `vcard` is checked before the `else`, because a contact card
         * is `text/vcard` and must not fall through to DOCUMENT — [MediaType.CONTACT] is
         * what makes the receiver decode it as a contact instead of an opaque blob.
         */
        fun forMime(mime: String?): MediaType = when {
            mime == null -> DOCUMENT
            mime.startsWith("image/") -> IMAGE
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("audio/") -> AUDIO
            mime.contains("vcard") -> CONTACT
            else -> DOCUMENT
        }
    }
}

/**
 * Mirrors iOS `DeliveryStatus` (`MessageManager.swift:8090`) field for field, including
 * its monotonic ordering rule: a receipt may only move a message FORWARD, and `FAILED`
 * is terminal. Copied here (not shared source) because it is pure local state-machine
 * logic with no bytes on the wire — there is nothing to diverge from by reimplementing it.
 */
enum class DeliveryStatus(val wire: String, val rank: Int) {
    PENDING("pending", 0),
    SENT("sent", 1),
    DELIVERED("delivered", 2),
    READ("read", 3),
    FAILED("failed", -1);

    /**
     * Advance to [target] iff that is strictly forward and this status is not terminal;
     * otherwise return this unchanged. Never downgrades, never un-fails — the same
     * contract as iOS's `advanced(to:)`, so a stale or reordered receipt can only ever
     * move a message closer to "read," never back toward "sent."
     */
    fun advanced(to: DeliveryStatus): DeliveryStatus =
        if (this == FAILED || to.rank <= rank) this else to

    companion object {
        /** Unknown values fall back to DELIVERED — the closest true statement, matching iOS. */
        fun fromWire(raw: String): DeliveryStatus = entries.firstOrNull { it.wire == raw } ?: DELIVERED
    }
}
