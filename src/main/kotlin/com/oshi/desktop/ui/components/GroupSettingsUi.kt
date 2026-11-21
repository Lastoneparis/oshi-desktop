package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.group.GroupPublicLink
import com.oshi.desktop.group.GroupSettings
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.GroupSettingsView
import com.oshi.desktop.ui.state.LinkResult
import com.oshi.desktop.ui.state.PublicJoinState
import com.oshi.desktop.ui.state.permissionLabel
import com.oshi.desktop.ui.state.postPolicyLabel
import com.oshi.desktop.ui.state.reactionsLabel

/**
 * __GROUP_INFO_SETTINGS_2026_11_20__ The Telegram-style group settings in the info panel —
 * GROUP_INFO_SETTINGS_SPEC.md §5, in its order: type and link, permissions, who can post,
 * reactions, welcome message, direct messages to admins, administrators, banned users,
 * recent events, delete. Auto-translate (§2.10) is not offered: the desktop has no
 * translation path.
 *
 * "(A)" items are editable by admins only; everyone else reads the value (rows without a
 * chevron) or does not see the row (banned users, recent events). The receiving side
 * enforces the same rule on every device (GroupUpdateAuthorizer.mergeSettings), so hiding a
 * control here is a courtesy, not the protection.
 */
class GroupSettingsActions(
    val onSetType: (GroupType) -> Unit,
    val onSetPublic: (handle: String, showPreview: Boolean) -> Unit,
    val onSetPrivate: () -> Unit,
    val onResetLink: () -> Unit,
    val onSetPostPolicy: (GroupSettings.PostPolicy) -> Unit,
    val onSetReactions: (GroupSettings.ReactionMode, List<String>) -> Unit,
    val onSetWelcome: (String) -> Unit,
    val onSetAdminDMs: (Boolean) -> Unit,
    val onUnban: (String) -> Unit,
    val onMessageAdmin: (String) -> Unit,
    val onDissolve: () -> Unit,
    val onDeleteForMe: () -> Unit,
    val onLinkEditorClosed: () -> Unit,
    val onCopy: (String) -> Unit,
    /** __GROUP_COMMENTS_2026_11_21__ §2.12.6 row 5b, the Comments switch. */
    val onSetComments: (Boolean) -> Unit = {},
)

/** Which settings dialog is open (hoisted so the renderer can photograph each). */
enum class GroupSettingsDialog { TYPE, PERMISSIONS, POST, REACTIONS, WELCOME, BANNED, EVENTS, DELETE }

// ---------------------------------------------------------------------------- panel sections

