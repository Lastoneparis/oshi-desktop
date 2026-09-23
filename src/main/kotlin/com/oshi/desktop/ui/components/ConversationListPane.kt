package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.dt
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
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
    modifier: Modifier = Modifier,
) {
    // The two filters compose in ONE order and it matters: the kind split first, the typed
    // query second. The other way round, an empty Groups tab would report "nothing matched
    // <query>" while the user had typed nothing at all, because the query filter would have
    // been handed a list that the kind filter then emptied.
    val inHalf = remember(state.conversations, half) { ConversationFilter.ofKind(state.conversations, half) }
    val rows = remember(inHalf, query) { ConversationFilter.apply(inHalf, query) }
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
        SegmentedControl(
            options = listOf(ConversationFilter.Half.MESSAGES, ConversationFilter.Half.GROUPS),
            selected = half,
            label = { if (it == ConversationFilter.Half.MESSAGES) t("tab.messages") else t("tab.groups") },
            onSelect = onHalf,
            badge = { ConversationFilter.unreadIn(state.conversations, it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        )

        Row(
            Modifier.fillMaxWidth().padding(start = OshiTheme.lg, end = OshiTheme.lg, bottom = OshiTheme.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            Text(
                // The heading and the segment above it are the same two words in English, but they
                // are NOT the same keys: `tab.*` is what a five-across control has room for and
                // `*.title` is what a screen title reads like, and the catalogs spell them apart in
                // several locales. Reusing one for the other is a guess that happens to be right here.
                if (half == ConversationFilter.Half.GROUPS) t("groups.title") else t("messages.title"),
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
            NetworkBadgeChip(badge)
        }

        FilterField(query, onQuery, Modifier.padding(horizontal = OshiTheme.lg))
        Spacer(Modifier.height(OshiTheme.md))

        if (half == ConversationFilter.Half.GROUPS) {
            GroupCreateCard(state.contacts, onCreateGroup, Modifier.padding(horizontal = OshiTheme.lg))
            Spacer(Modifier.height(OshiTheme.md))
        }

        when (emptyReason) {
            ConversationFilter.EmptyReason.NO_CONVERSATIONS ->
                Box(Modifier.weight(1f)) {
                    if (half == ConversationFilter.Half.GROUPS) NoGroupsYet() else NoConversationsYet()
                }
            ConversationFilter.EmptyReason.NO_MATCHES ->
                Box(Modifier.weight(1f)) { NoMatches(query) }
            null -> LazyColumn(Modifier.weight(1f), state = listState) {
                items(rows, key = { it.id }) { row ->
                    ConversationListRow(row, row.id == state.selectedId, today) { onSelect(row.id) }
                    Hairline(inset = OshiTheme.lg + Metrics.separatorInset)
                }
            }
        }

        Hairline()
        SidebarNav(dt("desktop.nav.account"), Glyph.LOCK, state.pane == Pane.ACCOUNT) { onShow(Pane.ACCOUNT) }
        SidebarNav(dt("desktop.nav.limits"), Glyph.ALERT, state.pane == Pane.LIMITS) { onShow(Pane.LIMITS) }
        Spacer(Modifier.height(OshiTheme.sm))
    }
}

/** A group starts from contacts, never arbitrary pasted key text or an invisible roster. */
@Composable
private fun GroupCreateCard(
    contacts: List<com.oshi.desktop.ui.state.ContactRow>,
    onCreate: (String, List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var selected by remember(contacts) { mutableStateOf(emptySet<String>()) }
    val eligible = contacts.filterNot { it.blocked }
    Column(modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.surface).padding(OshiTheme.md)) {
        Text(dt("desktop.group.create.title"), style = OshiTheme.typography.titleSmall, color = Ink.strong)
        Spacer(Modifier.height(OshiTheme.xxs))
        Text(dt("desktop.group.create.hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        Spacer(Modifier.height(OshiTheme.sm))
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = OshiTheme.typography.bodySmall.copy(color = Ink.strong),
            cursorBrush = SolidColor(OshiTheme.brand),
            modifier = Modifier.fillMaxWidth().padding(vertical = OshiTheme.xxs),
            decorationBox = { inner ->
                if (name.isBlank()) Text(dt("desktop.group.create.name"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                inner()
            },
        )
        if (eligible.isEmpty()) {
            Text(dt("desktop.group.create.noContacts"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        } else {
            for (contact in eligible) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(contact.label, style = OshiTheme.typography.bodySmall, color = Ink.strong, modifier = Modifier.weight(1f))
                    TextAction(if (contact.address in selected) dt("desktop.group.create.remove") else dt("desktop.group.create.add")) {
                        selected = if (contact.address in selected) selected - contact.address else selected + contact.address
                    }
                }
            }
            TextAction(dt("desktop.group.create.action")) { onCreate(name, selected.toList()) }
        }
    }
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
private fun NoGroupsYet() {
    Column(
        Modifier.fillMaxWidth().padding(OshiTheme.lg),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(OshiTheme.xxl))
        BrandDisc(Glyph.LOCK)
        Spacer(Modifier.height(OshiTheme.md))
        Text(t("empty.no_groups"), style = OshiTheme.typography.titleMedium, color = Ink.strong)
        Spacer(Modifier.height(OshiTheme.xs))
        Text(
            // Desktop-only: the phone's equivalent copy sends you to a button that does not
            // exist here, and `/group new` is a REPL command no iOS string has ever named.
            dt("desktop.empty.groups.body"),
            style = OshiTheme.typography.bodySmall,
            color = Ink.soft,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
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
        Monogram(state.displayName, state.selfAddress, Metrics.avatarHeader)
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
            GateChip(state.gateOpen)
        }
        GlyphIcon(Glyph.CHEVRON_RIGHT, Ink.soft, 14.dp)
    }
}

@Composable
private fun GateChip(open: Boolean) {
    val tint = if (open) OshiTheme.success else OshiTheme.danger
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
            if (open) dt("desktop.gate.open.chip") else dt("desktop.gate.closed.chip"),
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
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Metrics.rowHeight)
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
        Monogram(row.label, row.id, Metrics.avatarList)

        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                style = OshiTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink.strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A group, a bot post and a quarantined radio message are NOT the same kind of
            // thing as a ratcheted 1:1 message. The list says which is which rather than
            // letting them all read alike — `ChatShellModel`'s "what it refuses to show".
            if (row.kind != ConversationKind.DIRECT) {
                Spacer(Modifier.height(OshiTheme.xxs))
                KindBadge(row.kind)
            }
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(
                row.preview.ifBlank { t("messages.empty") },
                style = OshiTheme.typography.bodyMedium,
                color = Ink.soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            Text(
                DayBreaks.compactStamp(row.lastActivity, today),
                fontSize = 11.sp,
                fontWeight = if (row.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                // Violet on an unread row is the capture's own cue. `brand` on white is
                // 7.74:1, so it carries a timestamp safely.
                color = if (row.unread > 0) OshiTheme.brand else Ink.soft,
            )
            UnreadBadge(row.unread)
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
        ConversationKind.BOT -> dt("desktop.badge.bot") to OshiTheme.danger
        ConversationKind.LORA -> dt("desktop.badge.lora") to OshiTheme.warning
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
 * The onboarding empty state, adapted from `EmptyStateView` in `MessagesListView.swift`.
 *
 * The phone's three steps end in a "Scan their code" button. There is no camera here
 * (`DesktopLimits.MISSING`, VIEWS.md §6), so the steps name the REPL commands that actually
 * exist instead of offering a control that would open nothing.
 */
@Composable
private fun NoConversationsYet() {
    Column(
        Modifier.fillMaxWidth().padding(OshiTheme.lg),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        BrandDisc(Glyph.LOCK, 52.dp)
        // `ai_no_conversations` is filed under the AI screen upstream and its value is exactly
        // this sentence in all 34 catalogs. The alternative was a `desktop.` key shipping
        // English to 33 locales for a string this project has already paid to translate.
        Text(t("ai_no_conversations"), style = OshiTheme.typography.titleMedium, color = Ink.strong)
        Text(
            dt("desktop.empty.conversations.body"),
            style = OshiTheme.typography.bodyMedium,
            color = Ink.soft,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(OshiTheme.radiusMd)
                .background(OshiTheme.surface)
                .padding(OshiTheme.md),
            verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            Step(1, dt("desktop.empty.conversations.step1.title"), dt("desktop.empty.conversations.step1.detail"))
            Step(2, dt("desktop.empty.conversations.step2.title"), dt("desktop.empty.conversations.step2.detail"))
            Step(3, dt("desktop.empty.conversations.step3.title"), dt("desktop.empty.conversations.step3.detail"))
        }
        Text(
            dt("desktop.empty.conversations.footnote"),
            fontSize = 11.sp,
            color = Ink.soft,
        )
    }
}

@Composable
private fun Step(n: Int, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(OshiTheme.brand.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            // A WESTERN digit, deliberately, and this was measured rather than assumed.
            // `NumberFormat.getIntegerInstance(locale)` follows CLDR's default numbering system,
            // which emits extended Arabic-Indic digits for `fa` and Arabic-Indic ones for `ar`.
            // Counting the non-ASCII digits actually present in the 34 catalogs says that would
            // agree with the asset in one locale and contradict it in another: `fa` writes
            // extended Arabic-Indic digits 156 times, `ar` writes an Arabic-Indic digit ONCE in
            // 3800+ strings, and `hi` writes Devanagari digits not at all. So localising this
            // step number would set an Arabic-Indic 1 beside Arabic sentences that write "1".
            // The shipped asset's own convention wins until somebody changes the asset.
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OshiTheme.brand)
        }
        Column {
            Text(title, style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
            Text(detail, fontSize = 11.sp, color = Ink.soft, fontFamily = FontFamily.Default)
        }
    }
}

@Composable
private fun NoMatches(query: String) {
    Column(
        Modifier.fillMaxWidth().padding(OshiTheme.lg),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        Text(t("search.no_results"), style = OshiTheme.typography.titleMedium, color = Ink.strong)
        Text(
            dt("desktop.empty.matches.body", query.trim()),
            style = OshiTheme.typography.bodyMedium,
            color = Ink.soft,
        )
    }
}
