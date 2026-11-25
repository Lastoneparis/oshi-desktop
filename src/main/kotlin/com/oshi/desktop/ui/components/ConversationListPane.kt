package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ChatListMerge
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.ConversationRow
import com.oshi.desktop.ui.state.IncomingShare
import com.oshi.desktop.ui.state.NetworkBadge
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.ShellState
import java.time.LocalDate

/**
 * The left column: account, title, filter, conversations, and the two non-chat destinations.
 *
 * ============================================================ WHAT THE CAPTURE ACTUALLY SHOWS
 *
 * `~/Desktop/OSHI_Mac_Captures/HABILLE-1-Messages.png`, transcribed rather than reinterpreted:
 * a large bold "Messages" heading, a filled rounded filter field under it, then rows of a
 * circular gradient avatar, a bold name over a grey one-line preview, and a right-hand cluster
 * of the time above an unread badge. Hairline separators inset past the avatar. Light,
 * white-surfaced, violet used only for the avatar, the badge and an unread timestamp.
 *
 * The phone puts the unread badge at the END OF THE PREVIEW LINE instead — a deliberate change
 * recorded in `MessagesListView.swift`, because a separate right column was what set the row
 * height and pushed the pitch to 92pt. On a desktop the row is 72dp and there is width to
 * spare, so the capture's right-hand cluster is what this draws.
 *
 * ============================================================ WHAT IS NOT HERE
 *
 * No "New message" destination: there is no contact picker in this window and no live camera to
 * scan a code with (`DesktopLimits.MISSING`), so a button leading to neither would be an
 * invitation to a dead end. No compose FAB for the same reason. The account pane names
 * `/qr` and `/send`, which are the paths that exist.
 */