/** Items 3-8: type/link, then the settings card. */
@Composable
internal fun GroupSettingsBlock(
    v: GroupSettingsView,
    inviteLink: String?,
    a: GroupSettingsActions,
    open: (GroupSettingsDialog) -> Unit,
) {
    val s = v.settings
    val admin = v.isAdmin && !v.dissolved
    // 3. Group type + link.
    InfoSectionPublic(t("gs_type")) {
        Column {
            SettingValueRow(
                label = if (s.isPublic) t("gs_type_public") else t("gs_type_private"),
                value = if (s.isPublic) s.handle?.let(GroupPublicLink::display) else t("gs_type_private_footer"),
                monoValue = s.isPublic,
                inset = false,
                onClick = if (admin) ({ open(GroupSettingsDialog.TYPE) }) else null,
            )
            v.publicLink?.let { link ->
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), modifier = Modifier.padding(bottom = OshiTheme.xs)) {
                    SettingsButton(SettingsIcon.COPY, t("gs_copy_link")) { a.onCopy(link) }
                }
            }
        }
    }
    // 4-8. One card, Telegram's rows.
    InfoSectionPublic(t("gs_title")) {
        Column {
            SettingValueRow(t("gs_permissions"), permissionLabel(v.type), inset = false,
                onClick = if (admin) ({ open(GroupSettingsDialog.PERMISSIONS) }) else null)
            SettingValueRow(t("gs_who_can_post"), postPolicyLabel(s.postPolicy), inset = false,
                onClick = if (admin) ({ open(GroupSettingsDialog.POST) }) else null)
            // __GROUP_COMMENTS_2026_11_21__ §2.12.6 row 5b, admins, only in channel mode (the value
            // is kept, hidden, while "Who can post" is Everyone).
            if (v.isAdmin && s.postPolicy == GroupSettings.PostPolicy.ADMINS) {
                SettingSwitchRow(t("gs_comments"), t("gs_comments_footer"), s.comments, enabled = admin) { a.onSetComments(it) }
            }
            SettingValueRow(t("gs_reactions"), reactionsLabel(s), inset = false,
                onClick = if (admin) ({ open(GroupSettingsDialog.REACTIONS) }) else null)
            SettingValueRow(t("gs_welcome"), s.welcome.takeIf { it.isNotBlank() } ?: t("gs_welcome_off"), inset = false,
                onClick = if (admin) ({ open(GroupSettingsDialog.WELCOME) }) else null)
            if (v.isAdmin) {
                SettingSwitchRow(t("gs_admin_dms"), t("gs_admin_dms_footer"), s.adminDMs, enabled = admin) { a.onSetAdminDMs(it) }
            }
            if (!v.isAdmin) Text(
                t("gs_admin_only_hint"),
                fontSize = 12.sp, color = Ink.soft,
                modifier = Modifier.padding(vertical = OshiTheme.xs),
            )
        }
    }
    // §2.6 every member: "Message an admin".
    val targets = v.messageAdminTargets
    if (targets.isNotEmpty() && !v.dissolved && !v.isAdmin) {
        InfoSectionPublic(t("gs_admin_dms")) {
            MessageAdminRow(targets.map { it.address to it.label }, a.onMessageAdmin)
        }
    }
}

/** 10. Administrators, creator first. */
@Composable
internal fun GroupAdminsBlock(v: GroupSettingsView) {
    if (v.admins.isEmpty()) return
    InfoSectionPublic("${t("gs_admins")} · ${v.admins.size}") {
        Column {
            for (m in v.admins) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
                ) {
                    Monogram(m.label, m.address, 28.dp)
                    Text(m.label, fontSize = 14.sp, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (m.self) ChromePill(t("gs_you"), OshiTheme.brand)
                }
            }
        }
    }
}

/** 12-13. Banned users and recent events, admins only. */
@Composable
internal fun GroupModerationBlock(v: GroupSettingsView, open: (GroupSettingsDialog) -> Unit) {
    if (!v.isAdmin) return
    InfoCard {
        Column(Modifier.padding(horizontal = OshiTheme.md)) {
            SettingValueRow(t("gs_banned"), if (v.banned.isEmpty()) t("gs_banned_empty") else "${v.banned.size}", inset = false) { open(GroupSettingsDialog.BANNED) }
            SettingValueRow(t("gs_recent_events"), if (v.events.isEmpty()) t("gs_recent_events_empty") else "${v.events.size}", inset = false) { open(GroupSettingsDialog.EVENTS) }
        }
    }
}

/** §2.5 the one local welcome row, at the top of the chat. */
@Composable
fun GroupWelcomeRow(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = OshiTheme.sm), contentAlignment = Alignment.Center) {
        Text(
            t("gs_welcome_row", text),
            style = OshiTheme.typography.bodySmall,
            color = Ink.strong,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .clip(OshiTheme.radiusMd)
                .background(OshiTheme.brand.copy(alpha = 0.10f))
                .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        )
    }
}

/** §2.9 the read-only banner of a group an admin deleted for everyone. */
@Composable
fun DissolvedGroupBanner(onDeleteChat: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(OshiTheme.danger.copy(alpha = 0.12f))
            .padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Text(t("gs_dissolved_banner"), style = OshiTheme.typography.labelMedium, color = OshiTheme.danger, modifier = Modifier.weight(1f))
        SettingsButton(SettingsIcon.DELETE, t("desktop.group.settings.delete_chat"), danger = true, onClick = onDeleteChat)
    }
}

