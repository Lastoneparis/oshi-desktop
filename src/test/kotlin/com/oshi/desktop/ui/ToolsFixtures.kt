package com.oshi.desktop.ui

import androidx.compose.runtime.Composable
import com.oshi.desktop.ui.components.AiPane
import com.oshi.desktop.ui.components.MapCamera
import com.oshi.desktop.ui.components.MapMarker
import com.oshi.desktop.ui.components.MapTiles
import com.oshi.desktop.ui.components.PINNED_MARKER_ID
import com.oshi.desktop.ui.components.PlacesPane
import com.oshi.desktop.ui.state.AiConsoleModel
import com.oshi.desktop.ui.state.PlacesModel
import java.io.File

/** Fabricated state for [ToolsScreens]: one entry per tool screen. */
object ToolsFixtures {

    /** Pre-fetched tiles for renders (`$OSHI_TOOLS_TILES`); with none the basemap is a grid. */
    private fun useTiles() {
        val dir = System.getenv("OSHI_TOOLS_TILES")?.let(::File)
        MapTiles.forceOffline = true
        com.oshi.desktop.net.DesktopCover.useFile(File("build/tools-render-cover.json"))
        if (dir != null) MapTiles.cacheDir = dir
        else MapTiles.cacheDir = File("build/no-tiles")
    }

    private fun places(hits: List<PlacesModel.Hit>, searched: Boolean = hits.isNotEmpty(), regions: List<String> = listOf("ch-geneva", "fr-haute-savoie")) =
        PlacesModel.PlacesState(
            mapsPath = "/home/demo/Library/Application Support/OSHI/maps",
            poiRegions = regions, streetRegions = regions,
            kind = PlacesModel.Kind.POI, query = if (hits.isEmpty()) "" else "pharmacie",
            hits = hits, searched = searched, busy = false, notice = null,
        )

    private val geneva = listOf(
        PlacesModel.Hit("Pharmacie de la Servette", "pharmacy · Rue de la Servette 31 · Mo-Fr 08:00-19:00", "ch-geneva", "46.211, 6.136", 46.2112, 6.1365, 820.0),
        PlacesModel.Hit("Pharmacie des Pâquis", "pharmacy · Rue de Berne 12", "ch-geneva", "46.210, 6.146", 46.2101, 6.1466, 640.0),
        PlacesModel.Hit("Pharmacie Principale Rive", "pharmacy · Rue du Rhône 98", "ch-geneva", "46.202, 6.151", 46.2022, 6.1509, 690.0),
        PlacesModel.Hit("Pharmacie de Plainpalais", "pharmacy · Boulevard Carl-Vogt 2", "ch-geneva", "46.198, 6.140", 46.1985, 6.1401, 700.0),
        PlacesModel.Hit("Pharmacie de Champel", "pharmacy · Avenue de Champel 23", "ch-geneva", "46.193, 6.153", 46.1932, 6.1534, 1500.0),
    )

    private val radioMarkers = listOf(
        MapMarker(PINNED_MARKER_ID, 46.2044, 6.1432, "Pinned position"),
        MapMarker("node-1", 46.2190, 6.1210, "ThinkNode M1 A", OshiTheme.success, 1800.0),
        MapMarker("node-2", 46.1890, 6.1650, "T114 roof", OshiTheme.success, 2600.0),
    )

    private fun addr(c: Char) = c.toString().repeat(43) + "="
    private const val SAFETY = "37021 88410 22957 61403 99812 04476 11223 34455 66778 89900 12345 67890"
    val contacts = listOf(
        com.oshi.desktop.ui.state.ContactRow(addr('a'), "aaaa…", "Inès Haddad", true, false, SAFETY),
        com.oshi.desktop.ui.state.ContactRow(addr('b'), "bbbb…", "Bob Martin", false, false, SAFETY),
        com.oshi.desktop.ui.state.ContactRow(addr('c'), "cccc…", "Yusuf", false, false, SAFETY),
    )

