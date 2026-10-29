package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
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
    resulting -> "Scanning. This machine is visible on the network again."
    wanted && !meshRunning ->
        "The LAN mesh is not running in this window, so there is nothing to announce. It is a " +
            "launch flag, not a fault: start OSHI with --mesh. Discovery, this box and the peer " +
            "list all stay empty until then."
    wanted ->
        "Discovery did not start. The mesh is running, so this is the network refusing multicast " +
            "rather than a setting."
    else ->
        "Scanning off. Nothing is announced and nothing is looked for; open connections are left alone."
}

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
     * __MESH_OFF_IS_A_SENTENCE_2026_10_29__ Is the LAN mesh running in this process?
     *
     * `--ui` alone leaves it OFF. Everything this pane shows then looks like a network
     * fault: no peers, "Nobody found yet", and a scanning box that refuses to tick
     * because `onSetDiscovery` returns the RESULTING state and the mesh declines. The
     * notice even said *"Scanning off. Nothing is announced…"*, which describes a
     * successful switch-off rather than a refusal. Row 1.2-a records half an hour lost to
     * exactly that, blamed on mDNS. The window printed `mesh : off (pass --mesh to
     * enable)` on stdout the whole time, where nobody using a window reads.
     */
    meshRunning: () -> Boolean = { true },
) {
    var live by remember { mutableStateOf(peers) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            live = runCatching { refresh() }.getOrDefault(emptyList())
            scanning = runCatching { discovering() }.getOrDefault(false)
            delay(1_000)
        }
    }

    Column(
        Modifier.fillMaxSize().background(OshiTheme.surface).verticalScroll(rememberScrollState()).padding(OshiTheme.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {
            PaneHeading(
                "Nearby peers",
                "Other OSHI devices on this network, found over mDNS. This is the LAN mesh — " +
                    "it carries messages with no internet at all, and since it speaks the same " +
                    "envelope as the radio, a message from one of these arrives end-to-end " +
                    "encrypted and lands in that contact's conversation.",
            )

            notice?.let {
                Spacer(Modifier.height(OshiTheme.md))
                PaneCard { Text(it, style = OshiTheme.typography.bodySmall, color = Ink.soft) }
            }

            Spacer(Modifier.height(OshiTheme.lg))
            if (live.isEmpty() && !meshRunning()) {
                // The honest reason, where the person looking can read it.
                EmptyPaneNote(
                    "The LAN mesh is off in this window",
                    "Nothing is announced and nothing is looked for, so this list cannot fill and " +
                        "the box below will not tick. This is a launch flag rather than a fault: " +
                        "start OSHI with --mesh. Until then every symptom here looks exactly like " +
                        "blocked mDNS, which is what makes it worth saying plainly.",
                )
            } else if (live.isEmpty()) {
                EmptyPaneNote(
                    "Nobody found yet",
                    "Discovery runs continuously while OSHI is open. A peer appears here within a " +
                        "few seconds of joining the same network — if one never does, the two " +
                        "machines are probably on different subnets, or mDNS is blocked.",
                )
            } else {
                for (p in live) {
                    PaneCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.fillMaxWidth(0.72f)) {
                                Text(p.displayName, style = OshiTheme.typography.titleSmall, color = Ink.strong)
                                Spacer(Modifier.height(OshiTheme.xxs))
                                Text(
                                    "${p.platform} · ${p.address}",
                                    style = OshiTheme.typography.bodySmall,
                                    color = Ink.soft,
                                )
                            }
                            TextAction("Copy key") { onCopy(p.publicKey) }
                        }
                        Spacer(Modifier.height(OshiTheme.xxs))
                        Text(
                            // "connected" and "reachable" are different facts here. See the
                            // class doc: a peer that dialled us has no dialable port until
                            // mDNS supplies one, so it can reach us while we cannot re-open
                            // a connection to it.
                            buildList {
                                add(if (p.connected) "connected" else "known, not connected")
                                if (!p.dialable) add("no listen port yet — it dialled us, mDNS will fill it in")
                                p.hopCount?.let { add(if (it == 0) "direct" else "$it hop(s) away") }
                            }.joinToString(" · "),
                            style = OshiTheme.typography.bodySmall,
                            color = if (p.connected) Ink.strong else Ink.soft,
                        )
                        if (p.connected) {
                            Spacer(Modifier.height(OshiTheme.xxs))
                            // "Hang up", not "block". The peer stays in the directory and
                            // the next discovery tick may reconnect — which is honest
                            // rather than a defect. Blocking is a different question and
                            // lives under More, where it is also undone.
                            TextAction("Disconnect") {
                                notice = if (onDisconnect(p.publicKey))
                                    "Disconnected from ${p.displayName}. Discovery may reconnect; to stop that, block them or turn scanning off."
                                else "No live connection to close."
                            }
                        }
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                }
            }

            Spacer(Modifier.height(OshiTheme.lg))
            PaneCard {
                TextAction((if (scanning) "☑" else "☐") + "  Announce this machine and look for others") {
                    val wanted = !scanning
                    scanning = onSetDiscovery(wanted)
                    // A refusal is not an off-switch. Saying "Scanning off" to someone who
                    // just asked for ON is how this cost half an hour (row 1.2-a).
                    notice = discoveryNotice(wanted, scanning, meshRunning())
                }
                Text(
                    "mDNS is both halves at once: turning this off stops this machine ANNOUNCING " +
                        "itself as well as looking. A switch that silenced only one would leave you " +
                        "believing you were invisible while still broadcasting your name and public " +
                        "key on the network. Existing connections are left alone — this is how peers " +
                        "are FOUND, not how they are hung up on.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
            }

            Spacer(Modifier.height(OshiTheme.lg))
            PaneCard {
                Text("Connect by hand", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(
                    "When discovery cannot see a machine — a different subnet, or mDNS blocked — " +
                        "its address reaches it directly. The identity is still checked on " +
                        "connection: typing an address cannot make a stranger into a contact.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                Spacer(Modifier.height(OshiTheme.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = host,
                        onValueChange = { host = it },
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.fillMaxWidth(0.45f),
                        decorationBox = { inner ->
                            if (host.isEmpty()) Text("192.168.1.42", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                            inner()
                        },
                    )
                    BasicTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.fillMaxWidth(0.3f),
                        decorationBox = { inner ->
                            if (port.isEmpty()) Text("port", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                            inner()
                        },
                    )
                    TextAction("Connect") {
                        val p = port.toIntOrNull()
                        notice = when {
                            host.isBlank() || p == null || p !in 1..65_535 ->
                                "Give a host and a port from 1 to 65535."
                            onConnect(host.trim(), p) -> "Connecting to ${host.trim()}:$p…"
                            else -> "That address was refused."
                        }
                    }
                }
            }
            Spacer(Modifier.height(OshiTheme.xxl))
        }
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
