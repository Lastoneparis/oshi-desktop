package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.i18n.t
import com.oshi.desktop.lora.LoRaNodeBindings
import com.oshi.desktop.lora.LoRaProto
import com.oshi.desktop.lora.LoRaSerial
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ContactRow
import kotlinx.coroutines.delay

/**
 * The radio, with a mouse — the desktop counterpart of iOS's `MeshtasticSettingsView`.
 *
 * Until this existed every LoRa action on this client was a slash command, which is fine for
 * a developer and not an answer to "my Mac made a second conversation". The three things a
 * person actually does are here: attach the radio that is plugged in, say something every
 * Meshtastic client in range can read, and tell OSHI that a radio IS someone.
 *
 * ============================================================ WHAT THIS SCREEN MUST NOT HIDE
 *
 * Two facts are load-bearing and are printed next to the controls that depend on them, not
 * in a help page:
 *
 *  - **Sending here is PLAINTEXT.** Interop text rides portnum 1 on a channel whose key is
 *    public. That is the whole point — a stock Meshtastic user with no OSHI can read it —
 *    and it is the opposite of what the word "message" means everywhere else in this app.
 *  - **Linking is a person vouching, not proof.** A raw text packet's `from` is an unsigned
 *    integer, so linking means text claiming that node number appears in a contact's
 *    conversation without the ratchet having authenticated it. See [LoRaNodeBindings.link].
 *
 * __DESKTOP_LORA_OSHI_SEND_2026_10_28__ The heading used to carry a third fact — that an
 * OSHI↔OSHI envelope was reported and never opened, so a radio did not buy OSHI messaging.
 * That was true and is not any more (PARITY.md rows 0.16 / 0.27 are both closed), so the
 * heading no longer says it. A screen that kept disclaiming a gap it had closed would be
 * wrong in the other direction, and just as misleading.
 */
/**
 * What this pane needs from the radio, and nothing else.
 *
 * Deliberately not `OshiClient`. A pane that takes the whole client cannot be rendered or
 * tested without an account, a vault and a relay connection, which is how a screen ends up
 * shipping unlooked-at — and this one is mostly refusal wording, which has to be READ to be
 * checked. [loRaPaneHost] is the one-line adapter over the real client.
 */
interface LoRaPaneHost {
    val attached: Boolean
    fun devices(): List<LoRaSerial.Device>
    fun nodes(): List<com.oshi.desktop.lora.LoRaNodeRoster.Node>
    /** Node key (8 hex) → the identity a person attached to it. */
    fun links(): Map<String, String>
    fun attachSerial(path: String): Boolean
    fun attachTcp(host: String): Boolean
    fun detach()
    fun send(text: String): Boolean
    /** The ENCRYPTED lane: an OSHI envelope to one contact. Returns false with a reason logged. */
    fun sendOshi(recipient: String, text: String): Boolean
    /**
     * __DESKTOP_NODE_PERIMETER_2026_11_07__ Our own radio's position, the origin
     * the range diagram is drawn around. Null until it has a fix.
     */
    fun myPosition(): Pair<Double, Double>?
    fun link(node: UInt, address: String): LoRaNodeBindings.LearnResult
    fun unlink(node: UInt): Boolean

    /**
     * __MESSAGE_THE_NODE_FROM_THE_MAP_2026_11_07__ (desktop half) The conversation a
     * message to [node] belongs in.
     *
     * The PROVED identity when the node has one, so the message is ratchet-encrypted to
     * that contact; the `lora!<nodehex>` thread only when nothing has been proved. The
     * same routing decision the phones make — never hard-code the fallback, or every
     * proved peer is silently downgraded to cleartext.
     */
    fun conversationKeyFor(node: UInt): String
    /** The two switches. See [com.oshi.desktop.lora.DesktopTransportPolicy] for why two. */
    fun prefs(): com.oshi.desktop.store.TransportPreferences.Values
    fun setPrefs(values: com.oshi.desktop.store.TransportPreferences.Values)

    // __DESKTOP_LORA_BEACON_2026_10_28__ Sharing where this machine is.
    //
    // A position this machine's owner STATED, not one a sensor measured — a Mac has no GPS
    // and nothing here infers a location from WiFi or IP, which would be publishing a
    // position nobody consented to. Meshtastic covers the same case with `fixed_position`.
    /** Where this machine says it is, or null when nobody has said. */
    fun fixedPosition(): Pair<Double, Double>?
    /** Returns false, changing nothing, for coordinates that are not on Earth (0/0 included). */
    fun setFixedPosition(latitude: Double, longitude: Double): Boolean
    fun clearFixedPosition()
    /** Whether the beacon is on. Cannot be turned on with no position — the setter refuses. */
    val beaconEnabled: Boolean
    fun setBeaconEnabled(value: Boolean): Boolean
    /** One pass of the beacon, so the pane can say why nothing went out. */
    fun beaconTick(): com.oshi.desktop.lora.LoRaPositionBeacon.Decision

    // __DESKTOP_LORA_SET_OWNER_2026_10_29__ Naming the radio — both phones have had it.
    /** The attached node's own announced name, or null when it has not said one. */
    fun myNodeName(): String?
    /** `shortName` is clamped to four BYTES, not characters. Blank borrows the long name. */
    fun setOwner(longName: String, shortName: String): Boolean
    /** Rename only a node still wearing a factory default; leave a chosen name alone. */
    fun adoptIfFactoryDefault(): Boolean

    /** __DESKTOP_LORA_TRAFFIC_2026_10_29__ Frame SHAPES, newest first. Never payloads. */
    fun traffic(): List<com.oshi.desktop.lora.LoRaTrafficLog.Entry>

    /** __DESKTOP_LORA_RADIO_STATE_2026_10_29__ The region the NODE reports, not the one asked for. */
    fun reportedRegion(): com.oshi.desktop.lora.LoRaAdmin.Region?
    /** Non-null when the attached radio will never transmit — muted, or no region. */
    fun blockedReason(): String?

