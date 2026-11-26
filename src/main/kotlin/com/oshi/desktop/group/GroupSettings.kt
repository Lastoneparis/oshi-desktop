package com.oshi.desktop.group

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * __GROUP_INFO_SETTINGS_2026_11_20__ The Telegram-style group settings of
 * `docs/GROUP_INFO_SETTINGS_SPEC.md` §2.1: one optional `settings` object inside the group
 * definition, sent over the same v2 Double Ratchet state channel as everything else in it.
 *
 * Rules this file carries (spec §0, §2.1):
 *  - **Absent ≠ cleared.** A definition without `settings` keeps ours (an old build re-emits
 *    the definition without the key; treating that as "cleared" would let it wipe them).
 *    [GroupDefinition.settings] is therefore nullable: null = "this device never held one",
 *    and such a device never emits the key (byte-identical to before for unconfigured groups).
 *  - **Sanitised on parse**: invalid handle ⇒ absent and visibility forced private; unknown
 *    enum ⇒ default; a non-boolean where a boolean belongs ⇒ default; long strings truncated;
 *    unknown subkeys ignored. Vectors: `docs/fixtures/group_settings_v1/`.
 *  - **Only an admin's object is taken** — enforced in [GroupUpdateAuthorizer.apply], not here.
 *  - **`dissolved` is sticky** (OR-ed on merge), also in [GroupUpdateAuthorizer.apply].
 *
 * NOT a new group `type`: iOS 1.0.43 throws on an unknown `type` and drops the whole
 * definition, so Public/Private is `visibility` here (spec §0 rule 4).
 */
