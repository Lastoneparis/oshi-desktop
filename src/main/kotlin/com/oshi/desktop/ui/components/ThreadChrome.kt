package com.oshi.desktop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.GroupMemberRow
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ThreadView

/**
 * __THREAD_CHROME_2027__ The conversation's chrome, rebuilt to read like the phones.
 *
 * ============================================================ WHAT WAS WRONG
 *
 * The header was a FlowRow of text links — Rename, Media Gallery, Search messages, Report
 * Group, Show members, Leave Group, Change Picture, Mute, Block Group, Invite Link, Delete
 * Group, Group Description, Rename — over the group's raw UUID, and every message carried a
 * row of text links under it whose last item wrapped as "Dele/te". Every action was there
 * and none of them could be found.
 *
 * ============================================================ WHAT IT IS NOW
 *
 *  * ONE header row: avatar, bold name, one subline, then icons — search, voice, video —
 *    and a "⋯" menu that holds everything else, grouped and separated, destructive last
 *    and red, each destructive item confirmed. The address is not in the header; it is in
 *    the Info panel, with a copy button.
 *  * Clicking the name or avatar opens [ThreadInfoPanel], a side panel with the members,
 *    the description, the media and the same actions.
 *  * Messages get an icon-only toolbar that floats beside the bubble on hover or focus
 *    ([MessageHoverToolbar]), with tooltips that name the keyboard shortcut, and a
 *    right-click menu with the same actions, labelled ([MessageActionMenuItems]).
 *
 * Every label is an EXISTING key of the shared catalogs (extracted from the iOS `.lproj`),
 * so all 34 locales are served without a single new string.
 */

/**
 * Forces chrome state for the headless renderers (`ThreadChromeScreens`). The window
 * never provides it, so in the product every field is false/null.
 */
data class ThreadChromePreview(
    val headerMenuOpen: Boolean = false,
    val infoOpen: Boolean = false,
    val toolbarMessageId: String? = null,
    val contextMenuMessageId: String? = null,
    val reactBarMessageId: String? = null,
    val moreMenuMessageId: String? = null,
    /** __CONVERSATION_VERIFY_2027__ / __CONVERSATION_EXPORT_2027__ dialogs, for the renderer. */
    val verifyOpen: Boolean = false,
    val exportOpen: Boolean = false,
    /** Composer surfaces and the in-conversation search, for the renderer. */
    val panelOpen: Boolean = false,
    val pollOpen: Boolean = false,
    val contactOpen: Boolean = false,
    val searchQuery: String? = null,
    /** __MAC_PARITY_A_2027__ the new sheets and panels, for the renderer. */
    val reactorsMessageId: String? = null,
    val forwardMessageId: String? = null,
    val galleryOpen: Boolean = false,
    val galleryTab: com.oshi.desktop.ui.state.GalleryCategory? = null,
    val voicePreview: Boolean = false,
    val viewOnceArmed: Boolean = false,
    /** __GROUP_MEMBER_MODERATION_2027__ a member's menu / report panel, for the renderer. */
    val memberMenuAddress: String? = null,
    val reportMemberAddress: String? = null,
    /** __LOCK_STOPS_CAMERA__ the composer's camera panel starts open (renderer / tests). */
    val cameraOpen: Boolean = false,
    /** __GROUP_INFO_SETTINGS_2026_11_20__ a group-settings dialog starts open (renderer). */
    val groupDialog: GroupSettingsDialog? = null,
)

val LocalThreadChromePreview = staticCompositionLocalOf { ThreadChromePreview() }

// ---------------------------------------------------------------------------- primitives

/** A tooltip bubble: the label, and the keyboard shortcut in a key cap when there is one. */
@Composable
private fun ChromeTooltip(label: String, shortcut: String?) {
    Row(
        Modifier
            .glassFloating(OshiTheme.radiusSm, OshiTheme.textPrimary.copy(alpha = 0.92f))
            .padding(horizontal = OshiTheme.sm, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (OshiTheme.glass != null) OshiTheme.textPrimary else OshiTheme.background
        Text(label, fontSize = 12.sp, color = ink, maxLines = 1, softWrap = false)
        if (shortcut != null) {
            Text(
                shortcut,
                fontSize = 11.sp,
                color = ink.copy(alpha = 0.75f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ink.copy(alpha = 0.14f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** Wraps [content] in a delayed tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithTooltip(label: String, shortcut: String? = null, content: @Composable () -> Unit) {
    // __LOCK_STOPS_CAMERA__ a tooltip is a popup too: none under the lock screen.
    if (LocalHiddenByLock.current) { content(); return }
    TooltipArea(
        tooltip = { ChromeTooltip(label, shortcut) },
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomCenter,
            alignment = Alignment.BottomCenter,
            offset = DpOffset(0.dp, 6.dp),
        ),
    ) { content() }
}

/**
 * A round icon-only button: hover tint, focus ring, a tooltip, and the label as its
 * accessible name (a screen reader reads the label, never "button").
 */
@Composable
fun IconActionButton(
    label: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    size: Dp = Metrics.actionButton,
    active: Boolean = false,
    enabled: Boolean = true,
    icon: @Composable (tint: Color) -> Unit,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberRowInteraction()
    WithTooltip(label, shortcut) {
        Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    when {
                        active -> OshiTheme.brand.copy(alpha = 0.14f)
                        hovered.value && enabled -> OshiTheme.textPrimary.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    },
                )
                .focusRing(CircleShape)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = label,
                    role = Role.Button,
                    onClick = onClick,
                )
                .hoverable(source)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            val tint = when {
                !enabled -> Ink.soft.copy(alpha = 0.35f)
                active -> OshiTheme.brand
                hovered.value -> OshiTheme.textPrimary
                else -> Ink.soft
            }
            icon(tint)
        }
    }
}

@Composable
fun GlyphButton(
    glyph: Glyph,
    label: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    size: Dp = Metrics.actionButton,
    glyphSize: Dp = 18.dp,
    active: Boolean = false,
    onClick: () -> Unit,
) = IconActionButton(label, modifier, shortcut, size, active, icon = { GlyphIcon(glyph, it, glyphSize) }, onClick = onClick)

/** One menu row: glyph, label (never wrapping), optional shortcut, red when destructive. */
@Composable
fun ChromeMenuItem(
    glyph: Glyph?,
    label: String,
    danger: Boolean = false,
    shortcut: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ink = if (danger) OshiTheme.danger else OshiTheme.textPrimary
    DropdownMenuItem(
        // Two lines before an ellipsis: M3 caps a menu at 280 dp and a French or German label
        // ("Utiliser seulement le mesh et la radio") would otherwise lose its meaning.
        text = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 17.sp) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = glyph?.let { g -> { GlyphIcon(g, if (danger) OshiTheme.danger else Ink.soft, 18.dp) } },
        trailingIcon = shortcut?.let { s -> { Text(s, fontSize = 12.sp, color = Ink.soft, maxLines = 1, softWrap = false) } },
        colors = MenuDefaults.itemColors(textColor = ink),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        modifier = Modifier.heightIn(min = 38.dp).widthIn(min = 220.dp),
    )
}

@Composable
fun ChromeMenuSeparator() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = Metrics.hairline, color = OshiTheme.separator)
}

