package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.gif.AnimatedGifImage
import com.oshi.desktop.gif.GiphyClient
import com.oshi.desktop.gif.LocalGifLibrary
import com.oshi.desktop.i18n.dt
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The GIF & sticker picker — the desktop port of iOS `StickerGifPicker.swift`
 * (`__LOCAL_GIF_LIBRARY_2026_09_13__`), same layout, same rules:
 *  - TWO tabs, GIFs and Stickers; no separate "GIPHY" tab — GIPHY results come FIRST inside
 *    each tab, the offline pack after them;
 *  - a category chip row ("All" + iOS's category order); choosing a category means "the
 *    offline pack", so remote results are hidden, as on iOS;
 *  - one search field over both sources (GIPHY debounced 500 ms, iOS `searchDebounce`);
 *  - GIPHY failing, over quota or unconfigured never empties the grid or shows an error: the
 *    grid simply IS the offline pack;
 *  - "Powered by GIPHY" only while GIPHY results are on screen (their licence).
 *
 * Choosing a cell hands the GIF bytes UNTOUCHED to [onPick] — iOS `processGIF` never
 * re-encodes ("compressing a GIF flattens it").
 */
@Composable
fun GifPicker(
    onPick: (bytes: ByteArray, fileName: String) -> Unit,
    onClose: () -> Unit,
    library: LocalGifLibrary = LocalGifLibrary.shared,
    giphy: GiphyClient = GiphyClient.shared,
) {
    var kind by remember { mutableStateOf(LocalGifLibrary.Kind.GIF) }
    var category by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val remote = remember { mutableStateListOf<GiphyClient.Item>() }
    var remotePage by remember { mutableIntStateOf(0) }
    var remoteDone by remember { mutableStateOf(false) }
    var remoteLoading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val showsGiphy = category == null && giphy.isConfigured
    val local = remember(kind, category, query) { library.search(query, kind, category) }
    val cellPx = with(LocalDensity.current) { CELL.roundToPx() }

    fun loadRemote(reset: Boolean) {
        if (!giphy.isConfigured) return
        if (reset) { remote.clear(); remotePage = 0; remoteDone = false }
        if (remoteDone || remotePage >= GiphyClient.MAX_PAGES) return
        val page = remotePage
        val term = query
        val k = if (kind == LocalGifLibrary.Kind.STICKER) GiphyClient.Kind.STICKER else GiphyClient.Kind.GIF
        remoteLoading = true
        scope.launch {
            if (page == 0 && term.isNotBlank()) delay(500)
            if (term != query) return@launch
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    if (term.isBlank()) giphy.feed(k, page) else giphy.search(term, k, offset = page * GiphyClient.PAGE_SIZE)
                }.getOrDefault(emptyList())
            }
            if (term != query) return@launch
            if (items.isEmpty()) remoteDone = true
            val known = remote.map { it.id }.toSet()
            remote += items.filter { it.id !in known }
            remotePage = page + 1
            remoteLoading = false
        }
    }

    LaunchedEffect(kind, query) { loadRemote(reset = true) }

    Column(
        Modifier
            .fillMaxWidth()
            .height(380.dp)
            .background(OshiTheme.background)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            Text(dt("desktop.gif.title"), fontWeight = FontWeight.SemiBold, color = Ink.strong, fontSize = 14.sp)
            Box(Modifier.weight(1f))
            Segment(dt("desktop.gif.tab.gifs"), kind == LocalGifLibrary.Kind.GIF) { kind = LocalGifLibrary.Kind.GIF; category = null }
            Segment(dt("desktop.gif.tab.stickers"), kind == LocalGifLibrary.Kind.STICKER) { kind = LocalGifLibrary.Kind.STICKER; category = null }
            Box(Modifier.weight(1f))
            Text(dt("desktop.gif.close"), color = OshiTheme.brand, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClose))
        }
        Box(
            Modifier.fillMaxWidth().clip(OshiTheme.radiusSm).background(OshiTheme.surface)
                .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        ) {
            if (query.isEmpty()) Text(dt("desktop.gif.search"), color = Ink.soft, fontSize = 13.sp)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = Ink.strong),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            Chip(dt("desktop.gif.category.all"), category == null) { category = null }
            // Category names are shown as iOS shows them: the manifest's own English labels.
            library.categories(kind).forEach { c -> Chip(c, category == c) { category = c } }
        }
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CELL),
                horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
                verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showsGiphy) {
                    items(remote, key = { "g:${it.id}" }) { item ->
                        Cell(enabled = !sending, onClick = {
                            sending = true
                            scope.launch {
                                val bytes = withContext(Dispatchers.IO) { giphy.download(item.sendUrl) }
                                sending = false
                                if (bytes != null && com.oshi.desktop.gif.AnimatedGif.isGif(bytes)) onPick(bytes, "giphy-${item.id}.gif")
                            }
                        }) {
                            AnimatedGifImage(
                                cacheKey = "giphy-preview:${item.id}", maxPx = cellPx, contentDescription = item.title.ifBlank { "GIF" },
                                modifier = Modifier.fillMaxSize(),
                                load = { giphy.download(item.previewUrl, maxBytes = 2 * 1024 * 1024) },
                            )
                        }
                        // The last remote cell asks for the next page, as on iOS.
                        if (item.id == remote.lastOrNull()?.id && !remoteLoading) LaunchedEffect(item.id) { loadRemote(reset = false) }
                    }
                }
                items(local, key = { "l:${it.kind}:${it.id}:${it.fileName}" }) { item ->
                    Cell(enabled = !sending, onClick = {
                        val bytes = library.bytes(item)
                        if (bytes != null) onPick(bytes, item.fileName)
                    }) {
                        AnimatedGifImage(
                            cacheKey = "pack:${library.pathOf(item)}", maxPx = cellPx,
                            contentDescription = item.tags.firstOrNull() ?: item.id,
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                            load = { library.bytes(item) },
                        )
                    }
                }
                if (local.isEmpty() && remote.isEmpty() && !remoteLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (library.isAvailable) dt("desktop.gif.empty") else dt("desktop.gif.pack_missing"),
                            color = Ink.soft, fontSize = 13.sp, modifier = Modifier.padding(OshiTheme.lg),
                        )
                    }
                }
                if (showsGiphy && remote.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        // Required by GIPHY's licence while its results are on screen.
                        Text("Powered by GIPHY", color = Ink.soft, fontSize = 11.sp, modifier = Modifier.padding(OshiTheme.sm))
                    }
                }
            }
        }
    }
}

private val CELL = 96.dp

@Composable
private fun Cell(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.height(CELL).clip(RoundedCornerShape(10.dp)).background(OshiTheme.surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Color.White else Ink.strong,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) OshiTheme.brand else OshiTheme.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Ink.strong else Ink.soft,
        modifier = Modifier.clip(OshiTheme.radiusSm).background(if (selected) OshiTheme.surface else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
