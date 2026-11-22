package com.oshi.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.MediaItemRow
import com.oshi.desktop.ui.state.GalleryCategories
import com.oshi.desktop.ui.state.GalleryCategory
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * __DESKTOP_MEDIA_GALLERY_2026_10_29__ Everything this conversation has sent or received, in
 * one grid — iOS's `MediaGalleryView`, Android's "Media Gallery" menu item.
 *
 * Closed the last "real, still open" item from the iOS file sweep (PARITY row 0.0) that was a
 * capability rather than a schedule: this client could OPEN any attachment and had no way to
 * find one without scrolling the whole thread.
 *
 * ## It reuses, and that is the point
 *
 * The thumbnails come from `InlineBitmaps`, the same decoder and the same bounded cache the
 * bubbles use — decoding off the UI thread, downscaled to the tile, cached so scrolling back
 * costs nothing. A second decoder here would have been a second place to get the
 * `__PHOTO_DECODE_OFF_UI_2026_09_23__` lesson wrong. Opening goes through `LocalMediaOpener`,
 * so a tile and a bubble lead to exactly the same viewer with the same view-once and
 * reveal-in-folder rules.
 *
 * ## What it refuses to show
 *
 * View-once attachments are not in the list at all — excluded in `ChatShellModel`, not here,
 * so no caller can opt back in. "View once" means once, and a grid that lists it afterwards
 * is a second look by another route.
 *
 * A non-image is drawn as a labelled card rather than a blank tile: a grid of identical grey
 * squares is harder to search than the thread it replaces.
 */
@Composable
fun MediaGalleryPane(
    items: List<MediaItemRow>,
    links: List<com.oshi.desktop.ui.state.LinkItemRow> = emptyList(),
    initialTab: GalleryCategory? = null,
    onClose: () -> Unit,
) {
    // __GALLERY_BY_TYPE_DESKTOP_2027__ iOS `MediaGalleryView`'s categories — Photos, Videos,
    // Documents, Voice messages, Links — each with its count; opens on the first non-empty one.
    var tab by remember(items.isEmpty(), links.isEmpty()) {
        mutableStateOf(initialTab ?: GalleryCategories.firstNonEmpty(items, links))
    }
    Column(Modifier.fillMaxSize().background(OshiTheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = OshiTheme.xl, vertical = OshiTheme.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.IMAGE, OshiTheme.brand, 18.dp)
            Spacer(Modifier.width(OshiTheme.sm))
            Column(Modifier.weight(1f)) {
                Text(t("media_gallery.title"), style = OshiTheme.typography.titleSmall, color = Ink.strong)
                Text(
                    if (items.isEmpty() && links.isEmpty()) t("media_gallery.empty") else "${items.size + links.size}",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
            }
            TextAction(t("common.close")) { onClose() }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = OshiTheme.xl, vertical = OshiTheme.xs)) {
            ToolTabs(
                options = GalleryCategory.entries,
                selected = tab,
                label = { c -> "${categoryLabel(c)}  ${GalleryCategories.count(items, links, c)}" },
                icon = { c -> categoryIcon(c) },
                onSelect = { tab = it },
            )
        }
        Hairline()
        val shownMedia = remember(items, tab) { GalleryCategories.media(items, tab) }
        val empty = if (tab == GalleryCategory.LINKS) links.isEmpty() else shownMedia.isEmpty()
        if (empty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MascotEmptyState(
                    mascot = "peek",
                    title = t("conv.fa.gallery.empty_title", categoryLabel(tab)),
                    body = t("conv.fa.gallery.empty_body"),
                )
            }
            return@Column
        }
        if (tab == GalleryCategory.LINKS) {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = OshiTheme.xl, vertical = OshiTheme.md),
                verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
            ) {
                items(links.size, key = { links[it].messageId }) { i -> LinkRow(links[i]) { runCatching { uri.openUri(GalleryCategories.openable(links[i].url)) } } }
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (tab == GalleryCategory.PHOTOS || tab == GalleryCategory.VIDEOS) 120.dp else 220.dp),
            modifier = Modifier.fillMaxSize().padding(OshiTheme.md),
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
            verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            items(shownMedia, key = { it.messageId }) { item ->
                if (tab == GalleryCategory.DOCUMENTS || tab == GalleryCategory.AUDIO) DocumentRow(item) else GalleryTile(item)
            }
        }
    }
}

private fun categoryLabel(c: GalleryCategory): String = when (c) {
    GalleryCategory.PHOTOS -> t("media_gallery.category.photos")
    GalleryCategory.VIDEOS -> t("media_gallery.category.videos")
    GalleryCategory.DOCUMENTS -> t("media_gallery.category.documents")
    GalleryCategory.AUDIO -> t("media_gallery.category.audio")
    GalleryCategory.LINKS -> t("media_gallery.category.links")
}

