package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.desktop.ui.state.ConversationRow
import com.oshi.desktop.ui.state.ScheduledRow

/** Local-only schedule queue: a due time is not a background-delivery guarantee. */
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
    var choosingRecipient by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editedContent by remember { mutableStateOf("") }
    Column(
        modifier.fillMaxSize().background(OshiTheme.surface).verticalScroll(rememberScrollState()).padding(OshiTheme.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {
            PaneHeading("Scheduled messages", "Saved on this machine only")
            PaneCard {
                Text(
                    "OSHI sends a scheduled message on the first poll after its due time. If this app is closed then, it sends late on the next start and says so; nothing wakes this computer.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
            }
            Spacer(Modifier.height(OshiTheme.lg))
            PaneCard {
                Text("Schedule a message", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                if (eligible.isEmpty() && groups.isEmpty()) {
                    Text("Add an unblocked contact or join a group before scheduling a message.", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                } else {
                    val selectedLabel = if (recipientIsGroup) groups.firstOrNull { it.id == recipient }?.label else eligible.firstOrNull { it.address == recipient }?.label
                    TextAction("To: ${selectedLabel ?: "Choose recipient"}") { choosingRecipient = !choosingRecipient }
                    if (choosingRecipient) for (contact in eligible) {
                        TextAction(contact.label) { recipient = contact.address; recipientIsGroup = false; choosingRecipient = false }
                    }
                    if (choosingRecipient) for (group in groups) {
                        TextAction("Group · ${group.label}") { recipient = group.id; recipientIsGroup = true; choosingRecipient = false }
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                    BasicTextField(
                        value = content,
                        onValueChange = { content = it },
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (content.isEmpty()) Text("Message", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                            inner()
                        },
                    )
                    Spacer(Modifier.height(OshiTheme.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                        TextAction(if (busy) "Scheduling…" else "+15 min") { if (!busy) onSchedule(recipient, content, 15 * 60_000L, recipientIsGroup) }
                        TextAction("+1 hour") { if (!busy) onSchedule(recipient, content, 60 * 60_000L, recipientIsGroup) }
                        TextAction("+1 day") { if (!busy) onSchedule(recipient, content, 24 * 60 * 60_000L, recipientIsGroup) }
                    }
                }
            }
            Spacer(Modifier.height(OshiTheme.lg))
            if (rows.isEmpty()) {
                EmptyPaneNote("Nothing scheduled", "Choose a contact, type a message, then choose when it should be delivered.")
            } else {
                for (row in rows) {
                    PaneCard {
                        Text(if (row.group) "Group · ${row.recipient}" else row.recipient, style = OshiTheme.typography.titleSmall, color = Ink.strong)
                        Spacer(Modifier.height(OshiTheme.xxs))
                        if (editingId == row.id) {
                            BasicTextField(
                                value = editedContent,
                                onValueChange = { editedContent = it },
                                textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                                cursorBrush = SolidColor(OshiTheme.brand),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(row.content, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                        }
                        Spacer(Modifier.height(OshiTheme.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text("Due ${row.due} · ${row.status}", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                            if (row.status == "pending") {
                                if (editingId == row.id) {
                                    TextAction(if (busy) "Saving…" else "Save") {
                                        if (!busy) { onEdit(row.id, editedContent); editingId = null }
                                    }
                                    TextAction("Discard") { editingId = null; editedContent = "" }
                                } else {
                                    TextAction("Edit") { editingId = row.id; editedContent = row.content }
                                }
                                TextAction(if (busy) "Cancelling…" else "Cancel") { if (!busy) onCancel(row.id) }
                            }
                        }
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                }
            }
        }
    }
}
