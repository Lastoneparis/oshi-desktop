package com.oshi.desktop.ui.components

import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.ConversationRow

/**
 * The sidebar's search box — a LOCAL filter over rows the model already published.
 *
 * ============================================================ IT IS A FILTER, NOT A SEARCH
 *
 * The shipped `MessagesListView` has a `.searchable` drawer that searches messages AND
 * contacts, including conversations the user has no history with. This filters the list the
 * model handed over and nothing else: no store query, no contact lookup, no network. The
 * placeholder therefore says "Filter conversations" and not "Search messages & contacts",
 * because a box promising message search that only matches the one-line preview would be a
 * capability claim this window cannot back.
 *
 * ============================================================ THE EMPTY STATE IS A DECISION
 *
 * "No conversations yet, here is how to start one" and "nothing matched what you typed" are
 * different sentences, and showing the first one to somebody mid-search is the classic wrong
 * empty state — it reads as if their history had been deleted. [emptyReason] separates them
 * and is what `ConversationFilterTest` holds.
 */
object ConversationFilter {

    /** Why a list is empty, or null when it is not. */
    enum class EmptyReason { NO_CONVERSATIONS, NO_MATCHES }

    /**
     * Case-insensitive match over the label, the preview and the conversation id.
     *
     * A blank query returns the input list UNCHANGED — same order, same rows — so typing and
     * then clearing the box cannot reorder the sidebar.
     */
    fun apply(rows: List<ConversationRow>, query: String): List<ConversationRow> {
        val q = query.trim()
        if (q.isEmpty()) return rows
        val needle = q.lowercase()
        return rows.filter { row ->
            row.label.lowercase().contains(needle) ||
                row.preview.lowercase().contains(needle) ||
                row.id.lowercase().contains(needle)
        }
    }

    /**
     * @param total how many conversations the model published, before filtering.
     * @param shown how many survived [apply].
     */
    fun emptyReason(total: Int, shown: Int, query: String): EmptyReason? = when {
        shown > 0 -> null
        total == 0 -> EmptyReason.NO_CONVERSATIONS
        query.isBlank() -> EmptyReason.NO_CONVERSATIONS
        else -> EmptyReason.NO_MATCHES
    }

    /**
     * The filter box's placeholder.
     *
     * A `val`, not a `const`, because a `const` is resolved once at class initialisation —
     * which is how `DESTINATION_LABELS` ended up rendering an English navigation strip
     * inside an otherwise translated window. A language preference read after startup has
     * to reach this.
     *
     * No iOS key matches: the phone's box says "Search messages & contacts" because it
     * searches both, and this one filters a list the model already published. Borrowing
     * that key would have been a capability claim in 34 languages. So it is a desktop key.
     */
    val PLACEHOLDER: String get() = com.oshi.desktop.i18n.dt("desktop.filter.placeholder")

    /**
     * The `Messages | Groups` split from the shipped macOS list, applied to the same rows.
     *
     * The phone's control is two buttons over one store and so is this: there is no second
     * query and no second source. What lands in each half is decided by [ConversationKind],
     * which the model already computed — a group is a group because `GroupStore` has it,
     * never because its label looked like one.
     *
     * **`bot!` and `lora!` threads sit under Messages, not under Groups.** A bot channel IS
     * a group on the wire — it is keyed by `groupId` — and putting it in the Groups half
     * would file an unencrypted lane (row 0.26) next to ratcheted group conversations under
     * one heading. The kinds stay visibly distinct in the row itself; the tab must not be
     * what merges them.
     */
    fun ofKind(rows: List<ConversationRow>, half: Half): List<ConversationRow> = when (half) {
        Half.MESSAGES -> rows.filter { it.kind != ConversationKind.GROUP }
        Half.GROUPS -> rows.filter { it.kind == ConversationKind.GROUP }
    }

    enum class Half { MESSAGES, GROUPS }

    /** The unread total a half's badge shows — the phone puts it on the segment, not the row. */
    fun unreadIn(rows: List<ConversationRow>, half: Half): Int =
        ofKind(rows, half).sumOf { it.unread }
}
