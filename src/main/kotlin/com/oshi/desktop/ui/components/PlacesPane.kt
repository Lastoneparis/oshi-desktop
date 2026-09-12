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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.PlacesModel
import com.oshi.desktop.ui.state.Severity

/**
 * Places — the offline POI and street reader, and nothing that needs a satellite.
 *
 * ============================================================ WHAT IS DELIBERATELY ABSENT
 *
 * There is no map canvas, no "locate me", no route, and no check-in button. PARITY.md row
 * 0.19: the JDK has no GPS and none is faked here, so the phone's Map tab loses its three
 * headline features on this platform and the honest thing is to not draw controls for them.
 * The screen says which ones and why, once, at the bottom — rather than showing a greyed-out
 * compass, which still tells a user this app can find them.
 *
 * What remains is a genuine capability: the region databases the phones download are plain
 * SQLite, this opens them, and every answer on this screen came off local disk with no
 * network involved at all.
 */
@Composable
fun PlacesPane(
    state: PlacesModel.PlacesState,
    onKind: (PlacesModel.Kind) -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(OshiTheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(OshiTheme.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {

            PaneHeading(
                "Places",
                "Searches region files already on this disk. No network, no position, no navigation.",
            )

            if (!state.hasData) {
                PaneCard {
                    Text("No regions on this machine", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xs))
                    Text(
                        "This client does not download regions — there is no region manager here and " +
                            "this screen does not pretend to be one. It reads what is already in:",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    Spacer(Modifier.height(OshiTheme.md))
                    MonoBlock(state.mapsPath)
                    Spacer(Modifier.height(OshiTheme.md))
                    Text(
                        "Expected layout: regions/<id>/poi.db for points of interest, " +
                            "streets/<id>/streets.db for street names. Both are the same SQLite files " +
                            "the phones download.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                }
            } else {
                PaneCard {
                    SegmentedControl(
                        options = listOf(PlacesModel.Kind.POI, PlacesModel.Kind.STREET),
                        selected = state.kind,
                        label = { if (it == PlacesModel.Kind.POI) "Points of interest" else "Streets" },
                        onSelect = onKind,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(OshiTheme.lg))
                    SearchBox(
                        value = state.query,
                        onValue = onQuery,
                        placeholder = if (state.kind == PlacesModel.Kind.POI) "Pharmacy, café, hospital…" else "Street name…",
                        onSubmit = onSearch,
                    )
                    Spacer(Modifier.height(OshiTheme.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(if (state.busy) "Searching…" else "Search", enabled = !state.busy && state.query.isNotBlank(), onClick = onSearch)
                        Spacer(Modifier.widthIn(min = OshiTheme.md))
                        Text(
                            "${state.regionsFor.size} region(s): ${state.regionsFor.joinToString(", ")}",
                            style = OshiTheme.typography.bodySmall,
                            color = Ink.soft,
                        )
                    }
                }

                state.notice?.let {
                    Spacer(Modifier.height(OshiTheme.lg))
                    NoticeCard(it.text, it.severity == Severity.ERROR)
                }

                if (state.hits.isNotEmpty()) {
                    Spacer(Modifier.height(OshiTheme.lg))
                    PaneCard {
                        Text("${state.hits.size} result(s)", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                        Spacer(Modifier.height(OshiTheme.md))
                        for (h in state.hits) {
                            HitRow(h)
                            Hairline()
                        }
                    }
                } else if (state.searched && !state.busy) {
                    Spacer(Modifier.height(OshiTheme.lg))
                    EmptyPaneNote(
                        "Nothing matched",
                        "No row in the region files on this disk contains that. The search covers the " +
                            "regions listed above and nothing beyond them — there is no lookup service " +
                            "behind this box.",
                    )
                }
            }

            Spacer(Modifier.height(OshiTheme.xl))

            // ------------------------------------------------------------ the honest footer
            PaneCard {
                Text("What this screen cannot do", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.sm))
                for (line in CANNOT) {
                    Row(Modifier.padding(vertical = OshiTheme.xxs)) {
                        Text("· ", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                        Text(line, style = OshiTheme.typography.bodySmall, color = Ink.soft)
                    }
                }
            }

            Spacer(Modifier.height(OshiTheme.xl))
        }
    }
}

private val CANNOT = listOf(
    "Find where you are. The JDK has no GPS and this client fakes none (PARITY.md row 0.19).",
    "Navigate. There is no routing engine on this platform.",
    "Share your location or check in. Both are receive-only on this client by construction — " +
        "a desktop machine has no useful position to send.",
    "Draw a map. These are name and coordinate databases, not tiles.",
)

@Composable
private fun HitRow(h: PlacesModel.Hit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = OshiTheme.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(h.title, style = OshiTheme.typography.bodyLarge, color = Ink.strong)
            if (h.detail.isNotBlank()) {
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(h.detail, style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(h.region, style = OshiTheme.typography.labelSmall, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(
                h.coords,
                style = OshiTheme.typography.labelSmall,
                color = Ink.soft,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
