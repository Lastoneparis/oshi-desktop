package com.oshi.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.lora.MeshCore
import com.oshi.desktop.lora.MeshCoreLink
import com.oshi.desktop.lora.MeshCoreThread
import com.oshi.desktop.ui.OshiTheme

/**
 * __MESHCORE_INTEROP_DESKTOP_2026_11_25__ What the Radio screen shows of an attached MeshCore radio, as a
 * plain snapshot (polled once a second like the rest of the screen, never read during composition).
 */
data class MeshCoreRadioView(
    val label: String,
    val status: MeshCoreLink.Status,
    val problem: MeshCoreLink.Problem?,
    val selfName: String?,
    val publicKeyHex: String?,
    val contacts: List<Contact>,
    val channels: List<Channel>,
    val bridgeSlot: Int?,
    val lastJoin: Pair<Long, Boolean>?,
    val lastAdvert: Pair<Long, Boolean>?,
) {
    data class Contact(val key: String, val name: String, val kind: Int, val heardAtMs: Long?)
    data class Channel(val key: String, val name: String, val isPublic: Boolean)

    companion object {
        fun of(link: MeshCoreLink, lastJoin: Pair<Long, Boolean>?, lastAdvert: Pair<Long, Boolean>?): MeshCoreRadioView {
            val heard = link.heardAt()
            return MeshCoreRadioView(
                label = link.label,
                status = link.status,
                problem = link.problem,
                selfName = link.selfName,
                publicKeyHex = link.selfPublicKey?.let(MeshCore::hex),
                contacts = MeshCoreThread.chatContacts(link.contacts).map {
                    Contact(MeshCoreThread.contactKey(it.publicKey), it.name.ifBlank { "MeshCore " + it.publicKeyHex.take(6) }, it.type, heard[it.publicKeyHex])
                },
                channels = link.userChannels.map {
                    Channel(MeshCoreThread.channelKey(it.name), it.name, MeshCore.isPublicChannel(it.name, it.secret))
                },
                bridgeSlot = link.bridgeSlot,
                lastJoin = lastJoin,
                lastAdvert = lastAdvert,
            )
        }
    }
}

/** The status line of a MeshCore radio, in the reader's words. */
fun meshCoreStatusText(v: MeshCoreRadioView): Pair<String, ToolTone> = when {
    v.problem == MeshCoreLink.Problem.UNPLUGGED -> t("pc.meshcore.status.unplugged") to ToolTone.ERROR
    v.problem == MeshCoreLink.Problem.OPEN_FAILED -> t("pc.meshcore.status.openFailed", v.label) to ToolTone.ERROR
    v.problem == MeshCoreLink.Problem.NO_ANSWER && v.status != MeshCoreLink.Status.READY ->
        t("pc.meshcore.status.noAnswer") to ToolTone.ERROR
    v.status == MeshCoreLink.Status.READY -> t("pc.meshcore.status.ready") to ToolTone.SUCCESS
    v.status == MeshCoreLink.Status.SYNCING -> t("pc.meshcore.status.syncing") to ToolTone.INFO
    v.status == MeshCoreLink.Status.LOST -> t("pc.meshcore.status.lost") to ToolTone.WARNING
    else -> t("pc.meshcore.status.connecting") to ToolTone.INFO
}

@Composable
internal fun MeshCoreSection(
    v: MeshCoreRadioView,
    onDetach: () -> Unit,
    onMessage: (String) -> Unit,
    onCopy: (String) -> Unit,
    onAdvert: () -> Boolean,
    onRefresh: () -> Unit,
    onJoinHashtag: (String) -> MeshCoreLink.JoinResult,
    onJoinPrivate: (String, String) -> MeshCoreLink.JoinResult,
    onNotice: (String) -> Unit,
) {
    val ready = v.status == MeshCoreLink.Status.READY
    val (statusText, tone) = meshCoreStatusText(v)
    ToolCard(title = t("meshcore.title"), icon = ToolGlyph.RADIO, trailing = {
        ToolPill(t("pc.meshcore.badge"), OshiTheme.warning)
        ToolButton(t("pc.radio.detach"), icon = ToolGlyph.CLOSE, style = ToolButtonStyle.QUIET) { onDetach() }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.lg)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(v.selfName ?: v.label, style = ToolType.title, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(v.label, style = ToolType.caption, color = Ink.soft, maxLines = 1)
            }
            if (v.publicKeyHex != null) ToolIconButton(ToolGlyph.COPY, t("pc.meshcore.copyKey")) { onCopy(v.publicKeyHex) }
        }
        ToolNotice(statusText, tone)
        if (ready && v.selfName != null) ToolHint(t("pc.meshcore.seenAs", v.selfName))
        if (v.problem == MeshCoreLink.Problem.NO_FREE_CHANNEL) ToolHint(t("meshcore.noFreeChannel"))
        ToolHint(t("pc.meshcore.notE2e"), color = OshiTheme.warning)
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(t("pc.meshcore.advert"), icon = ToolGlyph.BROADCAST, enabled = ready) {
                onNotice(if (onAdvert()) t("pc.meshcore.advertQueued") else t("pc.meshcore.notReady"))
            }
            ToolButton(t("pc.meshcore.refreshContacts"), icon = ToolGlyph.REFRESH, style = ToolButtonStyle.QUIET, enabled = ready) { onRefresh() }
        }
        ToolHint(t("pc.meshcore.advertHint"))
    }

    ToolColumns(
        first = {
            ToolCard(title = t("pc.meshcore.channelsTitle"), icon = ToolGlyph.BROADCAST, trailing = { ToolPill("${v.channels.size}") }) {
                if (v.channels.isEmpty()) ToolHint(if (ready) t("pc.meshcore.noChannels") else t("pc.meshcore.status.syncing"))
                for (c in v.channels) {
                    ToolRow(
                        c.name,
                        subtitle = if (c.isPublic) t("pc.meshcore.channelPublic") else t("pc.meshcore.channelPrivate"),
                        icon = if (c.isPublic) ToolGlyph.GLOBE else ToolGlyph.LOCK,
                        onClick = { onMessage(c.key) },
                    ) {
                        ToolIconButton(ToolGlyph.COMPOSE, t("pc.meshcore.open")) { onMessage(c.key) }
                    }
                }
                ToolHint(t("pc.meshcore.channelsFooter"))
            }
            JoinChannelCard(ready, v.lastJoin, onJoinHashtag, onJoinPrivate, onNotice)
        },
        second = {
            ToolCard(title = t("pc.meshcore.contactsTitle"), icon = ToolGlyph.PEOPLE, trailing = { ToolPill("${v.contacts.size}") }) {
                if (v.contacts.isEmpty()) ToolHint(if (ready) t("pc.meshcore.noContacts") else t("pc.meshcore.status.syncing"))
                for (c in v.contacts) {
                    ToolRow(
                        c.name,
                        subtitle = if (c.kind == MeshCore.ADV_TYPE_ROOM) t("pc.meshcore.room") else null,
                        icon = ToolGlyph.NODE,
                        onClick = { onMessage(c.key) },
                    ) {
                        ToolIconButton(ToolGlyph.COMPOSE, t("lora.node.message")) { onMessage(c.key) }
                    }
                }
                ToolHint(t("pc.meshcore.contactsFooter"))
            }
        },
    )
}