data class GroupSettings(
    val visibility: Visibility = Visibility.PRIVATE,
    /** `^[a-z0-9_]{5,32}$`; meaningful only when [visibility] is PUBLIC. */
    val handle: String? = null,
    val showPreview: Boolean = false,
    /** Private-link secret (base64url, 16 bytes). Present ⇒ a link join must carry it (§3.2). */
    val inviteToken: String? = null,
    val reactions: ReactionMode = ReactionMode.ALL,
    val allowedReactions: List<String> = emptyList(),
    val welcome: String = "",
    val postPolicy: PostPolicy = PostPolicy.EVERYONE,
    val adminDMs: Boolean = true,
    val dissolved: Boolean = false,
    /**
     * __GROUP_COMMENTS_2026_11_21__ §2.12.1: members may comment under posts. Only meaningful in
     * channel mode ([commentsActive]); kept (and re-emitted) whatever [postPolicy] says, so an
     * admin who switches "Who can post" back and forth never loses it (§2.1 rule 2: a settings
     * object WITHOUT this subkey would reset it to false on every receiver).
     */
    val comments: Boolean = false,
    /**
     * __CHANNEL_WEB_2026_11_26__ GROUP_INFO_SETTINGS_SPEC §2.13 / PUBLIC_CHANNEL_WEB_SPEC §9.1:
     * "Public web preview" is on — the AUTHOR admin's device publishes a cleartext copy of each NEW
     * channel post at oshi-messenger.com/c/<handle>. Same re-emit rule as [comments]: a settings
     * object without it would turn the preview off on every receiver.
     */
    val webPreview: Boolean = false,
    /** §2.13 "Allow search engines" (`searchIndexing` of `PUT /v2/c/<handle>`); only meaningful with [webPreview]. */
    val webIndexing: Boolean = false,
) {
    enum class Visibility(val raw: String) { PRIVATE("private"), PUBLIC("public") }
    enum class ReactionMode(val raw: String) { ALL("all"), SOME("some"), NONE("none") }
    enum class PostPolicy(val raw: String) { EVERYONE("everyone"), ADMINS("admins") }

    /** §2.12.1 Active ⇔ `postPolicy == admins` AND `comments == true`. */
    val commentsActive: Boolean get() = postPolicy == PostPolicy.ADMINS && comments

    val isPublic: Boolean get() = visibility == Visibility.PUBLIC && handle != null

    /** §2.13 Active ⇔ [webPreview] AND channel mode AND a public handle (spec §1 hard rules). */
    val webPreviewActive: Boolean get() = webPreview && postPolicy == PostPolicy.ADMINS && isPublic

    /** §2.3: may a member ADD [emoji] here? (`remove` is always accepted.) */
    fun allowsReaction(emoji: String): Boolean = when (reactions) {
        ReactionMode.ALL -> true
        ReactionMode.NONE -> false
        // "some" with an empty list behaves as "none".
        ReactionMode.SOME -> allowedReactions.any { it == emoji || stripVs(it) == stripVs(emoji) }
    }

    /** True when no reaction at all can be added (none, or some with an empty list). */
    val reactionsOff: Boolean get() = reactions == ReactionMode.NONE || (reactions == ReactionMode.SOME && allowedReactions.isEmpty())

    companion object {
        const val VERSION = 1
        const val MAX_WELCOME = 1000
        const val MAX_DESCRIPTION = 500
        const val MAX_ALLOWED_REACTIONS = 12
        const val MAX_REACTION_UNITS = 16
        val HANDLE_RE = Regex("^[a-z0-9_]{5,32}$")
        private val TOKEN_RE = Regex("^[A-Za-z0-9_-]{16,64}$")

        /** The emoji offered by the "Some reactions" editor (spec §2.3), in its order. */
        val DEFAULT_CHOOSER: List<String> = listOf("👍", "❤️", "🔥", "😂", "😮", "😢", "🙏", "👏", "🎉", "👎")

        /** The wire rule (§2.1): lower-cased, then `^[a-z0-9_]{5,32}$`, or null. */
        fun wireHandle(raw: String?): String? = raw?.lowercase(Locale.ROOT)?.takeIf { HANDLE_RE.matches(it) }

        /** What a person types or pastes: also trims and drops a leading `@`. */
        fun normalizeHandle(raw: String?): String? =
            raw?.trim()?.removePrefix("@")?.lowercase(Locale.ROOT)?.takeIf { HANDLE_RE.matches(it) }

        /** §2.1: the object, sanitised; null when [raw] is not a JSON object (= key absent). */
        fun parse(raw: Any?): GroupSettings? {
            val o = raw as? JSONObject ?: return null
            val handle = wireHandle(o.opt("handle") as? String)
            var visibility = enumOf(o.opt("visibility"), Visibility.entries, Visibility.PRIVATE) { it.raw }
            if (visibility == Visibility.PUBLIC && handle == null) visibility = Visibility.PRIVATE
            val allowed = (o.opt("allowedReactions") as? JSONArray)?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    (arr.opt(i) as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_REACTION_UNITS }
                }.take(MAX_ALLOWED_REACTIONS)
            }.orEmpty()
            return GroupSettings(
                visibility = visibility,
                handle = handle,
                showPreview = (o.opt("showPreview") as? Boolean) ?: false,
                inviteToken = (o.opt("inviteToken") as? String)?.takeIf { TOKEN_RE.matches(it) },
                reactions = enumOf(o.opt("reactions"), ReactionMode.entries, ReactionMode.ALL) { it.raw },
                allowedReactions = allowed,
                welcome = truncateUtf16((o.opt("welcome") as? String).orEmpty(), MAX_WELCOME),
                postPolicy = enumOf(o.opt("postPolicy"), PostPolicy.entries, PostPolicy.EVERYONE) { it.raw },
                adminDMs = (o.opt("adminDMs") as? Boolean) ?: true,
                dissolved = (o.opt("dissolved") as? Boolean) ?: false,
                comments = (o.opt("comments") as? Boolean) ?: false,
                webPreview = (o.opt("webPreview") as? Boolean) ?: false,
                webIndexing = (o.opt("webIndexing") as? Boolean) ?: false,
            )
        }

        /** The wire object, rendered. Key order = the spec's example (§2.1). */
        fun encode(s: GroupSettings): String =
            GroupJson()
                .int("v", VERSION)
                .str("visibility", s.visibility.raw)
                .optional("handle", s.handle)
                .bool("showPreview", s.showPreview)
                .optional("inviteToken", s.inviteToken)
                .str("reactions", s.reactions.raw)
                .strArr("allowedReactions", s.allowedReactions)
                .str("welcome", s.welcome)
                .str("postPolicy", s.postPolicy.raw)
                .bool("adminDMs", s.adminDMs)
                .bool("dissolved", s.dissolved)
                .bool("comments", s.comments)
                .bool("webPreview", s.webPreview)
                .bool("webIndexing", s.webIndexing)
                .build()

        /** §3.2: 16 random bytes, base64url without padding (22 characters). */
        fun newInviteToken(random: SecureRandom = SecureRandom()): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))

        /** §3.3 rule 5: constant-time comparison of two invite tokens. */
        fun tokensMatch(expected: String, presented: String?): Boolean {
            if (presented == null) return false
            return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
        }

        /** §4.1: `hex sha256("oshi-group-handle-v1:" + UPPER(gid))` — the registry never sees the id. */
        fun groupRef(groupId: String): String {
            val d = MessageDigest.getInstance("SHA-256")
                .digest(("oshi-group-handle-v1:" + groupId.uppercase(Locale.ROOT)).toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }
        }

        /** Cut at [max] UTF-16 units without leaving half a surrogate pair behind. */
        fun truncateUtf16(s: String, max: Int): String {
            if (s.length <= max) return s
            val cut = s.substring(0, max)
            return if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut.dropLast(1) else cut
        }

        private fun stripVs(s: String) = s.replace("️", "")

        private fun <E> enumOf(raw: Any?, values: List<E>, default: E, wire: (E) -> String): E =
            (raw as? String)?.let { r -> values.firstOrNull { wire(it) == r } } ?: default
    }
}

