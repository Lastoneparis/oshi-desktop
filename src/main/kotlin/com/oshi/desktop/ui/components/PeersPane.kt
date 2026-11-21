package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.delay

/**
 * Who is on the LAN right now — PARITY.md row 1.3's named gap.
 *
 * __PEERS_ARE_A_SCREEN_2026_10_28__ That row read: *"NOT here: no peers/mesh SCREEN — the
 * reach badge now lists who is reachable, but it is a read-only list, not the phones' Peers
 * view: nothing here connects, disconnects or reconnects a peer by hand, there is no scan
 * toggle and no per-peer info sheet."*
 *
 * The badge answers "is anyone there". This answers "who, where, and can I reach them" —
 * which is the question you have when the answer to the first one is wrong.
 *
 * ============================================================ WHY THE PORT MATTERS ENOUGH
 * TO SHOW
 *
 * A peer that DIALLED US has no dialable port: the socket's remote port is its ephemeral
 * source port, not the port it listens on, and `MeshNode` records [PORT_UNKNOWN] rather
 * than guessing. mDNS fills it in later. Until it does, that peer can send to us and we
 * cannot re-open a connection to it — so "connected" and "reachable" are genuinely
 * different states, and a screen that showed only the first would mislead at the one moment
 * someone consults it.
 *
 * ============================================================ WHAT IS DELIBERATELY ABSENT
 *
 * No disconnect button. `MeshNode` exposes `connectToPeer` and no counterpart, and dropping
 * a socket the other side still believes in is not a user action with a defined meaning
 * here — it would reconnect on the next discovery tick and read as a button that does
 * nothing. Row 1.3's "disconnects or reconnects by hand" stays open on purpose, and says so.
 */
/**
 * __MESH_OFF_IS_A_SENTENCE_2026_10_29__ What to tell someone after they clicked the
 * scanning box. Pure, so the four cases are tested without a window.
 *
 * The case that mattered is [wanted] true with [resulting] false: a REFUSAL. The old text
 * said "Scanning off. Nothing is announced…", which describes a successful switch-off, so
 * the one person who most needed an explanation — someone who just asked for ON and did
 * not get it — was told they had turned it off on purpose.
 */
internal fun discoveryNotice(wanted: Boolean, resulting: Boolean, meshRunning: Boolean): String = when {
    resulting -> t("pc.peers.notice.on")
    wanted && !meshRunning -> t("pc.peers.notice.meshOff")
    wanted -> t("pc.peers.notice.refused")
    else -> t("pc.peers.notice.off")
}

/**
 * __DESKTOP_TOOLS_2027__ Nearby peers, redrawn with the tool kit: one card per concern
 * (who is here, discovery, connect by hand), icon actions, and the mascot when nobody is.
 */