/** The avatar every chrome surface draws: picture or 16-gradient monogram, plus the group mark. */
@Composable
fun ThreadAvatar(thread: ThreadView, size: Dp) {
    Box {
        GroupAvatar(thread.groupPictureBase64, thread.title, thread.address, size)
        if (thread.kind == ConversationKind.GROUP) {
            val badge = (size.value * 0.36f).coerceAtLeast(14f).dp
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .clip(CircleShape)
                    .background(OshiTheme.background)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(OshiTheme.brand),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.PEOPLE, if (OshiTheme.palette.isDark) Color(0xFF14082E) else Color.White, badge * 0.62f) }
        }
    }
}

/** A small rounded capsule ("Admin", "You"). */
@Composable
fun ChromePill(label: String, tint: Color) {
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(OshiTheme.pill)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// ---------------------------------------------------------------------------- dialogs

/** Glass alert with a red confirm button: every destructive chrome action goes through it. */
@Composable
fun ConfirmDestructiveDialog(
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = text?.let { { Text(it, style = OshiTheme.typography.bodyMedium, color = Ink.soft) } },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(onClick = { onDismiss(); onSecondary() }) {
                        Text(secondaryLabel, color = OshiTheme.danger, maxLines = 1, softWrap = false)
                    }
                }
                TextButton(onClick = { onDismiss(); onConfirm() }) {
                    Text(confirmLabel, color = OshiTheme.danger, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("common.cancel"), color = OshiTheme.textPrimary, maxLines = 1, softWrap = false) }
        },
    )
}

