package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.CommentThreadView
import com.oshi.desktop.ui.state.MessageRow

/**
 * __GROUP_COMMENTS_2026_11_21__ GROUP_INFO_SETTINGS_SPEC §2.12.6 on the desktop: the
 * "N comments" bar under each post (led by the OSHI mark, [CommentsMark]) of an Active group, and the comment thread as a SIDE PANEL
 * beside the group (the post on top, its comments, a composer). A side panel rather than a pane
 * replacing the group: the channel stays readable next to the discussion, the way the info panel
 * already sits beside it. Keyboard: Esc closes it, Enter sends, Shift+Enter is a new line, and
 * each comment keeps the bubble's shortcuts (R reply, E react, Delete, Enter to edit one's own).
 */
class CommentActions(
    val onOpen: (postId: String) -> Unit,
    val onClose: () -> Unit,
    val onDraft: (String) -> Unit,
    val onSend: () -> Unit,
    val onReply: (commentId: String) -> Unit,
    val onCancelReply: () -> Unit,
)

/**
 * The bar's words (§2.12.8 `cm_comments_count` / `cm_leave_comment`). The desktop catalog has no
 * plural rules (no `.stringsdict` reader — `IosFormat`), so the one/other forms are two keys:
 * `cm_comments_count_one` for exactly one, `cm_comments_count` otherwise; languages whose plural
 * has more than two forms carry a neutral wording ("Comments: %d") in both.
 */
fun commentsBarLabel(count: Int): String = when {
    count <= 0 -> t("cm_leave_comment")
    count == 1 -> t("cm_comments_count_one", count)
    else -> t("cm_comments_count", count)
}

/** §2.12.6 the bar under a post: full bubble width, a chevron, opens the thread. */
@Composable
fun CommentsBar(count: Int, open: Boolean, onOpen: () -> Unit) {
    val (src, hovered) = rememberRowInteraction()
    val label = commentsBarLabel(count)
    Row(
        Modifier
            .padding(top = OshiTheme.xs)
            .widthIn(min = 220.dp, max = Metrics.bubbleMax)
            .clip(OshiTheme.radiusMd)
            .background(
                when {
                    open -> OshiTheme.brand.copy(alpha = 0.16f)
                    hovered.value -> OshiTheme.brand.copy(alpha = 0.10f)
                    else -> OshiTheme.surfaceElevated
                },
            )
            .border(Metrics.hairline, OshiTheme.separator.atMostAlpha(0.6f), OshiTheme.radiusMd)
            .focusRing(OshiTheme.radiusMd)
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClickLabel = t("cm_thread_title"), onClick = onOpen)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        CommentsMark(18.dp)
        Text(
            label,
            style = OshiTheme.typography.labelMedium,
            fontWeight = if (count > 0) FontWeight.SemiBold else FontWeight.Normal,
            color = OshiTheme.brand,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        GlyphIcon(Glyph.CHEVRON_RIGHT, OshiTheme.brand.copy(alpha = 0.8f), 14.dp)
    }
}

/**
 * __COMMENTS_OSHI_MARK_2026_11_21__ The comments icon on the desktop: the OSHI mark (ring + bubble
 * tail, no face — the brand "O", [OshiSmileMark] with `face = false`) in the brand colour, instead
 * of the 💬 emoji. Vector, so it is crisp at 18 dp and takes the theme's brand tint in Light, Dark,
 * Extra dark and glass alike (the emoji was a fixed-colour bitmap from the system font, a different
 * drawing on Windows, macOS and each Linux distribution). Decorative: the bar/heading beside it
 * carries the words. Android and iOS still show 💬.
 */
@Composable
fun CommentsMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = OshiTheme.brand) {
    OshiSmileMark(size = size, modifier = modifier, color = color, face = false, label = null)
}

/**
 * The thread panel. [canReact]: the group's reaction setting allows at least one emoji.
 * [allowedReactions]: the "some" list, for the picker (null = all).
 */
