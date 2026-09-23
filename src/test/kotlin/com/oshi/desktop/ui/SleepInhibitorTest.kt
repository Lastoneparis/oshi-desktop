package com.oshi.desktop.ui

import com.oshi.desktop.call.media.CallDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SleepInhibitorTest {

    @Test
    fun `each OS asks its own tool, tied to this process`() {
        val mac = SleepInhibitor(os = "Mac OS X", pid = 4242).command()!!
        assertEquals(listOf("/usr/bin/caffeinate", "-d", "-i", "-w", "4242"), mac)

        val linux = SleepInhibitor(os = "Linux", pid = 1).command()!!
        assertEquals("systemd-inhibit", linux.first())
        assertTrue(linux.contains("--what=idle:sleep"))

        val win = SleepInhibitor(os = "Windows 11", pid = 777).command()!!
        assertEquals("powershell.exe", win.first())
        val script = win.last()
        assertTrue("ES_CONTINUOUS|ES_SYSTEM_REQUIRED|ES_DISPLAY_REQUIRED", script.contains("0x80000003"))
        assertTrue("exits with us", script.contains("Get-Process -Id 777"))
    }

    @Test
    fun `hold launches once, release destroys, a failed launch is harmless`() {
        val launched = ArrayList<List<String>>()
        val inhibitor = SleepInhibitor(os = "Linux", pid = 1, launch = { cmd ->
            launched += cmd
            ProcessBuilder("sleep", "30").start()
        })
        inhibitor.hold(); inhibitor.hold()
        assertEquals(1, launched.size)
        assertTrue(inhibitor.active)
        inhibitor.release()
        assertFalse(inhibitor.active)

        val broken = SleepInhibitor(os = "Linux", pid = 1, launch = { null })
        broken.hold()
        assertFalse(broken.active)
        broken.release()
    }

    @Test
    fun `on this Mac, caffeinate really holds and really lets go`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac") && File("/usr/bin/caffeinate").canExecute())
        val inhibitor = SleepInhibitor()
        inhibitor.hold()
        Thread.sleep(300)
        assertTrue("caffeinate is running", inhibitor.active)
        inhibitor.release()
        Thread.sleep(300)
        assertFalse(inhibitor.active)
    }

    @Test
    fun `linux cameras are the video nodes, in numeric order, named from sysfs when it can`() {
        val dev = Files.createTempDirectory("dev").toFile()
        listOf("video10", "video2", "video0", "vhci", "videoX").forEach { File(dev, it).createNewFile() }
        val cams = CallDevices.linuxCameras(dev).map { it.first.substringAfterLast('/') }
        assertEquals(listOf("video0", "video2", "video10"), cams)
        dev.deleteRecursively()
    }
}