// ---------------------------------------------------------------------------- dialogs

/** Every settings dialog, one at a time. */
@Composable
internal fun GroupSettingsDialogs(
    which: GroupSettingsDialog?,
    v: GroupSettingsView,
    inviteLink: String?,
    a: GroupSettingsActions,
    close: () -> Unit,
) {
    when (which) {
        null -> Unit
        GroupSettingsDialog.TYPE -> GroupTypeDialog(v, inviteLink, a) { a.onLinkEditorClosed(); close() }
        GroupSettingsDialog.PERMISSIONS -> ChoiceDialog(
            t("gs_permissions"),
            GroupType.entries.map { permissionLabel(it) to null },
            GroupType.entries.indexOf(v.type),
            onPick = { a.onSetType(GroupType.entries[it]) }, close = close,
        )
        GroupSettingsDialog.POST -> ChoiceDialog(
            t("gs_who_can_post"),
            GroupSettings.PostPolicy.entries.map { postPolicyLabel(it) to null },
            GroupSettings.PostPolicy.entries.indexOf(v.settings.postPolicy),
            onPick = { a.onSetPostPolicy(GroupSettings.PostPolicy.entries[it]) }, close = close,
        )
        GroupSettingsDialog.REACTIONS -> ReactionsDialog(v.settings, a.onSetReactions, close)
        GroupSettingsDialog.WELCOME -> WelcomeDialog(v.settings.welcome, a.onSetWelcome, close)
        GroupSettingsDialog.BANNED -> ListDialog(
            t("gs_banned"), t("gs_banned_empty"),
            v.banned.map { b -> ListLine(b.label, null, t("gs_unban")) { a.onUnban(b.address) } }, close,
        )
        GroupSettingsDialog.EVENTS -> ListDialog(
            t("gs_recent_events"), t("gs_recent_events_empty"),
            v.events.map { e -> ListLine(e.text, e.stamp, null, null) }, close,
        )
        GroupSettingsDialog.DELETE -> DeleteGroupDialog(a.onDissolve, a.onDeleteForMe, close)
    }
}

