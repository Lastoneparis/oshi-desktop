package com.oshi.desktop.app

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.store.ContactStore
import com.oshi.desktop.store.DesktopExportV2
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.EncryptedMessageExport
import com.oshi.desktop.store.ExportV2
import com.oshi.desktop.store.MessageStore
import java.io.File
import java.time.Instant

/**
 * __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Export and import of this account's message history
 * as an ENCRYPTED `.oshiexport` file (see [EncryptedMessageExport] for the container).
 *
 * One owner for both surfaces — the window's More pane and the REPL's `/export` / `/import` —
 * so the rules cannot drift between them:
 *
 *  - the export holds every conversation in the store (blocked peers included: it is a backup,
 *    not a view), sealed under the ACCOUNT key, never the machine-bound history key;
 *  - nothing plaintext is ever written to disk, not even a temp file;
 *  - an import is additive: messages already present (same conversation, same id) are kept
 *    as they are locally, and nothing is replayed — no receipts, no notifications, no sends.
 */
class MessageBackup(
    private val identity: DesktopIdentity,
    private val store: MessageStore,
    /** __EXPORT_V2_INTEROP_2026_09_22__ Aliases go into the export and missing ones come back. */
    private val contacts: ContactStore? = null,
    /** Group names for the export, and "do we hold this group" for the import. */
    private val groups: GroupStore? = null,
    /** Where imported media bytes are written; null = media is not restored. */
    private val mediaDir: File? = null,
) {

    data class ExportResult(
        val file: File,
        val messages: Int,
        val unreadableConversations: Int,
        val mediaSkipped: Int = 0,
    )

    data class ImportResult(
        val imported: Int,
        val alreadyPresent: Int,
        val invalid: Int,
        /** Group messages whose group is not on this machine (rejoin it, then import again). */
        val skippedGroups: Int = 0,
        /** Messages in a conversation kind this build does not know (e.g. newer writer). */
        val skippedUnknown: Int = 0,
    )

    /** Client wiring: the stores the window and the REPL both hold. */
    constructor(client: OshiClient) : this(client.identity, client.messages, client.contacts, client.groups, client.mediaDir)

    private val v2 = DesktopExportV2(
        selfAddress = identity.userKey,
        groupName = { gid -> groups?.get(gid)?.name },
        groupExists = { gid -> groups == null || groups.get(gid) != null },
        mediaWriter = mediaDir?.let { dir ->
            { id, fileName, bytes ->
                val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "file" }
                DesktopPaths.ensurePrivateDir(dir)
                val f = File(dir, "${id.take(8)}-import-$safe")
                f.writeBytes(bytes)
                DesktopPaths.makePrivate(f)
                f.absolutePath
            }
        },
    )

    /** Writes the platform-neutral VERSION 2 payload (readable on iOS and Android too). */
    fun export(target: File, now: Instant = Instant.now()): ExportResult {
        val file = EncryptedMessageExport.withExtension(target)
        val snapshot = store.snapshot()
        val peers = snapshot.messages.map { ExportV2.canonicalKey(it.conversationId) }.toSet()
        val aliases = contacts?.all().orEmpty()
            .filter { ExportV2.canonicalKey(it.address) in peers }
            .map { ExportV2.Contact(ExportV2.canonicalKey(it.address), it.displayName) }
        val built = v2.build(snapshot.messages, aliases, now.toEpochMilli())
        EncryptedMessageExport.writeSealed(identity.identity.priv, ExportV2.encode(built.payload), file)
        return ExportResult(file, snapshot.messages.size, snapshot.unreadableFiles, built.mediaSkipped)
    }

    /**
     * Reads v2 from any platform and v1 from a desktop.
     * @throws com.oshi.desktop.store.MessageExportException with a user-facing reason.
     */
    fun import(source: File): ImportResult =
        when (val opened = EncryptedMessageExport.readAny(identity.identity.priv, source)) {
            is EncryptedMessageExport.Opened.V1 -> {
                val counts = store.importMissing(opened.parsed.messages)
                ImportResult(counts.inserted, counts.alreadyPresent, opened.parsed.invalid)
            }
            is EncryptedMessageExport.Opened.V2 -> importV2(opened.parsed)
        }

    private fun importV2(parsed: ExportV2.Parsed): ImportResult {
        val mapped = v2.toNative(parsed.payload)
        // Ids compare case-insensitively: an iPhone upper-cases the UUIDs it round-trips.
        val fresh = ArrayList<com.oshi.desktop.store.Message>()
        var present = 0
        val known = HashMap<String, MutableSet<String>>()
        for (m in mapped.messages) {
            val ids = known.getOrPut(m.conversationId) {
                store.messages(m.conversationId).mapTo(HashSet()) { it.id.lowercase() }
            }
            if (!ids.add(m.id.lowercase())) { present++; continue }
            fresh += m
        }
        val counts = store.importMissing(fresh)
        // A conversation needs its contact row to carry a name; never overwrite a chosen one.
        contacts?.let { cs ->
            val aliases = parsed.payload.contacts.associate { it.publicKey to it.alias }
            for (m in fresh) {
                if (!ExportV2.isGroupId(m.conversationId) && m.conversationId.length == 44 && cs.get(m.conversationId) == null) {
                    cs.seen(m.conversationId, m.sentAtMs, displayNameHint = aliases[m.conversationId])
                }
            }
        }
        return ImportResult(
            imported = counts.inserted,
            alreadyPresent = present + counts.alreadyPresent,
            invalid = parsed.invalid + mapped.invalid,
            skippedGroups = mapped.skippedGroups,
            skippedUnknown = parsed.skippedUnknownKind,
        )
    }
}