/**
 * §3.1 public links: `https://oshi-messenger.com/g/<handle>` (also `.org`, `www.`),
 * `oshi://g/<handle>` and `oshi://group/join?h=<handle>`.
 */
object GroupPublicLink {
    const val HOST = "oshi-messenger.com"

    fun link(handle: String): String = "https://$HOST/g/$handle"

    /** The display form, without the scheme: `oshi-messenger.com/g/<handle>`. */
    fun display(handle: String): String = "$HOST/g/$handle"

    sealed interface Parsed {
        data class Handle(val handle: String) : Parsed
        /** It IS a public link, but the handle is not valid (§3.1 step 1: "invalid link"). */
        data object Invalid : Parsed
    }

    private val HTTPS = Regex("""https?://(?:www\.)?oshi-messenger\.(?:com|org)/g/([^\s/?#]*)""", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("""oshi://g/([^\s/?#]*)""", RegexOption.IGNORE_CASE)
    private val JOIN = Regex("""oshi://group/join\?(\S*)""", RegexOption.IGNORE_CASE)

    /** Null when [raw] is not a public group link at all (so other parsers may try it). */
    fun parse(raw: String?): Parsed? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = HTTPS.find(text)?.groupValues?.get(1)
            ?: SCHEME.find(text)?.groupValues?.get(1)
            ?: JOIN.find(text)?.groupValues?.get(1)?.let { q ->
                // Only the `h=` form is ours; `gid=` belongs to GroupInvite.
                val pairs = q.split('&').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }
                if (pairs.any { it.first == "gid" }) return null
                pairs.firstOrNull { it.first == "h" }?.second ?: return null
            }
            ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(candidate, Charsets.UTF_8) }.getOrDefault(candidate)
        return GroupSettings.normalizeHandle(decoded)?.let { Parsed.Handle(it) } ?: Parsed.Invalid
    }
}
