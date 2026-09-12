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
import com.oshi.desktop.ui.components.AiPane
import com.oshi.desktop.ui.components.ConversationFilter
import com.oshi.desktop.ui.components.ConversationListPane
import com.oshi.desktop.ui.components.DestinationStrip
import com.oshi.desktop.ui.components.Hairline
import com.oshi.desktop.ui.components.NoConversationPane
import com.oshi.desktop.ui.components.MorePane
import com.oshi.desktop.ui.components.NewPane
import com.oshi.desktop.ui.components.PlacesPane
import com.oshi.desktop.ui.state.AiConsoleModel
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.ConversationRow
import com.oshi.desktop.ui.state.Destination
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.PlacesModel
import com.oshi.desktop.ui.state.ShellState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.LocalDate

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
            NewPane(demoState(), onStartConversation = {}, onCopy = {})
        }
        render(File(out, "04-more.png"), W, 1100) {
            MorePane(demoState(), onShow = {}, onSelect = {}, onSetBlocked = { _, _ -> }, onRename = { _, _ -> }, onCopy = {})
        }
        render(File(out, "05-ai.png"), W, H) {
            AiPane(demoAi(), onUseModel = {}, onAsk = {}, onForget = {})
        }
        render(File(out, "06-places-empty.png"), W, H) {
            PlacesPane(demoPlaces(), onKind = {}, onQuery = {}, onSearch = {})
        }

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

    private fun render(target: File, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
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
            val image = scene.render()
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
        destination = Destination.MESSAGES,
        contacts = listOf(
            ContactRow(addr('a'), "aaaa…", "Alice", true, false, SAFETY),
            ContactRow(addr('b'), "bbbb…", "Bob", false, false, SAFETY),
            ContactRow(addr('c'), "cccc…", "Charlie", false, true, SAFETY),
        ),
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
