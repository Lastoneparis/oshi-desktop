package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.dt
import com.oshi.desktop.i18n.t
import com.oshi.desktop.media.VideoNoteFile
import com.oshi.desktop.media.VideoNoteFormat
import com.oshi.desktop.media.VideoNoteOutbox
import com.oshi.desktop.media.VideoNotePlayer
import com.oshi.desktop.media.VideoNoteRecorder
import com.oshi.desktop.media.VideoNoteThumbnails
import com.oshi.desktop.media.VideoNoteWire
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.VideoNoteRing
import java.awt.EventQueue
import java.io.File

/*
 * __VIDEO_NOTE_2026_09_24__ Round video notes — docs/VIDEO_NOTE_SPEC.md §4 — for the desktop:
 * the composer button, the recorder panel (circular preview, OSHI-brand progress ring, 15 s
 * cap, preview / send / discard) and the round bubble that plays inline on click.
 */

/** The ring's gradient: the OSHI violet into the theme's blue and back, so the seam never shows. */
private val RingBrush: Brush
    get() = Brush.sweepGradient(listOf(OshiTheme.brand, Color(0.20f, 0.40f, 0.95f), OshiTheme.brandSecondary, OshiTheme.brand))

/** A track ring plus a progress arc from 12 o'clock, clockwise. */
@Composable
private fun ProgressRing(fraction: Float, diameter: Dp, stroke: Dp, track: Color) {
    Canvas(Modifier.size(diameter)) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        if (fraction > 0f) {
            drawArc(RingBrush, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** The composer's video-note button: a camera in a circle. Disabled with a tooltip when there is no camera. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoNoteButton(unavailableReason: String?, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    val enabled = unavailableReason == null
    val label = if (enabled) t("videonote.button") else dt("desktop.videonote.unavailable", unavailableReason)
    TooltipArea(
        tooltip = {
            Text(
                label,
                fontSize = 11.sp,
                color = Ink.strong,
                modifier = Modifier.clip(OshiTheme.radiusSm).background(OshiTheme.surfaceElevated).padding(OshiTheme.sm),
            )
        },
    ) {
        Box(
            Modifier
                .size(Metrics.actionButton)
                .clip(CircleShape)
                .background(if (hovered.value && enabled) OshiTheme.surface else Color.Transparent)
                .focusRing(CircleShape)
                .clickable(enabled = enabled, interactionSource = source, indication = null, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            VideoNoteGlyph(if (enabled) Ink.strong else Ink.soft.copy(alpha = 0.45f), 20.dp)
        }
    }
}

/** A video camera inside a circle — the phones' "round video" affordance. */
@Composable
private fun VideoNoteGlyph(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.08f
        drawCircle(tint, radius = s * 0.44f, center = Offset(s / 2, s / 2), style = Stroke(w))
        // body
        drawRoundRect(tint, Offset(s * 0.27f, s * 0.37f), Size(s * 0.30f, s * 0.26f), androidx.compose.ui.geometry.CornerRadius(s * 0.05f), style = Stroke(w))
        // lens wedge
        drawPath(Path().apply { moveTo(s * 0.57f, s * 0.47f); lineTo(s * 0.73f, s * 0.39f); lineTo(s * 0.73f, s * 0.61f); lineTo(s * 0.57f, s * 0.53f); close() }, tint)
    }
}

@Composable
private fun PlayGlyph(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawPath(Path().apply { moveTo(s * 0.30f, s * 0.20f); lineTo(s * 0.82f, s * 0.5f); lineTo(s * 0.30f, s * 0.80f); close() }, tint)
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Text(
        label,
        style = OshiTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (primary) Color.White else Ink.strong,
        modifier = Modifier
            .clip(OshiTheme.pill)
            .then(
                if (primary) Modifier.background(OshiTheme.brandGradient)
                else Modifier.background(if (hovered.value) OshiTheme.separator.copy(alpha = 0.25f) else OshiTheme.surface)
            )
            .focusRing(OshiTheme.pill)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
    )
}

private sealed interface Phase {
    object Starting : Phase
    object Recording : Phase
    object Encoding : Phase
    data class Preview(val note: VideoNoteFile) : Phase
    object Sending : Phase
    data class Failed(val message: String) : Phase
}

/**
 * The recorder, drawn above the composer: a circular live preview inside the OSHI-gradient
 * ring that fills over the 15 s cap, a timer, and — once stopped — the recorded note playable
 * in the same circle with Send / Discard.
 *
 * Keyboard-first: Enter stops (recording) or sends (preview), Space plays the preview, Escape
 * cancels or discards. The panel takes focus when it opens.
 *
 * Plaintext: the recorder writes the MP4 to `media-tmp/`; Send SEALS it under
 * `media/videonotes/` (deleting the scratch copy) before handing it to the send path; Discard
 * and closing delete it.
 */
@Composable
fun VideoNoteRecorderPanel(
    onSend: (File, VideoNoteWire.Meta) -> Unit,
    onClose: () -> Unit,
    recorderFactory: () -> VideoNoteRecorder = { VideoNoteRecorder() },
) {
    val recorder = remember { recorderFactory() }
    val player = remember { VideoNotePlayer(side = 320) }
    var phase by remember { mutableStateOf<Phase>(Phase.Starting) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var playPos by remember { mutableFloatStateOf(0f) }
    var capped by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun stopAndEncode() {
        if (phase != Phase.Recording && phase != Phase.Starting) return
        phase = Phase.Encoding
        Thread({
            val r = recorder.stop()
            EventQueue.invokeLater {
                phase = when (r) {
                    is VideoNoteRecorder.Stop.Ready -> Phase.Preview(r.note)
                    is VideoNoteRecorder.Stop.Failure -> Phase.Failed(r.message)
                }
            }
        }, "oshi-videonote-encode").apply { isDaemon = true; start() }
    }

    fun discard() {
        player.stop()
        (phase as? Phase.Preview)?.note?.file?.delete()
        if (recorder.isRecording) Thread({ recorder.cancel() }, "oshi-videonote-cancel").apply { isDaemon = true; start() }
        onClose()
    }

    fun togglePlay() {
        val p = phase as? Phase.Preview ?: return
        if (playing) { player.stop(); return }
        playing = true
        player.play(p.note.file, object : VideoNotePlayer.Listener {
            override fun onFrame(image: com.oshi.desktop.call.video.VideoImage, positionMs: Long) {
                val bmp = image.toImageBitmap()
                EventQueue.invokeLater { frame = bmp; playPos = VideoNoteRing.fraction(positionMs, p.note.durationMs) }
            }
            override fun onEnd(error: String?) {
                EventQueue.invokeLater { playing = false; playPos = 0f }
            }
        })
    }

    fun send() {
        val p = phase as? Phase.Preview ?: return
        player.stop()
        phase = Phase.Sending
        Thread({
            val result = runCatching { VideoNoteOutbox.seal(p.note.file) }
            EventQueue.invokeLater {
                result.fold(
                    onSuccess = { sealed -> onSend(sealed, VideoNoteWire.Meta(p.note.durationMs.coerceIn(0, VideoNoteWire.MAX_DURATION_MS))); onClose() },
                    onFailure = { phase = Phase.Failed("The note could not be stored (${it.javaClass.simpleName}).") },
                )
            }
        }, "oshi-videonote-seal").apply { isDaemon = true; start() }
    }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        val started = recorder.start(object : VideoNoteRecorder.Listener {
            override fun onPreview(image: com.oshi.desktop.call.video.VideoImage) {
                val bmp = image.toImageBitmap()
                EventQueue.invokeLater { frame = bmp; if (phase == Phase.Starting) phase = Phase.Recording }
            }
            override fun onProgress(elapsedMs: Long) { EventQueue.invokeLater { elapsed = elapsedMs } }
            override fun onCapped() { EventQueue.invokeLater { capped = true; stopAndEncode() } }
            override fun onError(message: String) { EventQueue.invokeLater { phase = Phase.Failed(message) } }
        })
        if (!started) phase = Phase.Failed("Already recording.")
    }
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            if (recorder.isRecording) Thread({ recorder.cancel() }, "oshi-videonote-cancel").apply { isDaemon = true; start() }
            // A preview that was neither sent nor discarded does not outlive the panel.
            (phase as? Phase.Preview)?.note?.file?.delete()
        }
    }

    val diameter = 240.dp
    Column(
        Modifier
            .fillMaxWidth()
            .background(OshiTheme.surface)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Escape -> { discard(); true }
                    Key.Enter, Key.NumPadEnter -> { if (phase is Phase.Preview) send() else stopAndEncode(); true }
                    Key.Spacebar -> { togglePlay(); true }
                    else -> false
                }
            }
            .padding(vertical = OshiTheme.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        val ringFraction = when (val p = phase) {
            is Phase.Preview -> if (playing) playPos else VideoNoteRing.fraction(p.note.durationMs)
            else -> VideoNoteRing.fraction(elapsed)
        }
        Box(Modifier.size(diameter + 16.dp), contentAlignment = Alignment.Center) {
            ProgressRing(ringFraction, diameter + 16.dp, 5.dp, OshiTheme.separator.copy(alpha = 0.35f))
            Box(
                Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(Color(0xFF101018))
                    .clickable(enabled = phase is Phase.Preview, onClick = ::togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                frame?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                when (val p = phase) {
                    Phase.Starting -> Text(dt("desktop.videonote.starting"), color = Color.White, fontSize = 12.sp)
                    Phase.Encoding, Phase.Sending -> Text(t("videonote.processing"), color = Color.White, fontSize = 12.sp)
                    is Phase.Failed -> Text(p.message, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(OshiTheme.xl))
                    is Phase.Preview -> if (!playing) Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) { PlayGlyph(Color.White, 26.dp) }
                    Phase.Recording -> Unit
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            if (phase == Phase.Recording) Box(Modifier.size(8.dp).clip(CircleShape).background(OshiTheme.danger))
            val shown = (phase as? Phase.Preview)?.note?.durationMs ?: elapsed
            Text(
                "${VideoNoteFormat.formatDuration(shown)} / ${VideoNoteFormat.formatDuration(VideoNoteFormat.MAX_DURATION_MS)}",
                style = OshiTheme.typography.labelLarge,
                color = Ink.strong,
            )
            if (capped) Text(dt("desktop.videonote.capped"), fontSize = 11.sp, color = Ink.soft)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md), verticalAlignment = Alignment.CenterVertically) {
            when (phase) {
                Phase.Starting, Phase.Recording -> {
                    PillButton(t("common.cancel"), primary = false, onClick = ::discard)
                    PillButton(t("videonote.stop"), primary = true, onClick = ::stopAndEncode)
                }
                is Phase.Preview -> {
                    PillButton(t("videonote.discard"), primary = false, onClick = ::discard)
                    PillButton(t("common.send"), primary = true, onClick = ::send)
                }
                is Phase.Failed -> PillButton(t("common.cancel"), primary = false, onClick = ::discard)
                Phase.Encoding, Phase.Sending -> Unit
            }
        }
        Text(
            if (phase is Phase.Preview) dt("desktop.videonote.hint.preview") else dt("desktop.videonote.hint.recording"),
            fontSize = 10.sp,
            color = Ink.soft,
        )
    }
}

