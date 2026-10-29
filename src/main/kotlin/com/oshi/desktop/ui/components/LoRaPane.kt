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
import androidx.compose.foundation.layout.width
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
import com.oshi.desktop.app.OshiClient
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
    fun link(node: UInt, address: String): LoRaNodeBindings.LearnResult
    fun unlink(node: UInt): Boolean
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
}

/** The real radio. */
fun loRaPaneHost(client: OshiClient): LoRaPaneHost = object : LoRaPaneHost {
    override val attached: Boolean get() = client.loraAttached
    override fun devices() = client.loraSerialDevices()
    override fun nodes() = client.loraOnlineNodeList()
    override fun links() = client.loraManualLinks()
    override fun attachSerial(path: String) = client.loraAttachSerial(path)
    override fun attachTcp(host: String) = client.loraAttach(host)
    override fun detach() = client.loraDetach()
    override fun send(text: String) = client.loraSendInteropText(text)
    override fun sendOshi(recipient: String, text: String) = client.loraSendOshiMessage(recipient, text)
    override fun link(node: UInt, address: String) = client.loraLinkNode(node, address)
    override fun unlink(node: UInt) = client.loraUnlinkNode(node)
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
}

@Composable
fun LoRaPane(
    radio: LoRaPaneHost,
    contacts: List<ContactRow>,
    onCopy: (String) -> Unit = {},
) {
    var attached by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(emptyList<LoRaSerial.Device>()) }
    var nodes by remember { mutableStateOf(emptyList<com.oshi.desktop.lora.LoRaNodeRoster.Node>()) }
    var links by remember { mutableStateOf(emptyMap<String, String>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var prefs by remember { mutableStateOf(com.oshi.desktop.store.TransportPreferences.Values()) }
    var fixedPos by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var beaconOn by remember { mutableStateOf(false) }
    var beaconWhy by remember {
        mutableStateOf(com.oshi.desktop.lora.LoRaPositionBeacon.Decision.DISABLED)
    }
    var latField by remember { mutableStateOf("") }
    var lonField by remember { mutableStateOf("") }
    var myName by remember { mutableStateOf<String?>(null) }
    var traffic by remember { mutableStateOf(emptyList<com.oshi.desktop.lora.LoRaTrafficLog.Entry>()) }
    var reportedRegion by remember { mutableStateOf<com.oshi.desktop.lora.LoRaAdmin.Region?>(null) }
    var blocked by remember { mutableStateOf<String?>(null) }
    var longField by remember { mutableStateOf("") }
    var shortField by remember { mutableStateOf("") }

    // __DESKTOP_LORA_REGION_UI_2026_10_29__ Nothing is pre-selected: the radio's own region
    // is READ BACK into `reportedRegion`, and seeding the picker from it would make "Set
    // region" look like a no-op confirmation of what the node already said. A write is a
    // choice, so the owner makes one.
    var pickedRegion by remember { mutableStateOf<com.oshi.desktop.lora.LoRaAdmin.Region?>(null) }
    var choosingRegion by remember { mutableStateOf(false) }
    var pickedPreset by remember {
        mutableStateOf(com.oshi.desktop.lora.LoRaAdmin.ModemPreset.LONG_FAST)
    }
    var choosingPreset by remember { mutableStateOf(false) }
    var channelField by remember { mutableStateOf("") }
    var pskField by remember { mutableStateOf("") }

    var host by remember { mutableStateOf("") }
    var outgoing by remember { mutableStateOf("") }
    var oshiText by remember { mutableStateOf("") }
    var oshiTo by remember { mutableStateOf<ContactRow?>(null) }
    var choosingRecipient by remember { mutableStateOf(false) }
    /** Which node row has its contact picker open. Null = none; one at a time. */
    var picking by remember { mutableStateOf<UInt?>(null) }

    // Polled rather than pushed: the roster is folded on the radio's read thread and the
    // only thing this screen needs is to be roughly current. One second matches the header.
    LaunchedEffect(radio) {
        while (true) {
            attached = radio.attached
            devices = runCatching { radio.devices() }.getOrDefault(emptyList())
            nodes = runCatching { radio.nodes() }.getOrDefault(emptyList())
            links = runCatching { radio.links() }.getOrDefault(emptyMap())
            prefs = runCatching { radio.prefs() }.getOrDefault(com.oshi.desktop.store.TransportPreferences.Values())
            fixedPos = runCatching { radio.fixedPosition() }.getOrNull()
            myName = runCatching { radio.myNodeName() }.getOrNull()
            traffic = runCatching { radio.traffic() }.getOrDefault(emptyList())
            reportedRegion = runCatching { radio.reportedRegion() }.getOrNull()
            blocked = runCatching { radio.blockedReason() }.getOrNull()
            beaconOn = runCatching { radio.beaconEnabled }.getOrDefault(false)
            // __DESKTOP_LORA_BEACON_2026_10_28__ The beacon has no clock of its own; it
            // rides this poll. One second is far finer than the 15-minute floor, and the
            // policy — shared byte for byte with both phones — is what decides, so ticking
            // often is free. It transmits nothing unless the switch is on, a position is
            // stored and a radio is attached.
            beaconWhy = runCatching { radio.beaconTick() }
                .getOrDefault(com.oshi.desktop.lora.LoRaPositionBeacon.Decision.DISABLED)
            delay(1_000)
        }
    }

    Column(
        Modifier.fillMaxSize().background(OshiTheme.surface).verticalScroll(rememberScrollState()).padding(OshiTheme.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {

            PaneHeading(
                "Radio",
                "A Meshtastic node on USB or on the network. Two lanes, and the difference matters: " +
                    "stock Meshtastic text is PLAINTEXT that every client in range can read, while an " +
                    "OSHI message to a contact is end-to-end encrypted and needs no internet at all. " +
                    "Both go in both directions.",
            )

            notice?.let {
                Spacer(Modifier.height(OshiTheme.md))
                PaneCard { Text(it, style = OshiTheme.typography.bodySmall, color = Ink.soft) }
            }

            // ---------------------------------------------------------------- attach
            Spacer(Modifier.height(OshiTheme.lg))
            PaneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (attached) "Attached" else "Not attached",
                        style = OshiTheme.typography.titleSmall,
                        color = if (attached) Ink.strong else Ink.soft,
                    )
                    Spacer(Modifier.fillMaxWidth(0.02f))
                    if (attached) TextAction("Detach") { radio.detach(); notice = "Detached." }
                }
                Spacer(Modifier.height(OshiTheme.sm))

                Text("Plugged in", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                if (devices.isEmpty()) {
                    Text(
                        if (LoRaSerial.isSupported()) "No USB serial device found under /dev. Plug the radio in."
                        else "This build attaches over the network only; a Windows COM port is not a file.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                } else {
                    for (device in devices) {
                        TextAction(device.path) {
                            notice = if (radio.attachSerial(device.path)) "Attaching to ${device.path}…"
                            else "That device path was refused."
                        }
                    }
                }

                Spacer(Modifier.height(OshiTheme.sm))
                Hairline()
                Spacer(Modifier.height(OshiTheme.sm))
                Text("Or over the network (port ${com.oshi.desktop.lora.LoRaAttach.TCP_PORT})", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = host,
                        onValueChange = { host = it },
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.fillMaxWidth(0.6f),
                        decorationBox = { inner ->
                            if (host.isEmpty()) Text("meshtastic.local", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                            inner()
                        },
                    )
                    TextAction("Attach") {
                        notice = if (host.isNotBlank() && radio.attachTcp(host.trim())) "Attaching to ${host.trim()}…"
                        else "That host was refused: no spaces, and a port from 1 to 65535."
                    }
                }
            }

            // ---------------------------------------------------------------- send
            Spacer(Modifier.height(OshiTheme.lg))
            PaneCard {
                Text("Say something on the mesh", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(
                    "Broadcast as stock Meshtastic text. This is NOT encrypted by OSHI: the channel key " +
                        "is public, so every Meshtastic client in range can read it. That is what makes it " +
                        "interoperable, and why it is here rather than in a conversation.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                Spacer(Modifier.height(OshiTheme.sm))
                BasicTextField(
                    value = outgoing,
                    onValueChange = { outgoing = it },
                    textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                    cursorBrush = SolidColor(OshiTheme.brand),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (outgoing.isEmpty()) Text("Text", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                        inner()
                    },
                )
                Spacer(Modifier.height(OshiTheme.xs))
                val bytes = outgoing.toByteArray(Charsets.UTF_8).size
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        "Broadcast",
                        enabled = attached && bytes in 1..LoRaProto.INTEROP_TEXT_LIMIT,
                    ) {
                        notice = if (radio.send(outgoing)) {
                            outgoing = ""
                            "Handed to the radio. Plaintext — anyone in range can read it."
                        } else {
                            "Not sent."
                        }
                    }
                    Text(
                        // Over the limit is refused, never truncated: a stock client has no
                        // reassembler for OSHI's chunk header, so a long text would arrive in
                        // pieces nobody joins.
                        if (bytes > LoRaProto.INTEROP_TEXT_LIMIT) "$bytes bytes — over the ${LoRaProto.INTEROP_TEXT_LIMIT}-byte limit for one frame"
                        else "$bytes / ${LoRaProto.INTEROP_TEXT_LIMIT} bytes",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                }
            }

            // ---------------------------------------------------------------- the two switches
            Spacer(Modifier.height(OshiTheme.lg))
            PaneCard {
                Text("When a message goes out", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(
                    "These are two different questions, and the second one is not a stronger " +
                        "version of the first.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                Spacer(Modifier.height(OshiTheme.sm))

                TextAction(
                    (if (prefs.preferRadio) "☑" else "☐") + "  Prefer my radio over the internet"
                ) {
                    radio.setPrefs(prefs.copy(preferRadio = !prefs.preferRadio))
                    prefs = radio.prefs()
                }
                Text(
                    "An order, not a ban: the radio is tried first, and if nothing comes back " +
                        "within ${com.oshi.desktop.lora.DesktopTransportPolicy.ACK_WINDOW_MS / 1000} seconds the " +
                        "internet picks the message up. A broadcast is never acknowledged, so silence " +
                        "is the only signal there is.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )

                Spacer(Modifier.height(OshiTheme.sm))
                Hairline()
                Spacer(Modifier.height(OshiTheme.sm))

                TextAction(
                    (if (prefs.neverOverInternet) "☑" else "☐") + "  Never send over the internet"
                ) {
                    radio.setPrefs(prefs.copy(neverOverInternet = !prefs.neverOverInternet))
                    prefs = radio.prefs()
                }
                Text(
                    "A ban, and it outranks the setting above. The relay is not used as a " +
                        "fallback, not after the timeout, not when the radio fails. A message with " +
                        "nowhere to go WAITS rather than being quietly re-routed — which is the " +
                        "point of asking for it.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )

                Spacer(Modifier.height(OshiTheme.sm))
                Hairline()
                Spacer(Modifier.height(OshiTheme.sm))

                // __DESKTOP_LORA_MEDIA_2026_10_28__ The ceiling is stated up front rather
                // than discovered by refusal: three compounding base64 layers over a
                // 2160-byte frame budget leave about 170 bytes. A toggle that silently
                // refused almost everything would be worse than none.
                TextAction(
                    (if (prefs.mediaOverLoRa) "☑" else "☐") + "  Let attachments use the radio"
                ) {
                    radio.setPrefs(prefs.copy(mediaOverLoRa = !prefs.mediaOverLoRa))
                    prefs = radio.prefs()
                }
                Text(
                    "Off by default, and worth knowing why: an attachment sits under three " +
                        "layers of base64 inside one radio message, so the real ceiling is about " +
                        "${com.oshi.desktop.lora.LoRaMediaWire.maxMediaBytes()} bytes. That is an icon or a contact card — not a " +
                        "photo. Anything larger is refused with its size rather than cut short, " +
                        "because a truncated attachment arrives as an empty bubble.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
            }

            // ------------------------------------------- This radio will not transmit
            //
            // __DESKTOP_LORA_RADIO_STATE_2026_10_29__ iOS's reason for reading these back:
            // "a node with tx disabled looks identical to a healthy one everywhere except
            // here". Until now this pane would call such a radio "Attached" and leave the
            // owner wondering why nothing arrived.
            //
            // Drawn BEFORE everything it invalidates — naming, position, messages — because
            // a warning under three cards that do not work is a warning read too late.
            blocked?.let { why ->
                Spacer(Modifier.height(OshiTheme.lg))
                PaneCard {
                    Text("This radio will not transmit", style = OshiTheme.typography.titleSmall, color = OshiTheme.danger)
                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text(why, style = OshiTheme.typography.bodySmall, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text(
                        "The node reports this about itself, so it is not a guess about whether a " +
                            "setting we sent landed. Everything below will look like it worked and " +
                            "nothing will leave the radio.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                }
            }

            // --------------------------------------------------- Region and channel
            //
            // __DESKTOP_LORA_REGION_UI_2026_10_29__ The two writes that decide whether the
            // radio transmits at all and which mesh it lands on.
            //
            // Drawn immediately under the "will not transmit" banner, because that banner's
            // own wording — "Choose one and try again" — had nothing in this window to
            // click. Both calls existed on the client since 2026-10-28 and were reachable
            // only from the text console, so a person who attached a radio straight from
            // the flasher could read why it was silent and could not fix it with a mouse.
            if (attached) {
                Spacer(Modifier.height(OshiTheme.lg))
                PaneCard {
                    Text("Region and channel", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text(
                        "A node straight from the flasher boots with no region: it pairs, reports " +
                            "itself connected, and puts nothing on the air. Setting a region is what " +
                            "makes it transmit — and it is the law about which frequencies you may " +
                            "use where you are, so it is yours to choose, not ours to guess.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    reportedRegion?.let { r ->
                        Spacer(Modifier.height(OshiTheme.xxs))
                        Text("The node currently reports: ${r.label}",
                            style = OshiTheme.typography.bodySmall, color = Ink.strong)
                    }

                    Spacer(Modifier.height(OshiTheme.sm))
                    TextAction("Region: ${pickedRegion?.label ?: "Choose one"}") {
                        choosingRegion = !choosingRegion
                    }
                    if (choosingRegion) {
                        // `selectable`, not `entries`: UNSET is the state this card rescues a
                        // radio FROM, and offering it would be offering to silence a working one.
                        for (r in com.oshi.desktop.lora.LoRaAdmin.Region.selectable) {
                            TextAction(r.label) { pickedRegion = r; choosingRegion = false }
                        }
                    }

                    Spacer(Modifier.height(OshiTheme.xs))
                    TextAction("Range: ${pickedPreset.label}") { choosingPreset = !choosingPreset }
                    if (choosingPreset) {
                        for (p in com.oshi.desktop.lora.LoRaAdmin.ModemPreset.entries) {
                            TextAction(p.label) { pickedPreset = p; choosingPreset = false }
                        }
                    }

                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text(
                        "Writing a region replaces the whole radio configuration — the range " +
                            "preset above and a hop limit of " +
                            "${com.oshi.desktop.lora.LoRaAdmin.DEFAULT_HOP_LIMIT} go with it. " +
                            "Everyone you want to reach has to be on the same region and range.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    Spacer(Modifier.height(OshiTheme.sm))
                    PrimaryButton("Set region", enabled = pickedRegion != null) {
                        val r = pickedRegion
                        notice = if (r != null && radio.setRegion(r, pickedPreset))
                            "Asked the radio for ${r.label}, ${pickedPreset.label}. The line above " +
                                "updates when the node reports its own config back."
                        else "The radio did not take it — see the log."
                    }

                    Spacer(Modifier.height(OshiTheme.lg))
                    Text("Which mesh", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text(
                        "Leave both empty to go back to the stock " +
                            "${com.oshi.desktop.lora.LoRaAdmin.Channel.DEFAULT_NAME} channel every " +
                            "unconfigured Meshtastic node is on. A name with no key is still that " +
                            "public key: this replaces the channel wholesale and never keeps the " +
                            "key you had, so leaving the key out is a choice, not an omission.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    Spacer(Modifier.height(OshiTheme.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = channelField,
                            onValueChange = { channelField = it },
                            textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                            cursorBrush = SolidColor(OshiTheme.brand),
                            modifier = Modifier.width(160.dp),
                            decorationBox = { inner ->
                                if (channelField.isEmpty()) Text(
                                    com.oshi.desktop.lora.LoRaAdmin.Channel.DEFAULT_NAME,
                                    style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                                inner()
                            },
                        )
                        BasicTextField(
                            value = pskField,
                            onValueChange = { pskField = it },
                            textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                            cursorBrush = SolidColor(OshiTheme.brand),
                            modifier = Modifier.width(220.dp),
                            decorationBox = { inner ->
                                if (pskField.isEmpty()) Text("Key (base64, optional)",
                                    style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                                inner()
                            },
                        )
                        // The same live byte count the name card shows, for the same reason:
                        // the firmware's field is eleven BYTES and one emoji is four of them.
                        val clampedChannel =
                            com.oshi.desktop.lora.LoRaAdmin.Channel.clampName(channelField)
                        if (channelField.isNotBlank()) Text(
                            "${clampedChannel.toByteArray(Charsets.UTF_8).size}/" +
                                "${com.oshi.desktop.lora.LoRaAdmin.Channel.NAME_MAX_BYTES} B" +
                                if (clampedChannel != channelField.trim()) " → \"$clampedChannel\"" else "",
                            style = OshiTheme.typography.bodySmall, color = Ink.soft,
                        )
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                    PrimaryButton("Set channel") {
                        notice = if (radio.setChannel(channelField, pskField)) {
                            val shown = com.oshi.desktop.lora.LoRaAdmin.Channel
                                .clampName(channelField)
                                .ifEmpty { com.oshi.desktop.lora.LoRaAdmin.Channel.DEFAULT_NAME }
                            channelField = ""; pskField = ""
                            "Asked the radio to move onto $shown."
                        } else "That key is not one the firmware takes — base64 of 1, 16 or 32 bytes."
                    }
                }
            }

            // ------------------------------------------------------------ Name the radio
            //
            // __DESKTOP_LORA_SET_OWNER_2026_10_29__ Both phones have had this; this client
            // could set the node's region and channel but not its NAME, so a desktop
            // owner's radio kept its factory label on every other client's list and on
            // public mesh maps.
            if (attached) {
                Spacer(Modifier.height(OshiTheme.lg))
                PaneCard {
                    Text("Name this radio", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text(
                        "What other radios call it — in their lists, on the node's own screen, and on " +
                            "public mesh maps. The short name is what fits that little screen: four " +
                            "BYTES, so four letters, two accented ones, or a single emoji.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    myName?.let {
                        Spacer(Modifier.height(OshiTheme.xxs))
                        Text("Currently: $it", style = OshiTheme.typography.bodySmall, color = Ink.strong)
                    }
                    // REPORTED by the node, not the region we asked for — a write can be
                    // refused or never arrive, and insisting on the request is how a client
                    // ends up disagreeing with the radio in front of it.
                    reportedRegion?.let { r ->
                        Text("Region, as the node reports it: ${r.label}",
                            style = OshiTheme.typography.bodySmall, color = Ink.soft)
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = longField,
                            onValueChange = { longField = it },
                            textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                            cursorBrush = SolidColor(OshiTheme.brand),
                            modifier = Modifier.width(220.dp),
                            decorationBox = { inner ->
                                if (longField.isEmpty()) Text("Name", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                                inner()
                            },
                        )
                        BasicTextField(
                            value = shortField,
                            onValueChange = { shortField = it },
                            textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                            cursorBrush = SolidColor(OshiTheme.brand),
                            modifier = Modifier.width(90.dp),
                            decorationBox = { inner ->
                                if (shortField.isEmpty()) Text("Short", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                                inner()
                            },
                        )
                        // The live byte count, because "four bytes" is not something a
                        // person can count in their head once an emoji is involved.
                        val clamped = com.oshi.desktop.lora.LoRaAdmin.clampShortName(
                            shortField.ifBlank { longField })
                        Text(
                            "${clamped.toByteArray(Charsets.UTF_8).size}/4 B" +
                                if (clamped != shortField.ifBlank { longField }.trim()) " → \"$clamped\"" else "",
                            style = OshiTheme.typography.bodySmall, color = Ink.soft,
                        )
                        PrimaryButton("Set name", enabled = longField.isNotBlank()) {
                            notice = if (radio.setOwner(longField, shortField)) {
                                longField = ""; shortField = ""
                                "Asked the radio to rename itself. Other nodes learn it when it next announces."
                            } else "The radio refused the name — see the log."
                        }
                    }
                    Spacer(Modifier.height(OshiTheme.xs))
                    TextAction("Adopt a factory-default node") {
                        notice = if (radio.adoptIfFactoryDefault())
                            "Renamed to the OSHI name. A node somebody already named is left alone."
                        else "Nothing to adopt — this node already has a name somebody chose."
                    }
                }
            }

            // ------------------------------------------------------- Share my position
            //
            // __DESKTOP_LORA_BEACON_2026_10_28__ The phones beacon a GPS fix. This machine
            // has no GPS, which is why I first concluded it could not have a beacon at all
            // — a conclusion that answered a narrower question than the one asked. A Mac
            // has no sensor; it has an owner who knows where it is. Meshtastic covers
            // exactly this with `fixed_position`, and a desk not moving is the reason
            // stating it once is enough.
            //
            // The wording here has to carry what the switch EXPOSES, not just what it
            // does: the frame goes out in cleartext to every radio in range, on a channel
            // whose key is published in Meshtastic's own source.
            Spacer(Modifier.height(OshiTheme.lg))
            PaneCard {
                Text("Share where this machine is", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(
                    "Other radios can then draw this node on their map, the way Meshtastic does. " +
                        "This computer has no GPS, so the position is the one you type — nothing here " +
                        "guesses it from WiFi or your address, because that would be publishing a " +
                        "location you never gave. If the machine moves, the position is simply wrong " +
                        "until you change it.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                Spacer(Modifier.height(OshiTheme.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = latField,
                        onValueChange = { latField = it },
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.width(140.dp),
                        decorationBox = { inner ->
                            if (latField.isEmpty()) Text("Latitude", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                            inner()
                        },
                    )
                    BasicTextField(
                        value = lonField,
                        onValueChange = { lonField = it },
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.width(140.dp),
                        decorationBox = { inner ->
                            if (lonField.isEmpty()) Text("Longitude", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                            inner()
                        },
                    )
                    val la = latField.trim().toDoubleOrNull()
                    val lo = lonField.trim().toDoubleOrNull()
                    PrimaryButton("Set", enabled = la != null && lo != null) {
                        notice = if (la != null && lo != null && radio.setFixedPosition(la, lo)) {
                            fixedPos = radio.fixedPosition()
                            latField = ""; lonField = ""
                            "Position saved. Nothing is broadcast until you turn the beacon on."
                        } else {
                            // Named rather than generic: 0/0 is the one a blank form and a
                            // GPS with no lock both produce, and it is refused on purpose.
                            "Not a position on Earth. Latitude is -90 to 90, longitude -180 to 180, and 0/0 is not a place."
                        }
                    }
                }
                fixedPos?.let { (la, lo) ->
                    Spacer(Modifier.height(OshiTheme.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Saved: %.5f, %.5f".format(la, lo),
                            style = OshiTheme.typography.bodySmall, color = Ink.strong,
                        )
                        TextAction("Forget") {
                            radio.clearFixedPosition()
                            fixedPos = null; beaconOn = false
                            notice = "Position forgotten, and the beacon is off."
                        }
                    }
                }
                Spacer(Modifier.height(OshiTheme.sm))
                Hairline()
                Spacer(Modifier.height(OshiTheme.sm))
                TextAction((if (beaconOn) "☑" else "☐") + "  Beacon this position over the radio") {
                    val want = !beaconOn
                    if (!radio.setBeaconEnabled(want)) {
                        // The switch REFUSES rather than turning on with nothing to send: a
                        // control reading "on" while no frame leaves is the indicator
                        // failure this codebase keeps paying for.
                        notice = "Set a position first — there is nothing to beacon yet."
                    } else {
                        beaconOn = radio.beaconEnabled
                        notice = if (beaconOn) {
                            "Beacon on. Sent now, then about every 15 minutes while OSHI runs."
                        } else "Beacon off."
                    }
                }
                Text(
                    "Off by default. This tells the attached radio where it is; the radio then " +
                        "announces it UNENCRYPTED to every node in range — up to tens of kilometres, " +
                        "including people you have never met — on the default channel, whose key is " +
                        "public. That is what a mesh position is, and it is why this is a switch " +
                        "rather than something that happens on its own. Two things worth knowing: the " +
                        "RADIO decides how often it announces, not OSHI; and the position is stored " +
                        "on the radio, so it stays there for any app that connects to it until you " +
                        "press Forget.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                // Why nothing is going out, when that is the case. Five of the six reasons
                // are the person's to fix, so naming them beats a silent switch.
                if (beaconOn) {
                    val why = when (beaconWhy) {
                        com.oshi.desktop.lora.LoRaPositionBeacon.Decision.NO_RADIO ->
                            "Waiting for a radio — attach one above."
                        com.oshi.desktop.lora.LoRaPositionBeacon.Decision.NO_FIX,
                        com.oshi.desktop.lora.LoRaPositionBeacon.Decision.NO_PERMISSION ->
                            "No position set."
                        com.oshi.desktop.lora.LoRaPositionBeacon.Decision.TOO_SOON,
                        com.oshi.desktop.lora.LoRaPositionBeacon.Decision.STILL_HERE ->
                            // Deliberately not "other radios can see you": this says what
                            // was done, which is all that is known from here. A broadcast
                            // is never acknowledged and nothing reports back.
                            // The six hours are the FIRMWARE's rule, not ours, and they
                            // are why this says a number instead of "sent". PositionModule
                            // treats `fixed_position` as stationary and clamps the
                            // announcement to `default_position_stationary_broadcast_secs`
                            // = 6 h, overriding `position_broadcast_secs`. Lowering that
                            // setting changes nothing. Somebody watching a neighbour's map
                            // after flipping this switch would otherwise conclude it is
                            // broken — which is exactly what I concluded, three times,
                            // before reading the firmware.
                            "The radio has been told. It announces a fixed position about every 6 hours, so other radios may take that long to show you."
                        com.oshi.desktop.lora.LoRaPositionBeacon.Decision.SEND ->
                            "Telling the radio…"
                        com.oshi.desktop.lora.LoRaPositionBeacon.Decision.DISABLED -> null
                    }
                    why?.let {
                        Spacer(Modifier.height(OshiTheme.xxs))
                        Text(it, style = OshiTheme.typography.bodySmall, color = Ink.strong)
                    }
                }
            }

            // ---------------------------------------------------------------- OSHI message
            Spacer(Modifier.height(OshiTheme.lg))
            PaneCard {
                Text("Message a contact off-grid", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(
                    "End-to-end encrypted, over the radio, with no internet. Every OSHI radio in " +
                        "range receives the ciphertext and only this contact can open it — that is " +
                        "the addressing, so a broadcast is never acknowledged and this will say sent, " +
                        "not delivered.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                Spacer(Modifier.height(OshiTheme.sm))

                val eligible = contacts.filterNot { it.blocked }
                if (eligible.isEmpty()) {
                    Text("Add a contact first.", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                } else {
                    TextAction("To: ${oshiTo?.label ?: "Choose a contact"}") { choosingRecipient = !choosingRecipient }
                    if (choosingRecipient) {
                        for (contact in eligible) {
                            TextAction(contact.label) { oshiTo = contact; choosingRecipient = false }
                        }
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                    BasicTextField(
                        value = oshiText,
                        onValueChange = { oshiText = it },
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (oshiText.isEmpty()) Text("Message", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                            inner()
                        },
                    )
                    Spacer(Modifier.height(OshiTheme.xs))
                    val recipient = oshiTo
                    PrimaryButton(
                        "Send over the radio",
                        enabled = attached && recipient != null && oshiText.isNotBlank(),
                    ) {
                        if (recipient != null) {
                            notice = if (radio.sendOshi(recipient.address, oshiText)) {
                                oshiText = ""
                                "Encrypted and handed to the radio. Broadcasts are not acknowledged, so it will not report delivered."
                            } else {
                                "Not sent — see the radio log for the reason."
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------------- Live traffic
            //
            // __DESKTOP_LORA_TRAFFIC_2026_10_29__ iOS has had this and this pane showed
            // nothing: an owner could see WHICH nodes had been heard, never WHAT crossed.
            // An evening went into asking why a position never reached a second radio when
            // "did a portnum-3 frame leave at all?" was the whole question.
            //
            // SHAPES, never contents — direction, portnum, size, node. An OSHI envelope is
            // ciphertext this process is the only place for, and a stranger's plaintext
            // mesh message has no business on this screen either.
            if (attached && traffic.isNotEmpty()) {
                Spacer(Modifier.height(OshiTheme.lg))
                PaneCard {
                    Text("Live traffic", style = OshiTheme.typography.titleSmall, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text(
                        "The last frames in and out of the radio — what kind, how big, and with " +
                            "whom. Never what they said: an OSHI message is encrypted and stays that " +
                            "way, and a stranger's plain mesh text is not this screen's business.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    Spacer(Modifier.height(OshiTheme.sm))
                    for (e in traffic) {
                        Text(
                            e.summary,
                            style = OshiTheme.typography.bodySmall,
                            color = if (e.direction == "→") Ink.strong else Ink.soft,
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- nodes
            Spacer(Modifier.height(OshiTheme.lg))
            Text("Radios heard", style = OshiTheme.typography.titleSmall, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xs))
            if (nodes.isEmpty()) {
                EmptyPaneNote(
                    if (attached) "Nothing heard yet" else "No radio attached",
                    if (attached) "A node appears here when it is heard. Meshtastic counts a node as online for two hours after that."
                    else "Attach the radio above, then the nodes it hears appear here.",
                )
            } else {
                for (node in nodes) {
                    val nodeKey = "%08x".format(node.num.toLong())
                    val linked = links[nodeKey]
                    PaneCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.fillMaxWidth(0.72f)) {
                                Text(node.name, style = OshiTheme.typography.titleSmall, color = Ink.strong)
                                Spacer(Modifier.height(OshiTheme.xxs))
                                Text("!$nodeKey · ${node.num}", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                                // __DESKTOP_LORA_POSITION_2026_10_28__ A real distance only
                                // when BOTH ends have a fix. No fallback band is invented
                                // here: a distance to a node that never said where it is
                                // would be a made-up number, and so would one measured from
                                // a radio that has no lock of its own.
                                node.distanceKm?.let { km ->
                                    Spacer(Modifier.height(OshiTheme.xxs))
                                    Text(
                                        if (km < 1.0) "%.0f m away".format(km * 1000)
                                        else "%.1f km away".format(km),
                                        style = OshiTheme.typography.bodySmall,
                                        color = Ink.soft,
                                    )
                                }
                            }
                            TextAction("Copy id") { onCopy("!$nodeKey") }
                        }

                        Spacer(Modifier.height(OshiTheme.xs))
                        if (linked != null) {
                            val label = contacts.firstOrNull { it.address == linked }?.label ?: "${linked.take(12)}…"
                            Text(
                                "Its text goes to $label — unauthenticated, on the strength of the node number",
                                style = OshiTheme.typography.bodySmall,
                                color = Ink.strong,
                            )
                            Spacer(Modifier.height(OshiTheme.xxs))
                            TextAction("Remove") {
                                notice = if (radio.unlink(node.num)) "Unlinked. Its text goes back to its own radio thread."
                                else "Nothing to undo."
                            }
                        } else if (picking == node.num) {
                            Text("Whose radio is this?", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                            val eligible = contacts.filterNot { it.blocked }
                            if (eligible.isEmpty()) {
                                Text("Add a contact first.", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                            } else {
                                for (contact in eligible) {
                                    TextAction(contact.label) {
                                        notice = describe(radio.link(node.num, contact.address), contact.label)
                                        picking = null
                                    }
                                }
                            }
                            TextAction("Cancel") { picking = null }
                        } else {
                            TextAction("This radio is a contact…") { picking = node.num }
                        }
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                }
            }
            Spacer(Modifier.height(OshiTheme.xxl))
        }
    }
}

/**
 * Each refusal in its own words.
 *
 * A single "could not link" would be the worst of the five: the three refusals have
 * different causes and three different things a person can do next, and one of them
 * ("the ratchet already proved this is someone else") is a security answer that must not
 * read as a glitch.
 */
private fun describe(result: LoRaNodeBindings.LearnResult, label: String): String = when (result) {
    LoRaNodeBindings.LearnResult.BOUND ->
        "Linked to $label. Its stock Meshtastic text now appears in that conversation — " +
            "not authenticated, so treat it as coming from whoever holds that radio."
    LoRaNodeBindings.LearnResult.UNCHANGED -> "Already $label."
    LoRaNodeBindings.LearnResult.CONFLICT ->
        "Refused: this radio already PROVED it belongs to someone else, by sending an OSHI " +
            "envelope that decrypted. Evidence outranks a choice made here."
    LoRaNodeBindings.LearnResult.IGNORED_AMBIGUOUS ->
        "Refused: this radio's own evidence contradicted itself, so it routes nowhere. " +
            "Vouching for it cannot decide which claim was the lie."
    LoRaNodeBindings.LearnResult.REJECTED -> "Refused: that contact has no usable OSHI identity key."
}