/** One-field edit dialog (rename, description). Save refuses an unchanged value. */
@Composable
fun TextEditDialog(
    title: String,
    initial: String,
    maxLength: Int,
    singleLine: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            BasicTextField(
                value = value,
                onValueChange = { value = it.take(maxLength) },
                singleLine = singleLine,
                textStyle = OshiTheme.typography.bodyMedium.copy(color = OshiTheme.textPrimary),
                cursorBrush = SolidColor(OshiTheme.brand),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (singleLine) 0.dp else 88.dp)
                    .clip(OshiTheme.radiusSm)
                    .background(OshiTheme.textPrimary.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .focusRequester(focus),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = {
            val canSave = value != initial && (value.isNotBlank() || !singleLine)
            TextButton(onClick = { onSave(value.trim()); onDismiss() }, enabled = canSave) {
                Text(t("common.save"), color = if (canSave) OshiTheme.brand else Ink.soft, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel"), color = OshiTheme.textPrimary) } },
    )
}

/** The group's invite link as a QR code, the link, and a copy button. */
@Composable
fun InviteLinkDialog(link: String, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("group.invite_link"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                Box(Modifier.clip(OshiTheme.radiusMd).background(Color.White).padding(OshiTheme.sm)) { QrCode(link, size = 168.dp) }
                Text(t("group_invite_scan_hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft, textAlign = TextAlign.Center)
                SelectionContainer {
                    Text(link, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = OshiTheme.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                // __GROUP_QR_SAVE_2027__ the code as a picture, to send or print.
                var saved by remember(link) { mutableStateOf<Boolean?>(null) }
                SettingsButton(SettingsIcon.EXPORT, t("pc.groups.qr.save")) {
                    saved = saveGroupQrImage(link)
                }
                saved?.let { SettingsNotice(if (it) t("pc.groups.qr.saved") else t("pc.groups.qr.save_failed"), error = !it) }
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(link) }) { Text(t("url_action_copy"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.close"), color = OshiTheme.textPrimary) } },
    )
}

// ---------------------------------------------------------------------------- the header

/** Everything the header and the info panel can do. Null = not offered here. */
class ThreadChromeActions(
    val onOpenInfo: () -> Unit,
    val onOpenSearch: (() -> Unit)?,
    val onCall: ((video: Boolean) -> Unit)?,
    val onOpenGallery: (() -> Unit)?,
    val onWallpaper: () -> Unit,
    val onReport: (() -> Unit)?,
    val onSetOfflineOnly: ((Boolean) -> Unit)?,
    val onUnpin: (() -> Unit)?,
    val onRename: (() -> Unit)?,
    val onEditDescription: (() -> Unit)?,
    val onChangePicture: (() -> Unit)?,
    val onRemovePicture: (() -> Unit)?,
    val onShowInvite: (() -> Unit)?,
    val onSetMuted: ((Boolean) -> Unit)?,
    val onSetBlocked: ((Boolean) -> Unit)?,
    val onLeave: (() -> Unit)?,
    val onDelete: (() -> Unit)?,
    val onCopy: (String) -> Unit,
    val onAddMember: (String) -> Unit,
    val onRemoveMember: (String) -> Unit,
    val onSetMemberAdmin: (String, Boolean) -> Unit,
    /** __CONVERSATION_PIN_MUTE_2027__ pin the conversation to the top of the list. */
    val onSetChatPinned: ((Boolean) -> Unit)? = null,
    /** __CONVERSATION_VERIFY_2027__ open the safety-number sheet (1:1 only). */
    val onVerify: (() -> Unit)? = null,
    /** __CONVERSATION_EXPORT_2027__ export this conversation as text. */
    val onExport: (() -> Unit)? = null,
    /**
     * __GROUP_MEMBER_MODERATION_2027__ report ONE member, filed as a contact (iOS GroupViews
     * `memberReportTarget`): moderation receives a key it can act on, not a room id.
     */
    val onReportMember: ((String) -> Unit)? = null,
    /** __GROUP_MEMBER_MODERATION_2027__ block / unblock one member, for everyone (not only admins). */
    val onSetMemberBlocked: ((String, Boolean) -> Unit)? = null,
    /**
     * __MERGE_AB_MEMBER_MENU__ "Reply privately" from the member list: open the 1:1 with that
     * member, no quote (Android's member sheet `onReplyInPrivate`, first entry; the message
     * menu's Reply privately is the same action with the message quoted).
     */
    val onReplyPrivatelyToMember: ((String) -> Unit)? = null,
    /** __DESKTOP_1_6_PARITY__ open the safety check-in sheet (1:1 only). */
    val onAlive: (() -> Unit)? = null,
    /** __GROUP_INFO_SETTINGS_2026_11_20__ the group settings of GROUP_INFO_SETTINGS_SPEC §5 (groups only). */
    val groupSettings: GroupSettingsActions? = null,
)

/** Muted, whatever the kind: a group's flag or a 1:1's (both land in `groupMuted`). */
private val ThreadView.isMuted: Boolean get() = groupMuted

/** Blocked, whatever the kind. */
private val ThreadView.isBlocked: Boolean get() = if (kind == ConversationKind.GROUP) groupBlocked else contactBlocked

/**
 * One compact row: avatar, name, subline, then search / voice / video / "⋯".
 * [menuOpen] is hoisted so the renderer can photograph it open.
 */
@Composable
fun ThreadHeaderBar(
    thread: ThreadView,
    actions: ThreadChromeActions,
    infoOpen: Boolean,
    searchOpen: Boolean,
    wallpaperActive: Boolean,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    /** __WALLPAPER_BLUR_2026_11_21__ transparent when the bar is frosted over the thread (`frostedBar`). */
    ground: Color = OshiTheme.background,
) {
    val isGroup = thread.kind == ConversationKind.GROUP
    // __GROUP_HEADER_ISLAND_2026_11_24__ groups get the floating header (GroupHeaderBar.kt).
    if (isGroup) {
        GroupThreadHeaderBar(thread, actions, searchOpen, wallpaperActive, menuOpen, onMenuOpen, ground)
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(ground)
            .padding(start = OshiTheme.lg, end = OshiTheme.sm, top = OshiTheme.sm, bottom = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
    ) {
        // Avatar + name + subline are ONE target: it opens the info panel, like tapping the
        // title on both phones.
        val (source, hovered) = rememberRowInteraction()
        val infoLabel = if (isGroup) t("menu_group_info") else t("address.contact_info")
        Row(
            Modifier
                .weight(1f)
                .clip(OshiTheme.radiusMd)
                .background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.05f) else Color.Transparent)
                .focusRing(OshiTheme.radiusMd)
                .clickable(interactionSource = source, indication = null, onClickLabel = infoLabel, role = Role.Button, onClick = actions.onOpenInfo)
                .hoverable(source)
                .padding(horizontal = OshiTheme.xs, vertical = OshiTheme.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
        ) {
            ThreadAvatar(thread, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    Text(
                        thread.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink.strong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Groups carry the avatar mark; only the unusual kinds say what they are.
                    if (thread.kind == ConversationKind.BOT || thread.kind == ConversationKind.LORA || thread.kind == ConversationKind.MESHCORE) KindBadge(thread.kind)
                    if (thread.isMuted) GlyphIcon(Glyph.BELL_SLASH, Ink.soft, 14.dp)
                    // __CONVERSATION_VERIFY_2027__ a verified contact carries the shield, as on the phones.
                    if (!isGroup && thread.verified) ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, OshiTheme.success, 15.dp)
                }
                ThreadSubline(thread)
            }
        }

        actions.onOpenSearch?.let { GlyphButton(Glyph.SEARCH, t("chat.search"), shortcut = shortcutLabel("F", meta = true), active = searchOpen, onClick = it) }
        actions.onCall?.let { call ->
            IconActionButton(t("chat.voice_call"), icon = { CallGlyphIcon(CallGlyph.PHONE, it, 19.dp) }) { call(false) }
            IconActionButton(t("chat.video_call"), icon = { CallGlyphIcon(CallGlyph.CAMERA, it, 21.dp) }) { call(true) }
        }
        Box {
            GlyphButton(Glyph.MORE, t("a11y.more_options"), active = menuOpen) { onMenuOpen(!menuOpen) }
            OshiDropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }, offset = DpOffset((-8).dp, 4.dp)) {
                ThreadOverflowItems(thread, actions, wallpaperActive) { onMenuOpen(false) }
            }
        }
    }
}

@Composable
private fun ThreadSubline(thread: ThreadView) {
    val typing = when {
        thread.peerTyping -> t("typing.indicator", thread.title)
        thread.typingNames.isNotEmpty() -> t("typing.indicator", thread.typingNames.joinToString(", "))
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (typing != null) {
            Text(typing, fontSize = 12.sp, color = OshiTheme.brand, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@Row
        }
        when (thread.kind) {
            ConversationKind.GROUP -> {
                val n = thread.groupMembers.size
                Text(
                    if (n == 1) t("group_member_count_singular") else t("group_member_count", n),
                    fontSize = 12.sp, color = Ink.soft, maxLines = 1, softWrap = false,
                )
                if (thread.groupAdmin) ChromePill(t("group_admin"), OshiTheme.success)
            }
            else -> if (thread.reachLabel.isNotEmpty()) {
                // The measured reachability, never a presence dot: this client has no
                // presence protocol, so "online" would be a guess.
                val dot = when (thread.reach) {
                    Reach.REACHABLE -> OshiTheme.success
                    Reach.ERROR, Reach.NO_BUNDLE -> OshiTheme.danger.copy(alpha = 0.8f)
                    else -> Ink.soft.copy(alpha = 0.5f)
                }
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Text(thread.reachLabel, fontSize = 12.sp, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The "⋯" menu: info and content — preferences — report / block — leave / delete. */
@Composable
internal fun ThreadOverflowItems(thread: ThreadView, a: ThreadChromeActions, wallpaperActive: Boolean, close: () -> Unit) {
    val isGroup = thread.kind == ConversationKind.GROUP
    fun run(f: () -> Unit): () -> Unit = { close(); f() }

    ChromeMenuItem(Glyph.INFO, if (isGroup) t("menu_group_info") else t("address.contact_info"), onClick = run(a.onOpenInfo))
    if (isGroup) ChromeMenuItem(Glyph.PEOPLE, t("group_members"), onClick = run(a.onOpenInfo))
    a.onOpenGallery?.let { ChromeMenuItem(Glyph.IMAGE, t("media_gallery.title"), onClick = run(it)) }
    a.onVerify?.let { ChromeMenuItemIcon({ c -> ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, c, 18.dp) }, t("safety.title"), onClick = run(it)) }
    a.onShowInvite?.let { ChromeMenuItem(Glyph.LINK, t("group.invite_link"), onClick = run(it)) }
    a.onChangePicture?.let { ChromeMenuItem(Glyph.IMAGE, t("group.change_picture"), onClick = run(it)) }
    a.onRemovePicture?.let { ChromeMenuItem(Glyph.CLOSE, t("group.remove_picture"), onClick = run(it)) }
    a.onRename?.let { ChromeMenuItem(Glyph.PENCIL, t("mail.drive.rename"), onClick = run(it)) }
    a.onEditDescription?.let { ChromeMenuItem(Glyph.TEXT_LINES, t("group.edit_description"), onClick = run(it)) }

    ChromeMenuSeparator()
    a.onSetChatPinned?.let { set ->
        ChromeMenuItemIcon(
            { c -> ConvGlyphIcon(if (thread.conversationPinned) ConvGlyph.UNPIN else ConvGlyph.PIN_TOP, c, 18.dp) },
            if (thread.conversationPinned) t("conv.unpin_chat") else t("conv.pin_chat"),
            onClick = run { set(!thread.conversationPinned) },
        )
    }
    a.onSetMuted?.let { set ->
        ChromeMenuItem(
            if (thread.isMuted) Glyph.BELL else Glyph.BELL_SLASH,
            if (thread.isMuted) t("chat.unmute") else t("chat.mute"),
            onClick = run { set(!thread.isMuted) },
        )
    }
    a.onUnpin?.let { ChromeMenuItem(Glyph.PIN, t("pinned.unpin_message"), onClick = run(it)) }
    ChromeMenuItem(Glyph.PALETTE, t("wallpaper.title"), shortcut = if (wallpaperActive) "●" else null, onClick = run(a.onWallpaper))
    a.onSetOfflineOnly?.let { set ->
        ChromeMenuItem(
            Glyph.LOCK,
            if (thread.offlineOnly) t("offline_only.menu.off") else t("offline_only.menu.on"),
            onClick = run { set(!thread.offlineOnly) },
        )
    }
    a.onAlive?.let { ChromeMenuItem(Glyph.HEART, t("alive.title"), onClick = run(it)) }
    a.onExport?.let { ChromeMenuItemIcon({ c -> ConvGlyphIcon(ConvGlyph.EXPORT, c, 18.dp) }, t("conv.export.menu"), onClick = run(it)) }

    if (a.onReport != null || a.onSetBlocked != null) {
        ChromeMenuSeparator()
        a.onReport?.let { ChromeMenuItem(Glyph.FLAG, if (isGroup) t("group.report_group") else t("report.contact_title"), danger = true, onClick = run(it)) }
        a.onSetBlocked?.let { set ->
            ChromeMenuItem(
                Glyph.BLOCK,
                blockLabel(thread),
                danger = !thread.isBlocked,
                onClick = run { set(!thread.isBlocked) },
            )
        }
    }
    if (a.onLeave != null || a.onDelete != null) {
        ChromeMenuSeparator()
        a.onLeave?.let { ChromeMenuItem(Glyph.LEAVE, t("groups.leave"), danger = true, onClick = run(it)) }
        a.onDelete?.let { ChromeMenuItem(Glyph.TRASH, t("button_delete_group"), danger = true, onClick = run(it)) }
    }
}

// ---------------------------------------------------------------------------- the info panel

/**
 * The side panel the name opens: identity, quick actions, description, address (with copy —
 * this is where the UUID went), media, members, and the destructive actions at the bottom.
 */
@Composable
fun ThreadInfoPanel(
    thread: ThreadView,
    actions: ThreadChromeActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGroup = thread.kind == ConversationKind.GROUP
    var addingMembers by remember(thread.conversationId) { mutableStateOf(false) }
    // __GROUP_INFO_SETTINGS_2026_11_20__
    val settingsView = thread.groupSettings?.takeIf { isGroup }
    val settingsActions = actions.groupSettings?.takeIf { isGroup && settingsView != null }
    val previewDialog = LocalThreadChromePreview.current.groupDialog
    var settingsDialog by remember(thread.conversationId) { mutableStateOf(previewDialog) }
    Row(modifier.fillMaxHeight()) {
        Box(Modifier.width(Metrics.hairline).fillMaxHeight().background(OshiTheme.separator))
        Column(
            Modifier
                .width(340.dp)
                .fillMaxHeight()
                .background(OshiTheme.background),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = OshiTheme.lg, end = OshiTheme.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isGroup) t("menu_group_info") else t("address.contact_info"),
                    style = OshiTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.strong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                GlyphButton(Glyph.CLOSE, t("common.close"), shortcut = "Esc", onClick = onClose)
            }
            Hairline()
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = OshiTheme.xl),
            ) {
                // Identity.
                Column(
                    Modifier.fillMaxWidth().padding(top = OshiTheme.xl, bottom = OshiTheme.lg, start = OshiTheme.lg, end = OshiTheme.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ThreadAvatar(thread, 88.dp)
                    Spacer(Modifier.height(OshiTheme.md))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                        Text(
                            thread.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink.strong,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        actions.onRename?.let { GlyphButton(Glyph.PENCIL, t("mail.drive.rename"), size = 28.dp, glyphSize = 15.dp, onClick = it) }
                    }
                    Spacer(Modifier.height(OshiTheme.xxs))
                    ThreadSubline(thread)
                }

                // Quick actions, the phones' row of round buttons under the name.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg),
                    horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm, Alignment.CenterHorizontally),
                ) {
                    actions.onOpenSearch?.let { QuickTile(t("accessibility.search_button"), { GlyphIcon(Glyph.SEARCH, it, 20.dp) }, it) }
                    actions.onCall?.let { call ->
                        QuickTile(t("call.contact.default"), { CallGlyphIcon(CallGlyph.PHONE, it, 20.dp) }) { call(false) }
                        QuickTile(t("call.video.button"), { CallGlyphIcon(CallGlyph.CAMERA, it, 22.dp) }) { call(true) }
                    }
                    actions.onSetMuted?.let { set ->
                        QuickTile(
                            if (thread.isMuted) t("chat.unmute") else t("chat.mute"),
                            { GlyphIcon(if (thread.isMuted) Glyph.BELL else Glyph.BELL_SLASH, it, 20.dp) },
                        ) { set(!thread.isMuted) }
                    }
                    actions.onOpenGallery?.let { QuickTile(t("media.attachment"), { GlyphIcon(Glyph.IMAGE, it, 20.dp) }, it) }
                }
                Spacer(Modifier.height(OshiTheme.lg))

                // Description.
                if (isGroup && (!thread.groupDescription.isNullOrBlank() || actions.onEditDescription != null)) {
                    InfoSection(t("group.description")) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                thread.groupDescription?.takeIf { it.isNotBlank() } ?: t("group.edit_description"),
                                style = OshiTheme.typography.bodyMedium,
                                color = if (thread.groupDescription.isNullOrBlank()) Ink.soft else Ink.strong,
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            )
                            actions.onEditDescription?.let { GlyphButton(Glyph.PENCIL, t("group.edit_description"), size = 28.dp, glyphSize = 15.dp, onClick = it) }
                        }
                    }
                }

                // __GROUP_INFO_SETTINGS_2026_11_20__ §5 items 3-8: type, link and the settings rows.
                if (settingsView != null && settingsActions != null) {
                    GroupSettingsBlock(settingsView, thread.groupInviteLink, settingsActions) { settingsDialog = it }
                }

                // The address: moved here from the header, selectable, with a copy button.
                InfoSection(if (isGroup) t("group_id") else t("address.public_key")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(thread.address, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Ink.strong, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        GlyphButton(Glyph.COPY, t("common.copy"), size = 30.dp, glyphSize = 16.dp) { actions.onCopy(thread.address) }
                    }
                }
                if (!isGroup && thread.safetyNumber.isNotBlank()) {
                    InfoSection(t("safety_number")) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                            Text(thread.safetyNumber, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Ink.strong, modifier = Modifier.weight(1f))
                            if (thread.verified) ChromePill(t("safety.verified"), OshiTheme.success)
                            else actions.onVerify?.let { GlyphButton(Glyph.CHEVRON_RIGHT, t("profile.verify_identity"), size = 28.dp, glyphSize = 14.dp, onClick = it) }
                        }
                    }
                }

                // Content and preferences, as rows.
                InfoCard {
                    actions.onOpenGallery?.let { InfoRow(Glyph.IMAGE, t("media_gallery.title"), trailing = "${thread.media.size}", onClick = it) }
                    actions.onShowInvite?.let { InfoRow(Glyph.LINK, t("group.invite_link"), onClick = it) }
                    actions.onChangePicture?.let { InfoRow(Glyph.IMAGE, t("group.change_picture"), onClick = it) }
                    // __GROUP_HEADER_ISLAND_2026_11_24__ was only in the header's ⋯ menu.
                    actions.onRemovePicture?.let { InfoRow(Glyph.CLOSE, t("group.remove_picture"), onClick = it) }
                    actions.onVerify?.let { InfoRowIcon({ c -> ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, c, 18.dp) }, t("safety.title"), trailing = if (thread.verified) t("safety.verified") else t("safety.not_verified"), onClick = it) }
                    actions.onSetChatPinned?.let { set ->
                        InfoRowIcon({ c -> ConvGlyphIcon(if (thread.conversationPinned) ConvGlyph.UNPIN else ConvGlyph.PIN_TOP, c, 18.dp) },
                            if (thread.conversationPinned) t("conv.unpin_chat") else t("conv.pin_chat")) { set(!thread.conversationPinned) }
                    }
                    actions.onUnpin?.let { InfoRow(Glyph.PIN, t("pinned.unpin_message"), onClick = it) }
                    InfoRow(Glyph.PALETTE, t("wallpaper.title"), onClick = actions.onWallpaper)
                    actions.onExport?.let { InfoRowIcon({ c -> ConvGlyphIcon(ConvGlyph.EXPORT, c, 18.dp) }, t("conv.export.menu"), onClick = it) }
                    actions.onSetOfflineOnly?.let { set ->
                        InfoRow(Glyph.LOCK, if (thread.offlineOnly) t("offline_only.menu.off") else t("offline_only.menu.on")) { set(!thread.offlineOnly) }
                    }
                }

                // __GROUP_INFO_SETTINGS_2026_11_20__ §5 item 10: administrators.
                if (settingsView != null) {
                    Spacer(Modifier.height(OshiTheme.md))
                    GroupAdminsBlock(settingsView)
                }

                // Members.
                if (isGroup) {
                    Spacer(Modifier.height(OshiTheme.md))
                    InfoSection("${t("group_members")} · ${thread.groupMembers.size}") {
                        Column {
                            if (thread.groupAdmin && thread.groupCandidates.isNotEmpty()) {
                                InfoRow(Glyph.PLUS, t("groups.add_members"), tint = OshiTheme.brand, inset = false) { addingMembers = !addingMembers }
                                if (addingMembers) for (c in thread.groupCandidates) {
                                    MemberLine(c.label, c.address, badges = {}) {
                                        GlyphButton(Glyph.PLUS, t("common.add"), size = 28.dp, glyphSize = 15.dp) { actions.onAddMember(c.address) }
                                    }
                                }
                            }
                            for (m in thread.groupMembers.sortedWith(compareByDescending<GroupMemberRow> { it.self }.thenByDescending { it.isAdmin }.thenBy { it.label.lowercase() })) {
                                MemberLine(
                                    m.label, m.address,
                                    badges = {
                                        if (m.self) ChromePill(t("common.you"), OshiTheme.brand)
                                        if (m.isAdmin) ChromePill(t("group_admin"), OshiTheme.success)
                                        if (m.blocked) ChromePill(t("contact.blocked"), OshiTheme.danger)
                                    },
                                ) {
                                    if (!m.self && (thread.groupAdmin || actions.onReportMember != null || actions.onSetMemberBlocked != null || actions.onReplyPrivatelyToMember != null)) {
                                        MemberMenu(m, actions, admin = thread.groupAdmin)
                                    }
                                }
                            }
                        }
                    }
                }

                // __GROUP_INFO_SETTINGS_2026_11_20__ §5 items 12-13 (admins only).
                if (settingsView != null) {
                    Spacer(Modifier.height(OshiTheme.md))
                    GroupModerationBlock(settingsView) { settingsDialog = it }
                }

                // Destructive, last and red.
                val danger = buildList<Triple<Glyph, String, () -> Unit>> {
                    actions.onReport?.let { add(Triple(Glyph.FLAG, if (isGroup) t("group.report_group") else t("report.contact_title"), it)) }
                    actions.onSetBlocked?.let { set -> add(Triple(Glyph.BLOCK, blockLabel(thread)) { set(!thread.isBlocked) }) }
                    actions.onLeave?.let { add(Triple(Glyph.LEAVE, if (settingsActions != null) t("gs_leave_group") else t("groups.leave"), it)) }
                    // §2.9: an admin chooses "for everyone" or "for me".
                    actions.onDelete?.let { del ->
                        if (settingsActions != null) add(Triple(Glyph.TRASH, t("gs_delete_group")) { settingsDialog = GroupSettingsDialog.DELETE })
                        else add(Triple(Glyph.TRASH, t("button_delete_group"), del))
                    }
                }
                if (danger.isNotEmpty()) {
                    Spacer(Modifier.height(OshiTheme.md))
                    InfoCard { danger.forEach { (g, label, f) -> InfoRow(g, label, tint = OshiTheme.danger, onClick = f) } }
                }
            }
        }
    }
    if (settingsView != null && settingsActions != null) {
        GroupSettingsDialogs(settingsDialog, settingsView, thread.groupInviteLink, settingsActions) { settingsDialog = null }
    }
}

