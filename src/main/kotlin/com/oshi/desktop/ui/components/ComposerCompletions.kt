package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.i18n.t

/**
 * __COMPOSER_COMPLETIONS_2026_11_24__ The one list that opens above the composer while a
 * completion is being typed — the `@` member picker and the `/` command menu — and the
 * keyboard that drives it (Telegram's: Up/Down move, Enter or Tab pick, Esc closes).
 *
 * Before this the two lists were mouse-only: Enter with the `@` picker open took the FIRST
 * member whatever the user was looking at, and Enter with the `/` menu open SENT the half
 * typed `/pa` as a message.
 */
object CompletionKeys {

    sealed interface Action {
        data class Move(val index: Int) : Action
        data class Pick(val index: Int) : Action
        data object Dismiss : Action
        data object None : Action
    }

    /**
     * What [key] does to a list of [size] rows with [index] highlighted. Up/Down wrap, like
     * every desktop menu; nothing happens on an empty list, so the composer keeps its keys.
     */
    fun reduce(key: Key, index: Int, size: Int): Action {
        if (size <= 0) return Action.None
        val i = index.coerceIn(0, size - 1)
        return when (key) {
            Key.DirectionDown -> Action.Move((i + 1) % size)
            Key.DirectionUp -> Action.Move((i - 1 + size) % size)
            Key.Enter, Key.NumPadEnter, Key.Tab -> Action.Pick(i)
            Key.Escape -> Action.Dismiss
            else -> Action.None
        }
    }
}

/** One row of a [CompletionPopup]. [leading] draws the avatar / icon cell. */
data class CompletionRow(
    val title: String,
    val subtitle: String?,
    val trailing: String? = null,
    val leading: CompletionLeading,
)

sealed interface CompletionLeading {
    /** A person: monogram on their avatar gradient. */
    data class Person(val name: String, val key: String) : CompletionLeading
    /** A command: the brand-gradient squircle with a short label ("/" or an emoji). */
    data class Command(val label: String) : CompletionLeading
}

/**
 * The floating glass card itself. [selected] is drawn highlighted and scrolled into view;
 * a click picks the row (the keyboard is handled by the composer field, which keeps focus).
 */
@Composable
fun CompletionPopup(
    rows: List<CompletionRow>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** __BOTS_PHASE1_2026_11_24__ the row's "↗" arrow: insert instead of pick (null = no arrow). */
    onInsert: ((Int) -> Unit)? = null,
) {
    if (rows.isEmpty()) return
    val list = rememberLazyListState()
    LaunchedEffect(selected, rows.size) {
        if (selected in rows.indices) runCatching { list.animateScrollToItem(selected) }
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.xs)
            .glass(RoundedCornerShape(20.dp), OshiTheme.surfaceElevated, OshiTheme.cardOutline),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(OshiTheme.xs),
            state = list,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(rows) { i, row ->
                val (source, hovered) = rememberRowInteraction()
                val on = i == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                on -> OshiTheme.brand.copy(alpha = 0.14f)
                                hovered.value -> OshiTheme.brand.copy(alpha = 0.07f)
                                else -> Color.Transparent
                            },
                        )
                        .clickable(source, null) { onPick(i) }
                        .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Leading(row.leading)
                    Spacer(Modifier.width(OshiTheme.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            style = OshiTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (on) OshiTheme.brand else Ink.strong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!row.subtitle.isNullOrBlank()) {
                            Text(row.subtitle, fontSize = 12.sp, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (row.trailing != null) {
                        Spacer(Modifier.width(OshiTheme.sm))
                        Text(
                            row.trailing,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink.soft,
                            modifier = Modifier
                                .clip(OshiTheme.pill)
                                .background(Ink.soft.copy(alpha = 0.12f))
                                .padding(horizontal = OshiTheme.sm, vertical = 2.dp),
                        )
                    }
                    if (onInsert != null) {
                        Spacer(Modifier.width(OshiTheme.xs))
                        InsertArrow { onInsert(i) }
                    }
                }
            }
        }
    }
}

