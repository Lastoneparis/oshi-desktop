package com.oshi.desktop.group

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.ReplyEnvelope
import com.oshi.desktop.store.Message

/**
 * __GROUP_COMMENTS_2026_11_21__ Telegram-style comments under channel posts —
 * `docs/GROUP_INFO_SETTINGS_SPEC.md` §2.12.
 *
 * A comment is an ordinary E2E group message of the SAME group with one extra top-level key,
 * `commentOn` (the post's id), and a body that is always a `💬REPLY💬` envelope (so a build that
 * does not know comments shows a reply bubble quoting the post). Nothing new reaches the server.
 *
 * This file holds the parts that are pure, so every rule is unit-tested without a client:
 *  - [decide], the receive rules of §2.12.4 in their order (1, 2, 3, 4a–4e);
 *  - [Orphans], the 24 h / 50-per-group memory hold of comments that overtook their post;
 *  - [RateWindow], the 60 s counters (receiver flood > 30, sender limit 20).
 */
object GroupComments {

    /** §2.12.3: at most this many comments per minute per group from THIS device. */
    const val SENDER_LIMIT_PER_MINUTE = 20

    /** §2.12.4 d: more than this many from one sender in one group within [WINDOW_MS] ⇒ `flood`. */
    const val RECEIVER_FLOOD_LIMIT = 30
    const val WINDOW_MS = 60_000L

    /** §2.12.4 orphans: at most 50 per group, 24 h, memory only. */
    const val MAX_ORPHANS_PER_GROUP = 50
    const val ORPHAN_TTL_MS = 24L * 60 * 60 * 1000

    /** §2.12.2: the message limit, in UTF-16 units; longer comments are truncated on receive. */
    const val MAX_COMMENT_UNITS = 4096

    /** §2.12.2: `commentOn` is at most 64 characters. */
    const val MAX_COMMENT_ON_CHARS = 64

    /** What a message body is, for rule 4a. */
    enum class Kind { TEXT, NOT_TEXT }

    /**
     * Rule 4a's input: a comment must be plain text or a reply envelope around plain text. A
     * `mediaType`, a `v2file` key ([hasMedia]), a media JSON blob, or any sentinel payload
     * (`📍LOCATION📍`, `🛡️CHECK_IN🛡️`, poll, …) is NOT text.
     */
    fun kindOf(body: String, hasMedia: Boolean): Kind {
        if (hasMedia) return Kind.NOT_TEXT
        if (isMediaJson(body)) return Kind.NOT_TEXT
        val inner = if (ControlPrefix.match(body) == ControlPrefix.REPLY) {
            ReplyEnvelope.unwrap(body)?.content ?: return Kind.NOT_TEXT
        } else body
        if (ControlPrefix.isControl(inner) || isMediaJson(inner)) return Kind.NOT_TEXT
        return Kind.TEXT
    }

    private fun isMediaJson(s: String): Boolean = s.trimStart().startsWith("{") && s.contains("\"type\":\"media\"")

    /** The sanitised `commentOn` of a decoded payload: blank ⇒ null, cut at 64 characters. */
    fun wireCommentOn(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_COMMENT_ON_CHARS)

    sealed interface Verdict {
        /** Not a comment (rules 1, 2, 3-admin): today's rules apply and `commentOn` is ignored. */
        data object Ordinary : Verdict
        /** Rule 4e: store as a comment of [postId] (excluded from the timeline). */
        data class Accept(val postId: String) : Verdict
        /** Rule 4c: the post is not here (yet) — hold it as an orphan. */
        data object Hold : Verdict
        data class Drop(val reason: String) : Verdict
    }

    const val R_NOT_ADMIN = "not-admin"
    const val R_COMMENTS_OFF = "comments-off"
    const val R_KIND = "comment-kind"
    const val R_BANNED = "banned"
    const val R_BAD_PARENT = "bad-parent"
    const val R_PARENT_NOT_ADMIN = "parent-not-admin"
    const val R_FLOOD = "flood"
    const val R_DISSOLVED = "dissolved"

    /**
     * §2.12.4, a pure function of its inputs. [parentOf] finds a stored message of the group by
     * id, case-insensitively; [recentFromSender] = comments from [sender] in this group accepted
     * in the last [WINDOW_MS] (the caller's [RateWindow]).
     *
     * [Verdict.Ordinary] is returned only where the message may be posted under today's rules;
     * a message today's rules drop (§2.4 `not-admin`, a dissolved group) comes back as [Verdict.Drop].
     */
    fun decide(
        sender: String,
        commentOn: String?,
        kind: Kind,
        group: GroupDefinition,
        parentOf: (String) -> Message?,
        recentFromSender: Int,
    ): Verdict {
        val c = wireCommentOn(commentOn)
        val s = group.effectiveSettings
        val admin = group.isAdmin(sender)
        val ordinary: Verdict = if (group.mayPost(sender)) Verdict.Ordinary
        else Verdict.Drop(if (group.isDissolved) R_DISSOLVED else R_NOT_ADMIN)
        // 1. Not a comment: today's rules unchanged.
        if (c == null) return ordinary
        // 2. Not channel mode: `commentOn` is ignored (a reply bubble under the ordinary rules).
        if (s.postPolicy != GroupSettings.PostPolicy.ADMINS) return ordinary
        // 3. Channel mode, comments off: an admin's is an ordinary post; anyone else's is dropped.
        if (!s.comments) return if (admin) ordinary else Verdict.Drop(R_COMMENTS_OFF)
        // 4. Active. Admins go through the same rules (an admin's comment is a comment).
        if (group.isDissolved) return Verdict.Drop(R_DISSOLVED)
        if (kind != Kind.TEXT) return Verdict.Drop(R_KIND)                       // a
        if (group.isEvicted(sender)) return Verdict.Drop(R_BANNED)              // b
        val parent = parentOf(c) ?: return Verdict.Hold                          // c
        if (parent.commentOn != null || parent.isDeletedForEveryone) return Verdict.Drop(R_BAD_PARENT)
        if (!group.isAdmin(parent.senderAddress)) return Verdict.Drop(R_PARENT_NOT_ADMIN)
        if (recentFromSender >= RECEIVER_FLOOD_LIMIT) return Verdict.Drop(R_FLOOD)  // d
        return Verdict.Accept(parent.id)                                          // e
    }

