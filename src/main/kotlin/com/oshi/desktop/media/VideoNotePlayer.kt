package com.oshi.desktop.media

import com.oshi.desktop.call.video.VideoImage
import com.oshi.desktop.store.MediaVault
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * __VIDEO_NOTE_2026_09_24__ Inline playback of a round video note — picture AND sound, in
 * the bubble (VIDEO_NOTE_SPEC §4: tap plays with sound, tap again stops, the ring shows the
 * position, the end returns to the first frame).
 *
 * The first thing this client has ever played that is a video: until now `MediaViewer` said
 * "not played here" for every clip. It uses the call pipeline's FFmpeg (demux + H.264/AAC
 * decode, [VideoNoteReader]) and the voice notes' speaker seam ([AudioDevices]).
 *
 * A SEALED attachment is decrypted to `media-tmp/` for the duration of playback only and that
 * copy is deleted when playback ends, is stopped, or fails — libavformat needs a path, and a
 * ≤ 3 MB note in the scratch dir for 15 s is the same exposure the voice-note player accepts.
 *
 * One note plays at a time, process-wide ([stopAll]), like the phones.
 */
class VideoNotePlayer(
    private val devices: AudioDevices = AudioDevices.JavaSound,
    private val side: Int = 240,
) {
    interface Listener {
        fun onFrame(image: VideoImage, positionMs: Long) {}
        /** Always called exactly once per [play] that returned null. [error] null = ended or stopped. */
        fun onEnd(error: String?) {}
    }

    private val running = AtomicReference<AtomicBoolean?>(null)

    val isPlaying: Boolean get() = running.get()?.get() == true

    /** Start playing [file]. Returns null when started, or the reason it could not. */
    fun play(file: File, listener: Listener): String? {
        stopAll()
        val flag = AtomicBoolean(true)
        running.set(flag)
        current.set(this)
        Thread({ run(file, flag, listener) }, "oshi-videonote-play").apply { isDaemon = true; start() }
        return null
    }

    fun stop() { running.getAndSet(null)?.set(false) }

    private fun run(file: File, flag: AtomicBoolean, listener: Listener) {
        var scratch: File? = null
        var error: String? = null
        try {
            val plain = if (MediaVault.isSealed(file)) {
                val v = MediaVault.current() ?: throw IllegalStateException("no media key is loaded in this window")
                v.decryptToScratch(file).also { scratch = it }
            } else file
            VideoNoteReader(plain, targetSide = side).use { reader ->
                val line = if (reader.hasAudio) runCatching {
                    devices.openRender(VoiceNoteFormat.CAPTURE_FORMAT, VoiceNoteFormat.BYTES_PER_SECOND / 8)
                }.getOrNull() else null
                try {
                    val t0 = System.nanoTime()
                    while (flag.get()) {
                        val item = reader.next() ?: break
                        when (item) {
                            is VideoNoteReader.Item.Sound -> line?.write(item.pcm16Mono, 0, item.pcm16Mono.size)
                            is VideoNoteReader.Item.Picture -> {
                                val wait = item.ptsMs - (System.nanoTime() - t0) / 1_000_000L
                                if (wait > 0) Thread.sleep(wait)
                                if (flag.get()) listener.onFrame(item.image, item.ptsMs)
                            }
                        }
                    }
                    if (flag.get()) line?.drain()
                } finally {
                    runCatching { line?.close() }
                }
            }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        } finally {
            scratch?.delete()
            flag.set(false)
            running.compareAndSet(flag, null)
            listener.onEnd(error)
        }
    }

    companion object {
        private val current = AtomicReference<VideoNotePlayer?>(null)

        /** Stop whichever note is playing anywhere in this window. */
        fun stopAll() { current.getAndSet(null)?.stop() }
    }
}

/**
 * The bubble's still (first frame) and duration, decoded once per path OFF the UI thread and
 * cached — a LazyColumn forgets a row that scrolls away and must not re-decrypt and re-decode
 * a video every time it comes back. A failure is cached too.
 */
object VideoNoteThumbnails {
    class Thumb(val image: VideoImage?, val durationMs: Long?, val error: String?)

    private const val MAX = 64
    private val cache = object : LinkedHashMap<String, Thumb>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Thumb>?) = size > MAX
    }
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    fun cached(path: String, side: Int): Thumb? = synchronized(cache) { cache["$side@$path"] }

    /** Blocking — call it off the UI thread. */
    fun load(path: String, side: Int): Thumb {
        cached(path, side)?.let { return it }
        val key = "$side@$path"
        inFlight[key] = true
        val file = File(path)
        var scratch: File? = null
        val thumb = try {
            val plain = if (MediaVault.isSealed(file)) {
                val v = MediaVault.current() ?: throw IllegalStateException("no media key is loaded")
                v.decryptToScratch(file).also { scratch = it }
            } else file
            VideoNoteReader(plain, targetSide = side).use { r -> Thumb(r.firstPicture(), r.durationMs, null) }
        } catch (t: Throwable) {
            Thumb(null, null, t.message ?: t.javaClass.simpleName)
        } finally {
            scratch?.delete()
            inFlight.remove(key)
        }
        synchronized(cache) { cache[key] = thumb }
        return thumb
    }
}