@Composable
fun ConversationListPane(
    state: ShellState,
    query: String,
    onQuery: (String) -> Unit,
    half: ConversationFilter.Half,
    onHalf: (ConversationFilter.Half) -> Unit,
    onSelect: (String) -> Unit,
    onShow: (Pane) -> Unit,
    onCreateGroup: (String, List<String>) -> Unit = { _, _ -> },
    today: LocalDate,
    badge: NetworkBadge = NetworkBadge.OFFLINE,
    incomingShares: List<IncomingShare> = emptyList(),
    /** Read when the reach badge is clicked. Null leaves the badge a plain indicator. */
    onReach: (() -> com.oshi.desktop.ui.state.MeshReach)? = null,
    /** __MERGED_CHATS_2026_11_18__ "New chat" / "Scan an invite" in the New… menu: the New screen. */
    onNewChat: () -> Unit = {},
    /** __CONVERSATION_PIN_MUTE_2027__ the row menu (right-click or "⋯" on hover). */
    onSetPinned: (String, Boolean) -> Unit = { _, _ -> },
    onSetMuted: (String, Boolean) -> Unit = { _, _ -> },
    onMarkRead: (String) -> Unit = {},
    /** __DELETE_CONVERSATION_2027__ delete this device's copy of a direct conversation. */
    onDeleteConversation: ((String) -> Unit)? = null,
    /** __SEARCH_MESSAGE_TEXT_2027__ ids of conversations whose messages contain the query. */
    textMatches: (String) -> Set<String> = { emptySet() },
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf<ConversationRow?>(null) }
    // Message text is read off the store on a worker, after a short pause in typing.
    val textHits by androidx.compose.runtime.produceState(emptySet<String>(), query, state.conversations) {
        val q = query.trim()
        value = if (q.length < 2) emptySet() else {
            kotlinx.coroutines.delay(150)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { textMatches(q) }
        }
    }
    // __MERGED_CHATS_2026_11_18__ More › Appearance › "Combine messages and groups".
    val merged = com.oshi.desktop.ui.ChatListSettings.merged
    var filter by remember { mutableStateOf(ChatListMerge.Filter.ALL) }
    var groupSub by remember { mutableStateOf(ChatListMerge.GroupSub.ALL) }
    val listPreview = LocalConversationListPreview.current
    var creatingGroup by remember { mutableStateOf(listPreview.creatingGroup) }
    var newMenu by remember { mutableStateOf(false) }
    val showingGroups = if (merged) filter == ChatListMerge.Filter.GROUPS else half == ConversationFilter.Half.GROUPS

    // The two filters compose in ONE order and it matters: the kind split first, the typed
    // query second. The other way round, an empty Groups tab would report "nothing matched
    // <query>" while the user had typed nothing at all, because the query filter would have
    // been handed a list that the kind filter then emptied.
    val inHalf = remember(state.conversations, half, merged, filter, groupSub) {
        if (merged) state.conversations.filter { ChatListMerge.matches(it, filter) && (filter != ChatListMerge.Filter.GROUPS || ChatListMerge.matches(it, groupSub)) }
        else ConversationFilter.ofKind(state.conversations, half)
            .filter { half != ConversationFilter.Half.GROUPS || ChatListMerge.matches(it, groupSub) }
    }
    val rows = remember(inHalf, query, merged, filter, groupSub, textHits) {
        if (merged) ChatListMerge.arrange(state.conversations, filter, groupSub, query, textHits) else ConversationFilter.apply(inHalf, query, textHits)
    }
    val emptyReason = ConversationFilter.emptyReason(inHalf.size, rows.size, query)

    // __LIST_TOP_2026_09_22__ The list is keyed (`key = { it.id }`) and sorted newest-first
    // (`ChatShellModel.build`, `sortedByDescending { it.lastActivityMs }`). A keyed LazyColumn
    // keeps the FIRST VISIBLE KEY in place when items move, so when a message lands in any
    // conversation other than the top one, that conversation jumps to index 0 ABOVE the
    // viewport and the list silently shifts one row: the newest conversation — the one that
    // just changed — is the one you cannot see. If the list was at the very top before the
    // reorder, it stays at the top. `atTop` is read during composition, i.e. BEFORE the new
    // rows are measured, so it describes where the user was, not where the reorder put them.
    val listState = rememberLazyListState()
    val atTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    val wasAtTop = atTop
    val firstKey = rows.firstOrNull()?.id
    LaunchedEffect(firstKey) {
        // Unconditional on purpose: this may run before OR after the reordered rows are
        // measured, and `scrollToItem` forgets the remembered key either way, so the next
        // measure places index 0 — not the old first key — at the top.
        if (wasAtTop && firstKey != null) listState.scrollToItem(0)
    }

    Column(modifier.width(Metrics.sidebar).fillMaxHeight().background(OshiTheme.background)) {

        AccountStrip(state, onShow)
        Hairline()

        // The shipped list's own control, in its own place — first thing under the header.
        // Merged mode replaces it by the chips under the search field (as on both phones).
        if (!merged) SegmentedControl(
            options = listOf(ConversationFilter.Half.MESSAGES, ConversationFilter.Half.GROUPS),
            selected = half,
            label = { if (it == ConversationFilter.Half.MESSAGES) t("tab.messages") else t("tab.groups") },
            onSelect = onHalf,
            badge = { ConversationFilter.unreadIn(state.conversations, it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        )

        Row(
            Modifier.fillMaxWidth().padding(start = OshiTheme.lg, end = OshiTheme.lg, bottom = OshiTheme.md, top = if (merged) OshiTheme.lg else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            Text(
                // The heading and the segment above it are the same two words in English, but they
                // are NOT the same keys: `tab.*` is what a five-across control has room for and
                // `*.title` is what a screen title reads like, and the catalogs spell them apart in
                // several locales. Reusing one for the other is a guess that happens to be right here.
                when {
                    merged -> t("chats.merged.segment")
                    half == ConversationFilter.Half.GROUPS -> t("groups.title")
                    else -> t("messages.title")
                },
                style = OshiTheme.typography.headlineLarge,
                color = Ink.strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // __LIVE_SHARE_COUNTER_2026_09_22__ / __TOOLBAR_DRIFT_2026_09_22__ — see
            // HeaderIndicators.kt. The reach badge is always here; only the share counter
            // comes and goes, and only when a share starts or ends.
            IncomingSharesChip(incomingShares, onSelect)
            NetworkBadgeChip(badge, reach = onReach)
            if (merged) {
                Box {
                    NewButton(open = newMenu) { newMenu = !newMenu }
                    NewMenu(
                        expanded = newMenu,
                        onDismiss = { newMenu = false },
                        onNewChat = { newMenu = false; onNewChat() },
                        onNewGroup = { newMenu = false; creatingGroup = true; filter = ChatListMerge.Filter.GROUPS },
                        onScanInvite = { newMenu = false; onNewChat() },
                        onDiscover = { newMenu = false; onShow(Pane.DISCOVER_GROUPS) },
                    )
                }
            }
        }

        FilterField(query, onQuery, Modifier.padding(horizontal = OshiTheme.lg))
        Spacer(Modifier.height(OshiTheme.md))

        if (merged) {
            MergedFilterChips(filter, ChatListMerge.counts(state.conversations)) { filter = it; if (it != ChatListMerge.Filter.GROUPS) creatingGroup = false }
            Spacer(Modifier.height(OshiTheme.sm))
        }
        if (showingGroups) {
            GroupSubChips(groupSub) { groupSub = it }
            Spacer(Modifier.height(OshiTheme.sm))
        }

        // Merged mode: the create card is behind "New… › New group" (it was always open in
        // the Groups half, which keeps doing exactly that).
        if (if (merged) creatingGroup else half == ConversationFilter.Half.GROUPS) {
            GroupCreateCard(
                state.contacts,
                { name, members -> onCreateGroup(name, members); if (merged) creatingGroup = false },
                Modifier.padding(horizontal = OshiTheme.lg),
                onClose = if (merged) ({ creatingGroup = false }) else null,
            )
            Spacer(Modifier.height(OshiTheme.md))
        }

        when (emptyReason) {
            ConversationFilter.EmptyReason.NO_CONVERSATIONS ->
                Box(Modifier.weight(1f)) {
                    when {
                        merged && state.conversations.isNotEmpty() && filter == ChatListMerge.Filter.UNREAD ->
                            MergedEmpty(t("chats.merged.empty_unread"), "ok")
                        merged && state.conversations.isNotEmpty() && filter == ChatListMerge.Filter.PEOPLE ->
                            MergedEmpty(t("chats.merged.empty_people"), "wave")
                        showingGroups -> NoGroupsYet(
                            if (merged) ({ creatingGroup = true }) else null,
                        )
                        else -> NoConversationsYet(onNewChat)
                    }
                }
            ConversationFilter.EmptyReason.NO_MATCHES ->
                Box(Modifier.weight(1f)) { NoMatches(query) }
            null -> LazyColumn(Modifier.weight(1f), state = listState) {
                items(rows, key = { it.id }) { row ->
                    ConversationListRow(
                        row, row.id == state.selectedId, today, groupMark = merged,
                        menu = ConversationRowMenuActions(
                            onSetPinned = { onSetPinned(row.id, it) },
                            onSetMuted = if (row.kind == ConversationKind.DIRECT || row.kind == ConversationKind.GROUP) ({ onSetMuted(row.id, it) }) else null,
                            onMarkRead = if (row.unread > 0) ({ onMarkRead(row.id) }) else null,
                            onOpen = { onSelect(row.id) },
                            onDelete = if (onDeleteConversation != null && row.kind == ConversationKind.DIRECT) ({ confirmDelete = row }) else null,
                        ),
                    ) { onSelect(row.id) }
                    Hairline(inset = OshiTheme.lg + Metrics.separatorInset)
                }
            }
        }

        confirmDelete?.let { row ->
            OshiAlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text(t("alert.delete_conversation"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
                text = { Text(row.label + "\n\n" + t("alert.delete_conversation_message"), style = OshiTheme.typography.bodyMedium, color = Ink.soft) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmDelete = null; onDeleteConversation?.invoke(row.id) }) {
                        Text(t("common.delete"), color = OshiTheme.danger, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text(t("common.cancel"), color = OshiTheme.textPrimary) }
                },
            )
        }

        Hairline()
        SidebarNav(t("settings.account"), Glyph.LOCK, state.pane == Pane.ACCOUNT) { onShow(Pane.ACCOUNT) }
        // __DESKTOP_1_6_PARITY__ "Not available on desktop" is gone from the sidebar. It opened
        // the developer ledger (PARITY.md rows, several of them stale: leaving a group, edits,
        // deletes, typing all work in this window). What a computer genuinely cannot do is
        // said, translated and with the reason, in Settings → Not on a computer; the ledger
        // stays reachable from Settings → About.
        Spacer(Modifier.height(OshiTheme.sm))
    }
}

/**
 * A group starts from contacts, never arbitrary pasted key text or an invisible roster.
 *
 * __NEW_GROUP_2027__ The phones' "New Group" sheet in a card: the name, then the people as
 * avatar rows with a check, a count, and ONE Create button that is enabled only when there is
 * a name and at least one member — the old "Add / Remove" text links per row are gone.
 */
@Composable
private fun GroupCreateCard(
    contacts: List<com.oshi.desktop.ui.state.ContactRow>,
    onCreate: (String, List<String>) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf("") }
    var selected by remember(contacts) { mutableStateOf(emptySet<String>()) }
    val eligible = contacts.filterNot { it.blocked }
    Column(modifier.fillMaxWidth().glass(OshiTheme.radiusMd, OshiTheme.surface).padding(vertical = OshiTheme.md)) {
        Row(Modifier.padding(horizontal = OshiTheme.md), verticalAlignment = Alignment.CenterVertically) {
            Text(t("nav_title_new_group"), style = OshiTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink.strong, modifier = Modifier.weight(1f))
            if (onClose != null) GlyphButton(Glyph.CLOSE, t("common.close"), size = 26.dp, glyphSize = 12.dp, onClick = onClose)
        }
        Spacer(Modifier.height(OshiTheme.sm))
        Row(
            Modifier.padding(horizontal = OshiTheme.md).fillMaxWidth().clip(OshiTheme.radiusSm).background(OshiTheme.background)
                .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (name.isBlank()) Text(t("groups.group_name_placeholder"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    singleLine = true,
                    textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                    cursorBrush = SolidColor(OshiTheme.brand),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(OshiTheme.sm))
        if (eligible.isEmpty()) {
            Text(t("group.no_contacts"), style = OshiTheme.typography.bodySmall, color = Ink.soft, modifier = Modifier.padding(horizontal = OshiTheme.md))
        } else {
            Text(
                t("group_members") + if (selected.isEmpty()) "" else " · " + t("attach.selected_count", selected.size),
                style = OshiTheme.typography.labelMedium, color = Ink.soft,
                modifier = Modifier.padding(horizontal = OshiTheme.md, vertical = OshiTheme.xxs),
            )
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                for (contact in eligible) {
                    val on = contact.address in selected
                    val (src, hov) = rememberRowInteraction()
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (hov.value) OshiTheme.textPrimary.copy(alpha = 0.05f) else Color.Transparent)
                            .selectable(selected = on, role = androidx.compose.ui.semantics.Role.Checkbox, interactionSource = src, indication = null) {
                                selected = if (on) selected - contact.address else selected + contact.address
                            }
                            .padding(horizontal = OshiTheme.md, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
                    ) {
                        Monogram(contact.label, contact.address, 30.dp)
                        Text(contact.label, style = OshiTheme.typography.bodyMedium, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        CheckCircle(on)
                    }
                }
            }
            Spacer(Modifier.height(OshiTheme.sm))
            Box(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.md), contentAlignment = Alignment.CenterEnd) {
                PrimaryButton(t("button_create_group"), enabled = name.isNotBlank() && selected.isNotEmpty()) {
                    onCreate(name, selected.toList())
                    name = ""; selected = emptySet()
                }
            }
        }
    }
}

/** The round check of a multi-select row: an empty ring, or a filled brand disc with a tick. */
@Composable
private fun CheckCircle(on: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .background(if (on) OshiTheme.brand else Color.Transparent)
            .then(if (on) Modifier else Modifier.border(1.5.dp, Ink.soft.copy(alpha = 0.6f), CircleShape)),
        contentAlignment = Alignment.Center,
    ) { if (on) GlyphIcon(Glyph.CHECK, onBrandBadge(), 13.dp) }
}

/**
 * The Groups half, empty.
 *
 * Different words from [NoConversationsYet] on purpose: a group is not started here. It is
 * created in the REPL (`/group new`) or it arrives because somebody added this account to
 * one, and telling a user to paste a contact code would send them somewhere that cannot
 * produce a group.
 */
@Composable
private fun NoGroupsYet(onNewGroup: (() -> Unit)?) {
    // __MASCOT_EMPTY_2027__ The phones' copy, and the action that actually makes a group here.
    MascotEmptyState(
        mascot = "party",
        title = t("empty_groups_title"),
        body = t("empty_groups_subtitle"),
        modifier = Modifier.padding(top = OshiTheme.xl),
        size = 96.dp,
        action = onNewGroup?.let { t("chats.merged.new_group") to it },
    )
}

// ---------------------------------------------------------------------------- account strip

@Composable
private fun AccountStrip(state: ShellState, onShow: (Pane) -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered.value) OshiTheme.surface else Color.Transparent)
            .focusRing(OshiTheme.radiusSm)
            .clickable(interactionSource = source, indication = null) { onShow(Pane.ACCOUNT) }
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        GroupAvatar(state.selfPictureBase64, state.displayName, state.selfAddress, Metrics.avatarHeader)   // __PROFILE_PHOTOS_DESKTOP_2027__
        Column(Modifier.weight(1f)) {
            Text(
                state.displayName,
                style = OshiTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink.strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(OshiTheme.xxs))
            // The rollout gate is the difference between "sending works" and "sending is
            // refused before it reaches the network" (PARITY.md row 0.8), so it is on screen
            // and not in a log line. It is a STATE badge, never a presence dot: this client
            // has no presence protocol and a green "online" would be inventing one.
            GateChip(state.gateOpen, state.gateAnswered)
        }
        GlyphIcon(Glyph.CHEVRON_RIGHT, Ink.soft, 14.dp)
    }
}

