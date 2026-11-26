package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.bot.BotCommand
import com.oshi.desktop.bot.BotCommands
import com.oshi.desktop.bot.OwnedBot
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme

/**
 * __DESKTOP_BOT_COMMANDS_2026_11_24__ Settings → Bots: the bots this account owns, and the
 * editor for one — name, `@username`, description, the Telegram-style `/` command list
 * (add / edit / remove, validated with the server's own rules), the groups it serves, its
 * token and how to connect a program to it.
 *
 * Stateless over [OwnedBot]: the window passes the list and the four actions, so it renders
 * without an account (screenshots) and the editor's rules are plain Kotlin ([BotEditorDraft]).
 */
/** Bumped on every save/delete/publish so the window re-reads the store (and the `/` menus). */
object OwnedBotsUi {
    var version by mutableStateOf(0)
}

class BotsActions(
    val onSave: (OwnedBot) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    /** Register + setMyCommands, off the UI thread; the callback gets the server's answer. */
    val onPublish: (OwnedBot, (Result<OwnedBot>) -> Unit) -> Unit = { _, _ -> },
    val onCopy: (String) -> Unit = {},
    /** __BOTS_PHASE1_2026_11_24__ register if needed + setMyProfile; the callback gets the server's answer. */
    val onSaveProfile: (OwnedBot, (com.oshi.desktop.bot.BotPlatform.ProfileSave) -> Unit) -> Unit = { _, _ -> },
    /** BotFather /revoke: a new token, the old one dead. */
    val onRevoke: (OwnedBot, (Result<OwnedBot>) -> Unit) -> Unit = { _, _ -> },
)

/** One row of the command editor, as typed. */
data class CommandDraft(val name: String = "", val description: String = "")

/** The editor's state, with the rules the Save button obeys. Pure, so a test holds them. */
data class BotEditorDraft(
    val base: OwnedBot,
    val name: String = base.name,
    val username: String = base.username,
    val description: String = base.description,
    val about: String = base.about,
    val isPublic: Boolean = base.isPublic,
    val commands: List<CommandDraft> = base.commands.map { CommandDraft(it.slash, it.description) },
    val groups: Set<String> = base.assignedGroups.toSet(),
) {
    /** Per-row problem; Duplicate is reported on the later of two equal names. */
    fun rowProblems(): List<BotCommands.Problem> {
        val seen = HashSet<String>()
        return commands.map { c ->
            val p = BotCommands.problemOf(c.name, c.description)
            if (p != BotCommands.Problem.None) p
            else if (!seen.add(BotCommand.normalizeName(c.name))) BotCommands.Problem.Duplicate
            else BotCommands.Problem.None
        }
    }

    val canSave: Boolean
        get() = name.isNotBlank() && name.length <= OwnedBot.MAX_NAME && OwnedBot.isValidUsername(username.trim().removePrefix("@")) &&
            description.length <= OwnedBot.MAX_DESCRIPTION && about.length <= OwnedBot.MAX_ABOUT && commands.size <= BotCommand.MAX_COMMANDS &&
            rowProblems().all { it == BotCommands.Problem.None }

    fun build(): OwnedBot {
        val cmds = commands.mapNotNull { BotCommands.parse(it.name, it.description) }
        val changedCommands = cmds != base.commands
        return base.copy(
            name = name.trim(),
            username = username.trim().removePrefix("@"),
            description = description.trim(),
            about = about.trim(),
            isPublic = isPublic,
            profilePublished = base.profilePublished && username.trim().removePrefix("@") == base.username &&
                name.trim() == base.name && description.trim() == base.description && about.trim() == base.about && isPublic == base.isPublic,
            commands = cmds,
            assignedGroups = groups.toList().sorted(),
            commandsPublished = base.commandsPublished && !changedCommands && groups == base.assignedGroups.toSet(),
        )
    }
}

