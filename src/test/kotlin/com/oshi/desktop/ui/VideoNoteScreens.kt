package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.oshi.desktop.call.video.CameraCapture
import com.oshi.desktop.call.video.VideoImage
import com.oshi.desktop.call.video.VideoSource
import com.oshi.desktop.media.VideoNoteFormat
import com.oshi.desktop.media.VideoNoteMp4Writer
import com.oshi.desktop.media.VideoNoteRecorder
import com.oshi.desktop.media.VideoNoteThumbnails
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ThreadView
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.math.hypot

/**
 * __VIDEO_NOTE_2026_09_24__ Contact sheets for round video notes: the composer before and
 * while typing, the recorder panel mid-recording (scripted camera — no hardware), and the
 * round bubbles in a thread. A TOOL, gated:
 *
 *     OSHI_RENDER_VIDEONOTE=1 OSHI_RENDER_OUT=/some/dir ./gradlew test --tests 'com.oshi.desktop.ui.VideoNoteScreens'
 */
class VideoNoteScreens {

    /** A YUV420P card: a soft "face" disc on a gradient, moving with [n]. */
    private class Card(val side: Int) : AutoCloseable {
        val frame: AVFrame = avutil.av_frame_alloc().apply {
            format(avutil.AV_PIX_FMT_YUV420P); width(side); height(side)
            check(avutil.av_frame_get_buffer(this, 32) >= 0)
        }
        fun paint(n: Int): AVFrame {
            avutil.av_frame_make_writable(frame)
            val cx = side / 2 + (n % 40) - 20; val cy = side / 2
            val row = ByteArray(side)
            for (y in 0 until side) {
                for (x in 0 until side) {
                    val d = hypot((x - cx).toDouble(), (y - cy).toDouble())
                    row[x] = (if (d < side * 0.28) 190 else 40 + y * 90 / side).toByte()
                }
                frame.data(0).position(y.toLong() * frame.linesize(0)).put(*row)
            }
            val u = ByteArray(side / 2) { (150 - it * 40 / side).toByte() }
            val v = ByteArray(side / 2) { (110 + it * 60 / side).toByte() }
            for (y in 0 until side / 2) {
                frame.data(1).position(y.toLong() * frame.linesize(1)).put(*u)
                frame.data(2).position(y.toLong() * frame.linesize(2)).put(*v)
            }
            frame.data(0).position(0); frame.data(1).position(0); frame.data(2).position(0)
            return frame
        }
        fun preview(n: Int): VideoImage {
            val s = 160
            val cx = s / 2 + ((n % 40) - 20) / 3
            return VideoImage(s, s, IntArray(s * s) { i ->
                val x = i % s; val y = i / s
                val d = hypot((x - cx).toDouble(), (y - s / 2).toDouble())
                if (d < s * 0.28) 0xFFE8C4A8.toInt() else (0xFF000000.toInt() or ((40 + y) shl 16) or (60 shl 8) or (120 + y / 2))
            })
        }
        override fun close() { avutil.av_frame_free(frame) }
    }

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_VIDEONOTE") == "1")
        val out = File(System.getenv("OSHI_RENDER_OUT") ?: "build/screens").apply { mkdirs() }
        val tmp = Files.createTempDirectory("oshi-vn-shots").toFile()
        try {
            // Two real notes on disk (plaintext: no vault in this tool) — the bubbles decode them.
            fun note(name: String, frames: Int): File = Card(VideoNoteFormat.SIDE).use { c ->
                VideoNoteMp4Writer().use { w ->
                    for (i in 0 until frames) w.addFrame(c.paint(i), i * 33L)
                    w.finish(File(tmp, name), null).file
                }
            }
            val inbound = note("videonote_1.mp4", 120)
            val outbound = note("videonote_2.mp4", 60)
            // Warm the thumbnail cache so a single render shows the first frames.
            VideoNoteThumbnails.load(inbound.absolutePath, 320)
            VideoNoteThumbnails.load(outbound.absolutePath, 320)

            fun msg(id: String, fromMe: Boolean, body: String, stamp: String, attachment: String? = null, note: Boolean = false, ms: Long? = null) =
                MessageRow(
                    id = id, fromMe = fromMe, who = if (fromMe) "me" else "Alice", body = body, attachment = attachment,
                    stamp = stamp, status = if (fromMe) "read" else null, failed = false, edited = false, deleted = false,
                    reactions = "", videoNote = note, mediaDurationMs = ms,
                )
            val thread = ThreadView(
                conversationId = "A", title = "Alice", address = "a".repeat(43) + "=",
                kind = ConversationKind.DIRECT, groupAdmin = false,
                groupMembers = emptyList(), groupCandidates = emptyList(), safetyNumber = "", verified = true,
                reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
                messages = listOf(
                    msg("1", false, "Look what I found on the trail", "09:12"),
                    msg("2", false, "", "09:13", "[video] ${inbound.absolutePath}", note = true, ms = 4_000),
                    msg("3", true, "Ha! Here is mine", "09:15"),
                    msg("4", true, "", "09:15", "[video] ${outbound.absolutePath}", note = true, ms = 2_000),
                ),
                composer = ComposerState(true, null, true, null),
            )
            @Composable
            fun pane(draft: String) = com.oshi.desktop.ui.components.ThreadPane(
                thread = thread, notice = null, busy = false,
                wallpaper = com.oshi.desktop.ui.components.WallpaperId.NONE, onPickWallpaper = {},
                draft = draft, onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
                onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
                onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
                viewOnce = com.oshi.desktop.ui.components.ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
                today = LocalDate.now(),
                onGif = {},
                onVideoNote = { _, _ -> },
            )
            render(File(out, "desktop_thread_bubbles_composer_empty.png"), 900, 900, frames = 8) { pane("") }
            render(File(out, "desktop_composer_typing.png"), 900, 900, frames = 8) { pane("Typing a longer message, the field takes the row") }

            // The recorder, mid-recording, on a scripted camera.
            val card = Card(VideoNoteFormat.SIDE)
            var n = 0
            val cam = object : VideoSource {
                override val outW = VideoNoteFormat.SIDE
                override val outH = VideoNoteFormat.SIDE
                override val deviceName = "script"
                override fun next(wantPreview: Boolean): CameraCapture.Captured {
                    Thread.sleep(33)
                    n++
                    return CameraCapture.Captured(card.paint(n), if (wantPreview) card.preview(n) else null)
                }
                override fun close() {}
            }
            render(File(out, "desktop_recorder.png"), 700, 460, frames = 24) {
                com.oshi.desktop.ui.components.VideoNoteRecorderPanel(
                    onSend = { _, _ -> }, onClose = {},
                    recorderFactory = { VideoNoteRecorder(openCamera = { cam }, workDir = tmp, microphone = null, maxDurationMs = 3_000) },
                )
            }
            card.close()
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun render(target: File, wDp: Int, hDp: Int, frames: Int, content: @Composable () -> Unit) {
        val scale = 2f
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until frames) { scene.render(f * 50_000_000L); Thread.sleep(80) }
            val image = scene.render(frames * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("[screens] wrote ${target.path}")
        } finally {
            scene.close()
        }
    }
}
