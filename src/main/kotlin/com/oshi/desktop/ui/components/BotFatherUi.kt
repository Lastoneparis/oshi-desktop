package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.bot.BotCommand
import com.oshi.desktop.bot.BotFather
import com.oshi.desktop.bot.BotLink
import com.oshi.desktop.bot.BotPermissions
import com.oshi.desktop.bot.BotPlatform
import com.oshi.desktop.bot.BotUsername
import com.oshi.desktop.bot.InlineKeyboard
import com.oshi.desktop.bot.OwnedBot
import com.oshi.desktop.bot.PublicBotProfile
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * __BOTS_PHASE1_2026_11_24__ The bot surfaces of phase 1 as re-scoped by the owner ("like
 * Telegram with BotFather … and for channels where there is interaction inside. Not a bot
 * chat."): the guided creation flow, the bot profile sheet a link or a search opens (with
 * "Add to group" / "Add to channel"), the permissions an admin grants, the remote-bot notice,
 * the BOT badge and the inline keyboard under a bot post.
 */

/** A group or channel this account administers, as the add-bot pickers list it. */
data class BotGroupChoice(val id: String, val name: String, val isChannel: Boolean)

/**
 * What the bot screens need from the window, blocking calls included (they run off the UI
 * thread here). A local so the screens render in tests with a fake.
 */
interface BotUiHost {
    fun lookup(username: String): BotPlatform.Lookup
    fun search(query: String): Result<List<PublicBotProfile>>
    fun adminGroups(): List<BotGroupChoice>
    fun ownBotIds(): Set<String>
    fun add(bot: PublicBotProfile, groupId: String, permissions: BotPermissions): Result<Unit>
    fun copy(text: String)

    object None : BotUiHost {
        override fun lookup(username: String): BotPlatform.Lookup = BotPlatform.Lookup.Failed("no host")
        override fun search(query: String): Result<List<PublicBotProfile>> = Result.success(emptyList())
        override fun adminGroups(): List<BotGroupChoice> = emptyList()
        override fun ownBotIds(): Set<String> = emptySet()
        override fun add(bot: PublicBotProfile, groupId: String, permissions: BotPermissions): Result<Unit> = Result.failure(IllegalStateException())
        override fun copy(text: String) {}
    }
}

val LocalBotUiHost = staticCompositionLocalOf<BotUiHost> { BotUiHost.None }

/** Open a bot link (from a click, a paste, the OS) — the profile sheet shows it. */
val LocalOpenBotLink = staticCompositionLocalOf<(BotLink) -> Unit> { {} }

/** The profile sheet's state; one at a time, window-wide. */
object BotSheet {
    sealed interface State {
        data class Loading(val username: String) : State
        data class Shown(val bot: PublicBotProfile, val message: Pair<String, Boolean>? = null) : State
        data class NotFound(val username: String) : State
        data class Unreachable(val username: String) : State
    }

    var state: State? by mutableStateOf(null)

    fun open(link: BotLink) { state = State.Loading(link.username) }
    fun show(bot: PublicBotProfile) { state = State.Shown(bot) }
    fun close() { state = null }
}

// ------------------------------------------------------------------------------ small pieces

