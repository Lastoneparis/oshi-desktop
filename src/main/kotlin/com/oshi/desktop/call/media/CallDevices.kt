package com.oshi.desktop.call.media

import java.io.File
import java.util.prefs.Preferences
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * __CALL_DEVICES_2026_09_23__ Which microphone, speaker and camera a call uses — the desktop
 * counterpart of the iPhone's speaker/earpiece toggle and camera switch (PARITY.md row 2.1-d).
 *
 * A phone has one microphone and routes between a few outputs; a desktop has a list, and
 * the OS default is often the wrong one (a headset plugged in after the call started, a
 * virtual device a meeting app left as default). So the choice is the user's, by NAME:
 *
 * - **Null means "the system default"**, and is what everyone gets until they pick. A saved
 *   name that is no longer present (the headset was unplugged) falls back to the default
 *   rather than failing the call — a call that will not open because a remembered USB
 *   device is gone would be worse than the wrong microphone.
 * - **Audio applies when the next audio session opens** (the next call). Java Sound gives
 *   no hot-swap on an open line; switching mid-call would mean tearing down the capture and
 *   render threads under a live replay window, and a desktop user changes this in settings,
 *   not mid-sentence. The UI says so.
 * - **The camera applies immediately**: the call's camera is restarted on the new device
 *   (the video session reopens it through [CallDevices.camera]).
 *
 * Stored with `java.util.prefs` — the registry on Windows, a plist on macOS, a file under
 * `~/.java` on Linux — because these are device preferences of THIS machine, not account
 * data: they must never ride device sync or an export.
 */
object CallDevices {
    private val prefs: Preferences? = runCatching { Preferences.userRoot().node("com/oshi/desktop/call-devices") }.getOrNull()

    @Volatile var microphone: String? = load("microphone")
        set(value) { field = value; save("microphone", value) }

    @Volatile var speaker: String? = load("speaker")
        set(value) { field = value; save("speaker", value) }

    /** Camera device as the platform input takes it (`dshow` name, `/dev/videoN`); null = first. */
    @Volatile var camera: String? = load("camera")
        set(value) { field = value; save("camera", value) }

    private fun load(key: String): String? = runCatching { prefs?.get(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun save(key: String, value: String?) {
        runCatching {
            if (value == null) prefs?.remove(key) else prefs?.put(key, value)
            prefs?.flush()
        }
    }

    /** Mixers that can capture [format], by name, in the order Java Sound lists them. */
    fun microphones(format: AudioFormat = CallAudio.FORMAT): List<String> =
        mixersSupporting(TargetDataLine::class.java, format)

    /** Mixers that can render [format]. */
    fun speakers(format: AudioFormat = CallAudio.FORMAT): List<String> =
        mixersSupporting(SourceDataLine::class.java, format)

    private fun mixersSupporting(kind: Class<out Line>, format: AudioFormat): List<String> =
        runCatching {
            AudioSystem.getMixerInfo().filter { info ->
                runCatching { AudioSystem.getMixer(info).isLineSupported(DataLine.Info(kind, format)) }.getOrDefault(false)
            }.map { it.name }.distinct()
        }.getOrDefault(emptyList())

    /**
     * The capture line to open: the chosen microphone when it is still present and supports
     * the call format, else the system default. Never throws for a missing preference.
     */
    fun captureLine(format: AudioFormat = CallAudio.FORMAT): TargetDataLine =
        lineFrom(microphone, TargetDataLine::class.java, format) as TargetDataLine?
            ?: AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine

    /** The render line to open — same rule as [captureLine]. */
    fun renderLine(format: AudioFormat = CallAudio.FORMAT): SourceDataLine =
        lineFrom(speaker, SourceDataLine::class.java, format) as SourceDataLine?
            ?: AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine

    private fun lineFrom(name: String?, kind: Class<out Line>, format: AudioFormat): Line? {
        if (name == null) return null
        return runCatching {
            val info = AudioSystem.getMixerInfo().firstOrNull { it.name == name } ?: return null
            val mixer = AudioSystem.getMixer(info)
            val lineInfo = DataLine.Info(kind, format)
            if (!mixer.isLineSupported(lineInfo)) null else mixer.getLine(lineInfo)
        }.getOrNull()
    }

    /**
     * Cameras this machine can name, as `device → label`. Windows: every DirectShow video
     * input. Linux: `/dev/video*`. macOS: EMPTY — FFmpeg's avfoundation input cannot
     * enumerate devices through the device-list API (only by printing to its log), so the
     * Mac uses the system default camera and the picker says so.
     */
    fun cameras(os: String = System.getProperty("os.name").orEmpty()): List<Pair<String, String>> {
        val o = os.lowercase()
        return when {
            o.contains("win") -> com.oshi.desktop.call.video.CameraCapture.dshowCameras()
            o.contains("mac") -> emptyList()
            else -> linuxCameras(File("/dev"))
        }
    }

    internal fun linuxCameras(dev: File): List<Pair<String, String>> =
        (dev.listFiles { f -> f.name.matches(Regex("video\\d+")) } ?: emptyArray())
            .sortedBy { it.name.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }
            .map { it.path to (readV4l2Name(it.name) ?: it.path) }

    private fun readV4l2Name(node: String): String? = runCatching {
        File("/sys/class/video4linux/$node/name").readText().trim().takeIf { it.isNotEmpty() }?.let { "$it ($node)" }
    }.getOrNull()
}
