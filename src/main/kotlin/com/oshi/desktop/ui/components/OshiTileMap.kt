package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.oshi.desktop.net.DesktopCover
import com.oshi.desktop.ui.OshiTheme
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * __DESKTOP_TOOLS_2027__ A slippy map for the desktop, drawn from the SAME raster tiles the
 * phones use: `https://oshi-messenger.com/tiles/{z}/{x}/{y}.png` (OSHI's own renderer of
 * OpenStreetMap data — iOS `OfflineFirstTileOverlay`, Android `OshiTileSource`).
 *
 * Offline first, like the phones: a tile is read from the disk cache before the network,
 * and every tile fetched is kept. Two rules the phones do not need:
 *
 *  * While "Disguise OSHI traffic" is on, NOTHING is fetched: a tile request names
 *    oshi-messenger.com in the clear, which is exactly what that switch hides. Cached tiles
 *    still draw; the rest stay blank and [MapTiles.networkAllowed] says why.
 *  * A tile must decode as a 256×256 image to be cached. The 13/11 incident served an
 *    "Access blocked" picture with HTTP 200 and the phones cached it for 30 days.
 */
object MapTiles {
    const val TILE = 256
    const val URL = "https://oshi-messenger.com/tiles/%d/%d/%d.png"
    const val MIN_ZOOM = 2
    const val MAX_ZOOM = 18

    /** Overridable for renders and tests (`-Doshi.map.tiles=<dir>`). */
    private var cacheOverride: File? = System.getProperty("oshi.map.tiles")?.let(::File)
    var cacheDir: File
        get() = cacheOverride ?: File(com.oshi.desktop.store.DesktopPaths.dataDir, "map-tiles")
        set(value) { cacheOverride = value }

    /** False while cover traffic is on, or when a render forbids the network. */
    var forceOffline: Boolean = System.getProperty("oshi.map.offline") == "true"
    /** True while "Disguise OSHI traffic" is on: the one reason the pane names to the user. */
    val blockedByCover: Boolean get() = runCatching { DesktopCover.isEnabled() }.getOrDefault(false)
    val networkAllowed: Boolean get() = !forceOffline && !blockedByCover

    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 384
    }
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "oshi-map-tiles").apply { isDaemon = true } }
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build() }

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"
    private fun file(z: Int, x: Int, y: Int) = File(cacheDir, "$z/$x/$y.png")

    /** The tile if it is in memory or on disk; otherwise null, and a fetch starts if allowed. */
    fun get(z: Int, x: Int, y: Int, onLoaded: () -> Unit): ImageBitmap? {
        val k = key(z, x, y)
        synchronized(memory) { memory[k] }?.let { return it }
        if (!inflight.add(k)) return null
        pool.execute {
            try {
                val f = file(z, x, y)
                var bytes = if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
                if (bytes == null && networkAllowed && (failed[k] ?: 0L) < System.currentTimeMillis() - 60_000) {
                    bytes = runCatching {
                        val req = HttpRequest.newBuilder(URI(URL.format(z, x, y))).timeout(Duration.ofSeconds(12))
                            .header("User-Agent", "OSHI-Desktop").GET().build()
                        val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
                        resp.body().takeIf { resp.statusCode() == 200 && it.size in 100..400_000 }
                    }.getOrNull()
                    if (bytes == null) failed[k] = System.currentTimeMillis()
                }
                val image = bytes?.let { b -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(b) }.getOrNull()?.takeIf { it.width == TILE && it.height == TILE }?.let { b to it } }
                if (image != null) {
                    if (!f.isFile) runCatching { f.parentFile.mkdirs(); f.writeBytes(image.first) }
                    synchronized(memory) { memory[k] = image.second.toComposeImageBitmap() }
                    onLoaded()
                }
            } finally {
                inflight.remove(k)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Web Mercator

    fun worldSize(z: Int): Double = TILE.toDouble() * (1 shl z)
    fun lonToX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * worldSize(z)
    fun latToY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * worldSize(z)
    }
    fun xToLon(x: Double, z: Int) = x / worldSize(z) * 360.0 - 180.0
    fun yToLat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / worldSize(z)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }
}

/** A point drawn on the map. */
data class MapMarker(val id: String, val lat: Double, val lon: Double, val label: String, val color: Color? = null, val radiusM: Double? = null)

/** Camera state: hoisted so the pane's zoom buttons and "fly to" can drive it. */
@Stable
class MapCamera(lat: Double, lon: Double, zoom: Int) {
    var zoom by mutableIntStateOf(zoom.coerceIn(MapTiles.MIN_ZOOM, MapTiles.MAX_ZOOM))
        private set
    /** Centre in world pixels at [zoom]. */
    var cx by mutableDoubleStateOf(MapTiles.lonToX(lon, this.zoom))
        private set
    var cy by mutableDoubleStateOf(MapTiles.latToY(lat, this.zoom))
        private set

    val centerLat: Double get() = MapTiles.yToLat(cy, zoom)
    val centerLon: Double get() = MapTiles.xToLon(cx, zoom)

    fun panBy(dx: Float, dy: Float) {
        cx -= dx
        cy = (cy - dy).coerceIn(0.0, MapTiles.worldSize(zoom))
    }