/** Telegram's per-row arrow: puts `/name ` in the field so arguments can follow. */
@Composable
private fun InsertArrow(onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
            .background(if (hovered.value) OshiTheme.brand.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(source, null, onClickLabel = t("pc.bot.insert_command"), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("↖", color = if (hovered.value) OshiTheme.brand else Ink.soft, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Leading(leading: CompletionLeading) {
    when (leading) {
        is CompletionLeading.Person -> Box(
            Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(OshiTheme.avatarBrush(leading.key)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                OshiTheme.monogram(leading.name, leading.key),
                color = OshiTheme.avatarEntry(leading.key).ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        is CompletionLeading.Command -> Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(OshiTheme.brandGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(leading.label, color = Ink.onBrandStrong, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * __DESKTOP_BOT_COMMANDS_2026_11_24__ The `/` commands of the bots serving a conversation
 * (group id, or a `bot!<groupId>` channel), supplied by the window from `OwnedBotStore` and
 * `GroupBotCommandDirectory`. A local so the composer needs no new parameter: the default
 * (no bots) leaves every existing caller and screenshot unchanged.
 */
val LocalBotCommands = androidx.compose.runtime.staticCompositionLocalOf<(String) -> List<com.oshi.desktop.msg.SlashCommands.Command>> {
    { emptyList() }
}

/** Open the group behind a bot channel with `/command ` typed in its composer. */
val LocalOpenBotCommand = androidx.compose.runtime.staticCompositionLocalOf<(groupId: String, command: String) -> Unit> {
    { _, _ -> }
}

/** Telegram's "Menu" button: a "/" in a brand squircle beside the field. */
@Composable
internal fun BotMenuButton(open: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Box(
        Modifier
            .size(Metrics.actionButton)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    open -> OshiTheme.brandGradient
                    hovered.value -> androidx.compose.ui.graphics.SolidColor(OshiTheme.brand.copy(alpha = 0.18f))
                    else -> androidx.compose.ui.graphics.SolidColor(OshiTheme.brand.copy(alpha = 0.10f))
                },
            )
            .focusRing(RoundedCornerShape(14.dp))
            .clickable(source, null, enabled = enabled, onClickLabel = t("accessibility.menu"), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("/", color = if (open) Ink.onBrandStrong else OshiTheme.brand, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Under a bot channel's read-only notice: its commands, one click from the group composer. */
@Composable
internal fun BotThreadMenu(conversationId: String) {
    if (!conversationId.startsWith(com.oshi.desktop.ui.state.ChatShellModel.BOT_PREFIX)) return
    val commands = LocalBotCommands.current(conversationId)
    if (commands.isEmpty()) return
    val open = LocalOpenBotCommand.current
    val groupId = conversationId.removePrefix(com.oshi.desktop.ui.state.ChatShellModel.BOT_PREFIX)
    var expanded by androidx.compose.runtime.remember(conversationId) { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (expanded) {
            CompletionPopup(
                rows = commands.map { CompletionRow(it.command, it.description, it.source, CompletionLeading.Command(it.emoji ?: "/")) },
                selected = -1,
                onPick = { i -> expanded = false; open(groupId, commands[i].command) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotMenuButton(open = expanded, enabled = true) { expanded = !expanded }
            Spacer(Modifier.width(OshiTheme.sm))
            Text(
                t("pc.bot.commands") + " · " + commands.size,
                style = OshiTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink.strong,
            )
        }
    }
}

/** [BotThreadMenu] opened, for the headless renders (a click cannot be simulated there). */
@Composable
internal fun BotThreadMenuPreview(conversationId: String) {
    val commands = LocalBotCommands.current(conversationId)
    Column(Modifier.fillMaxWidth()) {
        CompletionPopup(
            rows = commands.map { CompletionRow(it.command, it.description, it.source, CompletionLeading.Command(it.emoji ?: "/")) },
            selected = -1,
            onPick = {},
        )
        BotThreadMenu(conversationId)
    }
}