    fun radio(attached: Boolean, healthy: Boolean = true) = object : com.oshi.desktop.ui.components.LoRaPaneHost {
        private val now = System.currentTimeMillis()
        override val attached = attached
        override fun myPosition(): Pair<Double, Double>? = 46.2044 to 6.1432
        override fun devices() = listOf(
            com.oshi.desktop.lora.LoRaSerial.Device("/dev/cu.usbmodem21201", "ThinkNode M1"),
            com.oshi.desktop.lora.LoRaSerial.Device("/dev/cu.usbmodem21301", "Heltec T114"),
        )
        override fun nodes() = if (!attached) emptyList() else listOf(
            com.oshi.desktop.lora.LoRaNodeRoster.Node(0x766eb396u, "ThinkNode M1 A", now - 60_000, 46.2190, 6.1210, 2.3, -88, 0),
            com.oshi.desktop.lora.LoRaNodeRoster.Node(0x4b88a86du, "T114 roof", now - 14 * 60_000, 46.1890, 6.1650, 2.4, -104, 0),
            com.oshi.desktop.lora.LoRaNodeRoster.Node(0x51e37c4au, "Tracker", now - 3 * 3_600_000, null, null, null, -118, 2),
        )
        override fun links() = mapOf("766eb396" to addr('b'))
        override fun attachSerial(path: String) = true
        override fun attachTcp(host: String) = true
        override fun detach() {}
        override fun send(text: String) = true
        override fun sendOshi(recipient: String, text: String) = true
        override fun link(node: UInt, address: String) = com.oshi.desktop.lora.LoRaNodeBindings.LearnResult.BOUND
        override fun unlink(node: UInt) = true
        override fun conversationKeyFor(node: UInt) = com.oshi.desktop.lora.LoRaNodeBindings.fallbackConversationKey(node)
        override fun prefs() = com.oshi.desktop.store.TransportPreferences.Values(preferRadio = true)
        override fun setPrefs(values: com.oshi.desktop.store.TransportPreferences.Values) {}
        override fun fixedPosition() = 46.2044 to 6.1432
        override fun setFixedPosition(latitude: Double, longitude: Double) = true
        override fun clearFixedPosition() {}
        override val beaconEnabled = true
        override fun setBeaconEnabled(value: Boolean) = true
        override fun beaconTick() = com.oshi.desktop.lora.LoRaPositionBeacon.Decision.STILL_HERE
        override fun myNodeName() = "OSHI ThinkNode M1 B"
        override fun setOwner(longName: String, shortName: String) = true
        override fun adoptIfFactoryDefault() = true
        override fun reportedRegion() = if (healthy) com.oshi.desktop.lora.LoRaAdmin.Region.EU868 else null
        override fun blockedReason() = if (healthy) null else "This radio has no region set, so it will not transmit. Choose one above."
        override fun setRegion(region: com.oshi.desktop.lora.LoRaAdmin.Region, preset: com.oshi.desktop.lora.LoRaAdmin.ModemPreset) = true
        override fun setChannel(name: String, psk: String) = true
        override fun traffic() = listOf(
            com.oshi.desktop.lora.LoRaTrafficLog.Entry("→", 3L, 16, "ALL", now),
            com.oshi.desktop.lora.LoRaTrafficLog.Entry("←", 4L, 62, "!f8fb2a75", now),
            com.oshi.desktop.lora.LoRaTrafficLog.Entry("←", 67L, 28, "!49e4ca65", now),
            com.oshi.desktop.lora.LoRaTrafficLog.Entry("→", 256L, 180, "!4b88a86d", now),
            com.oshi.desktop.lora.LoRaTrafficLog.Entry("←", 1L, 24, "!a6d7c767", now),
        )
        override fun radioCard() = com.oshi.desktop.lora.LoRaRadioCard(
            nodeNum = 0x766eb397u, name = "OSHI ThinkNode M1 B", batteryLevel = 76, voltage = 3.98f,
            channelUtilization = 12.4f, airUtilTx = 3.1f, uptimeSeconds = 4 * 3600 + 1200,
            lastRssi = -97, lastSnr = 6.25f, lastPacketMs = now - 40_000,
            region = if (healthy) com.oshi.desktop.lora.LoRaAdmin.Region.EU868 else null,
            modemPreset = 0, txPowerDbm = 22, muted = false, noRegion = !healthy, nodesNow = 3,
        )
    }