@Composable
private fun GateChip(open: Boolean, answered: Boolean) {
    // __GATE_NOT_YET_IS_NOT_NO_2026_10_29__ Three states, not two. Before the first
    // `/v2/config` answer this chip said "V2 gate closed — sends refused" in DANGER red:
    // a verdict about the account, shown to someone whose client had not finished asking.
    // On this relay that route has been measured at 9-25 s, so it is the state every
    // launch passes through. "Connecting..." is the shared catalog's own sentence
    // (`connection.connecting`, translated in 33 of 33 locales), so saying this honestly
    // costs no new English string.
    val shown = com.oshi.desktop.ui.state.gateDisplay(open, answered)
    val tint = when (shown) {
        com.oshi.desktop.ui.state.GateDisplay.OPEN -> OshiTheme.success
        com.oshi.desktop.ui.state.GateDisplay.ASKING -> Ink.soft
        com.oshi.desktop.ui.state.GateDisplay.CLOSED -> OshiTheme.danger
    }
    Row(
        Modifier
            .clip(OshiTheme.pill)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Text(
            when (shown) {
                com.oshi.desktop.ui.state.GateDisplay.OPEN -> t("conv.gate.open")
                com.oshi.desktop.ui.state.GateDisplay.ASKING -> t("connection.connecting")
                com.oshi.desktop.ui.state.GateDisplay.CLOSED -> t("conv.gate.closed")
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            // The chip's own ink, not the tint: `success` on a 14% wash of itself is far
            // under 4.5:1, and a status badge is exactly where that matters.
            color = Ink.strong,
        )
    }
}

// ---------------------------------------------------------------------------- filter field

@Composable
private fun FilterField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(if (hovered.value) OshiTheme.separator.copy(alpha = 0.12f) else OshiTheme.surface)
            .focusRing(OshiTheme.radiusMd)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        GlyphIcon(Glyph.SEARCH, Ink.soft, 15.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(ConversationFilter.PLACEHOLDER, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                interactionSource = source,
                textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                cursorBrush = SolidColor(OshiTheme.brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(18.dp).clip(CircleShape).clickable { onQuery("") },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.CLOSE, Ink.soft, 11.dp) }
        }
    }
}