private enum class JoinKind { HASHTAG, PRIVATE }

/** "#paris" (anyone who knows the name can read it) or a private channel (name + 16-byte secret). */
@Composable
private fun JoinChannelCard(
    ready: Boolean,
    lastJoin: Pair<Long, Boolean>?,
    onJoinHashtag: (String) -> MeshCoreLink.JoinResult,
    onJoinPrivate: (String, String) -> MeshCoreLink.JoinResult,
    onNotice: (String) -> Unit,
) {
    var kind by remember { mutableStateOf(JoinKind.HASHTAG) }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var askedAt by remember { mutableStateOf(0L) }
    // The radio answers asynchronously; report its answer once, for the join this card asked for.
    androidx.compose.runtime.LaunchedEffect(lastJoin) {
        val answer = lastJoin ?: return@LaunchedEffect
        if (askedAt != 0L && answer.first >= askedAt) {
            askedAt = 0L
            onNotice(if (answer.second) t("pc.meshcore.joined") else t("pc.meshcore.joinRefused"))
        }
    }
    ToolCard(title = t("pc.meshcore.joinTitle"), icon = ToolGlyph.PLUS) {
        ToolTabs(
            JoinKind.entries.toList(), kind,
            label = { if (it == JoinKind.HASHTAG) t("pc.meshcore.joinHashtag") else t("pc.meshcore.joinPrivate") },
            icon = { if (it == JoinKind.HASHTAG) ToolGlyph.GLOBE else ToolGlyph.LOCK },
            onSelect = { kind = it },
        )
        ToolHint(if (kind == JoinKind.HASHTAG) t("pc.meshcore.hashtagHint") else t("pc.meshcore.privateHint"),
            color = if (kind == JoinKind.HASHTAG) OshiTheme.warning else Ink.soft)
        ToolField(name, { name = it }, if (kind == JoinKind.HASHTAG) "#paris" else t("pc.meshcore.channelName"), Modifier.fillMaxWidth(),
            icon = if (kind == JoinKind.HASHTAG) ToolGlyph.TEXT else ToolGlyph.LOCK)
        if (kind == JoinKind.PRIVATE) ToolField(secret, { secret = it }, t("pc.meshcore.channelSecret"), Modifier.fillMaxWidth(), icon = ToolGlyph.KEY, mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            ToolButton(t("pc.meshcore.join"), icon = ToolGlyph.PLUS, style = ToolButtonStyle.PRIMARY,
                enabled = ready && name.isNotBlank() && (kind == JoinKind.HASHTAG || secret.isNotBlank())) {
                val r = if (kind == JoinKind.HASHTAG) onJoinHashtag(name) else onJoinPrivate(name, secret)
                when (r) {
                    MeshCoreLink.JoinResult.QUEUED -> { askedAt = System.currentTimeMillis(); onNotice(t("pc.meshcore.joining")); name = ""; secret = "" }
                    MeshCoreLink.JoinResult.ALREADY_JOINED -> onNotice(t("pc.meshcore.alreadyJoined"))
                    MeshCoreLink.JoinResult.NOT_READY -> onNotice(t("pc.meshcore.notReady"))
                    MeshCoreLink.JoinResult.NO_FREE_SLOT -> onNotice(t("pc.meshcore.joinNoSlot"))
                    MeshCoreLink.JoinResult.INVALID -> onNotice(
                        if (kind == JoinKind.PRIVATE && MeshCore.parseSecret(secret) == null) t("pc.meshcore.joinBadSecret") else t("pc.meshcore.joinBadName")
                    )
                }
            }
        }
    }
}