    // __DESKTOP_LORA_REGION_UI_2026_10_29__ Writing the region and the channel WITH A MOUSE.
    //
    // Both of these already existed on the client and were reachable only from `/lora
    // region` and `/lora channel` in the text console. That made the refusal this pane
    // already draws — "The radio has no region set, so it will not transmit. Choose one and
    // try again." — an instruction with nothing in the window that carries it out: the one
    // screen whose purpose is to rescue a silent radio told the owner to do something it
    // offered no way to do. iOS has had `MeshtasticSettingsView` for exactly this.
    /**
     * Write the regulatory region and modem preset.
     *
     * Replaces the WHOLE `Config.lora`, so the preset is passed explicitly rather than
     * left to be "kept" — see [com.oshi.desktop.lora.LoRaAdmin.setConfigFrame]. The hop
     * limit stays at [com.oshi.desktop.lora.LoRaAdmin.DEFAULT_HOP_LIMIT]; it is not on this
     * screen because it is not on the phones' either, and a field nobody understands is a
     * way to break a working radio.
     */
    fun setRegion(
        region: com.oshi.desktop.lora.LoRaAdmin.Region,
        preset: com.oshi.desktop.lora.LoRaAdmin.ModemPreset,
    ): Boolean
    /**
     * Move the node onto a channel — "which mesh am I on".
     *
     * Replaces the channel WHOLESALE: a key left out means the public default, never "keep
     * the one you had". A blank name IS the stock LongFast channel, which is how getting
     * back onto the normal mesh is reachable.
     */
    fun setChannel(name: String, psk: String): Boolean

    /** __DESKTOP_TOOLS_2027__ Battery, signal, airtime and config of the attached radio. */
    fun radioCard(): com.oshi.desktop.lora.LoRaRadioCard? = null
}

/** The real radio. */
fun loRaPaneHost(client: OshiClient): LoRaPaneHost = object : LoRaPaneHost {
    override val attached: Boolean get() = client.loraAttached
    override fun devices() = client.loraSerialDevices()
    override fun nodes() = client.loraOnlineNodeList()
    override fun myPosition() = client.loraMyPosition()
    override fun links() = client.loraManualLinks()
    override fun attachSerial(path: String) = client.loraAttachSerial(path)
    override fun attachTcp(host: String) = client.loraAttach(host)
    override fun detach() = client.loraDetach()
    override fun send(text: String) = client.loraSendInteropText(text)
    override fun sendOshi(recipient: String, text: String) = client.loraSendOshiMessage(recipient, text)
    override fun link(node: UInt, address: String) = client.loraLinkNode(node, address)
    override fun unlink(node: UInt) = client.loraUnlinkNode(node)
    override fun conversationKeyFor(node: UInt) = client.loraConversationKey(node)
    override fun prefs() = client.transportPrefs()
    override fun setPrefs(values: com.oshi.desktop.store.TransportPreferences.Values) = client.setTransportPrefs(values)
    override fun fixedPosition() = com.oshi.desktop.lora.LoRaFixedPosition.position
    override fun setFixedPosition(latitude: Double, longitude: Double) =
        com.oshi.desktop.lora.LoRaFixedPosition.set(latitude, longitude).also {
            // A new position must not inherit the old one's clock, or the first beacon from
            // the new place is suppressed as TOO_SOON — and a fixed position never moves,
            // so it would then wait out the one-hour ceiling before saying anything.
            if (it) com.oshi.desktop.lora.LoRaPositionBeacon.reset()
        }
    override fun clearFixedPosition() {
        com.oshi.desktop.lora.LoRaFixedPosition.clear()
        com.oshi.desktop.lora.LoRaPositionBeacon.reset()
    }
    override val beaconEnabled: Boolean get() = com.oshi.desktop.lora.LoRaFixedPosition.enabled
    override fun setBeaconEnabled(value: Boolean) =
        com.oshi.desktop.lora.LoRaFixedPosition.setEnabled(value).also {
            if (it && value) com.oshi.desktop.lora.LoRaPositionBeacon.reset()
        }
    override fun beaconTick() = client.loraPositionBeaconTick()
    override fun myNodeName(): String? {
        val me = client.loraMyNodeNumber()
        if (me == 0u) return null
        return client.loraOnlineNodeList().firstOrNull { it.num == me }?.name?.takeIf { it.isNotBlank() }
    }
    override fun setOwner(longName: String, shortName: String) = client.loraSetOwner(longName, shortName)
    override fun adoptIfFactoryDefault() = client.loraAdoptIfFactoryDefault()
    override fun traffic() = client.loraTraffic.recent(limit = 14)
    override fun setRegion(
        region: com.oshi.desktop.lora.LoRaAdmin.Region,
        preset: com.oshi.desktop.lora.LoRaAdmin.ModemPreset,
    ) = client.loraSetRegion(region, preset)
    override fun setChannel(name: String, psk: String) = client.loraSetChannel(name, psk)

    override fun reportedRegion() = client.loraRadioState.regionCode
        ?.let { code -> com.oshi.desktop.lora.LoRaAdmin.Region.entries.firstOrNull { it.wire == code } }
    override fun blockedReason() = client.loraRadioState.blockedReason
    override fun radioCard() = client.loraRadioCard()
}

/** The four pages of the radio screen. */
enum class RadioTab { OVERVIEW, SEND, SETTINGS, TRAFFIC }

/** Lets a render pin a tab without clicking. */
val LocalRadioTab = androidx.compose.runtime.staticCompositionLocalOf<RadioTab?> { null }

/**
 * __DESKTOP_TOOLS_2027__ The radio screen, redrawn around a RADIO CARD — battery, signal,
 * airtime, region, nodes — the way the phones' LoRa Node screen opens, with the rest split
 * into four tabs instead of one long wall: Overview (card, nodes, range diagram), Send (the
 * plaintext mesh lane and the encrypted OSHI lane), Settings (routing, region and channel,
 * name, position beacon) and Traffic (frame shapes, never contents).
 *
 * Every behaviour of the old pane is kept; the long explanations became one-line hints
 * that reuse the phones' translated sentences wherever the phones say the same thing.
 */
