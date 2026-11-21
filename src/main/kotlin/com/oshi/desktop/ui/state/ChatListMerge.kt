package com.oshi.desktop.ui.state

import com.oshi.desktop.group.GroupType

/**
 * __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes » — desktop twin of Android's
 * `data/model/ChatListMerge.kt` (b71e5440) and iOS's `ChatListMerge.swift` (a69e89e1).
 *
 * An OPTION (More › Appearance › "Combine messages and groups") that replaces the sidebar's
 * `Messages | Groups` control with one chronological list and filter chips — All · Unread ·
 * Groups · People. With the option off nothing about the two halves changes.
 *
 * Pure: the list pane feeds it the [ConversationRow]s the model already published.
 *
 * What counts as GROUP-LIKE follows the phones: a LoRa channel is a room, so it files under
 * Groups (Android `Kind.RADIO_CHANNEL`). A bot channel is a one-to-many lane keyed by a
 * group id, so it does too. Only a ratcheted 1:1 is a PERSON.
 */
object ChatListMerge {

    enum class Filter { ALL, UNREAD, GROUPS, PEOPLE }

    /**
     * The Groups sub-filter (the phones' Groups tab chips: All · Admin Only · Collaborative ·
     * Public). Applies to real groups only; a radio/bot channel has no group type and shows
     * under [ALL] alone.
     */
    enum class GroupSub(val type: GroupType?) {
        ALL(null), ADMIN_ONLY(GroupType.ADMIN_ONLY), COLLABORATIVE(GroupType.COLLABORATIVE), PUBLIC(GroupType.PUBLIC)
    }

    /** Badge counts for the chips: THREADS with something unread, per chip (not messages). */
    data class Counts(val unread: Int, val groups: Int, val people: Int)

    fun isGroupLike(row: ConversationRow): Boolean = row.kind != ConversationKind.DIRECT

    fun matches(row: ConversationRow, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.UNREAD -> row.unread > 0
        Filter.GROUPS -> isGroupLike(row)
        Filter.PEOPLE -> row.kind == ConversationKind.DIRECT
    }

    fun matches(row: ConversationRow, sub: GroupSub): Boolean =
        sub.type == null || (row.kind == ConversationKind.GROUP && row.groupType == sub.type)

    /**
     * What the merged list shows, in order: newest activity first across both kinds, ties
     * broken by id so two rows stamped in the same millisecond never swap between frames;
     * then the chip, the group sub-filter (only meaningful under [Filter.GROUPS]) and the
     * search, which matches the shown name or the preview, case-insensitive (the same
     * fields `ConversationFilter.apply` searches, so the box behaves the same in both modes).
     */
    fun arrange(rows: List<ConversationRow>, filter: Filter, sub: GroupSub, query: String, textHits: Set<String> = emptySet()): List<ConversationRow> {
        val q = query.trim()
        return rows
            .filter { matches(it, filter) }
            .filter { filter != Filter.GROUPS || matches(it, sub) }
            .filter { q.isEmpty() || it.id in textHits || it.label.contains(q, true) || it.preview.contains(q, true) || it.id.contains(q, true) }
            .sortedWith(compareByDescending<ConversationRow> { it.lastActivityMs }.thenBy { it.id })
    }

    fun counts(rows: List<ConversationRow>): Counts = Counts(
        unread = rows.count { it.unread > 0 },
        groups = rows.count { isGroupLike(it) && it.unread > 0 },
        people = rows.count { it.kind == ConversationKind.DIRECT && it.unread > 0 },
    )

    /**
     * DEFAULT, decided once per profile and written down (Android `MergedChatsSetting`, iOS
     * the same): ON only for a FRESH profile — someone new has no habit to break — and OFF
     * for every existing one, so an update never rearranges the list they open all day.
     * Fresh = no key vault on disk yet when the window starts.
     */
    fun defaultFor(profileIsFresh: Boolean): Boolean = profileIsFresh
}