@Composable
private fun GroupTypeDialog(v: GroupSettingsView, inviteLink: String?, a: GroupSettingsActions, close: () -> Unit) {
    val s = v.settings
    var public by remember { mutableStateOf(s.isPublic) }
    var handle by remember { mutableStateOf(s.handle.orEmpty()) }
    var preview by remember { mutableStateOf(s.showPreview) }
    var confirmReset by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    val normalized = GroupSettings.normalizeHandle(handle)
    val dirty = public != s.isPublic || (public && (normalized != s.handle || preview != s.showPreview))
    OshiAlertDialog(
        onDismissRequest = close,
        title = { Text(t("gs_type"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                TwoChoice(t("gs_type_private"), t("gs_type_public"), public) { public = it }
                Text(
                    if (public) t("gs_type_public_footer") else t("gs_type_private_footer"),
                    style = OshiTheme.typography.bodySmall, color = Ink.soft,
                )
                if (public) {
                    Text(t("gs_public_link"), style = OshiTheme.typography.labelMedium, color = Ink.strong)
                    Row(
                        Modifier.fillMaxWidth().clip(OshiTheme.radiusSm).background(OshiTheme.textPrimary.copy(alpha = 0.06f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(GroupPublicLink.HOST + "/g/", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Ink.soft, maxLines = 1, softWrap = false)
                        BasicTextField(
                            value = handle,
                            onValueChange = { handle = it.take(32) },
                            singleLine = true,
                            textStyle = OshiTheme.typography.bodyMedium.copy(color = OshiTheme.textPrimary, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(OshiTheme.brand),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val invalid = handle.isNotBlank() && normalized == null
                    Text(
                        if (invalid) t("gs_handle_invalid") else t("gs_handle_rules"),
                        style = OshiTheme.typography.bodySmall, color = if (invalid) OshiTheme.danger else Ink.soft,
                    )
                    SettingSwitchRow(t("gs_show_preview"), null, preview, enabled = true) { preview = it }
                    when {
                        v.linkBusy -> SettingsNotice(t("gs_handle_saving"), error = false)
                        v.linkResult == LinkResult.TAKEN -> SettingsNotice(t("gs_handle_taken"), error = true)
                        v.linkResult == LinkResult.FAILED -> SettingsNotice(t("gs_handle_error"), error = true)
                        v.linkResult == LinkResult.INVALID -> SettingsNotice(t("gs_handle_invalid"), error = true)
                        else -> Unit
                    }
                    v.publicLink?.takeIf { s.isPublic }?.let { link -> LinkTools(link, qr, { qr = it }, a.onCopy) }
                } else {
                    inviteLink?.let { link ->
                        Text(t("gs_invite_link"), style = OshiTheme.typography.labelMedium, color = Ink.strong)
                        LinkTools(link, qr, { qr = it }, a.onCopy)
                        if (!confirmReset) {
                            SettingsButton(SettingsIcon.LINK, t("gs_reset_link"), danger = true) { confirmReset = true }
                        } else {
                            Text(t("gs_reset_link_confirm"), style = OshiTheme.typography.bodySmall, color = OshiTheme.danger)
                            SettingsButtonRow {
                                SettingsButton(null, t("gs_reset_link"), danger = true) { confirmReset = false; a.onResetLink() }
                                SettingsButton(null, t("common.cancel")) { confirmReset = false }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val canSave = dirty && !v.linkBusy && (!public || normalized != null)
            TextButton(
                onClick = { if (public) a.onSetPublic(normalized!!, preview) else a.onSetPrivate() },
                enabled = canSave,
            ) { Text(t("gs_save"), color = if (canSave) OshiTheme.brand else Ink.soft, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.close"), color = OshiTheme.textPrimary) } },
    )
}

/** The link, Copy and a QR toggle. */
@Composable
private fun LinkTools(link: String, qr: Boolean, onQr: (Boolean) -> Unit, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
        SelectionContainer {
            Text(link, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = OshiTheme.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        SettingsButtonRow {
            SettingsButton(SettingsIcon.COPY, t("gs_copy_link")) { onCopy(link) }
            SettingsButton(SettingsIcon.QR, t("qr.code_image")) { onQr(!qr) }
        }
        if (qr) Box(Modifier.clip(OshiTheme.radiusMd).background(Color.White).padding(OshiTheme.sm)) { QrCode(link, size = 148.dp) }
    }
}

/** Two pills, one selected — Private / Public. */
@Composable
private fun TwoChoice(left: String, right: String, rightSelected: Boolean, onPick: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(OshiTheme.pill).background(OshiTheme.textPrimary.copy(alpha = 0.06f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((label, value) in listOf(left to false, right to true)) {
            val on = rightSelected == value
            Box(
                Modifier.weight(1f).clip(OshiTheme.pill)
                    .background(if (on) OshiTheme.brand else Color.Transparent)
                    .clickable(role = Role.RadioButton, onClickLabel = label) { onPick(value) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Ink.onBrandStrong else Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RadioLine(label: String, selected: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier.fillMaxWidth().clip(OshiTheme.radiusSm)
            .background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.RadioButton, onClickLabel = label, onClick = onClick)
            .hoverable(source)
            .heightIn(min = 40.dp).padding(horizontal = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape)
                .background(if (selected) OshiTheme.brand else OshiTheme.textPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White)) }
        Text(label, fontSize = 14.sp, color = Ink.strong, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChoiceDialog(title: String, options: List<Pair<String, String?>>, selected: Int, onPick: (Int) -> Unit, close: () -> Unit) {
    var pick by remember { mutableStateOf(selected) }
    OshiAlertDialog(
        onDismissRequest = close,
        title = { Text(title, style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.widthIn(max = 420.dp)) {
                options.forEachIndexed { i, (label, _) -> RadioLine(label, pick == i) { pick = i } }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pick != selected) onPick(pick); close() }, enabled = pick != selected) {
                Text(t("gs_save"), color = if (pick != selected) OshiTheme.brand else Ink.soft, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel"), color = OshiTheme.textPrimary) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionsDialog(s: GroupSettings, onSave: (GroupSettings.ReactionMode, List<String>) -> Unit, close: () -> Unit) {
    var mode by remember { mutableStateOf(s.reactions) }
    var chosen by remember { mutableStateOf(s.allowedReactions.ifEmpty { GroupSettings.DEFAULT_CHOOSER.take(3) }) }
    val offered = (GroupSettings.DEFAULT_CHOOSER + s.allowedReactions).distinct()
    OshiAlertDialog(
        onDismissRequest = close,
        title = { Text(t("gs_reactions"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                RadioLine(t("gs_reactions_all"), mode == GroupSettings.ReactionMode.ALL) { mode = GroupSettings.ReactionMode.ALL }
                RadioLine(t("gs_reactions_some"), mode == GroupSettings.ReactionMode.SOME) { mode = GroupSettings.ReactionMode.SOME }
                RadioLine(t("gs_reactions_none"), mode == GroupSettings.ReactionMode.NONE) { mode = GroupSettings.ReactionMode.NONE }
                if (mode == GroupSettings.ReactionMode.SOME) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = OshiTheme.sm, top = OshiTheme.xs)) {
                        for (e in offered) {
                            val on = e in chosen
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (on) OshiTheme.brand.copy(alpha = 0.22f) else OshiTheme.textPrimary.copy(alpha = 0.06f))
                                    .clickable(role = Role.Checkbox, onClickLabel = e) {
                                        chosen = if (on) chosen - e else if (chosen.size < GroupSettings.MAX_ALLOWED_REACTIONS) chosen + e else chosen
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, fontSize = 20.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(mode, chosen); close() }) {
                Text(t("gs_save"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel"), color = OshiTheme.textPrimary) } },
    )
}

@Composable
private fun WelcomeDialog(initial: String, onSave: (String) -> Unit, close: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    OshiAlertDialog(
        onDismissRequest = close,
        title = { Text(t("gs_welcome"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                Text(t("gs_welcome_hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                BasicTextField(
                    value = value,
                    onValueChange = { value = it.take(GroupSettings.MAX_WELCOME) },
                    textStyle = OshiTheme.typography.bodyMedium.copy(color = OshiTheme.textPrimary),
                    cursorBrush = SolidColor(OshiTheme.brand),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(OshiTheme.radiusSm)
                        .background(OshiTheme.textPrimary.copy(alpha = 0.06f)).padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Text("${value.length} / ${GroupSettings.MAX_WELCOME}", fontSize = 11.sp, color = Ink.soft, modifier = Modifier.align(Alignment.End))
            }
        },
        confirmButton = {
            val canSave = value.trim() != initial
            TextButton(onClick = { onSave(value.trim()); close() }, enabled = canSave) {
                Text(t("gs_save"), color = if (canSave) OshiTheme.brand else Ink.soft, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel"), color = OshiTheme.textPrimary) } },
    )
}

private class ListLine(val text: String, val sub: String?, val action: String?, val onAction: (() -> Unit)?)

@Composable
private fun ListDialog(title: String, empty: String, lines: List<ListLine>, close: () -> Unit) {
    OshiAlertDialog(
        onDismissRequest = close,
        title = { Text(title, style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.widthIn(min = 300.dp, max = 460.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (lines.isEmpty()) Text(empty, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                lines.forEachIndexed { i, l ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                        Column(Modifier.weight(1f)) {
                            Text(l.text, fontSize = 14.sp, color = Ink.strong)
                            l.sub?.let { Text(it, fontSize = 11.sp, color = Ink.soft) }
                        }
                        if (l.action != null && l.onAction != null) TextAction(l.action) { l.onAction.invoke() }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(t("common.close"), color = OshiTheme.textPrimary) } },
    )
}

/** §2.9 for an admin: for everyone (dissolve) or for me (leave + local delete). */
@Composable
private fun DeleteGroupDialog(onEveryone: () -> Unit, onMe: () -> Unit, close: () -> Unit) {
    OshiAlertDialog(
        onDismissRequest = close,
        title = { Text(t("gs_delete_group"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                Text(t("gs_delete_confirm"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                SettingsButton(SettingsIcon.DELETE, t("gs_delete_for_everyone"), danger = true) { close(); onEveryone() }
                SettingsButton(null, t("gs_delete_for_me"), danger = true) { close(); onMe() }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel"), color = OshiTheme.textPrimary) } },
    )
}

/** §3.1 step 3: the confirmation a public link needs. Nothing is sent before Join. */
@Composable
fun PublicJoinDialog(state: PublicJoinState, onJoin: () -> Unit, onDismiss: () -> Unit) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("gs_join_title"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                when (state) {
                    is PublicJoinState.Loading -> {
                        Text("@" + state.handle, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                        Text("…", color = Ink.soft)
                    }
                    is PublicJoinState.Found -> {
                        Text(
                            state.name?.let { com.oshi.desktop.pairing.ExternalLinkGate.displayGroupName(it) } ?: ("@" + state.handle),
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink.strong, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(GroupPublicLink.display(state.handle), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Ink.soft)
                        state.description?.let { Text(it, style = OshiTheme.typography.bodyMedium, color = Ink.strong, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                        state.memberCount?.let { Text(t("group_member_count", it), fontSize = 12.sp, color = Ink.soft) }
                        Spacer(Modifier.height(OshiTheme.xs))
                        Text(t("gs_type_public_footer"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                    }
                    is PublicJoinState.Problem -> Text(state.message, color = OshiTheme.danger)
                }
            }
        },
        confirmButton = {
            if (state is PublicJoinState.Found) TextButton(onClick = onJoin) {
                Text(t("button_join"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (state is PublicJoinState.Problem) t("common.close") else t("common.cancel"), color = OshiTheme.textPrimary)
            }
        },
    )
}

// ---------------------------------------------------------------------------- rows

/** Label over its value; a chevron and a click only when it can be edited here. */
@Composable
private fun SettingValueRow(label: String, value: String?, monoValue: Boolean = false, inset: Boolean = true, onClick: (() -> Unit)?) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusSm)
            .background(if (hovered.value && onClick != null) OshiTheme.textPrimary.copy(alpha = 0.06f) else Color.Transparent)
            .let { m -> if (onClick == null) m else m.focusRing(OshiTheme.radiusSm).clickable(interactionSource = source, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick).hoverable(source) }
            .heightIn(min = 44.dp)
            .padding(horizontal = if (inset) OshiTheme.md else 0.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = Ink.strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!value.isNullOrBlank()) Text(
                value, fontSize = 12.sp, color = Ink.soft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontFamily = if (monoValue) FontFamily.Monospace else null,
            )
        }
        if (onClick != null) GlyphIcon(Glyph.CHEVRON_RIGHT, Ink.soft.copy(alpha = 0.6f), 14.dp)
    }
}

@Composable
private fun SettingSwitchRow(label: String, detail: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = Ink.strong)
            detail?.let { Text(it, fontSize = 12.sp, color = Ink.soft) }
        }
        Switch(
            checked = checked, enabled = enabled, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = OshiTheme.brandFill),
        )
    }
}

@Composable
private fun MessageAdminRow(admins: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingValueRow(t("gs_message_admin"), admins.joinToString(", ") { it.second }, inset = false) {
            if (admins.size == 1) onPick(admins.single().first) else open = true
        }
        OshiDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((address, label) in admins) ChromeMenuItem(Glyph.PERSON, label) { open = false; onPick(address) }
        }
    }
}

@Composable
private fun InfoSectionPublic(title: String, content: @Composable () -> Unit) = InfoSection(title, content)