@Composable
fun BotsPage(
    bots: List<OwnedBot>,
    /** (group id, name) of the groups this account is in. */
    groups: List<Pair<String, String>>,
    actions: BotsActions,
    serverUrl: String = "https://oshi-messenger.com",
    /** Screenshots open the editor directly. */
    initiallyEditing: OwnedBot? = null,
    /** __BOTS_PHASE1_2026_11_24__ groups/channels this account administers (BotFather's last step). */
    adminGroups: List<BotGroupChoice> = emptyList(),
    fatherActions: BotFatherActions = BotFatherActions(onCopy = actions.onCopy),
    /** Screenshots open the guided flow at a given step. */
    initialWizard: com.oshi.desktop.bot.BotFather.Draft? = null,
) {
    var editing by remember { mutableStateOf<BotEditorDraft?>(initiallyEditing?.let { BotEditorDraft(it) }) }
    var wizard by remember { mutableStateOf(initialWizard) }
    val w = wizard
    if (w != null) {
        BotFatherWizard(w, adminGroups, serverUrl, fatherActions) { wizard = null; OwnedBotsUi.version++ }
        return
    }
    val e = editing
    if (e != null) {
        BotEditor(e, groups, actions, serverUrl, onChange = { editing = it }, onClose = { editing = null })
        return
    }
    // __BOTS_PHASE1_2026_11_24__ BotFather: one big friendly entry, the guided flow behind it.
    SettingsButtonRow {
        SettingsButton(SettingsIcon.BOT, t("pc.botfather.title"), primary = true) {
            wizard = com.oshi.desktop.bot.BotFather.Draft()
        }
    }
    Spacer(Modifier.height(OshiTheme.lg))
    if (bots.isEmpty()) {
        SettingsCard {
            MascotEmptyState("thinking", t("bot.empty_title"), t("bot.empty_desc"))
        }
        SettingsCard(title = t("bot.quickstart_title")) {
            SettingsCardBody {
                listOf(t("bot.quickstart_step1"), t("bot.quickstart_step2"), t("bot.quickstart_step3")).forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = OshiTheme.xs), verticalAlignment = Alignment.CenterVertically) {
                        StepNumber(i + 1)
                        Spacer(Modifier.width(OshiTheme.md))
                        Text(step, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                    }
                }
            }
        }
        return
    }
    SettingsCard(title = t("pc.bot.my_bots")) {
        bots.forEachIndexed { i, b ->
            if (i > 0) SettingsDivider()
            BotRow(b) { editing = BotEditorDraft(b) }
        }
    }
}