    fun all(shot: (String, Int, Int, @Composable () -> Unit) -> Unit) {
        val lora = { a: Boolean, h: Boolean, tab: com.oshi.desktop.ui.components.RadioTab -> @Composable {
            androidx.compose.runtime.CompositionLocalProvider(com.oshi.desktop.ui.components.LocalRadioTab provides tab) {
                com.oshi.desktop.ui.components.LoRaPane(radio(a, h), contacts)
            }
        } }
        shot("radio-overview", 1280, 900, lora(true, true, com.oshi.desktop.ui.components.RadioTab.OVERVIEW))
        shot("radio-wide", 2560, 1300, lora(true, true, com.oshi.desktop.ui.components.RadioTab.OVERVIEW))
        shot("radio-narrow", 1024, 900, lora(true, false, com.oshi.desktop.ui.components.RadioTab.OVERVIEW))
        shot("radio-send", 1280, 900, lora(true, true, com.oshi.desktop.ui.components.RadioTab.SEND))
        shot("radio-settings", 1280, 1400, lora(true, true, com.oshi.desktop.ui.components.RadioTab.SETTINGS))
        shot("radio-traffic", 1280, 900, lora(true, true, com.oshi.desktop.ui.components.RadioTab.TRAFFIC))
        shot("radio-detached", 1280, 900, lora(false, true, com.oshi.desktop.ui.components.RadioTab.OVERVIEW))

        // ------------------------------------------------ scheduled, peers, covert, limits
        val groups = listOf(com.oshi.desktop.ui.state.ConversationRow("g1", "Team Desk", com.oshi.desktop.ui.state.ConversationKind.GROUP, 4, 0L, "11:12", "", 0))
        val sched = listOf(
            com.oshi.desktop.ui.state.ScheduledRow("s1", "Inès Haddad", "Happy birthday! 🎂 See you tonight.", "24/12/2026 08:00", "pending", false),
            com.oshi.desktop.ui.state.ScheduledRow("s2", "Team Desk", "Reminder: distribution starts at 7 at the north gate.", "20/11/2026 18:30", "pending", true),
            com.oshi.desktop.ui.state.ScheduledRow("s3", "Bob Martin", "Sent the boxes list.", "18/11/2026 09:00", "sent", false),
        )
        shot("scheduled", 1280, 900) { com.oshi.desktop.ui.components.ScheduledPane(sched, contacts, groups) }
        shot("scheduled-empty", 1024, 800) { com.oshi.desktop.ui.components.ScheduledPane(emptyList(), contacts, groups) }
        val peers = listOf(
            com.oshi.desktop.ui.components.PeerRow(addr('a'), "Inès's iPhone", "iOS", "192.168.1.23:7942", connected = true, dialable = true, hopCount = 0),
            com.oshi.desktop.ui.components.PeerRow(addr('b'), "Bob — Pixel 8", "Android", "192.168.1.31", connected = true, dialable = false, hopCount = 0),
            com.oshi.desktop.ui.components.PeerRow(addr('c'), "Studio Mac", "macOS", "192.168.1.40:7942", connected = false, dialable = true, hopCount = 1),
        )
        shot("peers", 1280, 900) { com.oshi.desktop.ui.components.PeersPane(peers, { _, _ -> true }) }
        shot("peers-empty", 1024, 800) { com.oshi.desktop.ui.components.PeersPane(emptyList(), { _, _ -> true }, meshRunning = { false }) }
        val covert = com.oshi.desktop.covert.CovertText(com.oshi.messenger.network.v2.OSHICryptoV2.generateX25519().priv)
        shot("covert", 1280, 900) { com.oshi.desktop.ui.components.CovertPane(covert, contacts, {}) }
        shot("limits", 1280, 1000) { com.oshi.desktop.ui.components.LimitsPane() }

        run {
            val fixedNow = 1_790_000_000_000L
            val base = com.oshi.desktop.ui.state.CallScreenModel.CallScreen(
                phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.INCOMING,
                peer = addr('a'), label = "Inès Haddad", initials = "IH", callId = "C1", outgoing = false,
            )
            fun call(name: String, w: Int, h: Int, screen: com.oshi.desktop.ui.state.CallScreenModel.CallScreen) =
                shot(name, w, h) { com.oshi.desktop.ui.components.CallOverlay(screen, {}, {}, {}, {}, {}, nowMs = { fixedNow }) }
            call("call-incoming", 1280, 800, base)
            call("call-outgoing", 1280, 800, base.copy(phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.OUTGOING, outgoing = true, ringingBack = true))
            val live = base.copy(
                phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.CONNECTED,
                connectedAtMs = fixedNow - 605_000, audio = com.oshi.desktop.ui.state.CallScreenModel.Audio.FLOWING,
                carrier = com.oshi.desktop.ui.state.CallCarrier.P2P,
                quality = com.oshi.desktop.ui.state.CallQuality.GOOD, latencyMs = 128,
                voiceEffect = com.oshi.desktop.call.media.CallVoiceEffects.Effect.NONE,
            )
            call("call-connected", 1280, 800, live)
            call("call-wide", 2560, 1300, live.copy(muted = true, voiceEffect = com.oshi.desktop.call.media.CallVoiceEffects.Effect.ROBOT))
            call("call-narrow", 1024, 700, live.copy(micBlocked = true))
            call("call-ended", 1280, 800, base.copy(phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.ENDED, endedKey = "call.ended.network", problem = "no microphone"))
        }

        useTiles()
        shot("map-results", 1280, 800) {
            PlacesPane(places(geneva), {}, {}, {}, extraMarkers = radioMarkers, camera = MapCamera(46.2044, 6.1432, 13))
        }
        shot("map-wide", 2560, 1300) {
            PlacesPane(places(geneva), {}, {}, {}, extraMarkers = radioMarkers, camera = MapCamera(46.2044, 6.1432, 14))
        }
        shot("map-noregions", 1024, 720) {
            PlacesPane(places(emptyList(), regions = emptyList()), {}, {}, {}, camera = MapCamera(46.5, 6.6, 5))
        }

        val noModel = AiConsoleModel.AiState(modelPath = null, nativeProblem = null, turns = emptyList(), busy = false, notice = null)
        shot("ai-setup", 1280, 800) { AiPane(noModel, {}, {}, {}, pickModel = { null }) }
        val ready = AiConsoleModel.AiState(
            modelPath = "/home/demo/models/qwen2.5-1.5b-instruct-q4_k_m.gguf", nativeProblem = null,
            turns = emptyList(), busy = false, notice = null,
        )
        shot("ai-empty", 1280, 800) { AiPane(ready, {}, {}, {}, pickModel = { null }) }
        val chat = ready.copy(
            turns = listOf(
                AiConsoleModel.Turn("Help me write a short message to cancel Thursday's meeting politely.",
                    "Of course. Here is a short, polite version:\n\n\"Hi all — I'm sorry, but I have to cancel Thursday's meeting. Could we find another slot next week? Thanks for understanding.\"", true, "Generated in 4.2 s on this computer"),
                AiConsoleModel.Turn("Now in French.", "", false, "Gave up after 60 s. Nothing partial is shown."),
            ),
            busy = true,
        )
        shot("ai-chat", 1280, 800) { AiPane(chat, {}, {}, {}, pickModel = { null }) }
    }
}