@Composable
private fun QuickTile(label: String, icon: @Composable (Color) -> Unit, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Column(
        Modifier
            .width(68.dp)
            .clip(OshiTheme.radiusMd)
            .background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.10f) else OshiTheme.textPrimary.copy(alpha = 0.05f))
            .focusRing(OshiTheme.radiusMd)
            .clickable(interactionSource = source, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .hoverable(source)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon(OshiTheme.brand) }
        Text(label, fontSize = 11.sp, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun InfoSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.xs)) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.soft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = OshiTheme.xs, bottom = OshiTheme.xs),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .glass(OshiTheme.radiusMd, OshiTheme.surface, raised = false)
                .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        ) { content() }
    }
}

@Composable
internal fun InfoCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.xs)
            .glass(OshiTheme.radiusMd, OshiTheme.surface, raised = false)
            .padding(vertical = OshiTheme.xxs),
    ) { content() }
}

private fun blockLabel(thread: ThreadView): String = when {
    thread.kind == ConversationKind.GROUP -> if (thread.groupBlocked) t("group.unblock") else t("group.block")
    thread.contactBlocked -> t("chat.unblock_contact")
    else -> t("chat.block_contact")
}

@Composable
private fun InfoRow(
    glyph: Glyph,
    label: String,
    trailing: String? = null,
    tint: Color = OshiTheme.textPrimary,
    inset: Boolean = true,
    onClick: () -> Unit,
) = InfoRowIcon({ c -> GlyphIcon(glyph, c, 18.dp) }, label, trailing, tint, inset, onClick)