@Composable
fun LoRaPane(
    radio: LoRaPaneHost,
    contacts: List<ContactRow>,
    onCopy: (String) -> Unit = {},
    /** __MESSAGE_THE_NODE_FROM_THE_MAP_2026_11_07__ Open a conversation by key. */
    onMessage: (String) -> Unit = {},
) = ProvideLocaleDirection {
    var attached by remember { mutableStateOf(radio.attached) }
    var devices by remember { mutableStateOf(runCatching { radio.devices() }.getOrDefault(emptyList())) }
    var nodes by remember { mutableStateOf(runCatching { radio.nodes() }.getOrDefault(emptyList())) }
    var links by remember { mutableStateOf(runCatching { radio.links() }.getOrDefault(emptyMap())) }
    var notice by remember { mutableStateOf<String?>(null) }
    var prefs by remember { mutableStateOf(runCatching { radio.prefs() }.getOrDefault(com.oshi.desktop.store.TransportPreferences.Values())) }
    var fixedPos by remember { mutableStateOf(runCatching { radio.fixedPosition() }.getOrNull()) }
    var myPos by remember { mutableStateOf(runCatching { radio.myPosition() }.getOrNull()) }
    var beaconOn by remember { mutableStateOf(runCatching { radio.beaconEnabled }.getOrDefault(false)) }
    var beaconWhy by remember { mutableStateOf(com.oshi.desktop.lora.LoRaPositionBeacon.Decision.DISABLED) }
    var myName by remember { mutableStateOf(runCatching { radio.myNodeName() }.getOrNull()) }
    var traffic by remember { mutableStateOf(runCatching { radio.traffic() }.getOrDefault(emptyList())) }
    var reportedRegion by remember { mutableStateOf(runCatching { radio.reportedRegion() }.getOrNull()) }
    var blocked by remember { mutableStateOf(runCatching { radio.blockedReason() }.getOrNull()) }
    var card by remember { mutableStateOf(runCatching { radio.radioCard() }.getOrNull()) }
    var tab by remember { mutableStateOf(RadioTab.OVERVIEW) }
    LocalRadioTab.current?.let { tab = it }

    // Polled rather than pushed: the roster is folded on the radio's read thread and the
    // only thing this screen needs is to be roughly current. One second matches the header.
    LaunchedEffect(radio) {
        while (true) {
            attached = radio.attached
            devices = runCatching { radio.devices() }.getOrDefault(emptyList())
            nodes = runCatching { radio.nodes() }.getOrDefault(emptyList())
            // __DESKTOP_NODE_PERIMETER_2026_11_07__ Polled with the roster, not read during
            // composition: the roster takes a lock, and a lock held on the composition
            // thread is a frame dropped.
            myPos = runCatching { radio.myPosition() }.getOrNull()
            links = runCatching { radio.links() }.getOrDefault(emptyMap())
            prefs = runCatching { radio.prefs() }.getOrDefault(com.oshi.desktop.store.TransportPreferences.Values())
            fixedPos = runCatching { radio.fixedPosition() }.getOrNull()
            myName = runCatching { radio.myNodeName() }.getOrNull()
            traffic = runCatching { radio.traffic() }.getOrDefault(emptyList())
            reportedRegion = runCatching { radio.reportedRegion() }.getOrNull()
            blocked = runCatching { radio.blockedReason() }.getOrNull()
            card = runCatching { radio.radioCard() }.getOrNull()
            beaconOn = runCatching { radio.beaconEnabled }.getOrDefault(false)
            // __DESKTOP_LORA_BEACON_2026_10_28__ The beacon has no clock of its own; it rides
            // this poll. The policy — shared byte for byte with both phones — decides, so
            // ticking often is free. Nothing is transmitted unless the switch is on, a
            // position is stored and a radio is attached.
            beaconWhy = runCatching { radio.beaconTick() }.getOrDefault(com.oshi.desktop.lora.LoRaPositionBeacon.Decision.DISABLED)
            delay(1_000)
        }
    }

    ToolScaffold(
        title = t("lora.node.title"),
        subtitle = if (attached) (myName ?: card?.name ?: t("lora.status.ready")) else t("lora.error.not_connected"),
        icon = ToolGlyph.RADIO,
        maxContentWidth = 1180.dp,
        actions = {
            ToolIconButton(ToolGlyph.LINK, t("lora.buy.node")) { openUrl("https://oshi-messenger.com/lora/") { notice = t("pc.radio.browserFailed") } }
            if (attached) ToolButton(t("pc.radio.detach"), icon = ToolGlyph.CLOSE, style = ToolButtonStyle.QUIET) { radio.detach(); notice = t("pc.radio.detached") }
        },
    ) {
        notice?.let { ToolNotice(it, ToolTone.INFO) { ToolIconButton(ToolGlyph.CLOSE, t("common.dismiss"), size = 28.dp) { notice = null } } }

        if (!attached) {
            ConnectCard(radio, devices, onNotice = { notice = it })
            ToolEmptyState(t("lora.error.not_connected"), t("pc.radio.notAttached.body"), mascot = "sleep", size = 96.dp) {
                ToolButton(t("lora.buy.node"), icon = ToolGlyph.LINK, style = ToolButtonStyle.QUIET) { openUrl("https://oshi-messenger.com/lora/") { notice = t("pc.radio.browserFailed") } }
            }
            return@ToolScaffold
        }

        RadioCard(card, myName, reportedRegion, nodes.size)

        // __DESKTOP_LORA_RADIO_STATE_2026_10_29__ Drawn BEFORE everything it invalidates:
        // a node with tx disabled looks identical to a healthy one everywhere except here.
        if (blocked != null || card?.muted == true || card?.noRegion == true) {
            val muted = card?.muted == true || (blocked?.contains("muted") == true)
            ToolNotice(
                if (muted) t("lora.status.tx_off.detail") else t("lora.status.needs_region.detail"),
                ToolTone.ERROR,
            ) { ToolButton(t("lora.blocked.fix"), style = ToolButtonStyle.QUIET) { tab = RadioTab.SETTINGS } }
        }

        ToolTabs(
            RadioTab.entries.toList(), tab,
            label = {
                when (it) {
                    RadioTab.OVERVIEW -> t("lora.nodes.section")
                    RadioTab.SEND -> t("common.send")
                    RadioTab.SETTINGS -> t("lora.configure.section")
                    RadioTab.TRAFFIC -> t("meshtastic.traffic")
                }
            },
            icon = {
                when (it) {
                    RadioTab.OVERVIEW -> ToolGlyph.NODE
                    RadioTab.SEND -> ToolGlyph.SEND
                    RadioTab.SETTINGS -> ToolGlyph.SETTINGS
                    RadioTab.TRAFFIC -> ToolGlyph.WAVE
                }
            },
            onSelect = { tab = it },
        )

        when (tab) {
            RadioTab.OVERVIEW -> NodesSection(radio, nodes, links, contacts, myPos, onCopy, onMessage) { notice = it }
            RadioTab.SEND -> SendSection(radio, attached, contacts) { notice = it }
            RadioTab.SETTINGS -> ToolColumns(
                first = {
                    RoutingCard(radio, prefs) { prefs = it }
                    RegionCard(radio, reportedRegion) { notice = it }
                },
                second = {
                    NameCard(radio, myName) { notice = it }
                    PositionCard(radio, fixedPos, beaconOn, beaconWhy, onFixed = { fixedPos = it }, onBeacon = { beaconOn = it }) { notice = it }
                },
            )
            RadioTab.TRAFFIC -> TrafficCard(traffic)
        }
    }
}

