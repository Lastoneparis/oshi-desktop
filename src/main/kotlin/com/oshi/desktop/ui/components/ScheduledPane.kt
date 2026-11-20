package com.oshi.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.desktop.ui.state.ConversationRow
import com.oshi.desktop.ui.state.ScheduledRow
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Local-only schedule queue: a due time is not a background-delivery guarantee.
 *
 * __DESKTOP_TOOLS_2027__ Redrawn with the tool kit: recipient picker as a menu, quick times
 * plus an exact date and time, and the queue as rows with icon actions.
 */
@Composable
fun ScheduledPane(
    rows: List<ScheduledRow>,
    contacts: List<ContactRow> = emptyList(),
    groups: List<ConversationRow> = emptyList(),
    busy: Boolean = false,
    onSchedule: (String, String, Long, Boolean) -> Unit = { _, _, _, _ -> },
    onEdit: (String, String) -> Unit = { _, _ -> },
    onCancel: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val eligible = contacts.filterNot { it.blocked }
    var recipient by remember(eligible.map { it.address } + groups.map { it.id }) { mutableStateOf(eligible.firstOrNull()?.address ?: groups.firstOrNull()?.id.orEmpty()) }
    var recipientIsGroup by remember(eligible.map { it.address } + groups.map { it.id }) {
        mutableStateOf(eligible.isEmpty() && groups.isNotEmpty())
    }
    var content by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    var exact by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editedContent by remember { mutableStateOf("") }
    ToolScaffold(t("scheduled_msg.title"), modifier, subtitle = t("pc.sched.subtitle"), icon = ToolGlyph.CALENDAR) {
        ToolNotice(t("pc.sched.howItWorks"), ToolTone.INFO)
        ToolCard(title = t("scheduled_msg.schedule"), icon = ToolGlyph.CLOCK) {
            if (eligible.isEmpty() && groups.isEmpty()) {
                ToolHint(t("pc.sched.noRecipients"))
            } else {
                val selected = if (recipientIsGroup) groups.firstOrNull { it.id == recipient }?.label else eligible.firstOrNull { it.address == recipient }?.label
                Box {
                    ToolRow(
                        selected ?: t("pc.radio.chooseContact"),
                        leading = { Monogram(selected ?: "?", recipient, 30.dp) },
                        subtitle = if (recipientIsGroup) t("pc.sched.group") else null,
                        onClick = { choosing = true },
                        modifier = Modifier.clip(OshiTheme.radiusMd).background(OshiTheme.surface),
                    ) { ToolIcon(ToolGlyph.CHEVRON, Ink.soft, 14.dp) }
                    OshiDropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        for (c in eligible) DropdownMenuItem(text = { Text(c.label) }, onClick = { recipient = c.address; recipientIsGroup = false; choosing = false })
                        for (g in groups) DropdownMenuItem(text = { Text("${g.label} · ${t("pc.sched.group")}") }, onClick = { recipient = g.id; recipientIsGroup = true; choosing = false })
                    }
                }
                ToolField(content, { content = it }, t("new.composer.message"), Modifier.fillMaxWidth(), singleLine = false, minHeight = 88.dp)
                Text(t("scheduled_msg.when").uppercase(), style = ToolType.overline, color = Ink.soft)
                val ready = !busy && content.isNotBlank() && recipient.isNotBlank()
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    ToolButton(t("pc.sched.in15"), icon = ToolGlyph.CLOCK, enabled = ready) { onSchedule(recipient, content, 15 * 60_000L, recipientIsGroup); content = "" }
                    ToolButton(t("pc.sched.in1h"), enabled = ready) { onSchedule(recipient, content, 60 * 60_000L, recipientIsGroup); content = "" }
                    ToolButton(t("pc.sched.tomorrow9"), enabled = ready) {
                        val at = LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        onSchedule(recipient, content, at - System.currentTimeMillis(), recipientIsGroup); content = ""
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    ToolField(exact, { exact = it }, "2026-12-24 18:30", Modifier.weight(1f), icon = ToolGlyph.CALENDAR, mono = true)
                    val whenMs = runCatching {
                        LocalDateTime.parse(exact.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()?.takeIf { it > System.currentTimeMillis() }
                    ToolButton(t("scheduled_msg.schedule"), style = ToolButtonStyle.PRIMARY, enabled = ready && whenMs != null) {
                        onSchedule(recipient, content, whenMs!! - System.currentTimeMillis(), recipientIsGroup); content = ""; exact = ""
                    }
                }
                ToolHint(t("pc.sched.exactHint"))
            }
        }
        if (rows.isEmpty()) {
            ToolEmptyState(t("pc.sched.empty.title"), t("pc.sched.empty.body"), mascot = "sleep")
        } else ToolCard(title = t("pc.sched.queue"), icon = ToolGlyph.CALENDAR, trailing = { ToolPill("${rows.size}") }) {
            for (row in rows) {
                val pending = row.status == "pending"
                Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                    ToolRow(
                        if (row.group) "${row.recipient} · ${t("pc.sched.group")}" else row.recipient,
                        subtitle = "${row.due} · ${statusLabel(row.status)}",
                        icon = if (pending) ToolGlyph.CLOCK else if (row.status == "sent") ToolGlyph.CHECK else ToolGlyph.ALERT,
                    ) {
                        if (pending) {
                            if (editingId == row.id) {
                                ToolIconButton(ToolGlyph.CHECK, t("common.save"), enabled = !busy) { onEdit(row.id, editedContent); editingId = null }
                                ToolIconButton(ToolGlyph.CLOSE, t("common.cancel")) { editingId = null; editedContent = "" }
                            } else {
                                ToolIconButton(ToolGlyph.EDIT, t("common.edit")) { editingId = row.id; editedContent = row.content }
                            }
                            ToolIconButton(ToolGlyph.TRASH, t("pc.sched.cancelSend"), enabled = !busy, tint = OshiTheme.danger) { onCancel(row.id) }
                        }
                    }
                    if (editingId == row.id) ToolField(editedContent, { editedContent = it }, t("new.composer.message"), Modifier.fillMaxWidth().padding(start = 46.dp), singleLine = false, minHeight = 64.dp)
                    else Text(row.content, style = ToolType.body, color = Ink.strong, modifier = Modifier.padding(start = 54.dp, bottom = OshiTheme.sm))
                }
                Hairline()
            }
        }
    }
}

private fun statusLabel(wire: String): String = when (wire) {
    "pending" -> t("pc.sched.status.pending")
    "sent" -> t("pc.sched.status.sent")
    "cancelled" -> t("pc.sched.status.cancelled")
    "failed" -> t("pc.sched.status.failed")
    else -> wire
}