@Composable
private fun InfoRowIcon(
    icon: @Composable (Color) -> Unit,
    label: String,
    trailing: String? = null,
    tint: Color = OshiTheme.textPrimary,
    inset: Boolean = true,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusSm)
            .background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.06f) else Color.Transparent)
            .focusRing(OshiTheme.radiusSm)
            .clickable(interactionSource = source, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .hoverable(source)
            .heightIn(min = 40.dp)
            .padding(horizontal = if (inset) OshiTheme.md else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        icon(if (tint == OshiTheme.textPrimary) Ink.soft else tint)
        Text(label, fontSize = 14.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 13.sp, color = Ink.soft, maxLines = 1)
        if (tint == OshiTheme.textPrimary) GlyphIcon(Glyph.CHEVRON_RIGHT, Ink.soft.copy(alpha = 0.6f), 14.dp)
    }
}

@Composable
private fun MemberLine(label: String, address: String, badges: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        Monogram(label, address, 32.dp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 14.sp, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            badges()
        }
        trailing()
    }
}

@Composable
private fun MemberMenu(m: GroupMemberRow, a: ThreadChromeActions, admin: Boolean) {
    val previewOpen = LocalThreadChromePreview.current.memberMenuAddress == m.address
    var open by remember(m.address) { mutableStateOf(previewOpen) }
    Box {
        GlyphButton(Glyph.MORE, t("a11y.more_options"), size = 28.dp, glyphSize = 16.dp) { open = true }
        OshiDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // __MERGE_AB_MEMBER_MENU__ A's Reply privately first (Android's order), then B's
            // moderation; a blocked member cannot be written to, so it is not offered then.
            a.onReplyPrivatelyToMember?.takeIf { !m.blocked }?.let { f ->
                ChromeMenuItem(Glyph.PERSON, t("conv.fa.reply_privately")) { open = false; f(m.address) }
                ChromeMenuSeparator()
            }
            if (admin) {
                if (m.isAdmin && !m.isCreator) ChromeMenuItem(Glyph.PERSON, t("group.member.remove_admin")) { open = false; a.onSetMemberAdmin(m.address, false) }
                if (!m.isAdmin) ChromeMenuItem(Glyph.PERSON, t("group.member.make_admin")) { open = false; a.onSetMemberAdmin(m.address, true) }
            }
            // __GROUP_MEMBER_MODERATION_2027__ iOS GroupViews' member Menu: block / unblock,
            // report, copy the key — for every member, not only admins.
            ChromeMenuItem(Glyph.COPY, t("message.action.copy")) { open = false; a.onCopy(m.address) }
            a.onSetMemberBlocked?.let { set ->
                if (m.blocked) ChromeMenuItem(Glyph.BLOCK, t("group.unblock_member")) { open = false; set(m.address, false) }
                else ChromeMenuItem(Glyph.BLOCK, t("group.block_member"), danger = true) { open = false; set(m.address, true) }
            }
            a.onReportMember?.let { report ->
                ChromeMenuItem(Glyph.FLAG, t("group.report_member"), danger = true) { open = false; report(m.address) }
            }
            if (admin) {
                ChromeMenuSeparator()
                // __GROUP_INFO_SETTINGS_2026_11_20__ §2.7: removing IS banning (the key is tombstoned).
                ChromeMenuItem(Glyph.TRASH, if (a.groupSettings != null) t("gs_ban") else t("group.member.remove_member"), danger = true) { open = false; a.onRemoveMember(m.address) }
            }
        }
    }
}