// ---------------------------------------------------------------------------- radio card

@Composable
private fun RadioCard(card: com.oshi.desktop.lora.LoRaRadioCard?, myName: String?, region: com.oshi.desktop.lora.LoRaAdmin.Region?, heard: Int) {
    ToolCard(padding = OshiTheme.xl) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(OshiTheme.brandGradient), contentAlignment = Alignment.Center) {
                ToolIcon(ToolGlyph.RADIO, Color.White, 28.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(myName ?: card?.name ?: t("lora.node.unnamed"), style = ToolType.title, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val id = card?.nodeNum?.takeIf { it != 0u }?.let { "!%08x".format(it.toLong()) }
                Text(listOfNotNull(id, region?.let { regionLabel(it) }, card?.modemPreset?.let { presetLabel(it) }, card?.txPowerDbm?.let { "$it dBm" }).bidiJoin(),
                    style = ToolType.caption, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val (statusText, statusColor) = when {
                card?.muted == true -> t("lora.status.tx_off") to OshiTheme.danger
                card?.noRegion == true -> t("lora.status.needs_region") to OshiTheme.warning
                else -> t("lora.status.ready") to OshiTheme.success
            }
            ToolPill(statusText, statusColor)
        }
        Spacer(Modifier.height(OshiTheme.xs))
        BoxWithConstraints {
            val perRow = if (maxWidth > 900.dp) 4 else 2
            val tiles: List<@Composable (Modifier) -> Unit> = listOf(
                { m ->
                    val b = card?.batteryLevel
                    ToolStat(
                        ToolGlyph.BATTERY, t("pc.radio.battery"),
                        when { b == null -> "—"; b > 100 -> t("pc.radio.externalPower"); else -> "$b %" },
                        m,
                        detail = card?.voltage?.let { "%.2f V".format(java.util.Locale.ROOT, it) },
                        level = b?.let { (it.coerceAtMost(100)) / 100f },
                        tone = when { b == null || b > 100 -> OshiTheme.brand; b < 20 -> OshiTheme.danger; b < 40 -> OshiTheme.warning; else -> OshiTheme.success },
                    )
                },
                { m ->
                    val bars = card?.signalBars
                    ToolStat(
                        ToolGlyph.SIGNAL, t("pc.radio.signal"),
                        when (bars) { null -> "—"; 4 -> t("lora.signal.excellent"); 3 -> t("lora.signal.good"); 2 -> t("lora.signal.fair"); else -> t("lora.signal.weak") },
                        m,
                        detail = listOfNotNull(card?.lastRssi?.let { "$it dBm" }, card?.lastSnr?.let { "SNR %.1f dB".format(java.util.Locale.ROOT, it) }).bidiJoin().ifBlank { t("pc.radio.nothingHeard") },
                        level = bars?.let { it / 4f },
                    )
                },
                { m ->
                    val air = card?.airUtilTx
                    val limit = card?.airtimeLimitPercent ?: 10f
                    ToolStat(
                        ToolGlyph.AIRTIME, t("pc.radio.airtime"),
                        air?.let { "%.1f %%".format(java.util.Locale.ROOT, it) } ?: "—",
                        m,
                        detail = card?.channelUtilization?.let { t("pc.radio.channelBusy", "%.0f".format(java.util.Locale.ROOT, it)) } ?: t("pc.radio.airtime.detail"),
                        level = air?.let { (it / limit).coerceIn(0f, 1f) },
                        tone = if (air != null && air >= limit * 0.8f) OshiTheme.warning else OshiTheme.brand,
                    )
                },
                { m ->
                    ToolStat(
                        ToolGlyph.NODE, t("lora.hero.heard"), "${card?.nodesNow ?: heard}", m,
                        detail = card?.uptimeSeconds?.let { t("pc.radio.uptime", ltrIsolate(uptimeLabel(it))) },
                    )
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                for (row in tiles.chunked(perRow)) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                    for (tile in row) tile(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- connect

@Composable
private fun ConnectCard(radio: LoRaPaneHost, devices: List<LoRaSerial.Device>, onNotice: (String) -> Unit) {
    var host by remember { mutableStateOf("") }
    ToolCard(title = t("pc.radio.connect.title"), icon = ToolGlyph.USB) {
        ToolHint(t("lora.guide.footer"))
        if (devices.isEmpty()) {
            ToolHint(if (LoRaSerial.isSupported()) t("pc.radio.usb.none") else t("pc.radio.usb.unsupported"))
        } else for (device in devices) {
            ToolRow(device.name.ifBlank { device.path }, subtitle = device.path, icon = ToolGlyph.USB) {
                ToolButton(t("peers.peer.connect"), style = ToolButtonStyle.PRIMARY) {
                    onNotice(if (radio.attachSerial(device.path)) t("pc.radio.attaching", device.path) else t("pc.radio.pathRefused"))
                }
            }
        }
        Hairline()
        Text(t("pc.radio.network", com.oshi.desktop.lora.LoRaAttach.TCP_PORT), style = ToolType.caption, color = Ink.soft)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            ToolField(host, { host = it }, "meshtastic.local", Modifier.weight(1f), icon = ToolGlyph.GLOBE)
            ToolButton(t("peers.peer.connect"), enabled = host.isNotBlank()) {
                onNotice(if (radio.attachTcp(host.trim())) t("pc.radio.attaching", host.trim()) else t("pc.radio.hostRefused"))
            }
        }
    }
}

// ---------------------------------------------------------------------------- nodes

@Composable
private fun NodesSection(
    radio: LoRaPaneHost,
    nodes: List<com.oshi.desktop.lora.LoRaNodeRoster.Node>,
    links: Map<String, String>,
    contacts: List<ContactRow>,
    myPos: Pair<Double, Double>?,
    onCopy: (String) -> Unit,
    onMessage: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    if (nodes.isEmpty()) {
        ToolEmptyState(t("pc.radio.nodes.emptyTitle"), t("lora.nodes.empty"), mascot = "peek", size = 96.dp)
        return
    }
    var picking by remember { mutableStateOf<UInt?>(null) }
    ToolColumns(
        minTwoColumnWidth = 900.dp,
        first = {
            ToolCard(title = t("lora.nodes.section"), icon = ToolGlyph.NODE, trailing = { ToolPill("${nodes.size}") }) {
                for (node in nodes) {
                    val nodeKey = "%08x".format(node.num.toLong())
                    val linked = links[nodeKey]
                    val linkedLabel = linked?.let { a -> contacts.firstOrNull { it.address == a }?.label ?: "${a.take(12)}…" }
                    ToolRow(
                        node.name.ifBlank { t("lora.node.unnamed") },
                        subtitle = listOfNotNull(
                            "!$nodeKey", heardLabel(node.lastHeardMs),
                            node.hopsAway?.let { if (it == 0) t("lora.nodes.direct") else t("lora.nodes.hops", it) },
                            node.distanceKm?.let { if (it < 1.0) "%.0f m".format(java.util.Locale.ROOT, it * 1000) else "%.1f km".format(java.util.Locale.ROOT, it) },
                            linkedLabel?.let { t("pc.radio.linkedTo", it) },
                        ).bidiJoin(),
                        leading = {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(OshiTheme.success.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                ToolIcon(ToolGlyph.SIGNAL, OshiTheme.success, 17.dp, level = node.rssi?.let { r -> ((r + 130).coerceIn(0, 40)) / 40f })
                            }
                        },
                    ) {
                        ToolIconButton(ToolGlyph.COMPOSE, t("lora.node.message")) { onMessage(radio.conversationKeyFor(node.num)) }
                        ToolIconButton(ToolGlyph.PEOPLE, if (linked != null) t("pc.radio.unlink") else t("pc.radio.link"), active = linked != null) {
                            if (linked != null) onNotice(if (radio.unlink(node.num)) t("pc.radio.unlinked") else t("pc.radio.nothingToUndo"))
                            else picking = node.num
                        }
                        ToolIconButton(ToolGlyph.COPY, t("pc.radio.copyId")) { onCopy("!$nodeKey") }
                    }
                    if (picking == node.num) LinkPicker(contacts, onCancel = { picking = null }) { contact ->
                        onNotice(describe(radio.link(node.num, contact.address), contact.label)); picking = null
                    }
                }
                ToolHint(t("lora.nodes.footer"))
            }
        },
        second = {
            ToolCard(title = t("pc.radio.rangeDiagram"), icon = ToolGlyph.BROADCAST) {
                // __DESKTOP_NODE_PERIMETER_2026_11_07__ Our radio at the centre, each node by
                // bearing and distance, its estimated reach as a circle. It says why it draws
                // nothing when we have no fix of our own.
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    LoRaNodeMap(nodes = nodes, myLat = myPos?.first, myLon = myPos?.second)
                }
            }
        },
    )
}

@Composable
private fun LinkPicker(contacts: List<ContactRow>, onCancel: () -> Unit, onPick: (ContactRow) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 46.dp).clip(OshiTheme.radiusMd).background(OshiTheme.surface).padding(OshiTheme.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(t("pc.radio.whose"), style = ToolType.caption, color = Ink.soft)
        val eligible = contacts.filterNot { it.blocked }
        if (eligible.isEmpty()) ToolHint(t("pc.radio.addContactFirst"))
        for (c in eligible) ToolRow(c.label, leading = { Monogram(c.label, c.address, 26.dp) }, onClick = { onPick(c) })
        ToolButton(t("common.cancel"), style = ToolButtonStyle.QUIET) { onCancel() }
    }
}

// ---------------------------------------------------------------------------- send

@Composable
private fun SendSection(radio: LoRaPaneHost, attached: Boolean, contacts: List<ContactRow>, onNotice: (String) -> Unit) {
    var outgoing by remember { mutableStateOf("") }
    var oshiText by remember { mutableStateOf("") }
    var oshiTo by remember { mutableStateOf<ContactRow?>(null) }
    var choosing by remember { mutableStateOf(false) }
    ToolColumns(
        first = {
            ToolCard(title = t("pc.radio.oshi.title"), icon = ToolGlyph.LOCK, trailing = { ToolPill(t("call.connection.e2e"), OshiTheme.success) }) {
                ToolHint(t("pc.radio.oshi.hint"))
                val eligible = contacts.filterNot { it.blocked }
                if (eligible.isEmpty()) ToolHint(t("pc.radio.addContactFirst"))
                else {
                    Box {
                        ToolRow(
                            oshiTo?.label ?: t("pc.radio.chooseContact"),
                            leading = { oshiTo?.let { Monogram(it.label, it.address, 30.dp) } ?: ToolIcon(ToolGlyph.PEOPLE, OshiTheme.brand, 20.dp) },
                            onClick = { choosing = true },
                            modifier = Modifier.clip(OshiTheme.radiusMd).background(OshiTheme.surface),
                        ) { ToolIcon(ToolGlyph.CHEVRON, Ink.soft, 14.dp) }
                        OshiDropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                            for (c in eligible) androidx.compose.material3.DropdownMenuItem(text = { Text(c.label) }, onClick = { oshiTo = c; choosing = false })
                        }
                    }
                    ToolField(oshiText, { oshiText = it }, t("new.composer.message"), Modifier.fillMaxWidth(), singleLine = false, minHeight = 88.dp)
                    val recipient = oshiTo
                    ToolButton(t("pc.radio.oshi.send"), icon = ToolGlyph.SEND, style = ToolButtonStyle.PRIMARY, enabled = attached && recipient != null && oshiText.isNotBlank()) {
                        if (recipient != null) onNotice(if (radio.sendOshi(recipient.address, oshiText)) { oshiText = ""; t("pc.radio.oshi.sent") } else t("pc.radio.notSent"))
                    }
                }
            }
        },
        second = {
            ToolCard(title = t("pc.radio.broadcast.title"), icon = ToolGlyph.BROADCAST, trailing = { ToolPill(t("lora.interop.unverified"), OshiTheme.warning) }) {
                ToolHint(t("pc.radio.broadcast.hint"))
                ToolField(outgoing, { outgoing = it }, t("new.composer.message"), Modifier.fillMaxWidth(), singleLine = false, minHeight = 88.dp)
                val bytes = outgoing.toByteArray(Charsets.UTF_8).size
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                    ToolButton(t("pc.radio.broadcast.send"), icon = ToolGlyph.BROADCAST, enabled = attached && bytes in 1..LoRaProto.INTEROP_TEXT_LIMIT) {
                        onNotice(if (radio.send(outgoing)) { outgoing = ""; t("pc.radio.broadcast.sent") } else t("pc.radio.notSent"))
                    }
                    // Over the limit is refused, never truncated: a stock client has no
                    // reassembler, so a long text would arrive in pieces nobody joins.
                    Text(
                        "$bytes / ${LoRaProto.INTEROP_TEXT_LIMIT} B",
                        style = ToolType.caption, color = if (bytes > LoRaProto.INTEROP_TEXT_LIMIT) OshiTheme.danger else Ink.soft,
                    )
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------- settings

@Composable
private fun RoutingCard(radio: LoRaPaneHost, prefs: com.oshi.desktop.store.TransportPreferences.Values, onPrefs: (com.oshi.desktop.store.TransportPreferences.Values) -> Unit) {
    ToolCard(title = t("lora.routing.section").lowercase().replaceFirstChar { it.titlecase() }, icon = ToolGlyph.SEND) {
        ToolSwitchRow(t("lora.routing.prefer_radio"), t("pc.radio.preferRadio.hint", (com.oshi.desktop.lora.DesktopTransportPolicy.ACK_WINDOW_MS / 1000).toInt()), prefs.preferRadio) {
            radio.setPrefs(prefs.copy(preferRadio = it)); onPrefs(radio.prefs())
        }
        Hairline()
        ToolSwitchRow(t("lora.routing.no_internet"), t("lora.routing.no_internet.note"), prefs.neverOverInternet) {
            radio.setPrefs(prefs.copy(neverOverInternet = it)); onPrefs(radio.prefs())
        }
        Hairline()
        // __DESKTOP_LORA_MEDIA_2026_10_28__ The ceiling is stated up front rather than
        // discovered by refusal.
        ToolSwitchRow(t("lora.media.toggle"), t("pc.radio.media.hint", com.oshi.desktop.lora.LoRaMediaWire.maxMediaBytes()), prefs.mediaOverLoRa) {
            radio.setPrefs(prefs.copy(mediaOverLoRa = it)); onPrefs(radio.prefs())
        }
    }
}

@Composable
private fun RegionCard(radio: LoRaPaneHost, reported: com.oshi.desktop.lora.LoRaAdmin.Region?, onNotice: (String) -> Unit) {
    // __DESKTOP_LORA_REGION_UI_2026_10_29__ Nothing is pre-selected: the node's own region is
    // READ BACK, and seeding the picker from it would make "Set region" look like a no-op.
    var picked by remember { mutableStateOf<com.oshi.desktop.lora.LoRaAdmin.Region?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(com.oshi.desktop.lora.LoRaAdmin.ModemPreset.LONG_FAST) }
    var channel by remember { mutableStateOf("") }
    var psk by remember { mutableStateOf("") }
    ToolCard(title = t("lora.region.picker"), icon = ToolGlyph.GLOBE) {
        ToolHint(t("pc.radio.region.footer"))
        ToolValue(t("pc.radio.region.reported"), reported?.let { regionLabel(it) } ?: t("lora.region.unset"))
        Box {
            ToolRow(picked?.let { regionLabel(it) } ?: t("lora.region.change"), icon = ToolGlyph.GLOBE, onClick = { choosing = true },
                modifier = Modifier.clip(OshiTheme.radiusMd).background(OshiTheme.surface)) { ToolIcon(ToolGlyph.CHEVRON, Ink.soft, 14.dp) }
            OshiDropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                // `selectable`, not `entries`: UNSET is the state this card rescues a radio FROM.
                for (r in com.oshi.desktop.lora.LoRaAdmin.Region.selectable) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(regionLabel(r)) }, onClick = { picked = r; choosing = false })
                }
            }
        }
        Text(t("lora.preset.picker").uppercase(), style = ToolType.overline, color = Ink.soft)
        ToolTabs(
            com.oshi.desktop.lora.LoRaAdmin.ModemPreset.entries.toList(), preset,
            label = { if (it == com.oshi.desktop.lora.LoRaAdmin.ModemPreset.LONG_FAST) t("lora.preset.longfast") else t("lora.preset.longslow") },
            icon = { null }, onSelect = { preset = it },
        )
        ToolHint(if (preset == com.oshi.desktop.lora.LoRaAdmin.ModemPreset.LONG_FAST) t("lora.preset.longfast.detail") else t("lora.preset.longslow.detail"))
        ToolButton(t("lora.region.apply"), style = ToolButtonStyle.PRIMARY, enabled = picked != null) {
            val r = picked
            onNotice(if (r != null && radio.setRegion(r, preset)) t("pc.radio.region.asked", regionLabel(r)) else t("pc.radio.refused"))
        }
        Hairline()
        Text(t("lora.channel.section").lowercase().replaceFirstChar { it.titlecase() }, style = ToolType.bodyStrong, color = Ink.strong)
        ToolHint(t("lora.channel.footer"))
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
            ToolField(channel, { channel = it }, com.oshi.desktop.lora.LoRaAdmin.Channel.DEFAULT_NAME, Modifier.weight(1f))
            val clamped = com.oshi.desktop.lora.LoRaAdmin.Channel.clampName(channel)
            if (channel.isNotBlank()) Text(t("lora.channel.name_budget", clamped.toByteArray(Charsets.UTF_8).size, com.oshi.desktop.lora.LoRaAdmin.Channel.NAME_MAX_BYTES), style = ToolType.caption, color = Ink.soft)
        }
        ToolField(psk, { psk = it }, t("lora.channel.key_default"), Modifier.fillMaxWidth(), mono = true, icon = ToolGlyph.KEY)
        ToolButton(t("lora.channel.apply"), style = ToolButtonStyle.PRIMARY) {
            onNotice(
                if (radio.setChannel(channel, psk)) {
                    val shown = com.oshi.desktop.lora.LoRaAdmin.Channel.clampName(channel).ifEmpty { com.oshi.desktop.lora.LoRaAdmin.Channel.DEFAULT_NAME }
                    channel = ""; psk = ""
                    t("lora.channel.written", shown)
                } else t("lora.channel.key_invalid"),
            )
        }
    }
}

@Composable
private fun NameCard(radio: LoRaPaneHost, myName: String?, onNotice: (String) -> Unit) {
    // __DESKTOP_LORA_SET_OWNER_2026_10_29__ Both phones have had this.
    var long by remember { mutableStateOf("") }
    var short by remember { mutableStateOf("") }
    ToolCard(title = t("lora.name.section").lowercase().replaceFirstChar { it.titlecase() }, icon = ToolGlyph.EDIT) {
        myName?.let { ToolValue(t("lora.node.name"), it) }
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            ToolField(long, { long = it }, t("lora.name.long"), Modifier.weight(2f))
            ToolField(short, { short = it }, t("lora.name.short"), Modifier.weight(1f))
        }
        val clamped = com.oshi.desktop.lora.LoRaAdmin.clampShortName(short.ifBlank { long })
        ToolHint(t("lora.name.short.budget", clamped.toByteArray(Charsets.UTF_8).size))
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            ToolButton(t("lora.name.save"), style = ToolButtonStyle.PRIMARY, enabled = long.isNotBlank()) {
                onNotice(if (radio.setOwner(long, short)) { long = ""; short = ""; t("lora.name.saved") } else t("pc.radio.refused"))
            }
            ToolButton(t("pc.radio.adopt"), style = ToolButtonStyle.QUIET) {
                onNotice(if (radio.adoptIfFactoryDefault()) t("pc.radio.adopted") else t("pc.radio.adopt.none"))
            }
        }
        ToolHint(t("lora.name.footer"))
    }
}

