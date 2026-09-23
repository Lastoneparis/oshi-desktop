package com.oshi.desktop.devsync

import com.oshi.desktop.app.GroupStore
import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.store.ContactStore
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.DesktopExportV2
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.ExportV2
import com.oshi.desktop.store.MediaVault
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.MessageStore
import com.oshi.messenger.network.v2.devsync.ApplyResult
import com.oshi.messenger.network.v2.devsync.DevSyncCrypto
import com.oshi.messenger.network.v2.devsync.DevSyncStateStore
import com.oshi.messenger.network.v2.devsync.DevSyncStore
import com.oshi.messenger.network.v2.devsync.MediaSource
import com.oshi.messenger.network.v2.devsync.SyncAvatar
import com.oshi.messenger.network.v2.devsync.SyncContact
import com.oshi.messenger.network.v2.devsync.SyncConversation
import com.oshi.messenger.network.v2.devsync.SyncEdited
import com.oshi.messenger.network.v2.devsync.SyncGroup
import com.oshi.messenger.network.v2.devsync.SyncGroupKey
import com.oshi.messenger.network.v2.devsync.SyncJson
import com.oshi.messenger.network.v2.devsync.SyncMedia
import com.oshi.messenger.network.v2.devsync.SyncMessage
import com.oshi.messenger.network.v2.devsync.SyncProfile
import com.oshi.messenger.network.v2.devsync.SyncReadState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * __DEVSYNC_DIRECT_2026_09_22__ [DevSyncStore] over this client's stores: [MessageStore] (every
 * conversation, `desktop.local` threads included — forwarded only desktop↔desktop by the core),
 * [GroupStore], [ContactStore], attachments sealed by [MediaVault].
 *
 * Row mapping reuses [DesktopExportV2] (the export v2 mapper, design §7), with a media reader that
 * never loads bytes (sync sends media by reference). Writes are imports: no receipt, no
 * notification, no send.
 *
 * GROUP KEYS: the desktop keeps no group secret of its own (see `group/GroupModels.kt`), but the
 * owner decision is that group keys travel between the account's devices — so the keys another
 * device sends are kept, sealed, in the devsync state and handed on unchanged: a desktop never
 * becomes the device that drops them.
 */