// ---------------------------------------------------------------------------- row

@Composable
private fun ConversationListRow(
    row: ConversationRow,
    selected: Boolean,
    today: LocalDate,
    /** Merged list: group-like rows carry a small people glyph on the avatar (the phones' cue). */
    groupMark: Boolean = false,
    menu: ConversationRowMenuActions? = null,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberRowInteraction()
    val previewMenu = LocalConversationListPreview.current.rowMenuId == row.id
    var menuOpen by remember(row.id) { mutableStateOf(previewMenu) }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Metrics.rowHeight)
            .then(if (menu == null) Modifier else Modifier.onSecondaryClick { menuOpen = true })
            .background(
                when {
                    selected -> OshiTheme.brand.copy(alpha = 0.09f)
                    hovered.value -> OshiTheme.surface
                    else -> Color.Transparent
                }
            )
            .focusRing(OshiTheme.radiusSm)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Box {
            GroupAvatar(row.pictureBase64, row.label, row.id, Metrics.avatarList)
            if (groupMark && ChatListMerge.isGroupLike(row)) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(OshiTheme.background)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(OshiTheme.brand)
                        .semantics { contentDescription = t("chats.merged.group_a11y") },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.PEOPLE, onBrandBadge(), 11.dp) }
            }
        }

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                Text(
                    row.label,
                    style = OshiTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,   // iOS 1441b479: the NAME leads, in bold, preview under it
                    color = Ink.strong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // __CONVERSATION_PIN_MUTE_2027__ the two quiet marks the phones put by the name.
                if (row.muted) GlyphIcon(Glyph.BELL_SLASH, Ink.soft, 13.dp, Modifier.semantics { contentDescription = t("group.notifications.muted") })
            }
            // A group, a bot post and a quarantined radio message are NOT the same kind of
            // thing as a ratcheted 1:1 message. The list says which is which rather than
            // letting them all read alike — `ChatShellModel`'s "what it refuses to show".
            if (row.kind != ConversationKind.DIRECT) {
                Spacer(Modifier.height(OshiTheme.xxs))
                KindBadge(row.kind)
            }
            Spacer(Modifier.height(OshiTheme.xxs))
            // __DESKTOP_1_6_PARITY__ a voice note is a microphone, "Voice message" and its
            // length — never "[audio] note vocale été.m4a" (ListPreview).
            Row(verticalAlignment = Alignment.CenterVertically) {
                previewGlyph(row.previewKind)?.let { g ->
                    GlyphIcon(g, if (row.unread > 0) OshiTheme.brand else Ink.soft, 14.dp)
                    Spacer(Modifier.width(OshiTheme.xxs + 1.dp))
                }
                Text(
                    row.preview.ifBlank { t("messages.empty") },
                    style = OshiTheme.typography.bodyMedium,
                    color = Ink.soft,
                    fontStyle = if (row.previewKind == com.oshi.desktop.ui.state.ListPreview.Kind.DELETED) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
          Box(contentAlignment = Alignment.CenterEnd) {
            Text(
                DayBreaks.compactStamp(row.lastActivity, today),
                fontSize = 11.sp,
                fontWeight = if (row.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                // Violet on an unread row is the capture's own cue. `brand` on white is
                // 7.74:1, so it carries a timestamp safely.
                color = if (row.unread > 0) OshiTheme.brand else Ink.soft,
                // Hidden (not removed) under the hover "⋯" so the column keeps its width.
                modifier = Modifier.then(if (menu != null && (hovered.value || menuOpen)) Modifier.graphicsLayer(alpha = 0f) else Modifier),
            )
            if (menu != null && (hovered.value || menuOpen)) {
                Box {
                    GlyphButton(Glyph.MORE, t("a11y.more_options"), size = 24.dp, glyphSize = 15.dp, active = menuOpen) { menuOpen = true }
                    OshiDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp)) {
                        ConversationRowMenuItems(row, menu) { menuOpen = false }
                    }
                }
            } else if (menuOpen && menu != null) {
                Box {
                    OshiDropdownMenu(expanded = true, onDismissRequest = { menuOpen = false }) {
                        ConversationRowMenuItems(row, menu) { menuOpen = false }
                    }
                }
            }
          }
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs), verticalAlignment = Alignment.CenterVertically) {
                if (row.pinned) GlyphIcon(Glyph.PIN, Ink.soft, 13.dp, Modifier.semantics { contentDescription = t("conv.pinned") })
                // __MENTIONS_2026_09_23__ "@" pill: an unseen message here mentions you.
                if (row.mentionedYou) MentionBadge()
                UnreadBadge(row.unread)
            }
        }
    }
}