@Composable
private fun PositionCard(
    radio: LoRaPaneHost,
    fixedPos: Pair<Double, Double>?,
    beaconOn: Boolean,
    beaconWhy: com.oshi.desktop.lora.LoRaPositionBeacon.Decision,
    onFixed: (Pair<Double, Double>?) -> Unit,
    onBeacon: (Boolean) -> Unit,
    onNotice: (String) -> Unit,
) {
    // __DESKTOP_LORA_BEACON_2026_10_28__ A computer has no GPS but it has an owner who knows
    // where it is: Meshtastic's `fixed_position`. The position is typed, never inferred.
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    ToolCard(title = t("lora.position.section"), icon = ToolGlyph.PIN) {
        ToolHint(t("pc.radio.position.hint"))
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
            ToolField(lat, { lat = it }, t("pc.radio.latitude"), Modifier.weight(1f), mono = true)
            ToolField(lon, { lon = it }, t("pc.radio.longitude"), Modifier.weight(1f), mono = true)
            val la = lat.trim().replace(',', '.').toDoubleOrNull()
            val lo = lon.trim().replace(',', '.').toDoubleOrNull()
            ToolButton(t("common.save"), enabled = la != null && lo != null) {
                onNotice(
                    if (la != null && lo != null && radio.setFixedPosition(la, lo)) {
                        onFixed(radio.fixedPosition()); lat = ""; lon = ""
                        t("pc.radio.position.saved")
                    } else t("pc.radio.position.invalid"),
                )
            }
        }
        fixedPos?.let { (la, lo) ->
            ToolRow(ltrIsolate("%.5f, %.5f".format(java.util.Locale.ROOT, la, lo)), icon = ToolGlyph.PIN) {
                ToolIconButton(ToolGlyph.TRASH, t("pc.radio.position.forget"), tint = OshiTheme.danger) {
                    radio.clearFixedPosition(); onFixed(null); onBeacon(false); onNotice(t("pc.radio.position.forgotten"))
                }
            }
        }
        Hairline()
        ToolSwitchRow(t("lora.position.toggle"), t("pc.radio.position.footer"), beaconOn) { want ->
            // The switch REFUSES rather than turning on with nothing to send.
            if (!radio.setBeaconEnabled(want)) onNotice(t("pc.radio.position.first"))
            else onBeacon(radio.beaconEnabled)
        }
        if (beaconOn) {
            val why = when (beaconWhy) {
                com.oshi.desktop.lora.LoRaPositionBeacon.Decision.NO_RADIO -> t("pc.radio.beacon.noRadio")
                com.oshi.desktop.lora.LoRaPositionBeacon.Decision.NO_FIX,
                com.oshi.desktop.lora.LoRaPositionBeacon.Decision.NO_PERMISSION -> t("pc.radio.beacon.noFix")
                // The six hours are the FIRMWARE's rule for a fixed position, not ours.
                com.oshi.desktop.lora.LoRaPositionBeacon.Decision.TOO_SOON,
                com.oshi.desktop.lora.LoRaPositionBeacon.Decision.STILL_HERE -> t("pc.radio.beacon.told")
                com.oshi.desktop.lora.LoRaPositionBeacon.Decision.SEND -> t("pc.radio.beacon.sending")
                com.oshi.desktop.lora.LoRaPositionBeacon.Decision.DISABLED -> null
            }
            why?.let { ToolHint(it, color = Ink.strong) }
        }
    }
}