private fun categoryIcon(c: GalleryCategory): ToolGlyph = when (c) {
    GalleryCategory.PHOTOS -> ToolGlyph.LAYERS
    GalleryCategory.VIDEOS -> ToolGlyph.VIDEO
    GalleryCategory.DOCUMENTS -> ToolGlyph.DOC
    GalleryCategory.AUDIO -> ToolGlyph.WAVE
    GalleryCategory.LINKS -> ToolGlyph.LINK
}

/** A link: its host in bold, the full address under it, the date; a click opens the browser. */
@Composable
private fun LinkRow(link: com.oshi.desktop.ui.state.LinkItemRow, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.surface)
            .clickable(onClickLabel = link.url, onClick = onOpen)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Box(Modifier.size(36.dp).clip(OshiTheme.radiusSm).background(OshiTheme.brand.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            ToolIcon(ToolGlyph.LINK, OshiTheme.brand, 18.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(link.host, style = OshiTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ltrIsolate(link.url), style = OshiTheme.typography.bodySmall, color = OshiTheme.brand, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(link.stamp, fontSize = 11.sp, color = Ink.soft)
    }
}

/** A document or voice message as a wide row: icon, name, date — a name is what is searched for. */
@Composable
private fun DocumentRow(item: MediaItemRow) {
    val opener = LocalMediaOpener.current
    val target = remember(item.path, item.kind) {
        MediaViewerTarget(kind = item.kind, path = item.path, fileName = item.fileName, viewOnce = false)
    }
    Row(
        Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.surface)
            .clickable { opener.open(target) }
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Box(Modifier.size(36.dp).clip(OshiTheme.radiusSm).background(OshiTheme.brand.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            ToolIcon(if (item.kind == MediaType.AUDIO) ToolGlyph.WAVE else ToolGlyph.DOC, OshiTheme.brand, 18.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(item.fileName, style = OshiTheme.typography.bodyMedium, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(MediaPresentation.cardLabel(item.kind), style = OshiTheme.typography.bodySmall, color = Ink.soft, maxLines = 1)
        }
        Text(item.stamp, fontSize = 11.sp, color = Ink.soft)
    }
}

@Composable
private fun GalleryTile(item: MediaItemRow) {
    val opener = LocalMediaOpener.current
    val target = remember(item.path, item.kind) {
        MediaViewerTarget(kind = item.kind, path = item.path, fileName = item.fileName, viewOnce = false)
    }
    Box(
        Modifier.aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(OshiTheme.surfaceElevated)
            .clickable { opener.open(target) },
        contentAlignment = Alignment.Center,
    ) {
        if (item.kind == MediaType.IMAGE) ImageTile(item) else FileTile(item)
        // The date sits on every tile: a gallery is used to find WHEN something was sent at
        // least as often as what it was.
        Box(Modifier.align(Alignment.BottomStart).padding(4.dp)) {
            Text(
                item.stamp,
                fontSize = 9.sp,
                color = Ink.onBrandSoft,
                modifier = Modifier.background(OshiTheme.background.copy(alpha = 0.72f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ImageTile(item: MediaItemRow) {
    // 240 px: twice the 120dp tile, so it stays sharp on a HiDPI display without decoding a
    // 12 MP photo to draw it thumb-sized — the same reasoning as the bubble's target width.
    val px = 240
    var result by remember(item.path) {
        mutableStateOf(InlineBitmaps.cached(item.path, px) ?: InlineBitmaps.Result.Loading)
    }
    LaunchedEffect(item.path) {
        if (result is InlineBitmaps.Result.Loading) {
            result = withContext(Dispatchers.IO) { InlineBitmaps.load(item.path, px) }
        }
    }
    when (val r = result) {
        is InlineBitmaps.Result.Ready ->
            Image(r.bitmap, contentDescription = item.fileName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // A failed decode shows the file card rather than an empty square: the attachment is
        // still there and still openable, it just cannot be previewed.
        is InlineBitmaps.Result.Failed -> FileTile(item)
        InlineBitmaps.Result.Loading -> Box(Modifier.fillMaxSize().background(OshiTheme.surface))
    }
}

@Composable
private fun FileTile(item: MediaItemRow) {
    Column(
        Modifier.fillMaxSize().padding(OshiTheme.sm),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (item.kind) {
                MediaType.VIDEO -> "🎬"
                MediaType.AUDIO -> "🎵"
                MediaType.CONTACT -> "👤"
                else -> "📎"
            },
            fontSize = 22.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            item.fileName,
            style = OshiTheme.typography.bodySmall,
            color = Ink.soft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