@Composable
fun KindBadge(kind: ConversationKind) {
    // The badge is NOT uppercased in code, though the English literals it replaced were.
    // `String.uppercase()` rewrites text: it turns German `ß` into `SS`, and it is a complete
    // no-op in Arabic, Hebrew, Devanagari, Japanese, Hangul, Thai and Han — seven of the
    // scripts in this catalog set, nine of its 34 locales. A badge that "shouts" would
    // therefore shout in some languages, mangle one, and stay silent in the ones where case
    // does not exist. The catalog value is drawn exactly as translated and the emphasis comes
    // from the fill and the weight, which every script has.
    val (text, tint) = when (kind) {
        ConversationKind.DIRECT -> return
        ConversationKind.GROUP -> t("group_invite_default_name") to Ink.soft
        // Desktop-only, and these two are the ones that cost something: they are the warning
        // labels on the two lanes that are NOT end-to-end encrypted, and they stay English in
        // the other 33 locales until somebody translates them into the iOS tree.
        ConversationKind.BOT -> t("conv.badge.bot") to OshiTheme.danger
        ConversationKind.LORA -> t("conv.badge.lora") to OshiTheme.warning
        // __MESHCORE_INTEROP_DESKTOP_2026_11_25__ not OSHI end-to-end encrypted, said on the row too.
        ConversationKind.MESHCORE -> t("pc.meshcore.badge") to OshiTheme.warning
    }
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        // `danger` is #FF3B30 (3.8:1) and `warning` is #FF9500 (2.2:1) on white — neither
        // clears 4.5:1 as ink. They are used as a FILL here, with `strong` on top, which is
        // the same trick the gate chip uses and for the same measured reason.
        color = Ink.strong,
        modifier = Modifier
            .clip(OshiTheme.radiusSm)
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = OshiTheme.xs, vertical = 1.dp),
    )
}

