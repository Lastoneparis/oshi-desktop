package com.oshi.desktop.group

import org.json.JSONObject
import java.util.UUID

/**
 * __DESKTOP_GROUP_DISCOVERY_2027__ "Discover" public groups, as the phones do it.
 *
 * There is NO server directory: the phones find public groups by listening to the mesh.
 * Every member of a public group re-announces it every 30 s (iOS
 * `startPeriodicPublicGroupBroadcast`, `GroupMessaging.swift:1727`; Android
 * `broadcastPublicGroupsViaMesh`, `GroupManager.kt:1477`) as a `PUBLIC_GROUP_AD` frame to every
 * directly connected peer — recipient empty, `maxHops` 5 — and the Discover screen lists what
 * it has heard (iOS `DiscoverPublicGroupsView`, Android `DiscoverGroupsScreen`).
 *
 * The payload is the phones' `PublicGroupAd` JSON (Android writes exactly this):
 * `{"groupId","groupName","adminPublicKey","memberCount","avatar":null,"timestamp"}`, where
 * `timestamp` is seconds since 2001-01-01 (Swift `Date`'s reference date, which iOS's default
 * `JSONDecoder` expects — Android subtracts 978307200 for that reason).
 *
 * An ad carries a name and an admin key and nothing secret: joining sends an authenticated
 * `member_sync_request` to that admin, exactly as an invite link does, and the admin's client
 * decides. An ad heard once and then never again expires after [ttlMs], so a group whose
 * last announcer left the network leaves the list.
 */
class PublicGroupDirectory(private val ttlMs: Long = DEFAULT_TTL_MS) {

    data class Ad(
        val groupId: String,
        val name: String,
        val adminPublicKey: String,
        val memberCount: Int,
        /** The mesh peer that relayed it to us (not necessarily the admin). */
        val heardFrom: String,
        val lastHeardMs: Long,
    )

    private val ads = LinkedHashMap<String, Ad>()

    /** Take one frame's payload. Returns the ad, or null when it is not a usable one. */
    @Synchronized
    fun offer(payload: String, heardFrom: String, nowMs: Long): Ad? {
        val ad = parse(payload, heardFrom, nowMs) ?: return null
        ads[ad.groupId] = ad
        return ad
    }

    /** What is still being heard, by name. */
    @Synchronized
    fun list(nowMs: Long): List<Ad> {
        ads.values.removeAll { nowMs - it.lastHeardMs > ttlMs }
        return ads.values.sortedBy { it.name.lowercase() }
    }

    @Synchronized
    fun get(groupId: String): Ad? = ads[GroupIdentity.canonicalGroupId(groupId)]

    companion object {
        const val TYPE = "PUBLIC_GROUP_AD"
        const val PERIOD_MS = 30_000L
        /** Four missed announcements. */
        const val DEFAULT_TTL_MS = 4 * PERIOD_MS
        const val MAX_NAME = 100
        /** Seconds between 1970-01-01 and Swift's 2001-01-01 reference date. */
        const val APPLE_EPOCH_OFFSET_S = 978_307_200L

        fun parse(payload: String, heardFrom: String, nowMs: Long): Ad? {
            val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val gid = o.optString("groupId", "")
            if (gid.length != 36 || runCatching { UUID.fromString(gid) }.isFailure) return null
            val admin = o.optString("adminPublicKey", "").trim()
            if (admin.isEmpty() || admin.length > 128) return null
            // A display string from a stranger: one line, no bidi overrides, bounded.
            val name = o.optString("groupName", "")
                .replace(Regex("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F]"), " ")
                .trim().take(MAX_NAME)
            if (name.isEmpty()) return null
            return Ad(
                groupId = GroupIdentity.canonicalGroupId(gid),
                name = name,
                adminPublicKey = admin,
                memberCount = o.optInt("memberCount", 1).coerceIn(1, 100_000),
                heardFrom = heardFrom,
                lastHeardMs = nowMs,
            )
        }

        /** Our own announcement of [g], byte-compatible with Android's. */
        fun encode(g: GroupDefinition, nowMs: Long): String =
            JSONObject()
                .put("groupId", GroupIdentity.canonicalGroupId(g.groupId))
                .put("groupName", g.name)
                .put("adminPublicKey", g.adminPublicKey)
                .put("memberCount", g.members.size)
                .put("avatar", JSONObject.NULL)
                .put("timestamp", nowMs / 1000.0 - APPLE_EPOCH_OFFSET_S)
                .toString()
    }
}