@Composable
private fun StepNumber(n: Int) {
    Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(OshiTheme.brandGradient), contentAlignment = Alignment.Center) {
        Text(n.toString(), color = Ink.onBrandStrong, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BotAvatar(emoji: String, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(OshiTheme.brand.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = (size.value * 0.5f).sp) }
}

@Composable
private fun BotRow(b: OwnedBot, onEdit: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered.value) OshiTheme.brand.copy(alpha = 0.05f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(source, null, onClick = onEdit)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(b.emoji)
        Spacer(Modifier.width(OshiTheme.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.name, style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                if (b.username.isNotEmpty()) {
                    Spacer(Modifier.width(OshiTheme.xs))
                    Text("@" + b.username, style = OshiTheme.typography.bodySmall, color = Ink.soft)
                }
            }
            if (b.description.isNotEmpty()) {
                Text(b.description, style = OshiTheme.typography.bodySmall, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(OshiTheme.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                b.commands.take(4).forEach { ToolPill(it.slash) }
                if (b.commands.size > 4) ToolPill("+" + (b.commands.size - 4), Ink.soft)
            }
        }
        if (b.commandsPublished) {
            GlyphIcon(Glyph.CHECK, OshiTheme.success, 16.dp)
            Spacer(Modifier.width(OshiTheme.sm))
        }
        SettingsIconView(SettingsIcon.CHEVRON, Ink.soft, 14.dp)
    }
}

@Composable
private fun BotEditor(
    d: BotEditorDraft,
    groups: List<Pair<String, String>>,
    actions: BotsActions,
    serverUrl: String,
    onChange: (BotEditorDraft) -> Unit,
    onClose: () -> Unit,
) {
    var status by remember(d.base.id) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var publishing by remember(d.base.id) { mutableStateOf(false) }
    var confirmDelete by remember(d.base.id) { mutableStateOf(false) }
    var confirmRevoke by remember(d.base.id) { mutableStateOf(false) }
    var usernameTaken by remember(d.base.id) { mutableStateOf(false) }
    val problems = d.rowProblems()

    // Identity
    SettingsCard {
        SettingsCardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(d.base.emoji, 56.dp)
                Spacer(Modifier.width(OshiTheme.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    ToolField(d.name, { onChange(d.copy(name = it.take(OwnedBot.MAX_NAME))) }, t("bot.name_placeholder"), Modifier.fillMaxWidth())
                    // __BOTS_PHASE1_2026_11_24__ contract §1, checked as typed; "taken" comes from the server (409).
                    ToolField(d.username, { onChange(d.copy(username = com.oshi.desktop.bot.BotUsername.normalize(it).take(32))); usernameTaken = false },
                        "@" + t("bot.username_placeholder"), Modifier.fillMaxWidth(), mono = true)
                }
            }
            if (!OwnedBot.isValidUsername(d.username.trim().removePrefix("@"))) {
                SettingsNotice(t("pc.bot.username_rule"), error = true)
            } else if (usernameTaken) {
                SettingsNotice(t("pc.bot.username_taken"), error = true)
                val alts = com.oshi.desktop.bot.BotUsername.suggestions(d.username)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    alts.forEach { a -> ToolPill("@$a", modifier = Modifier.clip(OshiTheme.pill).clickable { onChange(d.copy(username = a)); usernameTaken = false }) }
                }
            }
            Spacer(Modifier.height(OshiTheme.md))
            Text(t("pc.bot.about"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xs))
            ToolField(d.about, { onChange(d.copy(about = it.take(OwnedBot.MAX_ABOUT))) }, t("pc.bot.about_hint"), Modifier.fillMaxWidth())
            Spacer(Modifier.height(OshiTheme.md))
            Text(t("bot.description_label"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xs))
            ToolField(d.description, { onChange(d.copy(description = it.take(OwnedBot.MAX_DESCRIPTION))) }, t("bot.description_hint"),
                Modifier.fillMaxWidth(), singleLine = false, minHeight = 64.dp)
        }
    }

    // __BOTS_PHASE1_2026_11_24__ public toggle + share link / QR.
    SettingsCard {
        SettingsToggleRow(SettingsIcon.GLOBE, t("pc.bot.public"), t("pc.bot.public_hint"), d.isPublic) { onChange(d.copy(isPublic = it)) }
    }
    BotShareCard(d.base.link, actions.onCopy)

    // Commands
    SettingsCard(title = t("pc.bot.commands"), footer = t("bot.hint.command") + " · " + t("pc.bot.command_rule")) {
        SettingsCardBody {
            d.commands.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().padding(vertical = OshiTheme.xs), verticalAlignment = Alignment.CenterVertically) {
                    ToolField(
                        c.name,
                        { v -> onChange(d.copy(commands = d.commands.toMutableList().also { it[i] = c.copy(name = v.take(BotCommand.MAX_NAME + 1)) })) },
                        "/command",
                        Modifier.width(170.dp),
                        mono = true,
                    )
                    Spacer(Modifier.width(OshiTheme.sm))
                    ToolField(
                        c.description,
                        { v -> onChange(d.copy(commands = d.commands.toMutableList().also { it[i] = c.copy(description = v.take(BotCommand.MAX_DESCRIPTION)) })) },
                        t("bot.description_label"),
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(OshiTheme.xs))
                    ToolIconButton(ToolGlyph.TRASH, t("common.delete")) {
                        onChange(d.copy(commands = d.commands.filterIndexed { j, _ -> j != i }))
                    }
                }
                when (problems.getOrNull(i)) {
                    BotCommands.Problem.BadName -> SettingsNotice(t("pc.bot.command_rule"), error = true)
                    BotCommands.Problem.Duplicate -> SettingsNotice(t("pc.bot.duplicate"), error = true)
                    else -> Unit
                }
            }
            Spacer(Modifier.height(OshiTheme.sm))
            if (d.commands.size < BotCommand.MAX_COMMANDS) {
                SettingsButton(null, "+ " + t("pc.bot.add_command")) {
                    onChange(d.copy(commands = d.commands + CommandDraft("/", "")))
                }
            }
        }
    }

    // Groups
    SettingsCard(title = t("bot.section.groups"), footer = if (groups.isEmpty()) t("bot.no_groups_hint") else t("bot.groupid_hint")) {
        if (groups.isEmpty()) {
            SettingsCardBody { Text(t("bot.no_groups_available"), style = OshiTheme.typography.bodyMedium, color = Ink.soft) }
        }
        groups.forEach { (id, name) ->
            val on = id in d.groups
            Row(
                Modifier.fillMaxWidth().clickable { onChange(d.copy(groups = if (on) d.groups - id else d.groups + id)) }
                    .padding(horizontal = OshiTheme.md, vertical = OshiTheme.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(on, { onChange(d.copy(groups = if (on) d.groups - id else d.groups + id)) },
                    colors = CheckboxDefaults.colors(checkedColor = OshiTheme.brandFill))
                Text(name, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
            }
        }
    }

    // Token + how to connect
    SettingsCard(title = t("bot.token_label"), footer = t("bot.token_hint")) {
        SettingsCardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.base.token.take(8) + "••••••••••••" + d.base.token.takeLast(4),
                    fontFamily = FontFamily.Monospace,
                    style = OshiTheme.typography.bodyMedium,
                    color = Ink.strong,
                    modifier = Modifier.weight(1f),
                )
                SettingsIconButton(SettingsIcon.COPY, t("a11y.bot.copy_token")) { actions.onCopy(d.base.token) }
            }
            Spacer(Modifier.height(OshiTheme.xs))
            SettingsButtonRow {
                SettingsButton(SettingsIcon.KEY, t("pc.bot.revoke"), danger = true, enabled = d.base.registered) { confirmRevoke = true }
            }
            Spacer(Modifier.height(OshiTheme.md))
            Text(t("bot.api_title"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
            Text(t("bot.api_description"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(
                connectExample(serverUrl, d.groups.firstOrNull() ?: "GROUP_ID"),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Ink.strong,
                modifier = Modifier.fillMaxWidth().clip(OshiTheme.radiusSm).background(OshiTheme.surface).padding(OshiTheme.md),
            )
        }
    }

    status?.let { (msg, err) -> SettingsNotice(msg, error = err); Spacer(Modifier.height(OshiTheme.md)) }

    SettingsButtonRow {
        SettingsButton(SettingsIcon.CHECK, t("common.save"), primary = true, enabled = d.canSave) {
            val b = d.build(); actions.onSave(b); onChange(BotEditorDraft(b)); onClose()
        }
        SettingsButton(SettingsIcon.EXPORT, t("pc.bot.publish"), enabled = d.canSave && d.groups.isNotEmpty() && !publishing) {
            val b = d.build()
            actions.onSave(b)
            publishing = true
            actions.onPublish(b) { r ->
                publishing = false
                r.onSuccess { onChange(BotEditorDraft(it)); status = t("pc.bot.published") to false }
                    .onFailure { status = t("pc.bot.publish_failed", it.message ?: "") to true }
            }
        }
        SettingsButton(SettingsIcon.GLOBE, t("pc.bot.save_profile"), enabled = d.canSave && d.username.isNotBlank() && !publishing) {
            val b = d.build()
            actions.onSave(b)
            publishing = true
            actions.onSaveProfile(b) { r ->
                publishing = false
                when (r) {
                    is com.oshi.desktop.bot.BotPlatform.ProfileSave.Saved -> { onChange(BotEditorDraft(b.copy(profilePublished = true, registered = true))); status = t("pc.bot.profile_saved") to false }
                    com.oshi.desktop.bot.BotPlatform.ProfileSave.UsernameTaken -> { usernameTaken = true; status = null }
                    com.oshi.desktop.bot.BotPlatform.ProfileSave.UsernameInvalid -> status = t("pc.bot.username_rule") to true
                    is com.oshi.desktop.bot.BotPlatform.ProfileSave.Failed -> status = t("pc.botprofile.unreachable") to true
                }
            }
        }
        SettingsButton(null, t("common.cancel"), onClick = onClose)
        if (d.base.name.isNotEmpty()) {
            SettingsButton(SettingsIcon.DELETE, t("bot.delete"), danger = true) { confirmDelete = true }
        }
    }
    if (confirmRevoke) {
        OshiAlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(t("pc.bot.revoke")) },
            text = { Text(t("pc.bot.revoke_confirm")) },
            confirmButton = {
                SettingsButton(SettingsIcon.KEY, t("pc.bot.revoke"), danger = true) {
                    confirmRevoke = false
                    actions.onRevoke(d.build()) { r ->
                        r.onSuccess { onChange(BotEditorDraft(it)); status = t("pc.bot.revoked") to false }
                            .onFailure { status = t("pc.botprofile.unreachable") to true }
                    }
                }
            },
            dismissButton = { SettingsButton(null, t("common.cancel")) { confirmRevoke = false } },
        )
    }
    if (confirmDelete) {
        OshiAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("bot.delete")) },
            text = { Text(t("bot.delete_confirm")) },
            confirmButton = {
                SettingsButton(SettingsIcon.DELETE, t("common.delete"), danger = true) {
                    confirmDelete = false; actions.onDelete(d.base.id); onClose()
                }
            },
            dismissButton = { SettingsButton(null, t("common.cancel")) { confirmDelete = false } },
        )
    }
}

/** The curl a program uses to post as the bot (`POST /api/bot/send`, BotApi.send). Not prose. */
internal fun connectExample(serverUrl: String, groupId: String): String =
    "curl -X POST $serverUrl/api/bot/send \\\n" +
        "  -H 'Content-Type: application/json' \\\n" +
        "  -d '{\"token\":\"<TOKEN>\",\"groupId\":\"$groupId\",\"content\":\"Hello\"}'"