// ---------------------------------------------------------------------------- nav + empties

@Composable
private fun SidebarNav(label: String, glyph: Glyph, selected: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> OshiTheme.brand.copy(alpha = 0.09f)
                    hovered.value -> OshiTheme.surface
                    else -> Color.Transparent
                }
            )
            .focusRing(OshiTheme.radiusSm)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        GlyphIcon(glyph, if (selected) OshiTheme.brand else Ink.soft, 16.dp)
        Text(
            label,
            style = OshiTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = Ink.strong,
        )
    }
}

/**
 * The onboarding empty state, `EmptyStateView` in `MessagesListView.swift`: the mascot,
 * "No conversations yet", one line, and the one action that starts one.
 */
@Composable
private fun NoConversationsYet(onNewChat: () -> Unit) {
    MascotEmptyState(
        mascot = "wave",
        title = t("ai_no_conversations"),
        body = t("messages_empty_subtitle"),
        modifier = Modifier.padding(top = OshiTheme.xl),
        size = 96.dp,
        action = t("chats.merged.new_chat") to onNewChat,
    )
}

@Composable
private fun NoMatches(query: String) {
    MascotEmptyState(
        mascot = "thinking",
        title = t("search.no_results"),
        body = t("conv.search.no_match", query.trim()),
        modifier = Modifier.padding(top = OshiTheme.xl),
        size = 80.dp,
    )
}


// ---------------------------------------------------------------------------- merged list

/**
 * __MERGED_CHATS_2026_11_18__ All · Unread · Groups · People, each with its count of threads
 * that have something unread (Android `MergedFilterChips`, iOS the same). Selected = brand
 * fill; Light/Dark = the field fill; OLED = glass. One focusable target per chip.
 */