class DesktopDevSyncStore(
    private val selfAddress: () -> String,
    private val messages: MessageStore,
    private val groups: GroupStore,
    private val contacts: ContactStore,
    private val mediaDir: File,
    private val vault: MediaVault?,
    private val state: DevSyncStateStore,
    /** __DEVSYNC_PROFILE_2026_09_23__ this account's nickname (the only profile field the desktop models). */
    private val ownNickname: () -> String? = { null },
    /** Adopt a nickname set on another device, WITHOUT telling the contacts (the editing device did). */
    private val adoptNickname: (String) -> Unit = {},
    /** __DEVSYNC_READ_STATE_2026_09_23__ shared with [DesktopDevSync.noteRead] (the window's "opened" signal). */
    private val readMarks: DesktopReadMarks = DesktopReadMarks(state),
    /** A read state from another device moved: native conversation id, readUpTo (the window clears badges). */
    private val onRemoteRead: (String, Long) -> Unit = { _, _ -> },
) : DevSyncStore {

    private val v2 get() = DesktopExportV2(
        selfAddress = selfAddress(),
        groupName = { gid -> groups.get(gid)?.name },
        groupExists = { gid -> groups.get(gid) != null },
        mediaReader = { null },
    )

    /** sync conversation id → the native conversation ids that map to it. */
    private val natives = ConcurrentHashMap<String, MutableSet<String>>()
    private data class Meta(val length: Long, val sha: String)
    private val metaCache = ConcurrentHashMap<String, Meta>()     // file path → meta
    private val shaIndex = ConcurrentHashMap<String, String>()     // sha → file path, or "#avatar:<gid>"

    private fun syncIdOf(nativeCid: String): SyncConversation = when {
        ExportV2.isGroupId(nativeCid) -> SyncConversation.group(nativeCid, groups.get(nativeCid)?.name)
        isKey(nativeCid) -> SyncConversation.direct(nativeCid)
        else -> SyncConversation.desktopLocal(nativeCid)
    }

    private fun isKey(s: String) = s.length in 43..44 &&
        runCatching { Base64.getDecoder().decode(ExportV2.canonicalKey(s)).size == 32 }.getOrDefault(false)

    override fun conversations(): List<SyncConversation> {
        val out = LinkedHashMap<String, SyncConversation>()
        natives.clear()
        for (s in messages.conversations()) {
            val c = syncIdOf(s.conversationId)
            out[c.id] = c
            natives.getOrPut(c.id) { HashSet() }.add(s.conversationId)
        }
        for (g in groups.all()) {
            val c = SyncConversation.group(g.groupId, g.name)
            out.putIfAbsent(c.id, c)
            natives.getOrPut(c.id) { HashSet() }.add(g.groupId)
        }
        return out.values.toList()
    }

    private fun nativeIds(convId: String): Set<String> {
        if (natives.isEmpty()) conversations()
        return natives[convId] ?: when {
            convId.startsWith("direct:") -> setOf(convId.removePrefix("direct:"))
            convId.startsWith("group:") -> setOf(convId.removePrefix("group:"))
            convId.startsWith("desktop:") -> setOf(convId.removePrefix("desktop:"))
            else -> emptySet()
        }
    }

    private fun nativeRows(convId: String): List<Message> =
        nativeIds(convId).flatMap { messages.messages(it) }.filter { !ExportV2.isControl(it.content) }

    override fun messages(conversationId: String): List<SyncMessage> {
        val rows = nativeRows(conversationId)
        if (rows.isEmpty()) return emptyList()
        val built = v2.build(rows, emptyList(), 0L, mediaBudget = 0)
        val byId = rows.associateBy { it.id }
        return built.payload.messages.map { x -> toSync(x, conversationId, byId[x.id]) }
    }

    override fun messagesByIds(conversationId: String, ids: Collection<String>): List<SyncMessage> {
        val want = ids.map { it.lowercase() }.toSet()
        return messages(conversationId).filter { it.id.lowercase() in want }
    }

    private fun toSync(x: ExportV2.Msg, conv: String, native: Message?): SyncMessage {
        val md = x.media?.let { m ->
            val file = native?.mediaRef?.let(::File)?.takeIf { it.isFile }
            val meta = if (file != null && !x.viewOnce && !x.deleted) meta(file) else null
            meta?.let { shaIndex[it.sha] = file!!.absolutePath }
            when {
                x.viewOnce -> SyncMedia(m.type, m.fileName, m.mime, omitted = "viewOnce")
                meta == null -> SyncMedia(m.type, m.fileName, m.mime, omitted = "unavailable")
                else -> SyncMedia(m.type, m.fileName, m.mime, size = meta.length, sha256 = meta.sha)
            }
        }
        return SyncMessage(
            id = x.id, conversation = conv, outgoing = x.outgoing, senderPublicKey = x.senderPublicKey,
            senderName = x.senderName, timestampMs = x.timestampMs, text = x.text, status = x.status, read = x.read,
            edited = x.editedText?.let { SyncEdited(it, x.editedAtMs) }, deleted = x.deleted, replyToId = x.replyToId,
            viewOnce = x.viewOnce, media = md, reactions = x.reactions,
        )
    }

    // THIS client's vault, not the process-wide one: two clients in one process (tests, two
    // profiles) hold different media keys.
    private fun openPlain(file: File): InputStream = vault?.openStream(file) ?: file.inputStream()
    private fun plainLength(file: File): Long = MediaVault.lengthOf(file) ?: file.length()

    private fun meta(file: File): Meta? = runCatching {
        val key = file.absolutePath
        val len = plainLength(file)
        metaCache[key]?.takeIf { it.length == len }?.let { return it }
        val md = MessageDigest.getInstance("SHA-256")
        openPlain(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        Meta(len, DevSyncCrypto.hex(md.digest())).also { metaCache[key] = it }
    }.getOrNull()

    // ------------------------------------------------------------------ writes

    override fun apply(conversation: SyncConversation, inserts: List<SyncMessage>, updates: List<SyncMessage>): ApplyResult {
        if (conversation.kind == SyncConversation.KIND_GROUP && groups.get(conversation.groupId ?: "") == null) {
            return ApplyResult(skippedNoGroup = inserts.size + updates.size)
        }
        var ins = 0
        if (inserts.isNotEmpty()) {
            val convJson = ExportV2.Conversation(conversation.id, conversation.kind, conversation.peerPublicKey,
                conversation.groupId, conversation.groupName, conversation.nativeId)
            val payload = ExportV2.Payload("sync", 0L, null, emptyList(), listOf(convJson), inserts.map(::toExport))
            val mapped = v2.toNative(payload)
            val existing = nativeIds(conversation.id).flatMap { cid -> messages.messages(cid).map { it.id.lowercase() } }.toSet()
            val fresh = mapped.messages.filter { it.id.lowercase() !in existing }
                .map { it.copy(transport = "devsync") }
            ins = messages.importMissing(fresh).inserted
            if (conversation.kind == SyncConversation.KIND_DIRECT && conversation.peerPublicKey != null &&
                contacts.get(conversation.peerPublicKey!!) == null && fresh.isNotEmpty()) {
                contacts.seen(conversation.peerPublicKey!!, fresh.maxOf { it.sentAtMs })
            }
            if (fresh.isNotEmpty()) natives.getOrPut(conversation.id) { HashSet() }.add(fresh.first().conversationId)
        }
        var upd = 0
        if (updates.isNotEmpty()) {
            val rows = nativeRows(conversation.id).associateBy { it.id.lowercase() }
            for (m in updates) {
                val row = rows[m.id.lowercase()] ?: continue
                val deleted = row.isDeletedForEveryone || m.deleted
                val status = DeliveryStatus.fromWire(m.status)
                val next = row.copy(
                    deliveryStatus = if (status.rank > row.deliveryStatus.rank) status else row.deliveryStatus,
                    editedContent = if (deleted) null else m.edited?.text ?: row.editedContent,
                    editedAtMs = if (deleted) row.editedAtMs else m.edited?.atMs ?: row.editedAtMs,
                    isDeletedForEveryone = deleted,
                    content = if (deleted) null else row.content,
                    reactions = if (deleted) emptyMap() else mergeReactions(row.reactions, m.reactions),
                    mediaRef = if (deleted) null else row.mediaRef,
                )
                if (!next.isDuplicateOf(row)) {
                    messages.append(next)
                    // §8.3: deleted anywhere, deleted everywhere, and local media is erased.
                    if (deleted && !row.isDeletedForEveryone) row.mediaRef?.let { runCatching { File(it).delete() } }
                    upd++
                }
            }
        }
        return ApplyResult(inserted = ins, updated = upd)
    }

    private fun mergeReactions(local: Map<String, Set<String>>, remote: Map<String, List<String>>): Map<String, Set<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        for ((e, ks) in local) out.getOrPut(e) { LinkedHashSet() }.addAll(ks)
        for ((e, ks) in remote) out.getOrPut(e) { LinkedHashSet() }.addAll(ks)
        return out
    }

    private fun toExport(m: SyncMessage) = ExportV2.Msg(
        id = m.id, conversation = m.conversation, outgoing = m.outgoing, senderPublicKey = m.senderPublicKey,
        senderName = m.senderName, timestampMs = m.timestampMs, text = m.text, status = m.status, read = m.read,
        editedText = m.edited?.text, editedAtMs = m.edited?.atMs, deleted = m.deleted, replyToId = m.replyToId,
        viewOnce = m.viewOnce,
        media = m.media?.let { ExportV2.Media(it.type, it.fileName, it.mime, null, it.omitted ?: "unavailable") },
        reactions = m.reactions,
    )

    // ------------------------------------------------------------------ media

    override fun openMedia(sha256: String): MediaSource? {
        if (!shaIndex.containsKey(sha256)) {
            for (c in conversations()) messages(c.id)
            groups()
        }
        val where = shaIndex[sha256] ?: return null
        if (where.startsWith(AVATAR)) {
            val bytes = groups.get(where.removePrefix(AVATAR))?.groupPictureBase64
                ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
            return object : MediaSource {
                override val size = bytes.size.toLong()
                override fun open(): InputStream = bytes.inputStream()
            }
        }
        val file = File(where).takeIf { it.isFile } ?: return null
        return object : MediaSource {
            override val size = plainLength(file)
            override fun open(): InputStream = openPlain(file)
        }
    }

    override fun attachMedia(conversationId: String, messageId: String, sha256: String, file: File): Boolean {
        if (messageId == DevSyncStore.AVATAR_REF) {
            val g = groups.get(conversationId.removePrefix("group:")) ?: return false
            if (file.length() > MAX_AVATAR_BYTES) return false
            groups.put(g.copy(groupPictureBase64 = Base64.getEncoder().encodeToString(file.readBytes())))
            shaIndex[sha256] = AVATAR + g.groupId
            return true
        }
        val row = nativeRows(conversationId).firstOrNull { it.id.equals(messageId, ignoreCase = true) } ?: return false
        DesktopPaths.ensurePrivateDir(mediaDir)
        val safe = (row.content ?: "file").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "file" }
        val dest = File(mediaDir, "${row.id.take(8)}-sync-$safe")
        if (vault != null) {
            vault.sealingStream(dest).use { out -> file.inputStream().use { it.copyTo(out) } }
        } else {
            file.copyTo(dest, overwrite = true)
        }
        DesktopPaths.makePrivate(dest)
        messages.append(row.copy(mediaRef = dest.absolutePath))
        metaCache[dest.absolutePath] = Meta(file.length(), sha256)
        shaIndex[sha256] = dest.absolutePath
        return true
    }

    // ------------------------------------------------------------------ contacts

    override fun contacts(): List<SyncContact> = contacts.all().mapNotNull { c ->
        if (!isKey(c.address)) return@mapNotNull null
        val pk = ExportV2.canonicalKey(c.address)
        if (pk == ExportV2.canonicalKey(selfAddress())) return@mapNotNull null
        // No per-field timestamps on this client: legacy data, gap-fill only (design §8.5).
        SyncContact(pk, alias = c.displayName?.takeIf { it.isNotBlank() }, blocked = c.blocked.takeIf { it },
            verified = (c.verification == ContactStore.VerificationState.VERIFIED).takeIf { it })
    }

    override fun putContact(contact: SyncContact) {
        val pk = contact.publicKey
        val existing = contacts.get(pk) ?: contacts.seen(pk, System.currentTimeMillis(), displayNameHint = contact.alias)
        if (contact.alias != null && contact.alias != existing.displayName) contacts.setDisplayName(pk, contact.alias)
        when (contact.blocked) {
            true -> if (!existing.blocked) contacts.block(pk)
            false -> if (existing.blocked) contacts.unblock(pk)
            null -> Unit
        }
        if (contact.verified == true && existing.verification != ContactStore.VerificationState.VERIFIED) {
            contacts.setVerification(pk, ContactStore.VerificationState.VERIFIED)
        }
    }

    // ------------------------------------------------------------------ groups (D11)

    private fun heldKeys(): MutableMap<String, List<SyncGroupKey>> {
        val o = runCatching { JSONObject(state.read(KEYS_FILE) ?: "{}") }.getOrDefault(JSONObject())
        val out = HashMap<String, List<SyncGroupKey>>()
        for (gid in o.keys()) {
            val a = o.optJSONArray(gid) ?: continue
            out[gid] = (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { k -> SyncGroupKey(k.optString("kind"), k.optLong("version"), k.optString("key")) }
            }
        }
        return out
    }

    private fun saveKeys(map: Map<String, List<SyncGroupKey>>) {
        val o = JSONObject()
        for ((gid, ks) in map) o.put(gid, JSONArray().apply { ks.forEach { put(it.toJson()) } })
        state.write(KEYS_FILE, o.toString())
    }

    override fun groups(): List<SyncGroup> {
        val keys = heldKeys()
        val live = liveGroups(keys)
        val present = live.map { it.groupId }.toSet()
        // __DEVSYNC_LEAVE_2026_09_23__ a group left on this account keeps travelling as
        // {left:true, leftAt} (sticky, earliest leftAt, §8.4) with the keys it held (never dropped);
        // no members at epoch 0 (the union keeps the peer's), no nameAt (never overrides a name).
        val joined = joinedGroups()
        val left = leftGroups().filterKeys { it !in present }.map { (gid, l) ->
            SyncGroup(groupId = gid, name = l.second, left = true, leftAtMs = l.first, joinedAtMs = joined[gid],
                keys = keys[gid] ?: emptyList(), updatedAtMs = l.first)
        }
        return live + left
    }

    private fun liveGroups(keys: Map<String, List<SyncGroupKey>>): List<SyncGroup> {
        val joined = joinedGroups()
        return groups.all().map { g ->
            val avatar = g.groupPictureBase64?.let { b64 ->
                runCatching { Base64.getDecoder().decode(b64) }.getOrNull()?.let { bytes ->
                    val sha = DevSyncCrypto.hex(DevSyncCrypto.sha256(bytes))
                    shaIndex[sha] = AVATAR + g.groupId
                    SyncAvatar(sha, bytes.size.toLong(), "image/jpeg")
                }
            }
            SyncGroup(
                groupId = SyncJson.canonicalGroupId(g.groupId),
                name = g.name,
                nameAtMs = g.lastActivityUnixMillis,
                avatar = avatar,
                avatarAtMs = if (avatar != null) g.groupPictureUpdatedAtUnixMillis ?: g.lastActivityUnixMillis else null,
                members = g.memberKeys.map(ExportV2::canonicalKey),
                admins = g.adminKeys.map(ExportV2::canonicalKey),
                epoch = (g.stateVersion ?: 0).toLong(),
                joinedAtMs = joined[SyncJson.canonicalGroupId(g.groupId)],
                keys = keys[SyncJson.canonicalGroupId(g.groupId)] ?: emptyList(),
                updatedAtMs = g.lastActivityUnixMillis,
                type = g.type.raw,
                creator = ExportV2.canonicalKey(g.adminPublicKey),
                avatarEmoji = g.avatar,
                pinnedMessageId = g.pinnedMessageId,
                pinnedBy = g.pinnedBy,
            )
        }
    }

    override fun putGroup(group: SyncGroup) {
        if (group.keys.isNotEmpty()) {
            val all = heldKeys()
            all[SyncJson.canonicalGroupId(group.groupId)] = group.keys
            saveKeys(all)
        }
        // __DEVSYNC_LEAVE_2026_09_23__ left on another device of this account: forget it here too,
        // the way leaving does locally (GroupStore.delete), silently — the leaving device already
        // told the members. Sticky: a later non-left definition never brings it back...
        val gid = SyncJson.canonicalGroupId(group.groupId)
        // __DEVSYNC_REJOIN_2026_09_23__ ...unless the user REJOINED on another device (§8.4 rejoin
        // rule): the merged record carries a joinedAt newer than this desktop's leave. The desktop
        // has no join action of its own; it keeps the latest joinedAt and hands it on.
        group.joinedAtMs?.let { recordJoin(gid, it) }
        val myLeave = leftGroups()[gid]
        if (!group.left && myLeave != null && group.joinedAtMs != null && group.joinedAtMs > myLeave.first) {
            forgetLeave(gid)
        } else if (group.left || myLeave != null) {
            if (group.left) recordLeave(gid, group.leftAtMs ?: System.currentTimeMillis(), group.name)
            groups.delete(gid)
            return
        }
        val existing = groups.get(group.groupId)
        val admins = group.admins.toSet()
        val joined = existing?.members?.associate { ExportV2.canonicalKey(it.publicKey) to it } ?: emptyMap()
        val def = GroupDefinition(
            groupId = existing?.groupId ?: group.groupId,
            name = group.name,
            type = GroupType.coerceForWire(group.type),
            adminPublicKey = group.creator ?: group.admins.firstOrNull() ?: existing?.adminPublicKey ?: selfAddress(),
            members = group.members.map { pk ->
                GroupMember(pk, alias = joined[pk]?.alias, joinedAtUnixMillis = joined[pk]?.joinedAtUnixMillis ?: group.updatedAtMs, isAdmin = pk in admins)
            },
            createdAtUnixMillis = existing?.createdAtUnixMillis ?: group.updatedAtMs,
            lastActivityUnixMillis = maxOf(existing?.lastActivityUnixMillis ?: 0L, group.updatedAtMs),
            groupPictureBase64 = existing?.groupPictureBase64,
            groupPictureUpdatedAtUnixMillis = group.avatarAtMs ?: existing?.groupPictureUpdatedAtUnixMillis,
            groupPictureUpdatedBy = existing?.groupPictureUpdatedBy,
            groupWallpaperBase64 = existing?.groupWallpaperBase64,
            groupWallpaperUpdatedAtUnixMillis = existing?.groupWallpaperUpdatedAtUnixMillis,
            groupWallpaperUpdatedBy = existing?.groupWallpaperUpdatedBy,
            isMuted = existing?.isMuted ?: false,
            pinnedMessageId = group.pinnedMessageId,
            pinnedBy = group.pinnedBy,
            avatar = group.avatarEmoji ?: existing?.avatar,
            stateVersion = if (group.epoch > 0) group.epoch.toInt() else existing?.stateVersion,
        )
        groups.put(def)
    }

    /** canonical groupId → (leftAt, name). */
    private fun leftGroups(): Map<String, Pair<Long, String>> {
        val o = runCatching { JSONObject(state.read(LEFT_FILE) ?: "{}") }.getOrDefault(JSONObject())
        val out = LinkedHashMap<String, Pair<Long, String>>()
        for (gid in o.keys()) o.optJSONObject(gid)?.let { out[gid] = it.optLong("leftAt") to it.optString("name", "") }
        return out
    }

    /** The latest leftAt that counts wins (§8.4 rejoin rule, __DEVSYNC_REJOIN_2026_09_23__). */
    @Synchronized
    private fun recordLeave(gid: String, leftAtMs: Long, name: String) {
        val o = runCatching { JSONObject(state.read(LEFT_FILE) ?: "{}") }.getOrDefault(JSONObject())
        val prev = o.optJSONObject(gid)
        val at = prev?.optLong("leftAt")?.let { maxOf(it, leftAtMs) } ?: leftAtMs
        o.put(gid, JSONObject().put("leftAt", at).put("name", prev?.optString("name")?.takeIf { it.isNotEmpty() } ?: name))
        state.write(LEFT_FILE, o.toString())
    }

    @Synchronized
    private fun forgetLeave(gid: String) {
        val o = runCatching { JSONObject(state.read(LEFT_FILE) ?: "{}") }.getOrDefault(JSONObject())
        if (o.remove(gid) != null) state.write(LEFT_FILE, o.toString())
    }

    /** canonical groupId → the latest (re)join of this account known here (§8.4 rejoin rule). */
    private fun joinedGroups(): Map<String, Long> {
        val o = runCatching { JSONObject(state.read(JOINED_FILE) ?: "{}") }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.optLong(it) }
    }

    @Synchronized
    private fun recordJoin(gid: String, joinedAtMs: Long) {
        val o = runCatching { JSONObject(state.read(JOINED_FILE) ?: "{}") }.getOrDefault(JSONObject())
        if (o.optLong(gid, -1L) >= joinedAtMs) return
        state.write(JOINED_FILE, o.put(gid, joinedAtMs).toString())
    }

    // ------------------------------------------------------------------ profile (§8.6)
    //
    // __DEVSYNC_PROFILE_2026_09_23__ The desktop models the nickname only. Photo, links and anything
    // else another device sends ride in `extra` and are handed on unchanged, so a nickname edited
    // here (a newer updatedAt) never strips the phone's picture. A local edit is noticed by a
    // fingerprint ([refreshProfileStamp], called from the poll tick); the first snapshot is legacy
    // (updatedAt 0: any stamped value from another device wins).

    private fun profileState(): JSONObject = runCatching { JSONObject(state.read(PROFILE_FILE) ?: "{}") }.getOrDefault(JSONObject())

    /** Returns true when a local nickname edit was just detected. */
    @Synchronized
    fun refreshProfileStamp(): Boolean {
        val st = profileState()
        val name = ownNickname().orEmpty()
        if (st.has("fp") && st.optString("fp") == name) return false
        val edited = st.has("fp")
        st.put("fp", name)
        st.put("updatedAt", if (edited) System.currentTimeMillis() else st.optLong("updatedAt", 0L))
        state.write(PROFILE_FILE, st.toString())
        return edited
    }

    @Synchronized
    override fun profile(): SyncProfile? {
        refreshProfileStamp()
        val st = profileState()
        val extra = st.optJSONObject("extra") ?: JSONObject()
        val name = ownNickname()
        if (name == null && extra.length() == 0) return null
        return SyncProfile(name, st.optLong("updatedAt", 0L), JSONObject(extra.toString()))
    }

    /** Called by the core only when the other device's profile is strictly newer. */
    @Synchronized
    override fun putProfile(profile: SyncProfile) {
        profile.name?.takeIf { it.isNotBlank() }?.let { runCatching { adoptNickname(it) } }
        val st = profileState()
        st.put("fp", ownNickname().orEmpty())
        st.put("updatedAt", profile.updatedAtMs)
        st.put("extra", JSONObject(profile.extra.toString()))
        state.write(PROFILE_FILE, st.toString())
    }

    // ------------------------------------------------------------------ read state (§8.7)

    override fun readStates(): List<SyncReadState> =
        readMarks.all().map { (conv, m) -> SyncReadState(conv, m.readUpToMs, m.atMs) }

    /** Max of both; never a receipt. The window recounts its unread badge for that conversation. */
    override fun putReadState(state: SyncReadState) {
        val m = readMarks.merge(state.conv, state.readUpToMs, state.atMs)
        for (native in nativeIds(state.conv)) runCatching { onRemoteRead(native, m.readUpToMs) }
    }

    companion object {
        private const val AVATAR = "#avatar:"
        private const val KEYS_FILE = "devsync-group-keys"
        private const val LEFT_FILE = "devsync-left-groups"
        private const val JOINED_FILE = "devsync-joined-groups"

        /**
         * __DEVSYNC_REJOIN_2026_09_23__ True when [groupId] was left on this account (here or synced)
         * and not rejoined since: the desktop has no other tombstone, so its group ingest asks this
         * before materialising an unknown group a member re-shares (a roster that still names us).
         */
        fun leftOnThisAccount(state: DevSyncStateStore, groupId: String): Boolean =
            runCatching { JSONObject(state.read(LEFT_FILE) ?: "{}").has(SyncJson.canonicalGroupId(groupId)) }.getOrDefault(false)

        /** __GROUP_E2E_V2_2026_09_23__ A leave made on THIS desktop (same record as a synced one). */
        @Synchronized
        fun recordLocalLeave(state: DevSyncStateStore, groupId: String, leftAtMs: Long, name: String) {
            val gid = SyncJson.canonicalGroupId(groupId)
            val o = runCatching { JSONObject(state.read(LEFT_FILE) ?: "{}") }.getOrDefault(JSONObject())
            val prev = o.optJSONObject(gid)
            val at = prev?.optLong("leftAt")?.let { maxOf(it, leftAtMs) } ?: leftAtMs
            o.put(gid, JSONObject().put("leftAt", at).put("name", prev?.optString("name")?.takeIf { it.isNotEmpty() } ?: name))
            state.write(LEFT_FILE, o.toString())
        }
        private const val PROFILE_FILE = "devsync-profile"
        private const val MAX_AVATAR_BYTES = 2L * 1024 * 1024
    }
}

