package com.oshi.desktop.ui.state

import com.oshi.desktop.store.MediaType

/*
 * __MAC_PARITY_A_2027__ The pure rules behind six Mac-app features brought to the desktop
 * window: forward to several people, who reacted, reply privately, the gallery by type,
 * view-once and the voice-message effects. Plain Kotlin so the tests hold them, not a screen.
 */

/** One reaction on a message and who chose it, in the order the "Reactions" sheet lists them. */
data class ReactorGroup(val emoji: String, val names: List<String>)

object Reactors {
    /**
     * iOS `ReactorsListView`: one section per reaction, the most-chosen first, then by emoji so
     * the order is stable; names sorted, "You" first. [label] names a sender address.
     */
    fun groups(reactions: Map<String, Set<String>>, isMe: (String) -> Boolean, label: (String) -> String, you: String): List<ReactorGroup> =
        reactions.filterValues { it.isNotEmpty() }
            .map { (emoji, senders) ->
                val mine = senders.any(isMe)
                val others = senders.filterNot(isMe).map(label).sortedWith(String.CASE_INSENSITIVE_ORDER)
                ReactorGroup(emoji, (if (mine) listOf(you) else emptyList()) + others)
            }
            .sortedWith(compareByDescending<ReactorGroup> { it.names.size }.thenBy { it.emoji })

    fun total(groups: List<ReactorGroup>): Int = groups.sumOf { it.names.size }
}

/** __FORWARD_MANY_DESKTOP_2027__ The multi-recipient forward sheet's search and summary. */
object ForwardSelection {
    /** iOS `ForwardTargetSearch`: the name case-insensitively, the key exactly (case is significant in a key). */
    fun matches(query: String, name: String, address: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return name.contains(q, ignoreCase = true) || address.contains(q)
    }

    fun filter(targets: List<GroupCandidateRow>, query: String): List<GroupCandidateRow> =
        targets.filter { matches(query, it.label, it.address) }

    /** The sheet holds at most this many: each one is a separate 1:1 send, as on the phones. */
    const val MAX_RECIPIENTS = 20

    fun toggle(selected: List<String>, address: String): List<String> = when {
        address in selected -> selected - address
        selected.size >= MAX_RECIPIENTS -> selected
        else -> selected + address
    }
}

/** __VIEW_ONCE_DESKTOP_2027__ The phones' view-once rules, in one place. */
object ViewOnceRules {
    /**
     * What may be sent view-once: a photo, a video or a voice note (iOS `MediaPickerView`,
     * the voice preview's "1" toggle; Android `PendingMedia(viewOnce)`). Never a document, a
     * contact card, a GIF sticker or a round video note, and never in a group — iOS sends
     * group media with `isViewOnce: false` unconditionally.
     */
    fun canSendViewOnce(type: MediaType, videoNote: Boolean = false): Boolean =
        !videoNote && (type == MediaType.IMAGE || type == MediaType.VIDEO || type == MediaType.AUDIO)

    enum class Look {
        /** Not a view-once row. */
        NONE,
        /** Ours: drawn normally with a "View Once" badge (iOS `viewOnceSenderView`). */
        SENDER,
        /** Theirs, unopened: "Tap to view" — nothing of it is drawn until then. */
        SEALED,
        /** Theirs, opened in THIS visit of the conversation: shown until the user leaves. */
        OPEN,
        /** Theirs, opened before: the bytes are gone, "Viewed" (iOS `viewOnceViewedPlaceholder`). */
        VIEWED,
    }

    fun look(viewOnce: Boolean, fromMe: Boolean, opened: Boolean, revealedNow: Boolean, hasFile: Boolean): Look = when {
        !viewOnce -> Look.NONE
        fromMe -> Look.SENDER
        revealedNow && hasFile -> Look.OPEN
        opened || !hasFile -> Look.VIEWED
        else -> Look.SEALED
    }
}

/** __GALLERY_BY_TYPE_DESKTOP_2027__ iOS `MediaGalleryView`'s five categories. */
enum class GalleryCategory { PHOTOS, VIDEOS, DOCUMENTS, AUDIO, LINKS }

/** A link found in a message, for the gallery's Links tab. */
data class LinkItemRow(
    val messageId: String,
    val url: String,
    val host: String,
    val stamp: String,
    val fromMe: Boolean,
)

object GalleryCategories {
    fun of(kind: MediaType): GalleryCategory = when (kind) {
        MediaType.IMAGE -> GalleryCategory.PHOTOS
        MediaType.VIDEO -> GalleryCategory.VIDEOS
        MediaType.AUDIO -> GalleryCategory.AUDIO
        else -> GalleryCategory.DOCUMENTS
    }

    fun media(items: List<MediaItemRow>, c: GalleryCategory): List<MediaItemRow> =
        if (c == GalleryCategory.LINKS) emptyList() else items.filter { of(it.kind) == c }

    fun count(items: List<MediaItemRow>, links: List<LinkItemRow>, c: GalleryCategory): Int =
        if (c == GalleryCategory.LINKS) links.size else items.count { of(it.kind) == c }

    /** The tab a gallery opens on: the first one with something in it (photos when all are empty). */
    fun firstNonEmpty(items: List<MediaItemRow>, links: List<LinkItemRow>): GalleryCategory =
        GalleryCategory.entries.firstOrNull { count(items, links, it) > 0 } ?: GalleryCategory.PHOTOS

    // http(s) URLs and bare `www.` hosts; stops at whitespace and the brackets/quotes around
    // a link in prose. Trailing sentence punctuation is trimmed afterwards.
    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'()\[\]{}]+""")

    fun links(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', '…') }
            .filter { it.length > 8 }.distinct().toList()
    }

    fun host(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#')
            .removePrefix("www.").lowercase()

    /** What a click opens: a bare `www.` link gets the scheme a browser needs. */
    fun openable(url: String): String = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
}