    /**
     * §2.12.3 sender side: may we comment on [post] in [group]? A stored, non-deleted,
     * non-comment message whose author is a current admin, in a group where comments are Active.
     */
    fun mayCommentOn(group: GroupDefinition, post: Message?, self: String): Boolean =
        post != null && group.effectiveSettings.commentsActive && !group.isDissolved &&
            group.isMember(self) && !group.isEvicted(self) &&
            post.commentOn == null && !post.isDeletedForEveryone && !post.hiddenLocally &&
            group.isAdmin(post.senderAddress)

    /**
     * §2.12.5: does this stored comment notify ME? Only a reply to one of my comments, or a
     * comment that mentions me. Top-level comments notify nobody.
     */
    fun notifiesMe(comment: Message, myComments: (String) -> Message?, self: String): Boolean {
        if (comment.fromMe || comment.commentOn == null) return false
        if (MentionWire.mentions(self, comment.mentions, GroupIdentity::sameIdentity)) return true
        val target = ReplyEnvelope.unwrap(comment.content)?.quote?.originalMessageId ?: return false
        if (target.equals(comment.commentOn, ignoreCase = true)) return false
        val parent = myComments(target) ?: return false
        return parent.commentOn != null && GroupIdentity.sameIdentity(parent.senderAddress, self)
    }

    /**
     * §2.12.3 push wake-up of a comment WE send: only the author of the comment it answers (when
     * that is not us and the fan-out reached them). A top-level comment ([answered] null) wakes
     * nobody; every other member gets it silently.
     */
    fun wakeTargets(answered: Message?, reached: List<String>, self: String): List<String> {
        val author = answered?.takeIf { !it.fromMe && !GroupIdentity.sameIdentity(it.senderAddress, self) }?.senderAddress
            ?: return emptyList()
        return reached.filter { GroupIdentity.sameIdentity(it, author) }.distinct()
    }

    /** Sliding 60 s counters keyed by an arbitrary string (thread-safe, memory only). */
    class RateWindow(private val windowMs: Long = WINDOW_MS, private val clock: () -> Long = System::currentTimeMillis) {
        private val hits = HashMap<String, ArrayDeque<Long>>()

        @Synchronized
        fun count(key: String): Int {
            val q = hits[key] ?: return 0
            val cutoff = clock() - windowMs
            while (q.isNotEmpty() && q.first() <= cutoff) q.removeFirst()
            if (q.isEmpty()) hits.remove(key)
            return q.size
        }

        @Synchronized
        fun note(key: String) {
            hits.getOrPut(key) { ArrayDeque() }.addLast(clock())
        }

        /** Count and, when under [limit], note one more — atomically. */
        @Synchronized
        fun tryAcquire(key: String, limit: Int): Boolean {
            if (count(key) >= limit) return false
            note(key)
            return true
        }
    }

    /**
     * §2.12.4 orphans: comments whose post we do not hold (yet). Memory only (lost on restart),
     * at most [MAX_ORPHANS_PER_GROUP] per group (the oldest goes first), [ORPHAN_TTL_MS] each.
     */
    class Orphans<T>(
        private val clock: () -> Long = System::currentTimeMillis,
        private val maxPerGroup: Int = MAX_ORPHANS_PER_GROUP,
        private val ttlMs: Long = ORPHAN_TTL_MS,
    ) {
        private data class Held<T>(val postId: String, val atMs: Long, val item: T)
        private val byGroup = HashMap<String, ArrayDeque<Held<T>>>()

        @Synchronized
        fun hold(groupId: String, postId: String, item: T) {
            val gid = GroupIdentity.canonicalGroupId(groupId)
            val q = byGroup.getOrPut(gid) { ArrayDeque() }
            expire(q)
            q.addLast(Held(postId, clock(), item))
            while (q.size > maxPerGroup) q.removeFirst()
        }

        /** Remove and return every held comment of [postId] (case-insensitive), oldest first. */
        @Synchronized
        fun release(groupId: String, postId: String): List<T> {
            val gid = GroupIdentity.canonicalGroupId(groupId)
            val q = byGroup[gid] ?: return emptyList()
            expire(q)
            val out = q.filter { it.postId.equals(postId, ignoreCase = true) }
            if (out.isEmpty()) return emptyList()
            q.removeAll { it.postId.equals(postId, ignoreCase = true) }
            if (q.isEmpty()) byGroup.remove(gid)
            return out.map { it.item }
        }

        @Synchronized
        fun size(groupId: String): Int {
            val q = byGroup[GroupIdentity.canonicalGroupId(groupId)] ?: return 0
            expire(q)
            return q.size
        }

        private fun expire(q: ArrayDeque<Held<T>>) {
            val cutoff = clock() - ttlMs
            while (q.isNotEmpty() && q.first().atMs < cutoff) q.removeFirst()
        }
    }
}
