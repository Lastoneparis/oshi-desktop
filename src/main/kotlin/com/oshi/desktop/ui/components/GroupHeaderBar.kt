package com.oshi.desktop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ThreadView

// ─────────────────────────────────────────────────────────────────────────
// __GROUP_HEADER_ISLAND_2026_11_24__ The group conversation's header.
//
// « Keep the arrow on the left, then the loupe, but make a little island /
// cloud floating effect with name and number of members, and on the right,
// instead of the button with the 3 dots, put the picture of the group,
// clickable. » The desktop has no back arrow (the list is always beside the
// thread), so left to right, mirrored in RTL by the Row itself:
//
//   (🔍)          ( Team Desk )          [calls]  (avatar)
//                 ( 4 members )
//
// Each piece floats on the frosted bar (`frostedBar`): a round glass button, a
// glass capsule, the group picture in a glass ring. Click on the island or the
// avatar opens the info panel; right-click (or long-press) on the avatar opens
// the old ⋯ menu, anchored to it. Every item of that menu is also in the info
// panel (ThreadInfoPanel). 1:1 conversations keep `ThreadHeaderBar` unchanged.
// ─────────────────────────────────────────────────────────────────────────

private val ButtonSize = 44.dp
private val IslandShape: Shape = RoundedCornerShape(50)

/** Glass when the glass switch is on; the surface with a hairline and a soft shadow otherwise. */
@Composable
private fun Modifier.headerFloat(shape: Shape): Modifier {
    val g = currentGlass()
    val shadowed = if (g == null) {
        this.shadow(4.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
    } else this
    return shadowed.glass(shape, flat = OshiTheme.surface, flatBorder = OshiTheme.separator, raised = true)
}

/** The round glass button of the header (search). */
@Composable
private fun HeaderRoundButton(label: String, shortcut: String?, active: Boolean, glyph: Glyph, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    WithTooltip(label, shortcut) {
        Box(
            Modifier
                .size(ButtonSize)
                .headerFloat(CircleShape)
                .background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.06f) else Color.Transparent)
                .focusRing(CircleShape)
                .clickable(interactionSource = source, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
                .hoverable(source)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph, if (active) OshiTheme.brand else OshiTheme.textPrimary, 18.dp)
        }
    }
}

/** "4 members" / "30 subscribers" (admin-only groups read like a channel), or who is typing. */
@Composable
internal fun groupHeaderSubtitle(thread: ThreadView): Pair<String, Boolean> {
    if (thread.typingNames.isNotEmpty()) return t("typing.indicator", thread.typingNames.joinToString(", ")) to true
    val n = thread.groupMembers.size
    val channel = thread.groupSettings?.type == GroupType.ADMIN_ONLY
    val text = when {
        channel && n == 1 -> t("pc.group_header.subscriber_singular")
        channel -> t("pc.group_header.subscribers", n)
        n == 1 -> t("group_member_count_singular")
        else -> t("group_member_count", n)
    }
    return text to false
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupThreadHeaderBar(
    thread: ThreadView,
    actions: ThreadChromeActions,
    searchOpen: Boolean,
    wallpaperActive: Boolean,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    ground: Color = OshiTheme.background,
) {
    val infoLabel = t("pc.group_header.info_a11y", thread.title)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(ground)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        actions.onOpenSearch?.let {
            HeaderRoundButton(t("pc.group_header.search"), shortcutLabel("F", meta = true), searchOpen, Glyph.SEARCH, it)
        }

        // The island, centred in the room between the buttons.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val (source, hovered) = rememberRowInteraction()
            val (subtitle, activity) = groupHeaderSubtitle(thread)
            Column(
                Modifier
                    .widthIn(min = 140.dp, max = 520.dp)
                    .heightIn(min = 44.dp)
                    .headerFloat(IslandShape)
                    .background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.04f) else Color.Transparent)
                    .focusRing(IslandShape)
                    .clickable(interactionSource = source, indication = null, onClickLabel = infoLabel, role = Role.Button, onClick = actions.onOpenInfo)
                    .hoverable(source)
                    .semantics { contentDescription = "$infoLabel, $subtitle" }
                    .padding(horizontal = 22.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        thread.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink.strong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (thread.groupMuted) GlyphIcon(Glyph.BELL_SLASH, Ink.soft, 13.dp)
                }
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = if (activity) OshiTheme.brand else Ink.soft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        actions.onCall?.let { call ->
            IconActionButton(t("chat.voice_call"), icon = { CallGlyphIcon(CallGlyph.PHONE, it, 19.dp) }) { call(false) }
            IconActionButton(t("chat.video_call"), icon = { CallGlyphIcon(CallGlyph.CAMERA, it, 21.dp) }) { call(true) }
        }

        // The group picture where the ⋯ was: click = info, right-click / long-press = the old menu.
        Box {
            val (source, hovered) = rememberRowInteraction()
            WithTooltip(infoLabel) {
                Box(
                    Modifier
                        .size(ButtonSize)
                        .headerFloat(CircleShape)
                        .focusRing(CircleShape)
                        .onSecondaryClick { onMenuOpen(true) }
                        .combinedClickable(
                            interactionSource = source,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = infoLabel,
                            onLongClickLabel = t("a11y.more_options"),
                            onLongClick = { onMenuOpen(true) },
                            onClick = actions.onOpenInfo,
                        )
                        .hoverable(source)
                        .semantics { contentDescription = infoLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(ButtonSize - 6.dp)
                            .clip(CircleShape)
                            .then(if (hovered.value || menuOpen) Modifier.border(2.dp, OshiTheme.brand.copy(alpha = 0.6f), CircleShape) else Modifier)
                            .clearAndSetSemantics {},
                    ) {
                        GroupAvatar(thread.groupPictureBase64, thread.title, thread.address, ButtonSize - 6.dp)
                    }
                }
            }
            OshiDropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }, offset = DpOffset((-8).dp, 4.dp)) {
                ThreadOverflowItems(thread, actions, wallpaperActive) { onMenuOpen(false) }
            }
        }
    }
}