@Composable
fun CommentsPanel(
    view: CommentThreadView,
    actions: CommentActions,
    wallpaper: WallpaperId,
    canReact: Boolean,
    onReact: (String, String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteForMe: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    Row(
        modifier.fillMaxHeight().onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                if (view.replyingTo != null) actions.onCancelReply() else actions.onClose()
                true
            } else false
        },
    ) {
        Box(Modifier.width(Metrics.hairline).fillMaxHeight().background(OshiTheme.separator))
        Column(Modifier.width(400.dp).fillMaxHeight().background(OshiTheme.background)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = OshiTheme.lg, end = OshiTheme.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommentsMark(22.dp, Modifier.padding(end = OshiTheme.sm))
                Text(
                    t("cm_thread_title"),
                    style = OshiTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.strong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (view.post != null) {
                    val n = view.comments.count { !it.deleted }
                    if (n > 0) Text("$n", style = OshiTheme.typography.labelMedium, color = Ink.soft, modifier = Modifier.padding(end = OshiTheme.sm))
                }
                GlyphButton(Glyph.CLOSE, t("common.close"), shortcut = "Esc", onClick = actions.onClose)
            }
            Hairline()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val post = view.post
                if (post == null) {
                    CenteredNote(t("cm_post_unavailable"))
                } else {
                    CommentLog(view, post, wallpaper, canReact, actions, onReact, onEdit, onDelete, onDeleteForMe, onCopy)
                }
            }
            Hairline()
            when (view.state) {
                CommentThreadView.State.OPEN -> {
                    view.replyingTo?.let { q ->
                        Row(
                            Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated)
                                .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t("cm_replying_to", q.who.ifBlank { "…" }), style = OshiTheme.typography.labelMedium, color = OshiTheme.brand, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(q.text, style = OshiTheme.typography.bodySmall, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            GlyphButton(Glyph.CLOSE, t("common.cancel"), onClick = actions.onCancelReply)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().background(OshiTheme.surface).padding(OshiTheme.md),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
                    ) {
                        // Text only (§2.12.2): no attach, voice, GIF or poll button here.
                        DraftField(
                            draft = view.draft,
                            busy = view.busy,
                            onDraft = actions.onDraft,
                            onEnter = { if (view.draft.isNotBlank() && !view.busy) actions.onSend() },
                            modifier = Modifier.weight(1f),
                            placeholder = t("cm_composer_hint"),
                            focusRequester = focus,
                        )
                        SendButton(canSend = view.draft.isNotBlank() && !view.busy, busy = view.busy, onSend = actions.onSend)
                    }
                    // Opening the thread, or picking a comment to answer, puts the caret in the box.
                    LaunchedEffect(view.postId, view.replyingTo?.messageId) { runCatching { focus.requestFocus() } }
                }
                CommentThreadView.State.DISABLED -> FooterNote(t("cm_disabled"))
                CommentThreadView.State.POST_UNAVAILABLE -> Unit
            }
        }
    }
}

@Composable
private fun CommentLog(
    view: CommentThreadView,
    post: MessageRow,
    wallpaper: WallpaperId,
    canReact: Boolean,
    actions: CommentActions,
    onReact: (String, String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteForMe: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    // A new comment (ours or theirs) scrolls into view; the post stays one scroll away.
    LaunchedEffect(view.postId, view.comments.size) {
        if (view.comments.isNotEmpty()) listState.animateScrollToItem(view.comments.size + 1)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = OshiTheme.lg),
        state = listState,
        contentPadding = PaddingValues(vertical = OshiTheme.md),
    ) {
        item(key = ROW_POST) {
            // The post as in the timeline (reactions included), without a bar of its own.
            MessageBubbleRow(
                row = post.copy(comments = null),
                grouped = false,
                lastInRun = true,
                wallpaper = wallpaper,
                viewOnce = false,
                revealed = true,
                onReveal = {},
                onReact = if (canReact && !post.deleted) { e -> onReact(post.id, e) } else null,
                onCopy = if (post.body.isNotBlank()) ({ onCopy(post.body) }) else null,
                showSender = true,
            )
        }
        item(key = ROW_DIVIDER) {
            Box(Modifier.fillMaxWidth().padding(vertical = OshiTheme.md)) {
                Box(Modifier.fillMaxWidth().heightIn(min = Metrics.hairline, max = Metrics.hairline).background(OshiTheme.separator).align(Alignment.Center))
            }
        }
        if (view.comments.isEmpty()) {
            item(key = ROW_EMPTY) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = OshiTheme.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CommentsMark(40.dp, color = OshiTheme.brand.copy(alpha = 0.55f))
                    CenteredNote(t("cm_empty"), Modifier.padding(top = 0.dp))
                }
            }
        }
        items(view.comments, key = { it.id }) { c ->
            val open = view.state == CommentThreadView.State.OPEN
            MessageBubbleRow(
                row = c,
                grouped = false,
                lastInRun = true,
                wallpaper = wallpaper,
                viewOnce = false,
                revealed = true,
                onReveal = {},
                onReact = if (canReact && !c.deleted) { e -> onReact(c.id, e) } else null,
                onEdit = if (c.fromMe && !c.deleted && open) { body -> onEdit(c.id, body) } else null,
                // §2.12.4 D: one's own, or any as an admin.
                onDelete = if (!c.deleted && (c.fromMe || view.canModerate)) { { onDelete(c.id) } } else null,
                onReply = if (open && !c.deleted) { { actions.onReply(c.id) } } else null,
                onDeleteForMe = { onDeleteForMe(c.id) },
                onCopy = if (c.body.isNotBlank() && !c.deleted) ({ onCopy(c.body) }) else null,
                showSender = true,
                deleteTitle = t("cm_delete_comment"),
            )
        }
    }
}

@Composable
private fun CenteredNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(OshiTheme.xl), contentAlignment = Alignment.Center) {
        Text(text, style = OshiTheme.typography.bodyMedium, color = Ink.soft, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FooterNote(text: String) {
    Box(
        Modifier.fillMaxWidth().background(OshiTheme.surface).padding(OshiTheme.lg).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OshiTheme.typography.bodyMedium, color = Ink.soft, textAlign = TextAlign.Center)
    }
}

// LazyColumn keys of the fixed rows. Constants, not `item(key = "…")` literals: the catalog audit
// reads every `…t("x")` call in the source as a string key, and `item(` ends in `t(`.
private const val ROW_POST = "comments.row.post"
private const val ROW_DIVIDER = "comments.row.divider"
private const val ROW_EMPTY = "comments.row.empty"
