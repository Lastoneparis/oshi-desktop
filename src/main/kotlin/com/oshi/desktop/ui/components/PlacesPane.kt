package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.PlacesModel
import com.oshi.desktop.ui.state.Severity

/**
 * __DESKTOP_TOOLS_2027__ Map — the phones' Map tab, desktop-shaped.
 *
 * A real map now: the same OSHI-rendered OpenStreetMap tiles the phones draw (see
 * [OshiTileMap], which also says when it refuses the network), with the offline place and
 * street search floating over it, the phones' category chips, markers for every result,
 * and the radio nodes that announced a position.
 *
 * What stays absent, and why, is one click away under the info button rather than a wall
 * of text: there is no GPS in a computer and none is faked (PARITY.md row 0.19), so no
 * "centre on me", no navigation and no check-in from here; regions are read from disk, not
 * downloaded.
 */
@Composable
fun PlacesPane(
    state: PlacesModel.PlacesState,
    onKind: (PlacesModel.Kind) -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    /** Category chip → search near the map centre. */
    onCategory: (String, Pair<Double, Double>?) -> Unit = { _, _ -> },
    /** Tells the model where the map is looking, so text results rank by distance. */
    onCenter: (Pair<Double, Double>?) -> Unit = {},
    /** Radio nodes and the pinned position, drawn under the results. */
    extraMarkers: List<MapMarker> = emptyList(),
    camera: MapCamera = remember { initialCamera(extraMarkers) },
) = ProvideLocaleDirection {
    var selected by remember { mutableStateOf<String?>(null) }
    var showTiles by remember { mutableStateOf(true) }
    var infoOpen by remember { mutableStateOf(false) }
    val hitMarkers = state.hits.mapIndexedNotNull { i, h -> if (h.lat != null && h.lon != null) MapMarker("hit$i", h.lat, h.lon, h.title) else null }
    // A new result set: frame the first result.
    LaunchedEffect(state.hits) {
        hitMarkers.firstOrNull()?.let { m -> selected = m.id; camera.flyTo(m.lat, m.lon, maxOf(camera.zoom, 13)) }
    }
    LaunchedEffect(camera.zoom, camera.cx, camera.cy) {
        onCenter(if (camera.zoom >= 10) camera.centerLat to camera.centerLon else null)
    }
    Box(modifier.fillMaxSize().background(OshiTheme.paneGround)) {
        OshiTileMap(
            camera, extraMarkers + hitMarkers, Modifier.fillMaxSize(),
            selectedId = selected, showTiles = showTiles,
            onMarker = { m -> selected = m.id },
        )

        // ------------------------------------------------------------ search panel
        Column(
            Modifier.align(Alignment.TopStart).padding(OshiTheme.lg).width(380.dp).heightIn(max = 720.dp)
                .glassFloating(OshiTheme.radiusXl, OshiTheme.surfaceElevated.copy(alpha = 0.97f))
                .padding(OshiTheme.lg),
            verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(32.dp).clip(OshiTheme.radiusSm).background(OshiTheme.brandGradient),
                    contentAlignment = Alignment.Center,
                ) { ToolIcon(ToolGlyph.MAP, androidx.compose.ui.graphics.Color.White, 18.dp) }
                Text(t("map.title"), style = ToolType.title, color = Ink.strong, modifier = Modifier.weight(1f))
                ToolIconButton(ToolGlyph.INFO, t("pc.map.limits.title")) { infoOpen = true }
            }
            if (!state.hasData) {
                ToolEmptyState(t("pc.map.noRegions.title"), t("pc.map.noRegions.body"), mascot = "thinking", size = 88.dp)
                Text(state.mapsPath, style = ToolType.mono, color = Ink.soft, modifier = Modifier.clip(OshiTheme.radiusSm).background(OshiTheme.surface).padding(OshiTheme.sm))
            } else {
                ToolTabs(
                    listOf(PlacesModel.Kind.POI, PlacesModel.Kind.STREET), state.kind,
                    label = { if (it == PlacesModel.Kind.POI) t("map.search.section.places") else t("map.search.section.streets_addresses") },
                    icon = { if (it == PlacesModel.Kind.POI) ToolGlyph.PIN else ToolGlyph.MAP },
                    onSelect = onKind,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                    ToolField(
                        state.query, onQuery,
                        if (state.kind == PlacesModel.Kind.POI) t("map.search.placeholder_short") else t("map.search_placeholder"),
                        Modifier.weight(1f).onEnter(onSearch), icon = ToolGlyph.SEARCH,
                    )
                    ToolIconButton(ToolGlyph.CHEVRON, t("search.title"), enabled = !state.busy && state.query.isNotBlank()) { onSearch() }
                }
                if (state.kind == PlacesModel.Kind.POI) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((wire, label) in categoryChips()) {
                            Text(
                                label, style = ToolType.caption, color = Ink.strong, maxLines = 1,
                                modifier = Modifier.clip(OshiTheme.pill).background(OshiTheme.surface)
                                    .clickable(role = Role.Button) { onCategory(wire, if (camera.zoom >= 9) camera.centerLat to camera.centerLon else null) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                Text(t("pc.map.regions", state.regionsFor.size, state.regionsFor.joinToString(", ")), style = ToolType.caption, color = Ink.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                state.notice?.let { ToolNotice(it.text, if (it.severity == Severity.ERROR) ToolTone.ERROR else ToolTone.INFO) }
                when {
                    state.busy -> Box(Modifier.fillMaxWidth().padding(OshiTheme.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = OshiTheme.brand, strokeWidth = 2.dp) }
                    state.hits.isNotEmpty() -> {
                        Text(t("pc.map.results", state.hits.size).uppercase(), style = ToolType.overline, color = Ink.soft)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            items(state.hits.withIndex().toList(), key = { it.index }) { (i, h) ->
                                ToolRow(
                                    h.title,
                                    subtitle = listOfNotNull(h.detail.ifBlank { null }, h.distanceM?.let(::distanceLabel)).bidiJoin().ifBlank { h.coords },
                                    icon = if (state.kind == PlacesModel.Kind.POI) ToolGlyph.PIN else ToolGlyph.MAP,
                                    selected = selected == "hit$i",
                                    onClick = {
                                        selected = "hit$i"
                                        if (h.lat != null && h.lon != null) camera.flyTo(h.lat, h.lon, maxOf(camera.zoom, 15))
                                    },
                                )
                            }
                        }
                    }
                    state.searched -> ToolEmptyState(t("map.no_results"), t("map.search.no_results_desc"), mascot = "thinking", size = 72.dp)
                    else -> ToolHint(t("map.search.empty_desc"))
                }
            }
        }

        // ------------------------------------------------------------ cover notice
        if (MapTiles.blockedByCover && showTiles) {
            Text(
                t("pc.map.tilesOffline"), style = ToolType.caption, color = Ink.strong,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = OshiTheme.lg).padding(start = 400.dp)
                    .glassFloating(OshiTheme.pill, OshiTheme.surfaceElevated).padding(horizontal = OshiTheme.md, vertical = 6.dp),
            )
        }

        // ------------------------------------------------------------ map controls
        Column(
            Modifier.align(Alignment.BottomEnd).padding(OshiTheme.lg)
                .glassFloating(OshiTheme.radiusMd, OshiTheme.surfaceElevated).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolIconButton(ToolGlyph.ZOOM_IN, t("pc.map.zoomIn")) { camera.zoomBy(1) }
            ToolIconButton(ToolGlyph.ZOOM_OUT, t("pc.map.zoomOut")) { camera.zoomBy(-1) }
            ToolIconButton(ToolGlyph.LAYERS, t("pc.map.basemap"), active = showTiles) { showTiles = !showTiles }
            extraMarkers.firstOrNull { it.id == PINNED_MARKER_ID }?.let { pin ->
                ToolIconButton(ToolGlyph.LOCATE, t("pc.map.pinned")) { selected = pin.id; camera.flyTo(pin.lat, pin.lon, maxOf(camera.zoom, 13)) }
            }
        }
        Text(
            "© OpenStreetMap", style = ToolType.overline, color = Ink.soft,
            modifier = Modifier.align(Alignment.BottomStart).padding(OshiTheme.sm)
                .clip(OshiTheme.radiusSm).background(OshiTheme.surfaceElevated.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        // Selected marker's label.
        (extraMarkers + hitMarkers).firstOrNull { it.id == selected }?.let { m ->
            Text(
                m.label, style = ToolType.bodyStrong, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = OshiTheme.lg).widthIn(max = 420.dp)
                    .glassFloating(OshiTheme.pill, OshiTheme.surfaceElevated).padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
            )
        }
    }
    if (infoOpen) OshiAlertDialog(
        onDismissRequest = { infoOpen = false },
        title = { ProvideLocaleDirection { Text(t("pc.map.limits.title"), style = ToolType.section) } },
        text = {
            ProvideLocaleDirection {
                Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    for ((g, k) in listOf(
                        ToolGlyph.LOCATE to t("pc.map.limits.gps"), ToolGlyph.MAP to t("pc.map.limits.route"),
                        ToolGlyph.DOWNLOAD to t("pc.map.limits.regions"), ToolGlyph.SHIELD to t("pc.map.limits.tiles"),
                    )) Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                        ToolIcon(g, OshiTheme.brand, 18.dp)
                        Text(k, style = ToolType.body, color = Ink.strong)
                    }
                    Text(state.mapsPath, style = ToolType.mono, color = Ink.soft)
                }
            }
        },
        confirmButton = { TextButton(onClick = { infoOpen = false }) { Text(t("common.ok")) } },
    )
}