@Composable
internal fun MergedFilterChips(
    selected: ChatListMerge.Filter,
    counts: ChatListMerge.Counts,
    onSelect: (ChatListMerge.Filter) -> Unit,
) {
    // __FILTER_CHIPS_WRAP_2027__ A chip that does not fit is never CUT (the scrolled row cut
    // "Pers…" at the pane edge). __DESKTOP_1_6_PARITY__ But wrapping was the common case, not
    // the exception: in English at the default list width the four chips took two lines
    // ("People" alone on the second). So the row now tightens before it wraps — measured,
    // not guessed: the labels and badges are laid out at each density in turn and the first
    // that fits on ONE line wins. Only when even the tightest does not fit (long translations
    // in a narrow window) does it fall back to the FlowRow.
    val entries = ChatListMerge.Filter.entries.map { f ->
        f to when (f) {
            ChatListMerge.Filter.ALL -> t("chats.merged.filter_all") to 0
            ChatListMerge.Filter.UNREAD -> t("chats.merged.filter_unread") to counts.unread
            ChatListMerge.Filter.GROUPS -> t("chats.merged.filter_groups") to counts.groups
            ChatListMerge.Filter.PEOPLE -> t("chats.merged.filter_people") to counts.people
        }
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Measure with the style the chip really draws with (the app font, its letter spacing),
    // not a bare TextStyle: the default font is narrower, so the row "fit" and then wrapped
    // ("Personnes" alone on a 2nd line in French at 1280 px). The selected chip is SemiBold,
    // the others Medium — measure all at SemiBold, the wider of the two.
    val baseStyle = androidx.compose.material3.LocalTextStyle.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg)) {
        val avail = maxWidth
        val level = remember(entries, avail, density, baseStyle) {
            ChipDensity.entries.firstOrNull { d ->
                val total = entries.sumOf { (_, lc) ->
                    val (label, count) = lc
                    val textW = with(density) {
                        measurer.measure(label, baseStyle.merge(androidx.compose.ui.text.TextStyle(fontSize = d.font, fontWeight = FontWeight.SemiBold))).size.width.toDp()
                    }
                    val badgeW = if (count > 0) with(density) {
                        d.badgeGap + measurer.measure(if (count > 99) "99+" else "$count", baseStyle.merge(androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold))).size.width.toDp() + d.badgePad * 2
                    } else 0.dp
                    (textW + badgeW + d.padding * 2).value.toDouble()
                } + d.gap.value * (entries.size - 1)
                // +1 dp per chip: rounding of each measured width up to whole pixels.
                total + entries.size <= avail.value
            }
        }
        if (level != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(level.gap), verticalAlignment = Alignment.CenterVertically) {
                for ((f, lc) in entries) ListChip(lc.first, lc.second, f == selected, chipDensity = level) { onSelect(f) }
            }
        } else {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
                verticalArrangement = Arrangement.spacedBy(OshiTheme.xs),
            ) {
                for ((f, lc) in entries) ListChip(lc.first, lc.second, f == selected, chipDensity = ChipDensity.MINI) { onSelect(f) }
            }
        }
    }
}

/** How much air a filter chip gets: the roomiest that keeps the row on one line. */
internal enum class ChipDensity(
    val padding: androidx.compose.ui.unit.Dp,
    val gap: androidx.compose.ui.unit.Dp,
    val font: androidx.compose.ui.unit.TextUnit,
    /** Horizontal padding of the count badge, and the space before it. */
    val badgePad: androidx.compose.ui.unit.Dp = 6.dp,
    val badgeGap: androidx.compose.ui.unit.Dp = 6.dp,
) {
    ROOMY(14.dp, 8.dp, 13.sp),
    COMPACT(10.dp, 6.dp, 13.sp),
    TIGHT(8.dp, 4.dp, 12.sp),
    // French "Personnes", German "Ungelesen"… at the default column width.
    MINI(6.dp, 4.dp, 12.sp, badgePad = 4.dp, badgeGap = 4.dp),
}

/** The Groups sub-filter: All · Admin Only · Collaborative · Public (the phones' Groups tab chips). */
@Composable
internal fun GroupSubChips(selected: ChatListMerge.GroupSub, onSelect: (ChatListMerge.GroupSub) -> Unit) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg),
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.xs),
    ) {
        for (g in ChatListMerge.GroupSub.entries) {
            val label = when (g) {
                ChatListMerge.GroupSub.ALL -> t("chats.merged.filter_all")
                ChatListMerge.GroupSub.ADMIN_ONLY -> t("group_type_admin_only")
                ChatListMerge.GroupSub.COLLABORATIVE -> t("group_type_collaborative")
                ChatListMerge.GroupSub.PUBLIC -> t("group_type_public")
            }
            ListChip(label, 0, g == selected, small = true) { onSelect(g) }
        }
    }
}

@Composable
private fun ListChip(label: String, count: Int, selected: Boolean, small: Boolean = false, chipDensity: ChipDensity = ChipDensity.ROOMY, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    val glassy = OshiTheme.glass != null
    // Dark/OLED lift `brand` to #A98BFF so it reads as INK; as a FILL it then needs dark text
    // (white on #A98BFF is ~2.6:1). Light keeps white on the deep violet.
    val onBrand = if (OshiTheme.palette.isDark) Color(0xFF14082E) else Color.White
    Row(
        Modifier
            .then(
                when {
                    selected -> Modifier.clip(OshiTheme.pill).background(OshiTheme.brand)
                    glassy -> Modifier.glass(OshiTheme.pill, OshiTheme.surface)
                    else -> Modifier.clip(OshiTheme.pill).background(if (hovered.value) OshiTheme.separator.copy(alpha = 0.18f) else OshiTheme.surface)
                }
            )
            .focusRing(OshiTheme.pill)
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = if (count > 0) "$label, $count" else label }
            .padding(horizontal = if (small) OshiTheme.md else chipDensity.padding, vertical = if (small) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = if (small) 12.sp else chipDensity.font,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) onBrand else Ink.strong,
            maxLines = 1,
        )
        if (count > 0) {
            Spacer(Modifier.width(chipDensity.badgeGap))
            Box(
                Modifier.clip(OshiTheme.pill)
                    .background(if (selected) onBrand.copy(alpha = 0.18f) else OshiTheme.brand)
                    .padding(horizontal = chipDensity.badgePad, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (count > 99) "99+" else "$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (selected) onBrand else onBrandBadge())
            }
        }
    }
}

