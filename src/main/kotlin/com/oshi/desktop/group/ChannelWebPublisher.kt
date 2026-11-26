package com.oshi.desktop.group

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * __CHANNEL_WEB_2026_11_26__ Runs the public-web outbox of PUBLIC_CHANNEL_WEB_SPEC §9: signed,
 * re-signed retries with backoff, coalescing, unknown-media re-upload, deletes/disables that never
 * give up, the profile/avatar re-PUT, the daily `GET …/admin` n-map, and the reader's on-demand
 * `n` lookup. Port of iOS `ChannelWebPublisher` (5e3982caa).
 *
 * Never on the E2E path: the normal channel send has already happened when a job is queued, and
 * a failure here only delays the web copy. Blocking I/O on its own single daemon thread; the
 * state is guarded by [lock], never held across a network call, and persisted (sealed) after
 * every change, so an interrupted job simply runs again (every write is idempotent server-side).
 */
class ChannelWebPublisher(
    private val transport: Transport,
    private val source: Source,
    private val persistence: Persistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Double = { Math.random() },
    private val log: (String) -> Unit = {},
) {
    /** One HTTP exchange. `code` < 100 = no answer (network). Signed calls are re-signed on every call. */
    fun interface Transport {
        fun send(method: String, path: String, query: String?, body: ByteArray?, contentType: String?, signed: Boolean): Pair<Int, String>
    }

    /** The live client's view of its groups and messages. */
    interface Source {
        fun post(groupId: String, postId: String): ChannelWebPostSource?
        /** Upload-ready bytes of the post's image ([ChannelWebImage.prepare]). */
        fun imageBytes(groupId: String, postId: String): ByteArray?
        fun profile(groupId: String): ChannelWebProfile?
        /** Upload-ready avatar bytes, or null (no picture / unreadable). */
        fun avatarBytes(groupId: String): ByteArray?
        fun channels(): List<Channel>
        /** (message id, sent-at ms) of a group's stored messages, for a deep link's `n`. */
        fun sentTimes(groupId: String): List<Pair<String, Long>>
        /** Re-PUT the group-link registry record (a new admin is not a controller yet). */
        fun reRegisterHandle(groupId: String): Boolean
    }

    /** One group as [reconcile] needs it. */
    data class Channel(val groupId: String, val handle: String?, val webActive: Boolean, val iAmAdmin: Boolean)

    interface Persistence {
        fun load(): String?
        fun save(json: String)

        companion object {
            fun memory(): Persistence = object : Persistence {
                @Volatile var s: String? = null
                override fun load(): String? = s
                override fun save(json: String) { s = json }
            }
        }
    }

    private val lock = Any()
    private val state: ChannelWebState = ChannelWebState.fromJson(runCatching { persistence.load() }.getOrNull())
    @Volatile private var executor: ScheduledExecutorService? = null
    private var wake: ScheduledFuture<*>? = null
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Redraw hook (pending count in the settings, a resolved `n`). */
    @Volatile var onChange: () -> Unit = {}

    // ------------------------------------------------------------------ lifecycle

    /** Launch: resume the persisted outbox and reconcile every channel this device administers. */
    fun start() {
        synchronized(lock) {
            if (executor != null) return
            executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "oshi-channel-web").apply { isDaemon = true } }
        }
        executor?.schedule({ runCatching { reconcile(null) } }, 3, TimeUnit.SECONDS)
    }

    fun stop() {
        synchronized(lock) { wake?.cancel(false); wake = null; executor?.shutdownNow(); executor = null }
    }

    private fun persistLocked() {
        runCatching { persistence.save(state.toJson()) }.onFailure { log("channel-web: state not saved: ${it.javaClass.simpleName}") }
    }

    private fun changed() { runCatching { onChange() } }

    // ------------------------------------------------------------------ queries

    fun n(messageId: String, handle: String): Int? = synchronized(lock) { state.n(ChannelWeb.postId(messageId), handle) }
    fun isPublished(messageId: String): Boolean = synchronized(lock) { state.posts.containsKey(ChannelWeb.postId(messageId)) }
    fun pendingJobs(handle: String): Int = synchronized(lock) { state.jobs.count { it.handle == handle } }
    /** A copy, for tests. */
    fun jobs(): List<ChannelWebState.Job> = synchronized(lock) { state.jobs.toList() }
    fun post(postId: String): ChannelWebState.PostRecord? = synchronized(lock) { state.posts[postId] }
    fun channel(handle: String): ChannelWebState.ChannelRecord? = synchronized(lock) { state.channels[handle] }

    /** Queue a job directly (tests; [record] creates the author's post record). */
    fun testEnqueue(job: ChannelWebState.Job, record: Boolean) {
        synchronized(lock) {
            val pid = job.postId
            if (record && pid != null && state.posts[pid] == null) state.posts[pid] = ChannelWebState.PostRecord(job.handle, job.groupId, mine = true)
            state.enqueue(job)
            persistLocked()
        }
    }

    // ------------------------------------------------------------------ hooks from the channel send path

    /**
     * A NEW channel post by THIS device, at send time. Only the author publishes (§9.2), only
     * while the preview is on, only posts written after it was turned on — guaranteed by
     * construction: this runs at send time. The caller checked admin + active + fromMe.
     */
    fun didSend(groupId: String, messageId: String, handle: String) {
        val pid = ChannelWeb.postId(messageId)
        if (!ChannelWeb.isValidPostId(pid) || source.post(groupId, pid) == null) return
        synchronized(lock) {
            state.posts[pid] = ChannelWebState.PostRecord(handle, groupId, mine = true)
            state.enqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.PUBLISH, groupId = groupId, handle = handle, postId = pid))
            persistLocked()
        }
        changed(); kick()
    }

    /** An edit-for-everyone of a post that is on the web → re-publish (same `n`). [handle] = the active one. */
    fun didEdit(groupId: String, messageId: String, handle: String?) {
        val pid = ChannelWeb.postId(messageId)
        synchronized(lock) {
            val r = state.posts[pid] ?: return
            if (handle == null || r.handle != handle) return
            state.enqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.PUBLISH, groupId = groupId, handle = r.handle, postId = pid))
            persistLocked()
        }
        kick()
    }

    /** A delete-for-everyone of a post that is (or is being put) on the web. */
    fun didDelete(groupId: String, messageId: String) {
        val pid = ChannelWeb.postId(messageId)
        synchronized(lock) {
            val r = state.posts[pid] ?: return
            state.enqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.DELETE, groupId = groupId, handle = r.handle, postId = pid))
            persistLocked()
        }
        changed(); kick()
    }

    // ------------------------------------------------------------------ admin actions

    enum class EnableResult { OK, NOT_REGISTERED, NOT_CONTROLLER, OFFLINE, FAILED }

    /**
     * Toggle ON (blocking; the UI calls it on its worker and flips the setting only on [EnableResult.OK]):
     * PUT the profile now; the avatar and later profile changes ride the queue.
     */
    fun enable(groupId: String, handle: String, searchIndexing: Boolean): EnableResult {
        val p = source.profile(groupId)?.copy(searchIndexing = searchIndexing) ?: return EnableResult.FAILED
        // Off → on before the purge went out: purge first, or the old posts would stay public.
        val pendingDisable = synchronized(lock) { state.jobs.firstOrNull { it.kind == ChannelWebState.Kind.DISABLE && it.handle == handle } }
        if (pendingDisable != null) {
            val r = call("DELETE", ChannelWeb.pathChannel(handle), null, null)
            if (outcome(r, ChannelWebState.Kind.DISABLE).first != ChannelWebOutcome.DONE) return EnableResult.OFFLINE
            synchronized(lock) {
                state.jobs.removeAll { it.id == pendingDisable.id }
                state.posts.entries.removeAll { it.value.handle == handle }
                state.channels.remove(handle)
                persistLocked()
            }
        }
        var r = putProfile(handle, p, avatar = null)
        if (r.first == 403 || r.first == 404) {
            // The registry may not list this device as a controller yet (new admin, expired handle).
            if (runCatching { source.reRegisterHandle(groupId) }.getOrDefault(false)) r = putProfile(handle, p, avatar = null)
        }
        return when {
            r.first in 200..299 -> {
                synchronized(lock) {
                    val c = state.channels[handle] ?: ChannelWebState.ChannelRecord(groupId)
                    state.channels[handle] = c.copy(groupId = groupId, profileSignature = null)   // the avatar step still has to run
                    state.enqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.PROFILE, groupId = groupId, handle = handle))
                    state.enqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.SYNC, groupId = groupId, handle = handle))
                    persistLocked()
                }
                changed(); kick()
                EnableResult.OK
            }
            r.first == 404 -> EnableResult.NOT_REGISTERED
            r.first == 403 -> EnableResult.NOT_CONTROLLER
            r.first < 100 -> EnableResult.OFFLINE
            else -> EnableResult.FAILED
        }
    }

    /** Toggle OFF (confirmed by the UI): DELETE purges posts + images. Durable: queued first. */
    fun disable(groupId: String, handle: String) {
        synchronized(lock) {
            state.enqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.DISABLE, groupId = groupId, handle = handle))
            persistLocked()
        }
        changed(); kick()
    }

    // ------------------------------------------------------------------ reconcile

    /**
     * For every channel with the preview on where we are admin: profile changed → re-PUT; never
     * synced (or > 24 h ago) → learn the postId → n map. Where it is off (another admin, made
     * private, demoted): drop our queued writes (a disable/delete stays).
     */
    fun reconcile(only: String?) {
        val chans = runCatching { source.channels() }.getOrDefault(emptyList())
        var any = false
        for (c in chans) {
            if (only != null && GroupIdentity.canonicalGroupId(c.groupId) != GroupIdentity.canonicalGroupId(only)) continue
            val h = c.handle
            if (c.webActive && c.iAmAdmin && h != null) {
                val p = runCatching { source.profile(c.groupId) }.getOrNull()
                synchronized(lock) {
                    val rec = state.channels[h]
                    if (p != null && rec?.profileSignature != p.signature &&
                        state.jobs.none { it.kind == ChannelWebState.Kind.PROFILE && it.handle == h }) {
                        state.enqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.PROFILE, groupId = c.groupId, handle = h)); any = true
                    }
                    val last = rec?.lastAdminSyncMs
                    if ((last == null || clock() - last > DAY_MS) && state.jobs.none { it.kind == ChannelWebState.Kind.SYNC && it.handle == h }) {
                        state.enqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.SYNC, groupId = c.groupId, handle = h)); any = true
                    }
                }
            } else synchronized(lock) {
                val before = state.jobs.size
                state.jobs.removeAll {
                    GroupIdentity.canonicalGroupId(it.groupId) == GroupIdentity.canonicalGroupId(c.groupId) &&
                        (it.kind == ChannelWebState.Kind.PUBLISH || it.kind == ChannelWebState.Kind.PROFILE || it.kind == ChannelWebState.Kind.SYNC)
                }
                if (before != state.jobs.size) any = true
            }
        }
        if (any) { synchronized(lock) { persistLocked() }; changed() }
        kick()
    }

    // ------------------------------------------------------------------ runner

    /** Run due jobs now on the publisher's thread (no-op before [start]). */
    fun kick() {
        val ex = executor ?: return
        runCatching { ex.execute { drain() } }
    }

    /**
     * Runs every job that is due, one at a time, then schedules a wake for the next one.
     * Tests call this directly (no [start]).
     */
    fun drain() {
        if (!draining.compareAndSet(false, true)) return
        try {
            while (true) {
                val job = synchronized(lock) { state.nextDue(clock()) } ?: break
                val o = runCatching { run(job) }.getOrElse { ChannelWebOutcome.RETRY to (it.javaClass.simpleName) }
                apply(o, job)
            }
        } finally { draining.set(false) }
        scheduleWake()
    }

    private fun scheduleWake() {
        val ex = executor ?: return
        synchronized(lock) {
            wake?.cancel(false); wake = null
            val next = state.jobs.minOfOrNull { it.notBeforeMs } ?: return
            val delay = (next - clock()).coerceIn(1_000L, 3_600_000L)
            wake = runCatching { ex.schedule({ drain() }, delay, TimeUnit.MILLISECONDS) }.getOrNull()
        }
    }

    private fun apply(outcome: Pair<ChannelWebOutcome, String?>, job: ChannelWebState.Job) {
        synchronized(lock) {
            val i = state.jobs.indexOfFirst { it.id == job.id }
            if (i < 0) { persistLocked(); return@synchronized }
            val j = state.jobs[i]
            when (outcome.first) {
                ChannelWebOutcome.DONE, ChannelWebOutcome.DROP -> {
                    if (outcome.first == ChannelWebOutcome.DROP) log("channel-web: ${j.kind.wire} dropped (${outcome.second})")
                    state.jobs.removeAt(i)
                }
                ChannelWebOutcome.REUPLOAD_MEDIA -> {
                    j.postId?.let { pid -> state.posts[pid]?.let { state.posts[pid] = it.copy(mediaIds = emptyList()) } }
                    val a = j.attempts + 1
                    if (a > ChannelWebOutcome.MAX_ATTEMPTS) state.jobs.removeAt(i)
                    else state.jobs[i] = j.copy(attempts = a, notBeforeMs = clock(), lastError = outcome.second)
                }
                ChannelWebOutcome.RETRY -> {
                    val a = j.attempts + 1
                    if (!j.forever && a > ChannelWebOutcome.MAX_ATTEMPTS) {
                        log("channel-web: ${j.kind.wire} gave up after $a tries (${outcome.second})")
                        state.jobs.removeAt(i)
                    } else {
                        state.jobs[i] = j.copy(attempts = a, lastError = outcome.second,
                            notBeforeMs = clock() + ChannelWebOutcome.backoffMs(a, jitter()))
                    }
                }
            }
            persistLocked()
        }
        changed()
    }

    private fun call(method: String, path: String, body: ByteArray?, contentType: String?, query: String? = null, signed: Boolean = true): Pair<Int, String> =
        runCatching { transport.send(method, path, query, body, contentType, signed) }.getOrElse { -1 to (it.javaClass.simpleName) }

    private fun outcome(r: Pair<Int, String>, kind: ChannelWebState.Kind): Pair<ChannelWebOutcome, String?> {
        val code = ChannelWebOutcome.errorCode(r.second)
        return ChannelWebOutcome.classify(r.first, code, kind) to (code ?: "${r.first}")
    }

    private fun json(s: String): JSONObject? = runCatching { JSONObject(s) }.getOrNull()

    private fun run(job: ChannelWebState.Job): Pair<ChannelWebOutcome, String?> {
        val h = job.handle
        return when (job.kind) {
            ChannelWebState.Kind.PUBLISH -> runPublish(job)
            ChannelWebState.Kind.DELETE -> {
                val pid = job.postId?.takeIf(ChannelWeb::isValidPostId) ?: return ChannelWebOutcome.DROP to "bad-id"
                val o = outcome(call("DELETE", ChannelWeb.pathPost(h, pid), null, null), job.kind)
                if (o.first == ChannelWebOutcome.DONE || o.first == ChannelWebOutcome.DROP) synchronized(lock) { state.posts.remove(pid) }
                o
            }
            ChannelWebState.Kind.DISABLE -> {
                val o = outcome(call("DELETE", ChannelWeb.pathChannel(h), null, null), job.kind)
                if (o.first == ChannelWebOutcome.DONE) synchronized(lock) {
                    state.posts.entries.removeAll { it.value.handle == h }
                    state.channels.remove(h)
                }
                o
            }
            ChannelWebState.Kind.PROFILE -> runProfile(job)
            ChannelWebState.Kind.SYNC -> {
                val r = call("GET", ChannelWeb.pathAdmin(h), null, null)
                if (r.first !in 200..299) return outcome(r, job.kind)
                mergeAdmin(r.second, h, job.groupId)
                ChannelWebOutcome.DONE to null
            }
        }
    }

    private fun upload(h: String, bytes: ByteArray): Pair<String?, Pair<Int, String>> {
        val up = call("POST", ChannelWeb.pathMedia(h), bytes, "application/octet-stream")
        val id = if (up.first in 200..299) json(up.second)?.opt("mediaId") as? String else null
        return id to up
    }

    private fun runPublish(job: ChannelWebState.Job): Pair<ChannelWebOutcome, String?> {
        val h = job.handle
        val pid = job.postId?.takeIf(ChannelWeb::isValidPostId) ?: return ChannelWebOutcome.DROP to "bad-id"
        val post = source.post(job.groupId, pid) ?: return ChannelWebOutcome.DROP to "gone"
        var rec = synchronized(lock) { state.posts[pid] } ?: ChannelWebState.PostRecord(h, job.groupId, mine = true)
        if (rec.handle != h) rec = ChannelWebState.PostRecord(h, job.groupId, mine = true)
        var media = if (post.hasImage) rec.mediaIds else emptyList()
        if (post.hasImage && media.isEmpty()) {
            val bytes = source.imageBytes(job.groupId, pid)
            if (bytes != null) {
                val (id, up) = upload(h, bytes)
                if (id != null) media = listOf(id)
                else {
                    val o = outcome(up, job.kind)
                    // An image the server will never take (415, 413, unparseable): the text alone.
                    if (o.first == ChannelWebOutcome.DROP && post.text.isNotEmpty()) media = emptyList() else return o
                }
            } else if (post.text.isEmpty()) return ChannelWebOutcome.DROP to "no-image"
        }
        val body = ChannelWebContent.postBody(post.text, media, post.createdAtMs)
        val r = call("PUT", ChannelWeb.pathPost(h, pid), body, "application/json")
        if (r.first in 200..299) {
            val n = (json(r.second)?.opt("n") as? Number)?.toInt() ?: return ChannelWebOutcome.RETRY to "no-n"
            synchronized(lock) { state.posts[pid] = rec.copy(n = n, mediaIds = media) }
            return ChannelWebOutcome.DONE to null
        }
        return outcome(r, job.kind)
    }

    private fun runProfile(job: ChannelWebState.Job): Pair<ChannelWebOutcome, String?> {
        val h = job.handle
        val p = source.profile(job.groupId) ?: return ChannelWebOutcome.DROP to "gone"
        val c = synchronized(lock) { state.channels[h] } ?: ChannelWebState.ChannelRecord(job.groupId)
        var avatar: String? = null   // null = unchanged; REMOVE_AVATAR = remove; else the new id
        if (p.avatarKey != null && p.avatarKey != c.avatarKey) {
            val bytes = source.avatarBytes(job.groupId)
            if (bytes != null) {
                // PUT (fields) → POST media → PUT avatarMediaId (§3.1 avatar flow).
                val r1 = putProfile(h, p, avatar = null)
                if (r1.first !in 200..299) return outcome(r1, job.kind)
                val (id, up) = upload(h, bytes)
                if (id != null) avatar = id
                else if (outcome(up, job.kind).first != ChannelWebOutcome.DROP) return outcome(up, job.kind)
            }
        } else if (p.avatarKey == null && c.avatarKey != null) {
            avatar = ChannelWebContent.REMOVE_AVATAR
        }
        val r = putProfile(h, p, avatar)
        if (r.first !in 200..299) return outcome(r, job.kind)
        synchronized(lock) {
            val now = state.channels[h] ?: c
            state.channels[h] = now.copy(
                groupId = job.groupId,
                profileSignature = p.signature,
                avatarKey = when (avatar) { null -> now.avatarKey; ChannelWebContent.REMOVE_AVATAR -> null; else -> p.avatarKey },
            )
        }
        return ChannelWebOutcome.DONE to null
    }

    private fun putProfile(h: String, p: ChannelWebProfile, avatar: String?): Pair<Int, String> =
        call("PUT", ChannelWeb.pathChannel(h), ChannelWebContent.profileBody(p.title, p.description, p.searchIndexing, avatar), "application/json")

    /** §3.6: adopt the server's postId → n (a new device, a co-admin). */
    fun mergeAdmin(body: String, h: String, groupId: String) {
        val o = json(body) ?: return
        synchronized(lock) {
            val c = state.channels[h] ?: ChannelWebState.ChannelRecord(groupId)
            state.channels[h] = c.copy(lastAdminSyncMs = clock())
            val arr = o.optJSONArray("posts")
            if (arr != null) for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pid = (p.opt("postId") as? String)?.takeIf(ChannelWeb::isValidPostId) ?: continue
                val n = (p.opt("n") as? Number)?.toInt() ?: continue
                val r = state.posts[pid] ?: ChannelWebState.PostRecord(h, groupId, n = n, mine = false)
                state.posts[pid] = r.copy(handle = h, n = n)
            }
            persistLocked()
        }
    }

    // ------------------------------------------------------------------ resolving n

    /**
     * The post number of a channel message, for "Web link". Known locally (author / admin map) →
     * at once. Admin without it → one signed `GET …/admin`. Reader → the PUBLIC feed, matched on
     * the post's send time (the feed never exposes postId, §4). Runs only when the person asks
     * for a web link: reading a channel never contacts the web server. Blocking.
     */
    fun resolveN(groupId: String, messageId: String, handle: String, isAdmin: Boolean, createdAtMs: Long, text: String?): Int? {
        n(messageId, handle)?.let { return it }
        val pid = ChannelWeb.postId(messageId)
        if (isAdmin) {
            val r = call("GET", ChannelWeb.pathAdmin(handle), null, null)
            if (r.first in 200..299) {
                mergeAdmin(r.second, handle, groupId)
                n(messageId, handle)?.let { return it }
            }
        }
        var before: Int? = null
        repeat(6) {
            val q = "limit=50" + (before?.let { "&before=$it" } ?: "")
            val r = call("GET", ChannelWeb.pathChannel(handle), null, null, query = q, signed = false)
            if (r.first !in 200..299) return null
            val o = json(r.second) ?: return null
            val arr = o.optJSONArray("posts")
            val posts = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            ChannelWebOutcome.matchFeed(posts, createdAtMs, text)?.let { n ->
                synchronized(lock) {
                    val rec = state.posts[pid] ?: ChannelWebState.PostRecord(handle, groupId, mine = false)
                    state.posts[pid] = rec.copy(handle = handle, n = n)
                    persistLocked()
                }
                return n
            }
            val oldest = posts.mapNotNull { (it.opt("createdAt") as? Number)?.toLong() }.minOrNull()
            val next = (o.opt("nextBefore") as? Number)?.toInt()
            if (next == null || oldest == null || oldest <= createdAtMs - 2000) return null
            before = next
        }
        return null
    }

    /** Deep link → local message id: from the map, else the public post's send time (±1 ms). Blocking. */
    fun localMessageId(handle: String, n: Int, groupId: String): String? {
        synchronized(lock) { state.postId(handle, n) }?.let { pid ->
            source.sentTimes(groupId).firstOrNull { ChannelWeb.postId(it.first) == pid }?.let { return it.first }
        }
        val r = call("GET", ChannelWeb.pathPublicPost(handle, n), null, null, signed = false)
        if (r.first !in 200..299) return null
        val created = (json(r.second)?.optJSONObject("post")?.opt("createdAt") as? Number)?.toLong() ?: return null
        return source.sentTimes(groupId).firstOrNull { kotlin.math.abs(it.second - created) <= 1 }?.first
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val FILE_NAME = "channel-web.json"
    }
}