const val PINNED_MARKER_ID = "pinned"

/** The phones' chips: wire category → label key. */
private fun categoryChips() = listOf(
    "pharmacy" to t("map.poi.chip.pharmacy"), "hospital" to t("map.poi.chip.hospital"), "restaurant" to t("map.poi.chip.restaurant"),
    "cafe" to t("map.poi.chip.cafe"), "supermarket" to t("map.poi.chip.supermarket"), "fuel" to t("map.poi.chip.fuel"),
    "atm" to t("map.poi.chip.atm"), "hotel" to t("map.poi.chip.hotel"), "train_station" to t("map.poi.chip.train_station"),
    "bus_station" to t("map.poi.chip.bus_station"), "parking" to t("map.poi.chip.parking"), "charging_station" to t("map.poi.chip.charging_station"),
)

private fun initialCamera(markers: List<MapMarker>): MapCamera {
    val first = markers.firstOrNull { it.id == PINNED_MARKER_ID } ?: markers.firstOrNull()
    return if (first != null) MapCamera(first.lat, first.lon, 12) else MapCamera(46.5, 6.6, 5)
}

private fun distanceLabel(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else "%.1f km".format(java.util.Locale.ROOT, m / 1000)

/** Enter in a single-line field runs [action]. */
internal fun Modifier.onEnter(action: () -> Unit): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { action(); true } else false
}

/** A one-line banner inside a scrolling pane — the same shape the thread's notice bar uses. */
@Composable
fun NoticeCard(text: String, error: Boolean) = ToolNotice(text, if (error) ToolTone.ERROR else ToolTone.SUCCESS)

/** A search field with a magnifier — shared by Places and anywhere else that searches. */
@Composable
fun SearchBox(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) = ToolField(value, onValue, placeholder, modifier.fillMaxWidth().onEnter(onSubmit), icon = ToolGlyph.SEARCH)