/**
 * __DEVSYNC_READ_STATE_2026_09_23__ READ_STATE marks per sync conversation id (`direct:<key>`,
 * `group:<UUID>`), sealed in the devsync state. The desktop keeps no per-message read flag: a mark
 * moves when the user opens a conversation ([DesktopDevSync.noteRead]) or when another device's
 * mark is newer (max of both, §8.7), so the desktop relays read state between phones too.
 */
class DesktopReadMarks(private val state: DevSyncStateStore) {
    data class Mark(val readUpToMs: Long, val atMs: Long)

    @Synchronized
    fun all(): Map<String, Mark> {
        val o = runCatching { JSONObject(state.read(FILE) ?: "{}") }.getOrDefault(JSONObject())
        val out = LinkedHashMap<String, Mark>()
        for (k in o.keys()) o.optJSONObject(k)?.let { out[k] = Mark(it.optLong("r"), it.optLong("a")) }
        return out
    }

    /** readUpTo = max, at = max. Returns the stored mark. */
    @Synchronized
    fun merge(conv: String, readUpToMs: Long, atMs: Long): Mark {
        val cur = all().toMutableMap()
        val prev = cur[conv]
        val next = Mark(maxOf(prev?.readUpToMs ?: 0L, readUpToMs), maxOf(prev?.atMs ?: 0L, atMs))
        if (next != prev) {
            cur[conv] = next
            val o = JSONObject()
            for ((k, v) in cur) o.put(k, JSONObject().put("r", v.readUpToMs).put("a", v.atMs))
            state.write(FILE, o.toString())
        }
        return next
    }

    /** A local read up to [readUpToMs]: only ever moves forward; `at` = now when it does. */
    fun noteLocal(conv: String, readUpToMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val prev = all()[conv]
        if (prev != null && prev.readUpToMs >= readUpToMs) return false
        merge(conv, readUpToMs, now)
        return true
    }

    companion object { private const val FILE = "devsync-read-state" }
}
