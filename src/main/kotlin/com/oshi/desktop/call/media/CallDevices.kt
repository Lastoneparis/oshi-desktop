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

    /**
     * __CALL_VOICE_EFFECTS_2026_10_28__ The voice effect a call STARTS with — iOS's
     * `@AppStorage("defaultVoiceEffect")`, same idea and the same stored spelling.
     *
     * Stored as `CallVoiceEffect.rawValue` ("None", "Chipmunk"…) because iOS says that raw
     * value is the wire/storage identity and must not change. Lives beside the device
     * choices rather than in account data for the same reason they do: it is a preference
     * of this machine and must never ride device sync or an export.
     *
     * Unknown or absent reads back as [CallVoiceEffects.Effect.NONE] — an effect this
     * build does not have must not silently become a different one.
     */
    @Volatile var voiceEffect: String? = load("voiceEffect")
        set(value) { field = value; save("voiceEffect", value) }

    /** The stored default, parsed. */
    fun defaultVoiceEffect(): CallVoiceEffects.Effect = CallVoiceEffects.Effect.ofWire(voiceEffect)

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
     * input. Linux: `/dev/video*`. macOS: see [macCameras].
     *
     * This doc used to end "macOS: EMPTY … so the Mac uses the system default camera and
     * the picker says so", and the reason it gave was correct: FFmpeg's avfoundation input
     * really does not implement `avdevice_list_input_sources` (dshow does, which is why
     * Windows gets a list from the API). The conclusion did not follow. FFmpeg is not the
     * only thing on a Mac that knows what cameras exist.
     */
    fun cameras(os: String = System.getProperty("os.name").orEmpty()): List<Pair<String, String>> {
        val o = os.lowercase()
        return when {
            o.contains("win") -> com.oshi.desktop.call.video.CameraCapture.dshowCameras()
            o.contains("mac") -> macCameras()
            else -> linuxCameras(File("/dev"))
        }
    }

    /**
     * The Mac's cameras, from `system_profiler SPCameraDataType -json`.
     *
     * **It does not open a camera to find out.** That matters more than it sounds: the
     * obvious alternative is to try avfoundation indices 0, 1, 2… and keep the ones that
     * open, which turns the camera light on several times to populate a menu nobody has
     * chosen from yet. `system_profiler` reads the device registry and touches nothing.
     *
     * The NAME is the key, not an index, because avfoundation accepts either and only the
     * name survives a device being unplugged and replaced — an index silently becomes a
     * different camera. A name containing `:` is DROPPED rather than offered: avfoundation
     * parses its input as `video:audio`, so such a name cannot be addressed unambiguously,
     * and a menu entry that selects the wrong camera is worse than one that is absent.
     *
     * There is precedent for calling a platform tool rather than adding a dependency —
     * `LoRaSerial` configures a serial port with `stty` for the same reason. A failure here
     * is an empty list, never an exception: the picker falling back to the default camera
     * is a working app, and a settings menu is not worth a crash.
     */
    internal fun macCameras(
        run: () -> String? = ::systemProfilerCameras,
    ): List<Pair<String, String>> = runCatching {
        parseMacCameras(run() ?: return emptyList())
    }.getOrDefault(emptyList())

    /** Pure, so the parse is tested without a Mac and without a subprocess. */
    internal fun parseMacCameras(json: String): List<Pair<String, String>> = runCatching {
        val items = org.json.JSONObject(json).optJSONArray("SPCameraDataType") ?: return emptyList()
        (0 until items.length())
            .mapNotNull { items.optJSONObject(it)?.optString("_name")?.trim()?.takeIf { n -> n.isNotEmpty() } }
            .filterNot { it.contains(':') }
            .distinct()
            .map { it to it }
    }.getOrDefault(emptyList())

    private fun systemProfilerCameras(): String? = runCatching {
        val p = ProcessBuilder("/usr/sbin/system_profiler", "SPCameraDataType", "-json")
            .redirectErrorStream(false)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        // Bounded: a hung system_profiler must not hold the menu open forever.
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        out.takeIf { p.exitValue() == 0 }
    }.getOrNull()

    internal fun linuxCameras(dev: File): List<Pair<String, String>> =
        (dev.listFiles { f -> f.name.matches(Regex("video\\d+")) } ?: emptyArray())
            .sortedBy { it.name.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }
            .map { it.path to (readV4l2Name(it.name) ?: it.path) }

    private fun readV4l2Name(node: String): String? = runCatching {
        File("/sys/class/video4linux/$node/name").readText().trim().takeIf { it.isNotEmpty() }?.let { "$it ($node)" }
    }.getOrNull()
}