@Composable
fun BotBadge(modifier: Modifier = Modifier) {
    Text(
        t("pc.botpost.badge"),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = Ink.onBrandStrong,
        modifier = modifier.clip(OshiTheme.radiusSm).background(OshiTheme.brand).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun BotEmojiAvatar(emoji: String, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(OshiTheme.brand.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Text(emoji.ifBlank { "🤖" }, fontSize = (size.value * 0.5f).sp) }
}

/** Telegram's inline keyboard under a bot post. [pressed] is the button waiting for the bot. */
@Composable
fun InlineKeyboardView(keyboard: InlineKeyboard, pressed: String?, onTap: (InlineKeyboard.Button) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        keyboard.rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { b ->
                    val (source, hovered) = rememberRowInteraction()
                    val isPressed = b is InlineKeyboard.Button.Callback && b.data == pressed
                    Box(
                        Modifier.weight(1f).heightIn(min = 34.dp).clip(RoundedCornerShape(10.dp))
                            .background(OshiTheme.brand.copy(alpha = if (isPressed) 0.30f else if (hovered.value) 0.20f else 0.12f))
                            .clickable(source, null, enabled = pressed == null) { onTap(b) }
                            .padding(horizontal = OshiTheme.sm, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val arrow = if (b is InlineKeyboard.Button.Callback) "" else " ↗"
                        Text(
                            (if (isPressed) "… " else "") + b.text + arrow,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OshiTheme.brand,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The remote-bot notice (re-scope, exact EN/FR texts), shown before a bot is added. */
@Composable
fun RemoteBotNotice(isChannel: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.warning.copy(alpha = 0.14f)).padding(OshiTheme.md),
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        GlyphIcon(Glyph.INFO, Ink.strong, 16.dp)
        Text(
            if (isChannel) t("pc.botprofile.remote_channel") else t("pc.botprofile.remote_group"),
            style = OshiTheme.typography.bodySmall, color = Ink.strong,
        )
    }
}

@Composable
fun BotPermissionsPicker(p: BotPermissions, onChange: (BotPermissions) -> Unit) {
    Text(t("pc.botprofile.permissions"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
    listOf(
        t("pc.botprofile.perm.post") to p.canPost,
        t("pc.botprofile.perm.edit") to p.canEdit,
        t("pc.botprofile.perm.delete") to p.canDelete,
        t("pc.botprofile.perm.pin") to p.canPin,
    ).forEachIndexed { i, (label, on) ->
        val flip = {
            onChange(when (i) { 0 -> p.copy(canPost = !on); 1 -> p.copy(canEdit = !on); 2 -> p.copy(canDelete = !on); else -> p.copy(canPin = !on) })
        }
        Row(Modifier.fillMaxWidth().clip(OshiTheme.radiusSm).clickable(onClick = flip), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, { flip() }, colors = CheckboxDefaults.colors(checkedColor = OshiTheme.brandFill))
            Text(label, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
        }
    }
}

/** Link + copy + QR — what an owner hands out (contract §2). */
@Composable
fun BotShareCard(link: BotLink?, onCopy: (String) -> Unit, qrSize: Dp = 150.dp) {
    SettingsCard(title = t("gs_share_link")) {
        SettingsCardBody {
            if (link == null) {
                Text(t("pc.bot.link_needs_username"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                return@SettingsCardBody
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.lg)) {
                QrCode(link.https, size = qrSize)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    Text("@" + link.username, style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                    Text(link.https, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink.soft)
                    SettingsButtonRow {
                        SettingsButton(SettingsIcon.COPY, t("gs_copy_link"), primary = true) { onCopy(link.https) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ profile sheet

/** The sheet a bot link or a search result opens: who the bot is, and "Add to group / channel". */
@Composable
fun BotProfileSheetHost() {
    val st = BotSheet.state ?: return
    val host = LocalBotUiHost.current
    if (st is BotSheet.State.Loading) {
        LaunchedEffect(st.username) {
            val r = withContext(Dispatchers.IO) { host.lookup(st.username) }
            if (BotSheet.state == st) BotSheet.state = when (r) {
                is BotPlatform.Lookup.Found -> BotSheet.State.Shown(r.bot)
                BotPlatform.Lookup.NotFound -> BotSheet.State.NotFound(st.username)
                is BotPlatform.Lookup.Failed -> BotSheet.State.Unreachable(st.username)
            }
        }
    }
    OshiAlertDialog(
        onDismissRequest = { BotSheet.close() },
        confirmButton = { SettingsButton(null, t("common.close")) { BotSheet.close() } },
        text = { Column(Modifier.widthIn(max = 460.dp)) { BotProfileContent(st, host) } },
    )
}

@Composable
fun BotProfileContent(st: BotSheet.State, host: BotUiHost, initialTarget: Boolean? = null, initialGroup: String? = null) {
    when (st) {
        is BotSheet.State.Loading -> Text(t("pc.botprofile.loading"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
        is BotSheet.State.NotFound -> SheetProblem("@" + st.username, t("pc.botprofile.not_found"))
        is BotSheet.State.Unreachable -> SheetProblem("@" + st.username, t("pc.botprofile.unreachable"))
        is BotSheet.State.Shown -> BotProfileShown(st, host, initialTarget, initialGroup)
    }
}

@Composable
private fun SheetProblem(title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        BotEmojiAvatar("🤖", 64.dp)
        Spacer(Modifier.height(OshiTheme.md))
        Text(title, style = OshiTheme.typography.titleMedium, color = Ink.strong)
        Spacer(Modifier.height(OshiTheme.xs))
        Text(body, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BotProfileShown(st: BotSheet.State.Shown, host: BotUiHost, initialTarget: Boolean?, initialGroup: String?) {
    val bot = st.bot
    val groups = remember(bot.botId) { host.adminGroups() }
    // null = not adding yet; false = adding to a group; true = adding to a channel.
    var target by remember(bot.botId) { mutableStateOf(initialTarget) }
    var chosen by remember(bot.botId) { mutableStateOf(initialGroup) }
    var perms by remember(bot.botId) { mutableStateOf(BotPermissions()) }
    var busy by remember(bot.botId) { mutableStateOf(false) }
    var message by remember(bot.botId) { mutableStateOf(st.message) }
    var adding by remember(bot.botId) { mutableStateOf<Pair<String, BotPermissions>?>(null) }
    val own = bot.botId in host.ownBotIds()

    adding?.let { (gid, p) ->
        LaunchedEffect(gid, p) {
            val r = withContext(Dispatchers.IO) { host.add(bot, gid, p) }
            busy = false
            adding = null
            val name = groups.firstOrNull { it.id == gid }?.name.orEmpty()
            message = r.fold({ t("pc.botprofile.added", name) to false }, { t("pc.botprofile.add_failed", it.message ?: "") to true })
            if (r.isSuccess) { target = null; chosen = null }
        }
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotEmojiAvatar(bot.avatarEmoji, 64.dp)
            Spacer(Modifier.width(OshiTheme.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                    Text(bot.name, style = OshiTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink.strong,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    BotBadge()
                }
                Text("@" + bot.username, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            }
            SettingsIconButton(SettingsIcon.COPY, t("gs_copy_link")) { host.copy(bot.link.https) }
        }
        if (bot.about.isNotBlank()) {
            Spacer(Modifier.height(OshiTheme.md))
            Text(bot.about, style = OshiTheme.typography.bodyLarge, color = Ink.strong)
        }
        if (bot.description.isNotBlank()) {
            Spacer(Modifier.height(OshiTheme.sm))
            Text(bot.description, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
        }
        if (bot.commands.isNotEmpty()) {
            Spacer(Modifier.height(OshiTheme.md))
            Text(t("pc.bot.commands"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xs))
            bot.commands.take(6).forEach { c ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(c.slash, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = OshiTheme.brand, modifier = Modifier.width(120.dp))
                    Text(c.description, style = OshiTheme.typography.bodySmall, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(OshiTheme.lg))
        message?.let { (m, err) -> SettingsNotice(m, error = err); Spacer(Modifier.height(OshiTheme.sm)) }

        val t0 = target
        if (t0 == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                PrimaryButton(t("pc.botprofile.add_group")) { target = false; message = null }
                SettingsButton(null, t("pc.botprofile.add_channel")) { target = true; message = null }
            }
            return@Column
        }
        val list = groups.filter { it.isChannel == t0 }
        Text(if (t0) t("pc.botprofile.choose_channel") else t("pc.botprofile.choose_group"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
        Spacer(Modifier.height(OshiTheme.xs))
        if (list.isEmpty()) {
            Text(t("pc.botprofile.no_admin"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs), verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            list.forEach { g ->
                val on = g.id == chosen
                Text(
                    g.name,
                    style = OshiTheme.typography.bodyMedium,
                    color = if (on) Ink.onBrandStrong else Ink.strong,
                    modifier = Modifier.clip(OshiTheme.pill)
                        .background(if (on) OshiTheme.brand else OshiTheme.surface)
                        .clickable { chosen = g.id }
                        .padding(horizontal = OshiTheme.md, vertical = OshiTheme.xs),
                )
            }
        }
        if (chosen != null) {
            Spacer(Modifier.height(OshiTheme.md))
            BotPermissionsPicker(perms) { perms = it }
            if (!own && bot.remote) {
                Spacer(Modifier.height(OshiTheme.sm))
                RemoteBotNotice(t0)
            }
        }
        Spacer(Modifier.height(OshiTheme.md))
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            PrimaryButton(t("pc.botprofile.add"), enabled = chosen != null && !busy) {
                busy = true; adding = chosen!! to perms
            }
            SettingsButton(null, t("common.back")) { target = null; chosen = null }
        }
    }
}

// ------------------------------------------------------------------------------ @ search

/** "New chat": `@` + text lists public bots (contract §4.1, re-scoped: to ADD one). */
@Composable
fun BotSearchResults(query: String, onOpen: (PublicBotProfile) -> Unit, preset: Result<List<PublicBotProfile>>? = null) {
    val host = LocalBotUiHost.current
    val q = BotPlatform.searchQuery(query) ?: return
    var result by remember(q) { mutableStateOf(preset) }
    if (preset == null) {
        LaunchedEffect(q) {
            delay(250)
            result = withContext(Dispatchers.IO) { host.search(q) }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(t("pc.botprofile.search_title"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
        Spacer(Modifier.height(OshiTheme.xs))
        val r = result
        when {
            r == null -> Text(t("pc.botprofile.searching"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            r.isFailure -> Text(t("pc.botprofile.unreachable"), style = OshiTheme.typography.bodySmall, color = OshiTheme.danger)
            r.getOrThrow().isEmpty() -> Text(t("pc.botprofile.no_results", q), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            else -> r.getOrThrow().forEach { b -> BotResultRow(b) { onOpen(b) } }
        }
    }
}

@Composable
private fun BotResultRow(b: PublicBotProfile, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier.fillMaxWidth().clip(OshiTheme.radiusMd)
            .background(if (hovered.value) OshiTheme.brand.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(source, null, onClick = onClick)
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotEmojiAvatar(b.avatarEmoji, 36.dp)
        Spacer(Modifier.width(OshiTheme.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                Text(b.name, style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong, maxLines = 1)
                BotBadge()
            }
            Text(("@" + b.username) + (if (b.about.isNotBlank()) " · " + b.about else ""),
                style = OshiTheme.typography.bodySmall, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SettingsIconView(SettingsIcon.CHEVRON, Ink.soft, 14.dp)
    }
}

// ------------------------------------------------------------------------------ BotFather

/** What the wizard asks the window to do; each callback answers on the UI thread. */
class BotFatherActions(
    val checkUsername: suspend (String) -> BotFather.Availability = { BotFather.Availability.Unknown },
    /** Save locally + register + setMyProfile. */
    val save: suspend (OwnedBot) -> Pair<OwnedBot, BotPlatform.ProfileSave> = { it to BotPlatform.ProfileSave.Failed("") },
    val addToGroup: suspend (OwnedBot, String, BotPermissions) -> Result<Unit> = { _, _, _ -> Result.failure(IllegalStateException()) },
    val onCopy: (String) -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotFatherWizard(
    initial: BotFather.Draft = BotFather.Draft(),
    adminGroups: List<BotGroupChoice>,
    serverUrl: String,
    actions: BotFatherActions,
    onClose: (OwnedBot?) -> Unit,
) {
    var d by remember { mutableStateOf(initial) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var revealSnippets by remember { mutableStateOf(initial.step == BotFather.Step.TOKEN) }
    var serverTaken by remember { mutableStateOf<String?>(null) }

    // Live availability: debounced public lookup while the username step is open.
    if (d.step == BotFather.Step.USERNAME) {
        val u = d.shownUsername
        LaunchedEffect(u) {
            if (BotUsername.problemOf(u) != BotUsername.Problem.None) {
                d = d.copy(availability = BotFather.Availability.Invalid(BotUsername.problemOf(u))); return@LaunchedEffect
            }
            // The server's own 409 beats a lookup (a private bot looks free to everyone else).
            if (u == serverTaken) { d = d.copy(availability = BotFather.Availability.Taken(BotUsername.suggestions(u))); return@LaunchedEffect }
            d = d.copy(availability = BotFather.Availability.Checking)
            delay(400)
            val a = actions.checkUsername(u)
            if (d.shownUsername == u) d = d.copy(availability = a)
        }
    }

    val total = BotFather.Step.entries.size
    SettingsCard {
        SettingsCardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("pc.botfather.title"), style = OshiTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink.strong, modifier = Modifier.weight(1f))
                Text("${d.step.ordinal + 1} / $total", style = OshiTheme.typography.labelLarge, color = Ink.soft)
            }
            Spacer(Modifier.height(OshiTheme.sm))
            // Progress: one segment per step.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(total) { i ->
                    Box(Modifier.weight(1f).height(4.dp).clip(OshiTheme.pill)
                        .background(if (i <= d.step.ordinal) OshiTheme.brand else OshiTheme.separator))
                }
            }
            if (d.step == BotFather.Step.NAME) {
                Spacer(Modifier.height(OshiTheme.md))
                Text(t("pc.botfather.intro"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            }
        }
    }

    when (d.step) {
        BotFather.Step.NAME -> StepCard(t("pc.botfather.name_title"), t("pc.botfather.name_help")) {
            ToolField(d.name, { d = d.copy(name = it.take(OwnedBot.MAX_NAME)) }, t("bot.name_placeholder"), Modifier.fillMaxWidth())
        }
        BotFather.Step.USERNAME -> StepCard(t("pc.botfather.username_title"), t("pc.botfather.username_help")) {
            ToolField(
                d.shownUsername,
                { d = d.copy(username = BotUsername.normalize(it).take(BotUsername.MAX), usernameEdited = true) },
                "@" + t("bot.username_placeholder"), Modifier.fillMaxWidth(), mono = true,
            )
            Spacer(Modifier.height(OshiTheme.sm))
            UsernameVerdict(d.availability) { s -> d = d.copy(username = s, usernameEdited = true) }
        }
        BotFather.Step.PROFILE -> StepCard(t("pc.botfather.profile_title"), t("pc.botfather.profile_help")) {
            Text(t("pc.botfather.emoji"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EMOJIS.forEach { e ->
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (e == d.emoji) OshiTheme.brand.copy(alpha = 0.22f) else OshiTheme.surface)
                            .then(if (e == d.emoji) Modifier.border(2.dp, OshiTheme.brand, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { d = d.copy(emoji = e) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 20.sp) }
                }
            }
            Spacer(Modifier.height(OshiTheme.md))
            Text(t("pc.bot.about"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
            ToolField(d.about, { d = d.copy(about = it.take(OwnedBot.MAX_ABOUT)) }, t("pc.bot.about_hint"), Modifier.fillMaxWidth())
            Spacer(Modifier.height(OshiTheme.sm))
            Text(t("bot.description_label"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
            ToolField(d.description, { d = d.copy(description = it.take(OwnedBot.MAX_DESCRIPTION)) }, t("bot.description_hint"),
                Modifier.fillMaxWidth(), singleLine = false, minHeight = 64.dp)
        }
        BotFather.Step.COMMANDS -> StepCard(t("pc.botfather.commands_title"), t("pc.botfather.commands_help")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BotFather.SUGGESTED.forEach { c -> CommandChip(c, d.commands.any { it.name == c.name }) { d = d.toggle(c) } }
            }
            Spacer(Modifier.height(OshiTheme.md))
            Text(t("pc.botfather.templates"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BotFather.Template.entries.forEach { tpl ->
                    val on = d.template == tpl
                    Text(
                        tpl.emoji + "  " + templateLabel(tpl),
                        style = OshiTheme.typography.bodyMedium, color = if (on) Ink.onBrandStrong else Ink.strong,
                        modifier = Modifier.clip(OshiTheme.pill).background(if (on) OshiTheme.brand else OshiTheme.surface)
                            .clickable { d = d.withTemplate(tpl) }.padding(horizontal = OshiTheme.md, vertical = OshiTheme.xs),
                    )
                }
            }
            if (d.commands.isNotEmpty()) {
                Spacer(Modifier.height(OshiTheme.md))
                d.commands.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.slash, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = OshiTheme.brand, modifier = Modifier.width(130.dp))
                        Text(c.description, style = OshiTheme.typography.bodySmall, color = Ink.soft, modifier = Modifier.weight(1f))
                        ToolIconButton(ToolGlyph.TRASH, t("common.delete")) { d = d.copy(commands = d.commands - c) }
                    }
                }
            }
        }
        BotFather.Step.TOKEN -> {
            val bot = d.bot
            StepCard(t("pc.botfather.token_title"), null) {
                if (bot != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotEmojiAvatar(bot.emoji, 48.dp)
                        Spacer(Modifier.width(OshiTheme.md))
                        Column(Modifier.weight(1f)) {
                            Text(bot.name, style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                            if (bot.username.isNotEmpty()) Text("@" + bot.username, style = OshiTheme.typography.bodySmall, color = Ink.soft)
                        }
                    }
                    Spacer(Modifier.height(OshiTheme.md))
                    Text(t("bot.token_label"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(bot.token, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink.strong, modifier = Modifier.weight(1f))
                        SettingsIconButton(SettingsIcon.COPY, t("a11y.bot.copy_token")) { actions.onCopy(bot.token) }
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                    Row(
                        Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.danger.copy(alpha = 0.12f)).padding(OshiTheme.md),
                        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
                    ) {
                        GlyphIcon(Glyph.LOCK, Ink.strong, 16.dp)
                        Text(t("pc.botfather.token_secret"), style = OshiTheme.typography.bodySmall, color = Ink.strong)
                    }
                    Spacer(Modifier.height(OshiTheme.md))
                    Text(
                        (if (revealSnippets) "▾ " else "▸ ") + t("pc.botfather.connect"),
                        style = OshiTheme.typography.labelLarge, color = OshiTheme.brand,
                        modifier = Modifier.clip(OshiTheme.radiusSm).clickable { revealSnippets = !revealSnippets }.padding(vertical = OshiTheme.xs),
                    )
                    if (revealSnippets) {
                        Snippet("curl", BotFather.curlSnippet(serverUrl, bot.token), actions.onCopy)
                        Spacer(Modifier.height(OshiTheme.sm))
                        Snippet("Python", BotFather.pythonSnippet(serverUrl, bot.token), actions.onCopy)
                    }
                }
            }
        }
        BotFather.Step.ADD -> {
            var chosen by remember { mutableStateOf<String?>(null) }
            var perms by remember { mutableStateOf(BotPermissions()) }
            StepCard(t("pc.botfather.add_title"), t("pc.botfather.add_help")) {
                if (adminGroups.isEmpty()) Text(t("pc.botfather.add_none"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                adminGroups.forEach { g ->
                    val on = g.id == chosen
                    Row(
                        Modifier.fillMaxWidth().clip(OshiTheme.radiusSm).clickable { chosen = if (on) null else g.id }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, { chosen = if (on) null else g.id }, colors = CheckboxDefaults.colors(checkedColor = OshiTheme.brandFill))
                        Text(g.name, style = OshiTheme.typography.bodyMedium, color = Ink.strong, modifier = Modifier.weight(1f))
                        Text(if (g.isChannel) "📣" else "👥", fontSize = 14.sp)
                    }
                }
                if (chosen != null) {
                    Spacer(Modifier.height(OshiTheme.sm))
                    BotPermissionsPicker(perms) { perms = it }
                    Spacer(Modifier.height(OshiTheme.sm))
                    PrimaryButton(t("pc.botprofile.add"), enabled = !working) {
                        val bot = d.bot ?: return@PrimaryButton
                        working = true
                        pendingAdd = Triple(bot, chosen!!, perms)
                    }
                }
            }
            pendingAdd?.let { (bot, gid, p) ->
                LaunchedEffect(gid) {
                    val r = actions.addToGroup(bot, gid, p)
                    working = false
                    pendingAdd = null
                    status = r.fold({ t("pc.botprofile.added", adminGroups.firstOrNull { it.id == gid }?.name.orEmpty()) to false },
                        { t("pc.botprofile.add_failed", it.message ?: "") to true })
                }
            }
        }
    }

    status?.let { (m, err) -> SettingsNotice(m, error = err); Spacer(Modifier.height(OshiTheme.sm)) }

    // Saving happens when leaving COMMANDS: the token is minted, the bot stored, its profile sent.
    if (working && d.step == BotFather.Step.COMMANDS) {
        LaunchedEffect(Unit) {
            val (saved, outcome) = actions.save(d.toBot())
            working = false
            when (outcome) {
                BotPlatform.ProfileSave.UsernameTaken -> {
                    serverTaken = saved.username
                    d = d.copy(bot = saved, step = BotFather.Step.USERNAME,
                        availability = BotFather.Availability.Taken(BotUsername.suggestions(saved.username)))
                    status = null
                }
                BotPlatform.ProfileSave.UsernameInvalid -> {
                    d = d.copy(bot = saved, step = BotFather.Step.USERNAME, availability = BotFather.Availability.Invalid(BotUsername.Problem.BadCharacters))
                }
                is BotPlatform.ProfileSave.Saved -> { d = d.copy(bot = saved, step = BotFather.Step.TOKEN); status = null; revealSnippets = true }
                // Saved here, not on the server: the token is real and shown; the editor can retry the profile.
                is BotPlatform.ProfileSave.Failed -> {
                    d = d.copy(bot = saved, step = BotFather.Step.TOKEN); revealSnippets = true
                    status = t("pc.botprofile.unreachable") to true
                }
            }
        }
    }

    SettingsButtonRow {
        val last = d.step == BotFather.Step.ADD
        if (last) {
            SettingsButton(SettingsIcon.CHECK, t("common.done"), primary = true) { onClose(d.bot) }
        } else if (d.step == BotFather.Step.COMMANDS) {
            SettingsButton(SettingsIcon.CHECK, if (working) t("pc.botfather.saving") else t("common.next"), primary = true,
                enabled = d.canLeave() && !working) { status = null; working = true }
        } else {
            SettingsButton(null, t("common.next"), primary = true, enabled = d.canLeave()) { status = null; d = d.next() }
        }
        if (d.step != BotFather.Step.NAME && d.step != BotFather.Step.TOKEN && d.step != BotFather.Step.ADD) {
            SettingsButton(null, t("common.back"), enabled = !working) { d = d.back() }
        }
        if (d.bot == null) SettingsButton(null, t("common.cancel"), enabled = !working) { onClose(null) }
    }
}

/** The (bot, group, permissions) the wizard's last step is adding; module-level so the effect survives recomposition. */
private var pendingAdd: Triple<OwnedBot, String, BotPermissions>? by mutableStateOf(null)

@Composable
private fun StepCard(title: String, help: String?, content: @Composable () -> Unit) {
    SettingsCard {
        SettingsCardBody {
            Text(title, style = OshiTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
            if (help != null) {
                Spacer(Modifier.height(OshiTheme.xs))
                Text(help, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            }
            Spacer(Modifier.height(OshiTheme.md))
            content()
        }
    }
}

@Composable
internal fun UsernameVerdict(a: BotFather.Availability, onSuggestion: (String) -> Unit) {
    when (a) {
        BotFather.Availability.Unchecked -> Unit
        BotFather.Availability.Checking -> Text(t("pc.botfather.checking"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        BotFather.Availability.LooksFree -> Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.CHECK, OshiTheme.success, 14.dp); Spacer(Modifier.width(4.dp))
            Text(t("pc.botfather.looks_free"), style = OshiTheme.typography.bodySmall, color = Ink.strong)
        }
        BotFather.Availability.Mine -> Text(t("pc.botfather.mine"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        BotFather.Availability.Unknown -> Text(t("pc.botfather.unknown"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        is BotFather.Availability.Invalid -> SettingsNotice(t("pc.bot.username_rule"), error = a.problem != BotUsername.Problem.Empty)
        is BotFather.Availability.Taken -> Column {
            if (a.suggestions.isEmpty()) SettingsNotice(t("pc.bot.username_taken"), error = true)
            else {
                SettingsNotice(t("pc.botfather.taken_try"), error = true)
                Spacer(Modifier.height(OshiTheme.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.suggestions.forEach { s -> ToolPill("@$s", modifier = Modifier.clip(OshiTheme.pill).clickable { onSuggestion(s) }) }
                }
            }
        }
    }
}

@Composable
private fun CommandChip(c: BotCommand, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(OshiTheme.pill).background(if (on) OshiTheme.brand else OshiTheme.surface).clickable(onClick = onClick)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text((if (on) "✓ " else "+ ") + c.slash, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            color = if (on) Ink.onBrandStrong else OshiTheme.brand, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Snippet(label: String, code: String, onCopy: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = OshiTheme.typography.labelLarge, color = Ink.soft, modifier = Modifier.weight(1f))
        SettingsIconButton(SettingsIcon.COPY, t("common.copy") + " " + label) { onCopy(code) }
    }
    Text(
        code, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Ink.strong,
        modifier = Modifier.fillMaxWidth().clip(OshiTheme.radiusSm).background(OshiTheme.surface).padding(OshiTheme.md),
    )
}

@Composable
private fun templateLabel(tpl: BotFather.Template): String = when (tpl) {
    BotFather.Template.POLL -> t("pc.botfather.tpl.poll")
    BotFather.Template.WELCOME -> t("pc.botfather.tpl.welcome")
    BotFather.Template.REMINDERS -> t("pc.botfather.tpl.reminders")
    BotFather.Template.FEED -> t("pc.botfather.tpl.feed")
    BotFather.Template.WEBHOOK -> t("pc.botfather.tpl.webhook")
}

private val EMOJIS = listOf("🤖", "🗳️", "👋", "⏰", "📰", "🔗", "🌦️", "🎮", "📊", "🛎️", "🎉", "🧠")

// ------------------------------------------------------------------------------ bot posts

/** The sender + keyboard of a bot post by message id (null for every other message). */
val LocalBotPostOf = staticCompositionLocalOf<(String) -> com.oshi.desktop.bot.BotPostExtra?> { { null } }

/**
 * A tap on a post's button. Callback buttons go to `/api/bot/dispatch`; [done] gets null on
 * success or the sentence to show. URL buttons are opened by the window.
 */
val LocalBotButtonTap = staticCompositionLocalOf<(com.oshi.desktop.bot.BotPostExtra, InlineKeyboard.Button, (String?) -> Unit) -> Unit> {
    { _, _, done -> done(null) }
}

/** The bot's answerCallback text for a tap made here (re-scope: "small toast"). */
object BotToast {
    var text: String? by mutableStateOf(null)
        private set
    private var serial = 0
    fun show(s: String) { text = s; serial++ }
    fun clear() { text = null }

    @Composable
    fun Host(modifier: Modifier = Modifier) {
        val s = text ?: return
        LaunchedEffect(s, serial) { delay(3_500); if (text == s) clear() }
        Box(modifier.fillMaxWidth().padding(OshiTheme.xl), contentAlignment = Alignment.BottomCenter) {
            Text(
                s, style = OshiTheme.typography.bodyMedium, color = Ink.onBrandStrong,
                modifier = Modifier.widthIn(max = 420.dp).clip(OshiTheme.pill).background(Color(0xE6202024))
                    .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
            )
        }
    }
}

/** Under a bot post: its BOT badge and name, and the inline keyboard. */
@Composable
fun BotPostChrome(post: com.oshi.desktop.bot.BotPostExtra) {
    val tap = LocalBotButtonTap.current
    var pressed by remember(post.messageId) { mutableStateOf<String?>(null) }
    var problem by remember(post.messageId) { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 4.dp).widthIn(max = 420.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            BotBadge()
            if (post.botName.isNotBlank()) Text(post.botName, fontSize = 11.sp, color = Ink.soft, maxLines = 1)
            if (post.pinned) GlyphIcon(Glyph.PIN, Ink.soft, 12.dp)
        }
        post.keyboard?.let { kb ->
            Spacer(Modifier.height(4.dp))
            InlineKeyboardView(kb, pressed, onTap = { b ->
                problem = null
                if (b is InlineKeyboard.Button.Callback) pressed = b.data
                tap(post, b) { err -> pressed = null; problem = err }
            })
        }
        problem?.let { Text(it, fontSize = 11.sp, color = OshiTheme.danger, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** Headless renders: the New pane's field pre-filled, and canned search results. */
val LocalNewPanePreset = staticCompositionLocalOf { "" }
val LocalBotSearchPreset = staticCompositionLocalOf<Result<List<PublicBotProfile>>?> { null }
