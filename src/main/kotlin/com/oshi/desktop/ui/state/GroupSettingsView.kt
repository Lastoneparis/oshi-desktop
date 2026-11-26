package com.oshi.desktop.ui.state

import com.oshi.desktop.group.GroupEvents
import com.oshi.desktop.group.GroupSettings
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.i18n.t

/**
 * __GROUP_INFO_SETTINGS_2026_11_20__ What the group info panel draws for GROUP_INFO_SETTINGS_SPEC
 * §5, already decided: who may edit what is computed here from the client's rules, so the
 * composables only place it.
 */
data class GroupSettingsView(
    val isAdmin: Boolean,
    val type: GroupType,
    val settings: GroupSettings,
    /** `https://oshi-messenger.com/g/<handle>` when the group is public. */
    val publicLink: String?,
    /** §2.4 may THIS account post? */
    val canPost: Boolean,
    val dissolved: Boolean,
    /** §2.7 current admins, creator first. */
    val admins: List<GroupMemberRow>,
    /** §2.7 the tombstones (admins only; empty for members). */
    val banned: List<BannedRow>,
    /** §2.8 last 48 h, newest first (admins only). */
    val events: List<EventRow>,
    /** §2.5 the welcome this device showed when it joined, if any. */
    val welcomeShown: String?,
    /** The registry call of the Group type editor: in flight, and its last answer. */
    val linkBusy: Boolean = false,
    val linkResult: LinkResult? = null,
    /** __CHANNEL_WEB_2026_11_26__ the "Public web preview" switch: in flight, last refusal, outbox size. */
    val web: ChannelWebView = ChannelWebView(),
) {
    /** "Message an admin": on, and there is an admin who is not us. */
    val messageAdminTargets: List<GroupMemberRow> get() =
        if (settings.adminDMs) admins.filter { !it.self } else emptyList()
}

/** __CHANNEL_WEB_2026_11_26__ [error] is already a sentence (`desktop.cw.err_*`). */
data class ChannelWebView(val busy: Boolean = false, val error: String? = null, val pending: Int = 0)

/**
 * __CHANNEL_WEB_2026_11_26__ The "Web link" dialog of one channel post: looking its number up,
 * then the link and the embed code — or "not on the web".
 */
data class WebLinkState(
    val conversationId: String,
    val messageId: String,
    val handle: String,
    val loading: Boolean = true,
    val n: Int? = null,
)

data class BannedRow(val address: String, val label: String)

data class EventRow(val text: String, val stamp: String)

/** Outcome of the last Public-link save, for the editor to show. */
enum class LinkResult { SAVED, TAKEN, INVALID, FAILED }

/**
 * §3.1 the confirmation a public link needs before anything is sent: resolved (or not) from the
 * registry. Nothing reaches any member until [Found] is confirmed.
 */
sealed interface PublicJoinState {
    val handle: String?
    data class Loading(override val handle: String) : PublicJoinState
    data class Found(
        override val handle: String,
        val name: String?,
        val description: String?,
        val memberCount: Int?,
        val result: com.oshi.desktop.group.GroupHandleRegistry.Lookup.Found,
    ) : PublicJoinState
    data class Problem(override val handle: String?, val message: String) : PublicJoinState
}

/** §2.8 one event as a sentence, in the reader's language. Literal keys only (CatalogAuditTest). */
fun eventSentence(e: GroupEvents.Event, actor: String, nameOf: (String) -> String): String = when (e.kind) {
    GroupEvents.Kind.RENAMED -> t("gs_ev_renamed", actor, e.detail)
    GroupEvents.Kind.DESCRIPTION_CHANGED -> t("gs_ev_description", actor)
    GroupEvents.Kind.PHOTO_CHANGED -> t("gs_ev_settings", actor, t("group.change_picture"))
    GroupEvents.Kind.MEMBER_ADDED -> t("gs_ev_member_added", actor, nameOf(e.detail))
    GroupEvents.Kind.MEMBER_REMOVED -> t("gs_ev_member_removed", actor, nameOf(e.detail))
    GroupEvents.Kind.MEMBER_LEFT -> t("gs_ev_member_left", actor)
    GroupEvents.Kind.ADMIN_PROMOTED -> t("gs_ev_admin_promoted", actor, nameOf(e.detail))
    GroupEvents.Kind.ADMIN_DEMOTED -> t("gs_ev_admin_demoted", actor, nameOf(e.detail))
    GroupEvents.Kind.VISIBILITY_CHANGED, GroupEvents.Kind.HANDLE_CHANGED -> t("gs_ev_settings", actor, t("gs_type"))
    GroupEvents.Kind.INVITE_LINK_RESET -> t("gs_ev_settings", actor, t("gs_invite_link"))
    GroupEvents.Kind.REACTIONS_CHANGED -> t("gs_ev_settings", actor, t("gs_reactions"))
    GroupEvents.Kind.WELCOME_CHANGED -> t("gs_ev_settings", actor, t("gs_welcome"))
    GroupEvents.Kind.POST_POLICY_CHANGED -> t("gs_ev_settings", actor, t("gs_who_can_post"))
    GroupEvents.Kind.ADMIN_DMS_CHANGED -> t("gs_ev_settings", actor, t("gs_admin_dms"))
    GroupEvents.Kind.JOINED_VIA_LINK -> t("gs_ev_joined_link", actor)
    GroupEvents.Kind.DISSOLVED -> t("gs_ev_dissolved", actor)
    GroupEvents.Kind.COMMENTS_CHANGED -> t("gs_ev_settings", actor, t("gs_comments"))
    GroupEvents.Kind.WEB_PREVIEW_CHANGED -> t("gs_ev_settings", actor, t("desktop.cw.title"))
}

fun permissionLabel(type: GroupType): String = when (type) {
    GroupType.ADMIN_ONLY -> t("gs_perm_admins")
    GroupType.COLLABORATIVE -> t("gs_perm_all")
    GroupType.PUBLIC -> t("gs_perm_open")
}

fun postPolicyLabel(p: GroupSettings.PostPolicy): String = when (p) {
    GroupSettings.PostPolicy.EVERYONE -> t("gs_post_everyone")
    GroupSettings.PostPolicy.ADMINS -> t("gs_post_admins")
}

fun reactionsLabel(s: GroupSettings): String = when {
    s.reactions == GroupSettings.ReactionMode.ALL -> t("gs_reactions_all")
    s.reactionsOff -> t("gs_reactions_none")
    else -> t("gs_reactions_some") + " · " + s.allowedReactions.joinToString(" ")
}