// ---------------------------------------------------------------------------- messages

/** What a message can do; null = not offered on this row. */
class MessageActions(
    val onReply: (() -> Unit)?,
    val onReact: ((String) -> Unit)?,
    val onForward: (() -> Unit)?,
    val onPin: (() -> Unit)?,
    val pinned: Boolean,
    val onCopy: (() -> Unit)?,
    val onEdit: (() -> Unit)?,
    val onInfo: () -> Unit,
    val onDelete: (() -> Unit)?,
    val onOpenPicker: () -> Unit,
    /** __REPLY_PRIVATELY_DESKTOP_2027__ a group member's message: answer them in a 1:1. */
    val onReplyPrivately: (() -> Unit)? = null,
) {
    val any: Boolean get() = onReply != null || onReact != null || onForward != null || onPin != null || onCopy != null || onEdit != null || onDelete != null
}

/** "⌘F" on a Mac, "Ctrl+F" elsewhere. */
fun shortcutLabel(key: String, meta: Boolean = false): String =
    if (!meta) key else if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) "⌘$key" else "Ctrl+$key"

/**
 * The floating icon toolbar: reply, react, forward, pin, "⋯". Icons only; every one has a
 * tooltip naming it and its shortcut, and an accessible label.
 */
@Composable
fun MessageHoverToolbar(
    actions: MessageActions,
    reactBarOpen: Boolean,
    onReactBar: (Boolean) -> Unit,
    moreOpen: Boolean,
    onMore: (Boolean) -> Unit,
    onPick: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .glassFloating(OshiTheme.pill, OshiTheme.surfaceElevated, OshiTheme.separator)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val s = 30.dp
        val g = 16.dp
        actions.onReply?.let { GlyphButton(Glyph.REPLY, t("message.action.reply"), shortcut = "R", size = s, glyphSize = g, onClick = it) }
        actions.onReact?.let { react ->
            Box {
                GlyphButton(Glyph.SMILE, t("reaction.choose"), shortcut = "E", size = s, glyphSize = g, active = reactBarOpen) { onReactBar(!reactBarOpen) }
                OshiDropdownMenu(expanded = reactBarOpen, onDismissRequest = { onReactBar(false) }, offset = DpOffset(0.dp, 4.dp)) {
                    MascotQuickBar(
                        onReact = { onReactBar(false); react(it) },
                        onMore = { onReactBar(false); onPick(true) },
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
        actions.onForward?.let { GlyphButton(Glyph.FORWARD, t("message.action.forward"), shortcut = "F", size = s, glyphSize = g, onClick = it) }
        actions.onPin?.let { GlyphButton(Glyph.PIN, if (actions.pinned) t("message.unpin") else t("message.pin"), shortcut = "P", size = s, glyphSize = g, active = actions.pinned, onClick = it) }
        Box {
            GlyphButton(Glyph.MORE, t("a11y.more_options"), size = s, glyphSize = g, active = moreOpen) { onMore(!moreOpen) }
            OshiDropdownMenu(expanded = moreOpen, onDismissRequest = { onMore(false) }, offset = DpOffset(0.dp, 4.dp)) {
                MessageMoreItems(actions) { onMore(false) }
            }
        }
    }
}

/** The "⋯" of the toolbar: reply privately, copy, edit, info, delete. */
@Composable
private fun MessageMoreItems(a: MessageActions, close: () -> Unit) {
    a.onReplyPrivately?.let { f -> ChromeMenuItem(Glyph.PERSON, t("conv.fa.reply_privately")) { close(); f() } }
    a.onCopy?.let { f -> ChromeMenuItem(Glyph.COPY, t("message.action.copy"), shortcut = shortcutLabel("C", meta = true)) { close(); f() } }
    a.onEdit?.let { f -> ChromeMenuItem(Glyph.PENCIL, t("message.action.edit"), shortcut = "Enter") { close(); f() } }
    ChromeMenuItem(Glyph.INFO, t("message.info"), shortcut = "I") { close(); a.onInfo() }
    a.onDelete?.let { f ->
        ChromeMenuSeparator()
        ChromeMenuItem(Glyph.TRASH, t("message.action.delete"), danger = true, shortcut = "Del") { close(); f() }
    }
}

/** The right-click menu: the quick reactions on top, then every action, labelled. */
@Composable
fun MessageActionMenuItems(a: MessageActions, close: () -> Unit) {
    a.onReact?.let { react ->
        MascotQuickBar(
            onReact = { close(); react(it) },
            onMore = { close(); a.onOpenPicker() },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
        ChromeMenuSeparator()
    }
    a.onReply?.let { f -> ChromeMenuItem(Glyph.REPLY, t("message.action.reply"), shortcut = "R") { close(); f() } }
    a.onReplyPrivately?.let { f -> ChromeMenuItem(Glyph.PERSON, t("conv.fa.reply_privately")) { close(); f() } }
    a.onForward?.let { f -> ChromeMenuItem(Glyph.FORWARD, t("message.action.forward"), shortcut = "F") { close(); f() } }
    a.onPin?.let { f -> ChromeMenuItem(Glyph.PIN, if (a.pinned) t("message.unpin") else t("message.pin"), shortcut = "P") { close(); f() } }
    a.onCopy?.let { f -> ChromeMenuItem(Glyph.COPY, t("message.action.copy"), shortcut = shortcutLabel("C", meta = true)) { close(); f() } }
    a.onEdit?.let { f -> ChromeMenuItem(Glyph.PENCIL, t("message.action.edit"), shortcut = "Enter") { close(); f() } }
    ChromeMenuItem(Glyph.INFO, t("message.info"), shortcut = "I") { close(); a.onInfo() }
    a.onDelete?.let { f ->
        ChromeMenuSeparator()
        ChromeMenuItem(Glyph.TRASH, t("message.action.delete"), danger = true, shortcut = "Del") { close(); f() }
    }
}

/** Forward targets, as a menu of people. */
@Composable
fun ForwardTargetsMenu(expanded: Boolean, targets: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    OshiDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            t("message.forward_to"),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.soft,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        for ((address, label) in targets) {
            DropdownMenuItem(
                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) },
                leadingIcon = { Monogram(label, address, 24.dp) },
                onClick = { onDismiss(); onPick(address) },
                colors = MenuDefaults.itemColors(textColor = OshiTheme.textPrimary),
                modifier = Modifier.height(40.dp).widthIn(min = 220.dp),
            )
        }
    }
}

/** "Message Info": who, when, delivery state. */
@Composable
fun MessageInfoDialog(row: MessageRow, onDismiss: () -> Unit) {
    val whenText = if (row.sentAtMs > 0L) {
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
            .format(java.time.Instant.ofEpochMilli(row.sentAtMs).atZone(java.time.ZoneId.systemDefault()))
    } else row.stamp
    val status = when {
        row.failed || row.status == "failed" -> t("message.info.failed")
        row.status == "read" -> t("message.info.read")
        row.status == "delivered" -> t("message.info.delivered")
        else -> t("message.info.sent")
    }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("message.info.title"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.sm), modifier = Modifier.widthIn(min = 280.dp)) {
                if (row.body.isNotBlank()) {
                    Text(
                        row.body, style = OshiTheme.typography.bodyMedium, color = OshiTheme.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(OshiTheme.radiusSm).background(OshiTheme.textPrimary.copy(alpha = 0.06f)).padding(10.dp),
                    )
                }
                if (!row.fromMe && row.who.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                        Monogram(row.who, row.who, 22.dp)
                        Text(row.who, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OshiTheme.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                InfoPair(t("message.info.sent"), whenText)
                if (row.fromMe) InfoPair(t("sync.status"), status)
                if (row.edited || row.forwardedFrom != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                        if (row.edited) ChromePill(t("message.edited"), Ink.soft)
                        if (row.forwardedFrom != null) ChromePill(t("message.forwarded"), Ink.soft)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("common.done"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold) } },
    )
}

@Composable
private fun InfoPair(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
        Text(label, fontSize = 13.sp, color = Ink.soft, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 13.sp, color = OshiTheme.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** Ask where, then write the invite QR as a PNG. null = cancelled. */
internal fun saveGroupQrImage(link: String): Boolean? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("pc.groups.qr.save"), java.awt.FileDialog.SAVE)
    dialog.file = "OSHI-group-QR.png"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val target = java.io.File(dialog.directory, if (name.endsWith(".png", true)) name else "$name.png")
    return runCatching { com.oshi.desktop.pairing.QrPng.write(link, target) }.isSuccess
}