    fun flyTo(lat: Double, lon: Double, z: Int = zoom) {
        zoom = z.coerceIn(MapTiles.MIN_ZOOM, MapTiles.MAX_ZOOM)
        cx = MapTiles.lonToX(lon, zoom)
        cy = MapTiles.latToY(lat, zoom)
    }

    /** Zoom by one step, keeping the world point under [anchor] (viewport px from centre) still. */
    fun zoomBy(step: Int, anchorDx: Float = 0f, anchorDy: Float = 0f) {
        val nz = (zoom + step).coerceIn(MapTiles.MIN_ZOOM, MapTiles.MAX_ZOOM)
        if (nz == zoom) return
        val f = if (nz > zoom) 2.0 else 0.5
        val ax = cx + anchorDx
        val ay = cy + anchorDy
        cx = ax * f - anchorDx
        cy = ay * f - anchorDy
        zoom = nz
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OshiTileMap(
    camera: MapCamera,
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    showTiles: Boolean = true,
    onMarker: (MapMarker) -> Unit = {},
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableIntStateOf(0) }
    val ground = OshiTheme.surface
    val grid = OshiTheme.separator.copy(alpha = 0.35f)
    val brand = OshiTheme.brand
    val dark = OshiTheme.palette.isDark
    Box(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(camera) { detectDragGestures { change, drag -> change.consume(); camera.panBy(drag.x, drag.y) } }
            .pointerInput(camera, markers) {
                detectTapGestures(onDoubleTap = { p -> camera.zoomBy(1, p.x - size.width / 2f, p.y - size.height / 2f) }) { p ->
                    val hit = markers.minByOrNull { m ->
                        val o = markerOffset(camera, m, size)
                        (o - p).getDistance()
                    }
                    if (hit != null && (markerOffset(camera, hit, size) - p).getDistance() < 22f) onMarker(hit)
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val ch = e.changes.firstOrNull() ?: return@onPointerEvent
                val dy = ch.scrollDelta.y
                if (dy != 0f) {
                    camera.zoomBy(if (dy < 0) 1 else -1, ch.position.x - size.width / 2f, ch.position.y - size.height / 2f)
                    ch.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tick
            drawRect(ground)
            val z = camera.zoom
            val w = size.width
            val h = size.height
            val left = camera.cx - w / 2.0
            val top = camera.cy - h / 2.0
            val n = 1 shl z
            val tx0 = floor(left / MapTiles.TILE).toInt()
            val ty0 = floor(top / MapTiles.TILE).toInt().coerceAtLeast(0)
            val tx1 = floor((left + w) / MapTiles.TILE).toInt()
            val ty1 = floor((top + h) / MapTiles.TILE).toInt().coerceAtMost(n - 1)
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                val wx = ((tx % n) + n) % n
                val ox = (tx * MapTiles.TILE - left).toFloat()
                val oy = (ty * MapTiles.TILE - top).toFloat()
                val img = if (showTiles) MapTiles.get(z, wx, ty) { tick++ } else null
                if (img != null) {
                    drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = androidx.compose.ui.unit.IntSize(MapTiles.TILE, MapTiles.TILE),
                        colorFilter = if (dark) androidx.compose.ui.graphics.ColorFilter.colorMatrix(DIM_MATRIX) else null)
                } else {
                    drawRect(grid, Offset(ox, oy), Size(MapTiles.TILE.toFloat(), MapTiles.TILE.toFloat()), style = Stroke(1f))
                }
            }
            for (m in markers) {
                val o = markerOffset(camera, m, IntSize(w.toInt(), h.toInt()))
                val c = m.color ?: brand
                m.radiusM?.let { r ->
                    val px = metersToPixels(r, m.lat, z)
                    if (px > 6) {
                        drawCircle(c.copy(alpha = 0.10f), px.toFloat(), o)
                        drawCircle(c.copy(alpha = 0.45f), px.toFloat(), o, style = Stroke(1.5f))
                    }
                }
                val sel = m.id == selectedId
                val r = if (sel) 11f else 8f
                drawCircle(Color.White, r + 3f, o)
                drawCircle(c, r, o)
                if (sel) drawCircle(c.copy(alpha = 0.25f), r + 10f, o)
            }
        }
    }
}

/** Dark themes: the light basemap, dimmed and inverted enough not to glare. */
private val DIM_MATRIX = androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
    // invert, then rotate hue 180° (water stays blue, land goes dark), then dim to 80 %.
    0.459f, -1.144f, -0.115f, 0f, 204f,
    -0.341f, -0.344f, -0.115f, 0f, 204f,
    -0.341f, -1.144f, 0.685f, 0f, 204f,
    0f, 0f, 0f, 1f, 0f,
))

private fun markerOffset(camera: MapCamera, m: MapMarker, size: IntSize): Offset {
    val z = camera.zoom
    var x = MapTiles.lonToX(m.lon, z) - camera.cx
    val world = MapTiles.worldSize(z)
    // Take the copy of the world nearest the centre.
    x -= Math.round(x / world) * world
    val y = MapTiles.latToY(m.lat, z) - camera.cy
    return Offset((x + size.width / 2.0).toFloat(), (y + size.height / 2.0).toFloat())
}

private fun metersToPixels(m: Double, lat: Double, z: Int): Double {
    val metersPerPixel = 156543.03392 * kotlin.math.cos(Math.toRadians(lat)) / (1 shl z)
    return m / metersPerPixel
}