// ---------------------------------------------------------------------------- traffic

@Composable
private fun TrafficCard(traffic: List<com.oshi.desktop.lora.LoRaTrafficLog.Entry>) {
    // __DESKTOP_LORA_TRAFFIC_2026_10_29__ SHAPES, never contents — direction, portnum, size, node.
    ToolCard(title = t("meshtastic.traffic"), icon = ToolGlyph.WAVE) {
        ToolHint(t("pc.radio.traffic.hint"))
        if (traffic.isEmpty()) ToolEmptyState(t("pc.radio.traffic.empty"), null, mascot = "sleep", size = 72.dp)
        for (e in traffic) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            ToolIcon(if (e.direction == "→") ToolGlyph.UPLOAD else ToolGlyph.DOWNLOAD, if (e.direction == "→") OshiTheme.brand else OshiTheme.success, 14.dp)
            Text(e.summary, style = ToolType.mono, color = if (e.direction == "→") Ink.strong else Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------- helpers

/**
 * Each refusal in its own words. A single "could not link" would be the worst of the five:
 * one of them ("the ratchet already proved this is someone else") is a security answer that
 * must not read as a glitch.
 */
private fun describe(result: LoRaNodeBindings.LearnResult, label: String): String = when (result) {
    LoRaNodeBindings.LearnResult.BOUND -> t("pc.radio.link.bound", label)
    LoRaNodeBindings.LearnResult.UNCHANGED -> t("pc.radio.link.unchanged", label)
    LoRaNodeBindings.LearnResult.CONFLICT -> t("pc.radio.link.conflict")
    LoRaNodeBindings.LearnResult.IGNORED_AMBIGUOUS -> t("pc.radio.link.ambiguous")
    LoRaNodeBindings.LearnResult.REJECTED -> t("pc.radio.link.rejected")
}

/**
 * A region as people say it, in THEIR language: the country names come from the JDK's own
 * CLDR data for the active locale, the band from the region's frequency.
 */
internal fun regionLabel(r: com.oshi.desktop.lora.LoRaAdmin.Region): String {
    val l = java.util.Locale.forLanguageTag(com.oshi.desktop.i18n.Strings.activeTag)
    fun c(code: String) = java.util.Locale("", code).getDisplayCountry(l)
    return when (r) {
        com.oshi.desktop.lora.LoRaAdmin.Region.UNSET -> t("lora.region.unset")
        com.oshi.desktop.lora.LoRaAdmin.Region.US -> "${c("US")} · " + bidiIsolate("915 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.EU433 -> "${c("EU")} · " + bidiIsolate("433 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.EU868 -> "${c("EU")} · " + bidiIsolate("868 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.CN -> "${c("CN")} · " + bidiIsolate("470 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.JP -> "${c("JP")} · " + bidiIsolate("920 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.ANZ -> "${c("AU")} / ${c("NZ")} · " + bidiIsolate("915 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.KR -> "${c("KR")} · " + bidiIsolate("920 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.TW -> "${c("TW")} · " + bidiIsolate("920 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.RU -> "${c("RU")} · " + bidiIsolate("868 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.IN -> "${c("IN")} · " + bidiIsolate("865 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.NZ865 -> "${c("NZ")} · " + bidiIsolate("865 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.TH -> "${c("TH")} · " + bidiIsolate("920 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.LORA24 -> "2.4 GHz"
        com.oshi.desktop.lora.LoRaAdmin.Region.UA433 -> "${c("UA")} · " + bidiIsolate("433 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.UA868 -> "${c("UA")} · " + bidiIsolate("868 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.MY433 -> "${c("MY")} · " + bidiIsolate("433 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.MY919 -> "${c("MY")} · " + bidiIsolate("919 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.SG923 -> "${c("SG")} · " + bidiIsolate("923 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.PH433 -> "${c("PH")} · " + bidiIsolate("433 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.PH868 -> "${c("PH")} · " + bidiIsolate("868 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.PH915 -> "${c("PH")} · " + bidiIsolate("915 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.ANZ433 -> "${c("AU")} / ${c("NZ")} · " + bidiIsolate("433 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.KZ433 -> "${c("KZ")} · " + bidiIsolate("433 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.KZ863 -> "${c("KZ")} · " + bidiIsolate("863 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.NP865 -> "${c("NP")} · " + bidiIsolate("865 MHz")
        com.oshi.desktop.lora.LoRaAdmin.Region.BR902 -> "${c("BR")} · " + bidiIsolate("902 MHz")
    }
}

private fun presetLabel(wire: Long): String? = when (wire) {
    com.oshi.desktop.lora.LoRaAdmin.ModemPreset.LONG_FAST.wire -> t("lora.preset.longfast")
    com.oshi.desktop.lora.LoRaAdmin.ModemPreset.LONG_SLOW.wire -> t("lora.preset.longslow")
    else -> null
}

private fun heardLabel(ms: Long, now: Long = System.currentTimeMillis()): String {
    val min = ((now - ms) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> t("lora.nodes.now")
        min < 60 -> t("lora.nodes.minutes", min.toInt())
        min < 48 * 60 -> t("lora.nodes.hours", (min / 60).toInt())
        else -> t("lora.nodes.days", (min / 1440).toInt())
    }
}

private fun uptimeLabel(s: Long): String = when {
    s < 3600 -> "${s / 60} min"
    s < 86_400 -> "${s / 3600} h"
    else -> "${s / 86_400} d"
}

private fun openUrl(url: String, onFail: () -> Unit) {
    runCatching {
        val d = java.awt.Desktop.getDesktop()
        if (java.awt.Desktop.isDesktopSupported() && d.isSupported(java.awt.Desktop.Action.BROWSE)) d.browse(java.net.URI(url)) else onFail()
    }.onFailure { onFail() }
}