@Composable
fun PeersPane(
    peers: List<PeerRow>,
    onConnect: (host: String, port: Int) -> Boolean,
    onCopy: (String) -> Unit = {},
    refresh: () -> List<PeerRow> = { peers },
    /** __PEERS_ARE_A_SCREEN_2026_10_28__ Hang up on one peer. Not a block. */
    onDisconnect: (publicKey: String) -> Boolean = { false },
    /** Whether this machine is announcing itself and looking. */
    discovering: () -> Boolean = { true },
    /** Turn that on or off; returns the state after the call. */
    onSetDiscovery: (Boolean) -> Boolean = { it },
    /**
     * __MESH_OFF_IS_A_SENTENCE_2026_10_29__ Is the LAN mesh running in this process? With
     * `--ui` alone it is OFF, and every symptom here would otherwise look like blocked mDNS.
     */
    meshRunning: () -> Boolean = { true },
) {
    var live by remember { mutableStateOf(peers) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(runCatching { discovering() }.getOrDefault(true)) }

    LaunchedEffect(Unit) {
        while (true) {
            live = runCatching { refresh() }.getOrDefault(emptyList())
            scanning = runCatching { discovering() }.getOrDefault(false)
            delay(1_000)
        }
    }

    ToolScaffold(t("pc.peers.title"), subtitle = t("pc.peers.subtitle"), icon = ToolGlyph.WIFI) {
        notice?.let { ToolNotice(it) { ToolIconButton(ToolGlyph.CLOSE, t("common.dismiss"), size = 28.dp) { notice = null } } }
        if (live.isEmpty() && !meshRunning()) {
            ToolEmptyState(t("pc.peers.meshOff.title"), t("pc.peers.meshOff.body"), mascot = "sleep")
        } else if (live.isEmpty()) {
            ToolEmptyState(t("pc.peers.empty.title"), t("pc.peers.empty.body"), mascot = "peek")
        } else ToolCard(title = t("pc.peers.title"), icon = ToolGlyph.PEOPLE, trailing = { ToolPill("${live.size}") }) {
            for (p in live) {
                ToolRow(
                    p.displayName,
                    // "connected" and "reachable" are different facts: a peer that dialled
                    // us has no dialable port until mDNS supplies one.
                    subtitle = buildList {
                        add(p.platform); add(p.address)
                        add(if (p.connected) t("pc.peers.connected") else t("pc.peers.known"))
                        if (!p.dialable) add(t("pc.peers.noPort"))
                        p.hopCount?.let { add(if (it == 0) t("lora.nodes.direct") else t("lora.nodes.hops", it)) }
                    }.bidiJoin(),
                    leading = {
                        Box {
                            Monogram(p.displayName, p.publicKey, 36.dp)
                            if (p.connected) Box(Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(OshiTheme.success))
                        }
                    },
                ) {
                    ToolIconButton(ToolGlyph.COPY, t("settings.backup.copyKey")) { onCopy(p.publicKey) }
                    // "Hang up", not "block": the next discovery tick may reconnect.
                    if (p.connected) ToolIconButton(ToolGlyph.CLOSE, t("peers.peer.disconnect")) {
                        notice = if (onDisconnect(p.publicKey)) t("pc.peers.disconnected", p.displayName) else t("pc.peers.noConnection")
                    }
                }
            }
        }
        ToolColumns(
            first = {
                ToolCard(title = t("pc.peers.discovery"), icon = ToolGlyph.BROADCAST) {
                    ToolSwitchRow(t("pc.peers.announce"), t("pc.peers.announce.hint"), scanning) { wanted ->
                        scanning = onSetDiscovery(wanted)
                        // A refusal is not an off-switch (row 1.2-a).
                        notice = discoveryNotice(wanted, scanning, meshRunning())
                    }
                }
            },
            second = {
                ToolCard(title = t("pc.peers.byHand"), icon = ToolGlyph.LINK) {
                    ToolHint(t("pc.peers.byHand.hint"))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                        ToolField(host, { host = it }, "192.168.1.42", Modifier.weight(2f), mono = true)
                        ToolField(port, { port = it.filter(Char::isDigit).take(5) }, t("pc.peers.port"), Modifier.weight(1f), mono = true)
                        ToolButton(t("peers.peer.connect"), style = ToolButtonStyle.PRIMARY, enabled = host.isNotBlank() && port.isNotBlank()) {
                            val p = port.toIntOrNull()
                            notice = when {
                                host.isBlank() || p == null || p !in 1..65_535 -> t("pc.peers.badAddress")
                                onConnect(host.trim(), p) -> t("pc.radio.attaching", "${host.trim()}:$p")
                                else -> t("pc.peers.refused")
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * One peer, flattened for the screen.
 *
 * A data class rather than `MeshNode.Peer` directly, for the reason [LoRaPaneHost] exists:
 * the pane must render without a running mesh, so a screenshot and a test can see it.
 */
data class PeerRow(
    val publicKey: String,
    val displayName: String,
    val platform: String,
    /** `host:port`, or `host` alone when no listen port is known yet. */
    val address: String,
    val connected: Boolean,
    /** False when this peer dialled us and mDNS has not yet supplied its listen port. */
    val dialable: Boolean,
    /** Hops from here, or null when no route is known. */
    val hopCount: Int? = null,
)
