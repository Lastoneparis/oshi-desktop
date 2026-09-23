package com.oshi.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.mail.FakeMailRelay
import com.oshi.desktop.mail.MailClient
import com.oshi.desktop.ui.components.AiPane
import com.oshi.desktop.ui.components.ConversationFilter
import com.oshi.desktop.ui.components.ConversationListPane
import com.oshi.desktop.ui.components.DestinationStrip
import com.oshi.desktop.ui.components.Hairline
import com.oshi.desktop.ui.components.NoConversationPane
import com.oshi.desktop.ui.components.MorePane
import com.oshi.desktop.ui.components.MailPane
import com.oshi.desktop.ui.components.BrowserPairingDialog
import com.oshi.desktop.ui.components.NewPane
import com.oshi.desktop.ui.components.PlacesPane
import com.oshi.desktop.ui.components.ScheduledPane
import com.oshi.desktop.ui.state.AiConsoleModel
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.ConversationRow
import com.oshi.desktop.ui.state.Destination
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.PlacesModel
import com.oshi.desktop.ui.state.MailModel
import com.oshi.desktop.ui.state.ShellState
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.Executor

/**
 * Renders this window's screens to PNG files — with no display, no window and no Simulator.
 *
 * ============================================================ WHY THIS EXISTS
 *
 * Every composable in `com.oshi.desktop.ui` is covered by nothing, on purpose: a test that
 * needs a display cannot run on the headless runners this project's Windows and Linux
 * evidence has to come from. That reasoning is sound and it leaves a real hole — the class
 * of defect it cannot catch is the one nobody finds by reading a diff: a pane that draws as
 * a single hairline because a `Canvas` had no width, a label sitting on top of another, a
 * whole section that measures to zero height and simply is not there.
 *
 * `ImageComposeScene` closes it. It rasterises a composition into a Skia surface with no
 * window, no AWT frame and no display server — so it runs anywhere the suite runs, INCLUDING
 * the Windows and Linux CI legs where this window has never been looked at by a human.
 *
 * ============================================================ IT IS A TOOL, NOT A TEST
 *
 * Deliberately a `main` behind a Gradle task rather than a `@Test`:
 *
 *   * A golden-image test on a UI this young would fail on every intentional change, and a
 *     suite that cries wolf gets its assertions deleted rather than read.
 *   * Rendering proves a composition MEASURES and PAINTS. It does not prove it is right.
 *     Calling it a test would claim the second, which is exactly the kind of overstatement
 *     this repository spends its comments preventing.
 *
 * So it produces contact sheets for a person to look at:
 *
 *     ./gradlew renderScreens          → build/screens/ (one PNG per screen)
 *
 * The state it feeds in is FABRICATED and says so in the data — "demo", obviously fake
 * addresses — because this renders panes in isolation and never touches a real account.
 */
object ScreenRenderer {

