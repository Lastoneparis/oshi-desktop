package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.media.CameraSession
import com.oshi.desktop.media.ImageMarkup
import com.oshi.desktop.media.MarkupDoc
import com.oshi.desktop.media.MarkupGeometry
import com.oshi.desktop.media.MarkupOp
import com.oshi.desktop.media.PixelRect
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.MediaVault
import com.oshi.desktop.ui.OshiTheme
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.File

// ============================================================================ camera

/**
 * __DESKTOP_CAMERA_2027__ The composer's camera: a live preview, then "Take photo" (the
 * picture opens in the markup editor before anything is sent) or "Record video" (≤ 60 s,
 * sent as a video when stopped). Drawn above the composer, like the video-note recorder.
 *
 * [cameraFactory] / [previewFrame] / [initialError] are seams for the renderer.
 */
/**
 * __LOCK_STOPS_CAMERA__ How [CameraPanel] gets its session — a seam so a test can count the
 * device being opened and released without a real camera.
 */
val LocalCameraFactory = androidx.compose.runtime.staticCompositionLocalOf<() -> CameraSession> { { CameraSession() } }

@Composable
fun CameraPanel(
    onPhoto: (BufferedImage) -> Unit,
    onVideo: (File) -> Unit,
    onClose: () -> Unit,
    cameraFactory: () -> CameraSession = LocalCameraFactory.current,
    previewFrame: ImageBitmap? = null,
    initialError: String? = null,
    startCamera: Boolean = true,
) {
    val camera = remember { cameraFactory() }
    var frame by remember { mutableStateOf(previewFrame) }
    var error by remember { mutableStateOf(initialError) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }

    fun stopRecording() {
        if (!recording) return
        recording = false
        busy = true
        Thread({
            val clip = camera.stopRecording()
            EventQueue.invokeLater {
                busy = false
                when (clip) {
                    is CameraSession.Clip.Ready -> { onVideo(clip.file); onClose() }
                    is CameraSession.Clip.Failure -> error = t("pc.camera.video_failed")
                }
            }
        }, "oshi-camera-clip").apply { isDaemon = true; start() }
    }

    LaunchedEffect(Unit) {
        if (!startCamera) return@LaunchedEffect
        camera.start(object : CameraSession.Listener {
            override fun onPreview(image: com.oshi.desktop.call.video.VideoImage) {
                val bmp = image.toImageBitmap()
                EventQueue.invokeLater { frame = bmp }
            }
            override fun onPhoto(image: BufferedImage) {
                EventQueue.invokeLater { onPhoto(image) }
            }
            override fun onRecording(elapsedMs: Long) { EventQueue.invokeLater { elapsed = elapsedMs } }
            override fun onCapped() { EventQueue.invokeLater { stopRecording() } }
            override fun onError(message: String) { EventQueue.invokeLater { error = message } }
        })
    }
    DisposableEffect(Unit) {
        onDispose { Thread({ camera.close() }, "oshi-camera-close").apply { isDaemon = true; start() } }
    }

    Column(
        Modifier.fillMaxWidth().background(OshiTheme.surface).padding(OshiTheme.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t("chat.camera"), style = OshiTheme.typography.titleSmall, color = Ink.strong, modifier = Modifier.weight(1f))
            if (recording) ToolPill("● " + com.oshi.desktop.media.VideoNoteFormat.formatDuration(elapsed), color = OshiTheme.danger)
            Spacer(Modifier.width(OshiTheme.sm))
            GlyphButton(Glyph.CLOSE, t("common.cancel"), size = 30.dp, glyphSize = 15.dp) { onClose() }
        }
        Box(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(OshiTheme.radiusMd).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            when {
                error != null -> Column(Modifier.padding(OshiTheme.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("pc.camera.unavailable"), style = OshiTheme.typography.bodyMedium, color = Color.White)
                    Spacer(Modifier.height(OshiTheme.xs))
                    Text(error!!, style = OshiTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                }
                f != null -> Image(f, contentDescription = t("chat.camera"), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else -> Text(t("conv.new.opening_camera"), style = OshiTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
            val ready = frame != null && error == null && !busy
            ToolButton(t("take_photo"), icon = ToolGlyph.SPARKLE, style = ToolButtonStyle.PRIMARY, enabled = ready && !recording) { camera.snap() }
            if (!recording) {
                ToolButton(t("pc.camera.record"), icon = ToolGlyph.VIDEO, enabled = ready) {
                    if (camera.startRecording()) { recording = true; elapsed = 0 } else error = t("pc.camera.video_failed")
                }
            } else {
                ToolButton(t("pc.camera.stop_send"), icon = ToolGlyph.SEND, style = ToolButtonStyle.DANGER) { stopRecording() }
            }
        }
        ToolHint(t("pc.camera.hint"))
    }
}

// ============================================================================ markup

enum class MarkupTool { DRAW, TEXT, BLUR, CROP }

/**
 * __PHOTO_MARKUP_DESKTOP_2027__ The picture, and the four tools: draw (the phones' six pens),
 * text (click where it goes, type, Enter), blur (drag a box: a mosaic, irreversible once sent)
 * and crop (drag the frame to keep). Undo takes back the last mark, then the crop. Send
 * flattens it all into fresh pixels — see [ImageMarkup].
 *
 * [initialDoc] / [initialTool] are seams for the renderer.
 */
@Composable
fun MarkupEditor(
    image: BufferedImage,
    onSend: (File) -> Unit,
    onCancel: () -> Unit,
    initialDoc: MarkupDoc? = null,
    initialTool: MarkupTool = MarkupTool.DRAW,
) {
    var doc by remember(image) { mutableStateOf(initialDoc ?: MarkupDoc(image.width, image.height)) }
    var tool by remember { mutableStateOf(initialTool) }
    var pen by remember { mutableStateOf(ImageMarkup.PENS[0]) }
    // Rendered with every mark but WITHOUT the crop while editing, so the crop frame can move.
    val shown = remember(image, doc.ops) { ImageMarkup.render(image, doc, applyCrop = false).toComposeImageBitmap() }
    val live = remember { mutableStateListOf<Offset>() }      // a stroke being drawn, in view px
    var dragRect by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var textAt by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var textDraft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun send() {
        if (sending) return
        sending = true
        Thread({
            val out = runCatching {
                val flat = ImageMarkup.fitForSending(ImageMarkup.render(image, doc))
                val dir = DesktopPaths.file(MediaVault.SCRATCH_DIR_NAME)
                DesktopPaths.ensurePrivateDir(dir)
                File(dir, "OSHI-photo-${System.currentTimeMillis()}.jpg").also {
                    it.writeBytes(ImageMarkup.encodeJpeg(flat))
                    DesktopPaths.makePrivate(it)
                    it.deleteOnExit()
                }
            }
            EventQueue.invokeLater {
                sending = false
                out.fold(onSuccess = { onSend(it) }, onFailure = { failed = true })
            }
        }, "oshi-markup-flatten").apply { isDaemon = true; start() }
    }

    Column(Modifier.fillMaxSize().background(OshiTheme.paneGround)) {
        // Tool bar.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            ToolTabs(
                options = MarkupTool.entries,
                selected = tool,
                label = {
                    when (it) {
                        MarkupTool.DRAW -> t("pc.markup.draw")
                        MarkupTool.TEXT -> t("pc.markup.text")
                        MarkupTool.BLUR -> t("pc.markup.blur")
                        MarkupTool.CROP -> t("pc.markup.crop")
                    }
                },
                icon = {
                    when (it) {
                        MarkupTool.DRAW -> ToolGlyph.EDIT
                        MarkupTool.TEXT -> ToolGlyph.TEXT
                        MarkupTool.BLUR -> ToolGlyph.EYE_OFF
                        MarkupTool.CROP -> ToolGlyph.LAYERS
                    }
                },
                onSelect = { tool = it; textAt = null },
            )
            Spacer(Modifier.weight(1f))
            ToolButton(t("markup.undo"), icon = ToolGlyph.RESTORE, style = ToolButtonStyle.QUIET, enabled = doc.canUndo) { doc = doc.undo() }
            ToolButton(t("common.cancel"), style = ToolButtonStyle.QUIET) { onCancel() }
            ToolButton(t("common.send"), icon = ToolGlyph.SEND, style = ToolButtonStyle.PRIMARY, enabled = !sending) { send() }
        }
        if (tool == MarkupTool.DRAW || tool == MarkupTool.TEXT) {
            Row(
                Modifier.padding(horizontal = OshiTheme.lg).padding(bottom = OshiTheme.sm),
                horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (c in ImageMarkup.PENS) {
                    val selected = c == pen
                    Box(
                        Modifier
                            .size(if (selected) 26.dp else 22.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(if (selected) 2.dp else 1.dp, if (selected) OshiTheme.brand else OshiTheme.separator, CircleShape)
                            .clickable { pen = c },
                    )
                }
                Spacer(Modifier.width(OshiTheme.md))
                ToolHint(
                    when (tool) {
                        MarkupTool.TEXT -> t("pc.markup.text.hint")
                        else -> t("pc.markup.draw.hint")
                    },
                )
            }
        } else {
            ToolHint(
                if (tool == MarkupTool.BLUR) t("pc.markup.blur.hint") else t("pc.markup.crop.hint"),
                modifier = Modifier.padding(horizontal = OshiTheme.lg).padding(bottom = OshiTheme.sm),
            )
        }
        if (failed) ToolNotice(t("pc.markup.failed"), tone = ToolTone.ERROR, modifier = Modifier.padding(horizontal = OshiTheme.lg))

        // The picture.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(OshiTheme.lg)) {
            val vw = constraints.maxWidth.toFloat()
            val vh = constraints.maxHeight.toFloat()
            val iw = image.width.toFloat(); val ih = image.height.toFloat()
            val fit = MarkupGeometry.fit(iw, ih, vw, vh)
            fun toView(x: Float, y: Float) = Offset(fit.offsetX + x * fit.scale, fit.offsetY + y * fit.scale)

            Image(shown, contentDescription = t("pc.markup.title"), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(tool, pen, vw, vh) {
                        detectTapGestures { p ->
                            if (tool == MarkupTool.TEXT) {
                                MarkupGeometry.toImage(p.x, p.y, iw, ih, vw, vh)?.let { textAt = it; textDraft = "" }
                            } else if (tool == MarkupTool.DRAW) {
                                MarkupGeometry.toImage(p.x, p.y, iw, ih, vw, vh)?.let {
                                    doc = doc.add(MarkupOp.Stroke(listOf(it), pen, MarkupGeometry.widthInImage(ImageMarkup.PEN_WIDTH_ON_SCREEN, iw, ih, vw, vh)))
                                }
                            }
                        }
                    }
                    .pointerInput(tool, pen, vw, vh) {
                        detectDragGestures(
                            onDragStart = { p ->
                                live.clear(); live.add(p)
                                dragRect = p to p
                            },
                            onDrag = { change, _ ->
                                live.add(change.position)
                                dragRect = dragRect?.let { it.first to change.position }
                            },
                            onDragEnd = {
                                when (tool) {
                                    MarkupTool.DRAW -> {
                                        val pts = live.mapNotNull { MarkupGeometry.toImage(it.x, it.y, iw, ih, vw, vh) }
                                        if (pts.isNotEmpty()) doc = doc.add(
                                            MarkupOp.Stroke(pts, pen, MarkupGeometry.widthInImage(ImageMarkup.PEN_WIDTH_ON_SCREEN, iw, ih, vw, vh)),
                                        )
                                    }
                                    MarkupTool.BLUR, MarkupTool.CROP -> dragRect?.let { (a, b) ->
                                        val (x1, y1) = MarkupGeometry.toImageClamped(a.x, a.y, iw, ih, vw, vh)
                                        val (x2, y2) = MarkupGeometry.toImageClamped(b.x, b.y, iw, ih, vw, vh)
                                        val r = PixelRect(x1, y1, x2, y2)
                                        if (tool == MarkupTool.CROP) doc = doc.withCrop(r)
                                        else if (!r.isTiny) doc = doc.add(MarkupOp.Blur(r))
                                    }
                                    MarkupTool.TEXT -> Unit
                                }
                                live.clear(); dragRect = null
                            },
                            onDragCancel = { live.clear(); dragRect = null },
                        )
                    },
            ) {
                // The stroke being drawn.
                if (tool == MarkupTool.DRAW && live.size > 1) {
                    val path = Path().apply { moveTo(live[0].x, live[0].y); for (p in live.drop(1)) lineTo(p.x, p.y) }
                    drawPath(path, Color(pen), style = Stroke(ImageMarkup.PEN_WIDTH_ON_SCREEN * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // The crop frame: everything outside it dimmed.
                val crop = if (tool == MarkupTool.CROP && dragRect != null) dragRect!!.let { (a, b) ->
                    Offset(minOf(a.x, b.x), minOf(a.y, b.y)) to Offset(maxOf(a.x, b.x), maxOf(a.y, b.y))
                } else doc.crop?.let { toView(it.left, it.top) to toView(it.right, it.bottom) }
                if (crop != null) {
                    val (tl, br) = crop
                    val dim = Color.Black.copy(alpha = 0.55f)
                    val l = fit.offsetX; val tp = fit.offsetY; val r = fit.offsetX + iw * fit.scale; val b = fit.offsetY + ih * fit.scale
                    drawRect(dim, Offset(l, tp), Size(r - l, tl.y - tp))
                    drawRect(dim, Offset(l, br.y), Size(r - l, b - br.y))
                    drawRect(dim, Offset(l, tl.y), Size(tl.x - l, br.y - tl.y))
                    drawRect(dim, Offset(br.x, tl.y), Size(r - br.x, br.y - tl.y))
                    drawRect(Color.White, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(2f * density))
                }
                if (tool == MarkupTool.BLUR) dragRect?.let { (a, b) ->
                    val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                    drawRect(Color.White.copy(alpha = 0.25f), tl, Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
                    drawRect(Color.White, tl, Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)), style = Stroke(1.5f * density))
                }
                textAt?.let { (x, y) -> drawCircle(Color(pen), 5f * density, toView(x, y)) }
            }
        }
        // Text entry for the TEXT tool.
        textAt?.let { at ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg).padding(bottom = OshiTheme.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
            ) {
                ToolField(
                    textDraft, { textDraft = it.take(200) }, t("pc.markup.text.placeholder"),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                        .onEnterKey {
                            if (textDraft.isNotBlank()) {
                                doc = doc.add(MarkupOp.Label(at.first, at.second, textDraft.trim(), pen, textSizeInImage(image)))
                            }
                            textAt = null
                        },
                )
                ToolButton(t("common.add"), icon = ToolGlyph.PLUS, style = ToolButtonStyle.PRIMARY, enabled = textDraft.isNotBlank()) {
                    doc = doc.add(MarkupOp.Label(at.first, at.second, textDraft.trim(), pen, textSizeInImage(image)))
                    textAt = null
                }
            }
        }
    }
}

/** Text a twentieth of the picture's short side: legible on a thumbnail, not a banner. */
internal fun textSizeInImage(image: BufferedImage): Float = (minOf(image.width, image.height) / 20f).coerceAtLeast(14f)

private fun Modifier.onEnterKey(action: () -> Unit): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { action(); true } else false
}