/**
 * A received (or sent) video note in the thread: a circle with the first frame, a play
 * overlay and the duration capsule; click plays inline with sound, click again stops, the
 * brand ring tracks the position, and the end returns to the first frame (spec §4).
 *
 * The first frame is decoded OFF the UI thread (a sealed note is decrypted to `media-tmp/`
 * for that and the copy deleted at once) and cached per path.
 */
@Composable
fun VideoNoteBubble(path: String, announcedDurationMs: Long?, missingNote: String?, diameter: Dp = 220.dp) {
    val px = 320
    val thumb by produceState(VideoNoteThumbnails.cached(path, px), path) {
        if (value != null || missingNote != null) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VideoNoteThumbnails.load(path, px) }
    }
    val still = remember(thumb) { thumb?.image?.toImageBitmap() }
    val player = remember(path) { VideoNotePlayer(side = px) }
    var playing by remember(path) { mutableStateOf(false) }
    var live by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var pos by remember(path) { mutableFloatStateOf(0f) }
    var problem by remember(path) { mutableStateOf<String?>(null) }
    DisposableEffect(path) { onDispose { player.stop() } }
    val duration = announcedDurationMs ?: thumb?.durationMs
    val playable = missingNote == null && thumb?.error == null

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
        Box(Modifier.size(diameter + 10.dp), contentAlignment = Alignment.Center) {
            ProgressRing(if (playing) pos else 0f, diameter + 10.dp, 3.dp, Color.Transparent)
            Box(
                Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(Color(0xFF101018))
                    .focusRing(CircleShape)
                    .clickable(enabled = playable, onClickLabel = dt("desktop.videonote.play")) {
                        if (playing) { player.stop(); return@clickable }
                        problem = null
                        playing = true
                        player.play(File(path), object : VideoNotePlayer.Listener {
                            override fun onFrame(image: com.oshi.desktop.call.video.VideoImage, positionMs: Long) {
                                val bmp = image.toImageBitmap()
                                val total = duration ?: VideoNoteFormat.MAX_DURATION_MS
                                EventQueue.invokeLater { live = bmp; pos = VideoNoteRing.fraction(positionMs, total) }
                            }
                            override fun onEnd(error: String?) {
                                EventQueue.invokeLater { playing = false; live = null; pos = 0f; problem = error }
                            }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                (live ?: still)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (!playing) {
                    if (playable) Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) { PlayGlyph(Color.White, 24.dp) }
                    else VideoNoteGlyph(Color.White.copy(alpha = 0.6f), 40.dp)
                }
                if (duration != null) Box(Modifier.fillMaxSize().padding(bottom = 14.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        VideoNoteFormat.formatDuration(duration),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.clip(OshiTheme.pill).background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = OshiTheme.sm, vertical = 2.dp),
                    )
                }
            }
        }
        (missingNote ?: thumb?.error?.let { dt("desktop.videonote.unplayable") } ?: problem)?.let {
            Text(it, fontSize = 10.sp, color = Ink.soft, modifier = Modifier.padding(horizontal = OshiTheme.sm))
        }
    }
}

/**
 * Whether the video-note button is enabled, asked ONCE per process and off the UI thread (it
 * loads the FFmpeg device library and, on Windows, lists DirectShow cameras).
 */
object VideoNoteAvailability {
    @Volatile private var answer: Result<String?>? = null

    /** Null = can record, else the tooltip's reason. Blocking on first call. */
    fun reason(): String? {
        answer?.let { return it.getOrNull() }
        val r = runCatching { VideoNoteRecorder.unavailableReason() }
            .recover { "The camera library did not load on this machine (${it.javaClass.simpleName})." }
        answer = r
        return r.getOrNull()
    }

    /** What is known without blocking: null (enabled) until [reason] has run. */
    fun cachedOrNull(): String? = answer?.getOrNull()
}