    /**
     * SIZES ARE IN DP, and [render] multiplies by the density.
     *
     * `ImageComposeScene` takes PIXELS. Handing it 1180 at density 2 composes a 590dp-wide
     * window — narrower than the real one, which silently truncated the segmented control's
     * labels in the first sheet this tool ever produced and made a layout bug look like a
     * rendering bug. The window opens at 1180x780dp (`rememberWindowState`), so that is what
     * gets drawn.
     */
    private const val W = 1180
    private const val H = 820
    private const val SIDEBAR = 344

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }

        // The chrome on its own: the destination strip over the sidebar, which is the one
        // composition no single pane covers and the one a reader compares to the capture.
        render(File(out, "00-shell-chrome.png"), W, H) {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().padding(vertical = OshiTheme.sm),
                ) {
                    DestinationStrip(
                        // THE SAME LOOKUP THE WINDOW USES, not a hand-typed copy.
                        // It used to be `listOf("Messages", "AI", "New", "Places", "More")`,
                        // so every contact sheet showed an English navigation strip above a
                        // translated window — the sheets were lying about the one row a
                        // reader checks first. A screenshot tool that does not go through
                        // the same code as the product is a screenshot tool that reassures.
                        options = com.oshi.desktop.ui.destinationLabels(),
                        selectedIndex = 0,
                        onSelect = {},
                    )
                }
                Hairline()
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                    ConversationListPane(
                        state = demoState(),
                        query = "",
                        onQuery = {},
                        half = ConversationFilter.Half.MESSAGES,
                        onHalf = {},
                        onSelect = {},
                        onShow = {},
                        today = LocalDate.now(),
                    )
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(1.dp).fillMaxSize()
                            .background(OshiTheme.separator),
                    )
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxSize()) {
                        NoConversationPane(demoState())
                    }
                }
            }
        }
        render(File(out, "01-messages-list.png"), SIDEBAR, H) {
            ConversationListPane(
                state = demoState(),
                query = "",
                onQuery = {},
                half = ConversationFilter.Half.MESSAGES,
                onHalf = {},
                onSelect = {},
                onShow = {},
                today = LocalDate.now(),
            )
        }
        // The header's reach badge CONNECTED (2 mesh peers, a radio hearing 3 nodes) and the
        // incoming live-share counter at two — the states 01 cannot show, since a demo client
        // has no peers, no radio and nobody sharing.
        render(File(out, "01b-messages-header-live.png"), SIDEBAR, H) {
            ConversationListPane(
                state = demoState(),
                query = "",
                onQuery = {},
                half = ConversationFilter.Half.MESSAGES,
                onHalf = {},
                onSelect = {},
                onShow = {},
                today = LocalDate.now(),
                badge = com.oshi.desktop.ui.state.NetworkBadge(meshPeers = 2, loraAttached = true, loraNodes = 3),
                incomingShares = listOf(
                    com.oshi.desktop.ui.state.IncomingShare("a", "Alice", System.currentTimeMillis() + 900_000),
                    com.oshi.desktop.ui.state.IncomingShare("b", "Bob", System.currentTimeMillis() + 1_800_000),
                ),
            )
        }
        // __DESKTOP_CALL_UI_2026_09_23__ The call screens, full window, from CallScreen values
        // (the model is tested separately; these are for a person to look at).
        run {
            val base = com.oshi.desktop.ui.state.CallScreenModel.CallScreen(
                phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.INCOMING,
                peer = addr('a'), label = "Alice Martin", initials = "AM", callId = "C1", outgoing = false,
            )
            val fixedNow = 1_790_000_000_000L
            fun shot(name: String, screen: com.oshi.desktop.ui.state.CallScreenModel.CallScreen) =
                render(File(out, name), W, H) {
                    com.oshi.desktop.ui.components.CallOverlay(screen, {}, {}, {}, {}, {}, nowMs = { fixedNow })
                }
            shot("12-call-incoming.png", base)
            shot("13-call-outgoing.png", base.copy(phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.OUTGOING, outgoing = true, ringingBack = true))
            shot("14-call-connected.png", base.copy(
                phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.CONNECTED,
                connectedAtMs = fixedNow - 83_000, audio = com.oshi.desktop.ui.state.CallScreenModel.Audio.FLOWING,
            ))
            shot("15-call-connected-muted-connecting-audio.png", base.copy(
                phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.CONNECTED, muted = true,
                connectedAtMs = fixedNow - 4_000, audio = com.oshi.desktop.ui.state.CallScreenModel.Audio.CONNECTING,
            ))
            shot("16-call-ended-audio-failed.png", base.copy(
                phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.ENDED, endedKey = "call.ended.network",
                problem = "this call could not open its audio devices (LineUnavailableException: no microphone), so it was ended rather than connected in silence",
            ))
            // PARITY.md row 2.1-v — the video call. Synthetic pictures (a test card for the
            // peer, a gradient for the self-view): the harness must not depend on a camera.
            fun card(w: Int, h: Int, hue: Float) = com.oshi.desktop.call.video.VideoImage(w, h, IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                val band = (x * 7 / w)
                val c = java.awt.Color.HSBtoRGB(hue + band / 7f, 0.55f, 0.35f + 0.5f * y / h)
                c or (0xFF shl 24)
            })
            val remoteImg = card(640, 360, 0.55f); val localImg = card(320, 180, 0.05f)
            val frames = object : com.oshi.desktop.ui.components.VideoFrames {
                override val remoteCount = 1L
                override fun remote() = remoteImg
                override val localCount = 1L
                override fun local() = localImg
            }
            fun videoShot(name: String, screen: com.oshi.desktop.ui.state.CallScreenModel.CallScreen) =
                render(File(out, name), W, H, frames = 3) {
                    com.oshi.desktop.ui.components.CallOverlay(screen, {}, {}, {}, {}, {}, nowMs = { fixedNow }, video = { frames })
                }
            val connected = base.copy(
                phase = com.oshi.desktop.ui.state.CallScreenModel.Phase.CONNECTED,
                connectedAtMs = fixedNow - 83_000, audio = com.oshi.desktop.ui.state.CallScreenModel.Audio.FLOWING,
            )
            shot("18-video-incoming.png", base.copy(video = true))
            videoShot("19-video-connected.png", connected.copy(video = true, cameraOn = true))
            videoShot("20-video-camera-refused.png", connected.copy(
                video = true, cameraOn = false,
                cameraProblem = "the camera would not open (Input/output error (-5)) — on macOS this is usually Camera permission: System Settings → Privacy & Security → Camera",
            ))
            videoShot("21-video-peer-camera-off.png", connected.copy(video = true, cameraOn = true, remoteCameraOff = true))
            shot("22-voice-call-video-request.png", connected.copy(upgradeRequested = true))
            render(File(out, "17-call-rating.png"), W, H) {
                com.oshi.desktop.ui.components.CallRatingPrompt({}, {})
            }
        }
        render(File(out, "02-groups-list.png"), SIDEBAR, H) {
            ConversationListPane(
                state = demoState(),
                query = "",
                onQuery = {},
                half = ConversationFilter.Half.GROUPS,
                onHalf = {},
                onSelect = {},
                onShow = {},
                today = LocalDate.now(),
            )
        }
        render(File(out, "03-new.png"), W, H) {
            NewPane(demoState(), onStartConversation = {}, onCopy = {}, onPickQrImage = { null })
        }
        render(File(out, "04-more.png"), W, 1100) {
            MorePane(demoState(), onShow = {}, onSelect = {}, onSetBlocked = { _, _ -> }, onRename = { _, _ -> }, onVerifySafetyNumber = { _, _ -> false }, onCopy = {})
        }
        render(File(out, "05-ai.png"), W, H) {
            AiPane(demoAi(), onUseModel = {}, onAsk = {}, onForget = {})
        }
        render(File(out, "06-places-empty.png"), W, H) {
            PlacesPane(demoPlaces(), onKind = {}, onQuery = {}, onSearch = {})
        }
        render(File(out, "11-scheduled.png"), W, H) {
            ScheduledPane(rows = demoState().scheduled, contacts = demoState().contacts, groups = demoState().conversations.filter { it.kind == ConversationKind.GROUP })
        }
        renderMail(out)

        println("[screens] wrote ${out.listFiles()?.size ?: 0} file(s) to ${out.absolutePath}")
        checkNotBlank(out)
    }

    /**
     * A rendered file that is one flat colour is the failure this tool exists to catch.
     *
     * PARITY's own history has the lesson written down: a blank-capture gate on standard
     * deviation ALONE let 13 black frames through. So this checks the mean AND the spread,
     * and it prints both rather than only complaining — a number a reader can compare beats
     * a pass/fail they cannot.
     */
    private fun checkNotBlank(dir: File) {
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            val img = javax.imageio.ImageIO.read(f) ?: continue
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (y in 0 until img.height step 3) {
                for (x in 0 until img.width step 3) {
                    val rgb = img.getRGB(x, y)
                    val lum = 0.299 * ((rgb shr 16) and 0xFF) + 0.587 * ((rgb shr 8) and 0xFF) + 0.114 * (rgb and 0xFF)
                    sum += lum; sumSq += lum * lum; n++
                }
            }
            val mean = sum / n
            val sd = kotlin.math.sqrt(sumSq / n - mean * mean)
            val verdict = if (sd < 3.0) "  <-- FLAT, nothing was drawn" else ""
            println("[screens] %-26s mean=%6.2f sd=%6.2f%s".format(f.name, mean, sd, verdict))
        }
    }

    private const val SCALE = 2f

    private fun render(target: File, wDp: Int, hDp: Int, frames: Int = 1, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = (wDp * SCALE).toInt(),
            height = (hDp * SCALE).toInt(),
            density = Density(SCALE),
        ) {
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        try {
            // More than one frame for compositions that load state in an effect (the video
            // stage polls its pictures in a LaunchedEffect): frame 1 launches, frame 2 draws.
            for (f in 1 until frames) { scene.render(f * 50_000_000L); Thread.sleep(60) }
            val image = scene.render(frames * 50_000_000L)
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("skia refused to encode ${target.name}")
            target.writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    // ---------------------------------------------------------------- fabricated state

    private const val ME = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun addr(seed: Char) = seed.toString().repeat(43) + "="

    private fun demoState() = ShellState(
        selfAddress = ME,
        selfAddressShort = "3q2+7w…",
        displayName = "Hugo",
        relayUrl = "https://oshi-messenger.com",
        gateOpen = true,
        deliveryReceiptsEnabled = true,
        readReceiptsEnabled = true,
        syncNotice = null,
        destination = Destination.MESSAGES,
        contacts = listOf(
            ContactRow(addr('a'), "aaaa…", "Alice", true, false, SAFETY),
            ContactRow(addr('b'), "bbbb…", "Bob", false, false, SAFETY),
            ContactRow(addr('c'), "cccc…", "Charlie", false, true, SAFETY),
        ),
        scheduled = emptyList(),
        shareText = "Add me on OSHI: oshi://add?key=$ME",
        pane = Pane.CONVERSATION,
        conversations = listOf(
            row(addr('a'), "Alice", "Second one, so the preview is not the first message.", 2, ConversationKind.DIRECT),
            row(addr('b'), "Bob", "Hey Bob, this is OSHI running on the desktop.", 0, ConversationKind.DIRECT),
            row("grp-demo", "Weekend plans", "Charlie: bringing the maps", 1, ConversationKind.GROUP),
            row("bot!news", "bot: news", "Daily digest — this lane is NOT encrypted", 0, ConversationKind.BOT),
            row("lora!deadbeef", "radio: deadbeef", "unverified radio text", 0, ConversationKind.LORA),
        ),
        selectedId = addr('a'),
        thread = null,
        draft = "",
        busy = false,
        notice = null,
    )

    private const val SAFETY =
        "12345 67890 12345 67890 12345 67890 12345 67890 12345 67890 12345 67890"

    private fun row(id: String, label: String, preview: String, unread: Int, kind: ConversationKind) =
        ConversationRow(
            id = id,
            label = label,
            kind = kind,
            messageCount = 4,
            lastActivityMs = System.currentTimeMillis() - 600_000,
            lastActivity = "11:12",
            preview = preview,
            unread = unread,
        )

    private fun demoAi() = AiConsoleModel.AiState(
        modelPath = null,
        nativeProblem = null,
        turns = emptyList(),
        busy = false,
        notice = null,
    )

    /**
     * A claimed mailbox over the in-process relay, never the real service. The direct
     * executor gives [MailPane] a settled account snapshot before Skia measures it.
     */
    private fun renderMail(out: File) {
        FakeMailRelay().use { relay ->
            val home = Files.createTempDirectory("oshi-mail-render").toFile()
            try {
                val vault = KeyVault.open(home.resolve("vault"), InMemorySecretStore(), null)
                val client = MailClient(DesktopIdentity.generate(), vault, relay.baseUrl)
                val mail = MailModel(
                    client,
                    Executor { it.run() },
                )
                mail.refresh()
                render(File(out, "10-mail-setup.png"), W, H) { MailPane(mail, pickFile = { null }) }
                mail.claim("demo") { require(it == null) }
                render(File(out, "07-mail.png"), W, H) { MailPane(mail, pickFile = { null }) }
                client.addAlias("demo.alt").getOrThrow()
                val envelope = """{"from":"Alice <alice@example.com>","to":"demo@${MailClient.MAIL_DOMAIN}","subject":"Welcome","date":"2026-09-22T00:00:00Z"}"""
                relay.addMessage(FakeMailRelay.StoredMessage(
                    id = "render-contact", folder = "inbox", seen = true,
                    sealedEnvelope = Base64.getEncoder().encodeToString(client.sealForSelf(envelope.toByteArray())),
                    sealedBody = client.sealForSelf(ByteArray(0)),
                ))
                mail.refresh()
                mail.newDraft()
                mail.editDraft(mail.state.editingDraft!!.copy(to = "ali"))
                render(File(out, "08-mail-compose.png"), W, H) { MailPane(mail, pickFile = { null }) }
                mail.clearEditingDraft()
                render(File(out, "09-mail-browser-pair.png"), W, H) {
                    BrowserPairingDialog(mail, mail.state, mail.state.account!!.address, onDismiss = {})
                }
            } finally {
                home.deleteRecursively()
            }
        }
    }

    private fun demoPlaces() = PlacesModel.PlacesState(
        mapsPath = "/Users/demo/Library/Application Support/OSHI/maps",
        poiRegions = emptyList(),
        streetRegions = emptyList(),
        kind = PlacesModel.Kind.POI,
        query = "",
        hits = emptyList(),
        searched = false,
        busy = false,
        notice = null,
    )
}
