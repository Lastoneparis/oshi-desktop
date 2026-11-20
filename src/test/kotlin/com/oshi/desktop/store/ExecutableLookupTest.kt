package com.oshi.desktop.store

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** __WHICH_FORGOT_DOT_EXE_2026_11_19__ ffmpeg on a Windows PATH is ffmpeg.exe. */
class ExecutableLookupTest {
    private val dir: File = Files.createTempDirectory("oshi which").toFile()

    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun `on Windows ffmpeg resolves to ffmpeg exe on PATH`() {
        val exe = File(dir, "ffmpeg.exe").apply { writeText("") }
        val got = ExecutableLookup.which("ffmpeg", path = "C:\\nowhere;\"${dir.path}\"", windows = true, pathExt = ".COM;.EXE")
        assertEquals(exe.absolutePath, got)
    }

    @Test
    fun `on Windows a bare name with no extension file is not found`() {
        assertNull(ExecutableLookup.which("ffmpeg", path = dir.path, windows = true, pathExt = null))
    }

    @Test
    fun `off Windows the name is used as is and must be executable`() {
        val f = File(dir, "ffmpeg").apply { writeText("") }
        assertNull(ExecutableLookup.which("ffmpeg", path = dir.path, windows = false, isExecutable = { false }))
        // `/x:<dir>` only where a directory has no drive letter: on a real Windows host the temp
        // dir is `C:\…`, and a ':'-separated PATH cannot carry it (this case failed there).
        val path = if (File.pathSeparatorChar == ':') "/x:${dir.path}" else dir.path
        assertEquals(f.absolutePath, ExecutableLookup.which("ffmpeg", path = path, windows = false, isExecutable = { it.isFile }))
    }
}

/**
 * __VOICE_NOTES_NEED_NO_SYSTEM_FFMPEG_2026_11_19__ The ffmpeg program bundled with the video
 * natives is found and actually transcodes, so a machine with no ffmpeg on PATH plays phone notes.
 */
class BundledFfmpegTest {
    @Test
    fun `the bundled ffmpeg exists and turns a WAV into M4A without anything on PATH`() {
        val exe = com.oshi.desktop.media.AudioProc.bundledFfmpeg
        org.junit.Assume.assumeTrue("no bytedeco natives for this host", exe != null)
        val dir = Files.createTempDirectory("oshi bundled ffmpeg").toFile()
        try {
            val t = com.oshi.desktop.media.SystemAudioTranscoder(
                tools = listOf(com.oshi.desktop.media.TranscodeTool.FFMPEG),
                resolve = { b -> if (b == "ffmpeg") exe else null },
                workDir = dir,
            )
            val src = File(dir, "in.wav").apply { writeBytes(com.oshi.desktop.media.WavWriter.tone(millis = 300)) }
            val out = File(dir, "out.m4a")
            val r = t.toM4a(src, out)
            org.junit.Assert.assertTrue("bundled ffmpeg did not produce M4A: $r", r is com.oshi.desktop.media.TranscodeResult.Produced)
            val back = File(dir, "back.wav")
            org.junit.Assert.assertTrue(t.toWav(out, back) is com.oshi.desktop.media.TranscodeResult.Produced)
        } finally { dir.deleteRecursively() }
    }
}