/** The compose button beside the title in merged mode: opens "New…". */
@Composable
private fun NewButton(open: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (open || hovered.value) OshiTheme.brand.copy(alpha = 0.14f) else Color.Transparent)
            .focusRing(CircleShape)
            .clickable(interactionSource = source, indication = null, onClickLabel = t("chats.merged.new_title"), onClick = onClick)
            .semantics { contentDescription = t("chats.merged.new_title") },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(Glyph.COMPOSE, OshiTheme.brand, 18.dp) }
}

/**
 * "New…" (Android `NewChatSheet`, iOS the compose sheet). Desktop offers what it can DO:
 * New chat, New group, Scan / import an invite (the New screen, which takes a pasted link, a
 * QR image file or the camera). "Discover public channels" and the "group nearby" card are
 * not offered: they are fed by Bluetooth / radio discovery the desktop does not have, and a
 * row that opens nothing is the greyed-out control `DesktopLimits` argues against.
 */
@Composable
private fun NewMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onScanInvite: () -> Unit,
    onDiscover: () -> Unit = {},
) {
    OshiDropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp)) {
        Text(
            t("chats.merged.new_title"),
            style = OshiTheme.typography.labelMedium,
            color = Ink.soft,
            modifier = Modifier.padding(horizontal = OshiTheme.md, vertical = OshiTheme.xs),
        )
        NewMenuRow(Glyph.PERSON, t("chats.merged.new_chat"), t("chats.merged.new_chat_desc"), onNewChat)
        NewMenuRow(Glyph.PEOPLE, t("chats.merged.new_group"), t("chats.merged.new_group_desc"), onNewGroup)
        NewMenuRow(Glyph.QR, t("chats.merged.scan_invite"), t("chats.merged.scan_invite_desc"), onScanInvite)
        // __DESKTOP_GROUP_DISCOVERY_2027__
        NewMenuRow(Glyph.SEARCH, t("pc.groups.discover.title"), t("pc.groups.discover.menu"), onDiscover)
    }
}

@Composable
private fun NewMenuRow(glyph: Glyph, title: String, detail: String, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .width(300.dp)
            .background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(OshiTheme.brand.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, OshiTheme.brand, 18.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
            Text(detail, style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
    }
}

@Composable
private fun MergedEmpty(title: String, mascot: String) {
    MascotEmptyState(mascot = mascot, title = title, modifier = Modifier.padding(top = OshiTheme.xl), size = 88.dp)
}

@Composable
private fun onBrandBadge(): Color = if (OshiTheme.palette.isDark) Color(0xFF14082E) else Color.White

/** Forces list state for the headless renderer (`MessagesScreens`); the window never provides it. */
data class ConversationListPreview(val rowMenuId: String? = null, val creatingGroup: Boolean = false)

val LocalConversationListPreview = androidx.compose.runtime.staticCompositionLocalOf { ConversationListPreview() }

/** __DESKTOP_1_6_PARITY__ The glyph a list row draws before a non-text preview. */
internal fun previewGlyph(kind: com.oshi.desktop.ui.state.ListPreview.Kind): Glyph? = when (kind) {
    com.oshi.desktop.ui.state.ListPreview.Kind.VOICE -> Glyph.MIC
    com.oshi.desktop.ui.state.ListPreview.Kind.PHOTO, com.oshi.desktop.ui.state.ListPreview.Kind.GIF -> Glyph.IMAGE
    com.oshi.desktop.ui.state.ListPreview.Kind.VIDEO, com.oshi.desktop.ui.state.ListPreview.Kind.VIDEO_NOTE -> Glyph.VIDEO
    com.oshi.desktop.ui.state.ListPreview.Kind.FILE -> Glyph.FILE
    com.oshi.desktop.ui.state.ListPreview.Kind.CONTACT -> Glyph.PERSON
    com.oshi.desktop.ui.state.ListPreview.Kind.LOCATION -> Glyph.MAP_PIN
    com.oshi.desktop.ui.state.ListPreview.Kind.POLL -> Glyph.POLL
    com.oshi.desktop.ui.state.ListPreview.Kind.ALIVE -> Glyph.HEART
    com.oshi.desktop.ui.state.ListPreview.Kind.DELETED -> Glyph.BLOCK
    com.oshi.desktop.ui.state.ListPreview.Kind.TEXT -> null
}
