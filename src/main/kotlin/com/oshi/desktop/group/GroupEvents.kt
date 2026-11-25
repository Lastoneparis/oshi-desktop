package com.oshi.desktop.group

import org.json.JSONObject

/**
 * __GROUP_INFO_SETTINGS_2026_11_20__ "Recent events" — GROUP_INFO_SETTINGS_SPEC §2.8.
 *
 * Local, per device, nothing on the wire: every applied definition is diffed against our
 * previous copy and each change is one [Event]. Kept 48 h, at most 500 per group, in the
 * SEALED group store ([com.oshi.desktop.app.GroupStore]) — it names people, so never in plain
 * preferences. Shown to admins only.
 */
object GroupEvents {

    const val TTL_MS = 48L * 60 * 60 * 1000
    const val MAX_PER_GROUP = 500

    enum class Kind(val wire: String) {
        RENAMED("renamed"), DESCRIPTION_CHANGED("description_changed"), PHOTO_CHANGED("photo_changed"),
        MEMBER_ADDED("member_added"), MEMBER_REMOVED("member_removed"), MEMBER_LEFT("member_left"),
        ADMIN_PROMOTED("admin_promoted"), ADMIN_DEMOTED("admin_demoted"),
        VISIBILITY_CHANGED("visibility_changed"), HANDLE_CHANGED("handle_changed"), INVITE_LINK_RESET("invite_link_reset"),
        REACTIONS_CHANGED("reactions_changed"), WELCOME_CHANGED("welcome_changed"),
        POST_POLICY_CHANGED("post_policy_changed"), ADMIN_DMS_CHANGED("admin_dms_changed"),
        JOINED_VIA_LINK("joined_via_link"), DISSOLVED("dissolved"),
        /** __GROUP_COMMENTS_2026_11_21__ §2.12.1 `comments_changed`. */
        COMMENTS_CHANGED("comments_changed");

        companion object { fun fromWire(w: String?): Kind? = entries.firstOrNull { it.wire == w } }
    }

    /** [actor] = the AUTHENTICATED sender (or our own key for a local change). */
    data class Event(val atMs: Long, val actor: String, val kind: Kind, val detail: String = "") {
        fun toJson(): JSONObject = JSONObject().put("at", atMs).put("actor", actor).put("kind", kind.wire).put("detail", detail)

        companion object {
            fun fromJson(o: JSONObject): Event? {
                val kind = Kind.fromWire(o.optString("kind", "")) ?: return null
                val actor = o.optString("actor", "").takeIf { it.isNotEmpty() } ?: return null
                return Event(o.optLong("at"), actor, kind, o.optString("detail", ""))
            }
        }
    }

    /** Every change from [before] to [after], attributed to [actor]. Pure. */
    fun diff(before: GroupDefinition, after: GroupDefinition, actor: String, atMs: Long): List<Event> {
        val out = ArrayList<Event>()
        fun ev(kind: Kind, detail: String = "") { out += Event(atMs, actor, kind, detail) }
        if (before.name != after.name) ev(Kind.RENAMED, after.name)
        if (before.description.orEmpty() != after.description.orEmpty()) ev(Kind.DESCRIPTION_CHANGED)
        if (before.groupPictureBase64 != after.groupPictureBase64) ev(Kind.PHOTO_CHANGED)

        val beforeKeys = before.members.associateBy { GroupIdentity.canonicalIdentity(it.publicKey) }
        val afterKeys = after.members.associateBy { GroupIdentity.canonicalIdentity(it.publicKey) }
        val actorKey = GroupIdentity.canonicalIdentity(actor)
        for ((k, m) in afterKeys) if (k !in beforeKeys) ev(Kind.MEMBER_ADDED, m.publicKey)
        for ((k, m) in beforeKeys) if (k !in afterKeys) ev(if (k == actorKey) Kind.MEMBER_LEFT else Kind.MEMBER_REMOVED, m.publicKey)
        for ((k, m) in afterKeys) {
            val was = beforeKeys[k] ?: continue
            if (!was.isAdmin && m.isAdmin) ev(Kind.ADMIN_PROMOTED, m.publicKey)
            if (was.isAdmin && !m.isAdmin) ev(Kind.ADMIN_DEMOTED, m.publicKey)
        }

        val a = before.effectiveSettings
        val b = after.effectiveSettings
        if (a.isPublic != b.isPublic) ev(Kind.VISIBILITY_CHANGED, if (b.isPublic) b.visibility.raw else GroupSettings.Visibility.PRIVATE.raw)
        else if (b.isPublic && a.handle != b.handle) ev(Kind.HANDLE_CHANGED, b.handle.orEmpty())
        if (a.inviteToken != b.inviteToken && b.inviteToken != null) ev(Kind.INVITE_LINK_RESET)
        if (a.reactions != b.reactions || a.allowedReactions != b.allowedReactions) ev(Kind.REACTIONS_CHANGED, b.reactions.raw)
        if (a.welcome != b.welcome) ev(Kind.WELCOME_CHANGED)
        if (a.postPolicy != b.postPolicy) ev(Kind.POST_POLICY_CHANGED, b.postPolicy.raw)
        if (a.adminDMs != b.adminDMs) ev(Kind.ADMIN_DMS_CHANGED, b.adminDMs.toString())
        if (a.comments != b.comments) ev(Kind.COMMENTS_CHANGED, b.comments.toString())
        if (!a.dissolved && b.dissolved) ev(Kind.DISSOLVED)
        return out
    }

    /** Drop entries older than [TTL_MS] and keep the newest [MAX_PER_GROUP]. */
    fun prune(events: List<Event>, nowMs: Long): List<Event> =
        events.filter { it.atMs >= nowMs - TTL_MS && it.atMs <= nowMs + TTL_MS }
            .sortedBy { it.atMs }
            .takeLast(MAX_PER_GROUP)
}
