package com.oshi.desktop.security

import com.oshi.desktop.security.RemoteControlDetector.Category
import com.oshi.desktop.security.RemoteControlDetector.Proc
import com.oshi.desktop.store.StrictMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** __STRICT_MODE_2026_11_22__ the maintained list, the matcher, and the two OS scanners' parsers. */
class RemoteControlDetectorTest {

    @After fun reset() { StrictMode.attachForTest(null); RemoteControlWatch.preview(emptyList()) }

    private fun ids(vararg p: Proc) = RemoteControlDetector.match(p.toList()).map { it.id }

    @Test fun the_spec_list_is_covered_on_windows_image_names() {
        val rows = mapOf(
            "TeamViewer.exe" to "teamviewer", "TeamViewer_Service.exe" to "teamviewer",
            "AnyDesk.exe" to "anydesk", "rustdesk.exe" to "rustdesk",
            "remoting_host.exe" to "chrome-remote-desktop",
            "tvnserver.exe" to "vnc", "winvnc.exe" to "vnc", "vncserver.exe" to "vnc",
            "parsecd.exe" to "parsec", "SRServer.exe" to "splashtop",
            "ScreenConnect.ClientService.exe" to "screenconnect",
            "rdpclip.exe" to "rdp", "QuickAssist.exe" to "quickassist",
            "CptHost.exe" to "zoom-share", "obs64.exe" to "obs",
            "AutoHotkey64.exe" to "autohotkey", "PAD.Robot.exe" to "power-automate",
            "Claude.exe" to "claude", "ChatGPT.exe" to "chatgpt", "AIXHost.exe" to "recall",
            "UI TARS.exe" to "ui-tars",
        )
        for ((image, id) in rows) assertEquals(image, listOf(id), ids(Proc(image)))
    }

    @Test fun the_spec_list_is_covered_on_linux_commands() {
        assertEquals(listOf("vnc"), ids(Proc("x11vnc", listOf("x11vnc", "-display", ":0"))))
        assertEquals(listOf("vnc"), ids(Proc("Xtightvnc")))
        assertEquals(listOf("vnc"), ids(Proc("vncserver-x11-c", listOf("/usr/bin/vncserver-x11-core"))))
        assertEquals(listOf("teamviewer"), ids(Proc("teamviewerd")))
        assertEquals(listOf("anydesk"), ids(Proc("anydesk", listOf("/usr/bin/anydesk", "--service"))))
        assertEquals(listOf("rustdesk"), ids(Proc("rustdesk")))
        assertEquals(listOf("xrdp"), ids(Proc("xrdp-sesman")))
        assertEquals(listOf("vnc"), ids(Proc("krfb")))
    }

    @Test fun a_15_character_proc_comm_still_matches_a_longer_name() {
        // Chrome Remote Desktop's host on Linux: comm is cut to "remoting_me2me_".
        assertEquals(listOf("chrome-remote-desktop"), ids(Proc("remoting_me2me_")))
        assertEquals(listOf("gnome-remote-desktop"), ids(Proc("gnome-remote-de")))
        // ...but a short name is never matched by prefix.
        assertEquals(emptyList<String>(), ids(Proc("obsidian")))
    }

    @Test fun python_and_node_agents_are_recognised_by_their_script() {
        assertEquals(listOf("open-interpreter"), ids(Proc("python3", listOf("/usr/bin/python3", "/home/a/.local/bin/interpreter", "--os"))))
        assertEquals(listOf("self-operating-computer"), ids(Proc("python3.12", listOf("python3.12", "-m", "operate"))))
        assertEquals(listOf("agent-s"), ids(Proc("python", listOf("python", "-m", "agent_s"))))
        assertEquals(listOf("open-interpreter"), ids(Proc("python.exe", listOf("C:\\Python312\\python.exe", "C:\\Users\\a\\Scripts\\interpreter.exe"))))
    }

    @Test fun ffmpeg_counts_only_when_grabbing_the_screen() {
        assertEquals(emptyList<String>(), ids(Proc("ffmpeg", listOf("ffmpeg", "-i", "in.mp4", "out.webm"))))
        assertEquals(listOf("ffmpeg-grab"), ids(Proc("ffmpeg", listOf("ffmpeg", "-f", "x11grab", "-i", ":0", "o.mkv"))))
        assertEquals(listOf("ffmpeg-grab"), ids(Proc("ffmpeg.exe", listOf("ffmpeg.exe", "-f", "gdigrab", "-i", "desktop", "o.mkv"))))
    }

    @Test fun ordinary_processes_are_not_flagged() {
        val normal = listOf("explorer.exe", "svchost.exe", "chrome.exe", "Teams.exe", "Discord.exe", "java.exe",
            "systemd", "gnome-shell", "Xorg", "pipewire", "firefox", "code", "bash", "OSHI.exe", "python3")
        assertEquals(emptyList<String>(), RemoteControlDetector.match(normal.map { Proc(it) }).map { it.id })
    }

    @Test fun each_program_is_reported_once_in_catalog_order() {
        val hits = RemoteControlDetector.match(listOf(Proc("AnyDesk.exe"), Proc("TeamViewer.exe"), Proc("anydesk"), Proc("tv_x64.exe")))
        assertEquals(listOf("teamviewer", "anydesk"), hits.map { it.id })
        assertEquals(Category.REMOTE_CONTROL, hits.first().category)
    }

    @Test fun catalog_is_well_formed() {
        val ids = RemoteControlDetector.CATALOG.map { it.id }
        assertEquals("ids are unique", ids.size, ids.toSet().size)
        for (k in RemoteControlDetector.CATALOG) {
            assertTrue(k.id, k.names.isNotEmpty())
            k.names.forEach { n -> assertEquals("names are stored normalised: $n", RemoteControlDetector.normalize(n), n) }
        }
        for (c in Category.entries) assertTrue("$c has entries", RemoteControlDetector.CATALOG.any { it.category == c })
    }

    @Test fun tasklist_csv_is_parsed() {
        val out = "\r\n\"System Idle Process\",\"0\",\"Services\",\"0\",\"8 K\"\r\n" +
            "\"AnyDesk.exe\",\"4242\",\"Console\",\"1\",\"21,004 K\"\r\nINFO: something\r\n"
        val procs = RemoteControlDetector.parseTasklistCsv(out)
        assertEquals(listOf("System Idle Process", "AnyDesk.exe"), procs.map { it.comm })
        assertEquals(listOf("anydesk"), RemoteControlDetector.match(procs).map { it.id })
    }

    @Test fun proc_tree_is_scanned() {
        val root = Files.createTempDirectory("fakeproc").toFile()
        try {
            fun proc(pid: Int, comm: String, vararg argv: String) = File(root, pid.toString()).apply {
                mkdirs(); File(this, "comm").writeText("$comm\n")
                File(this, "cmdline").writeBytes(argv.joinToString("\u0000", postfix = "\u0000").toByteArray())
            }
            proc(1, "systemd", "/sbin/init")
            proc(812, "x11vnc", "x11vnc", "-forever")
            proc(900, "python3", "python3", "-m", "operate")
            File(root, "self").mkdirs()          // not a pid
            File(root, "77").mkdirs()            // a process that vanished: no comm
            val hits = RemoteControlDetector.match(RemoteControlDetector.scanProc(root)).map { it.id }
            assertEquals(listOf("vnc", "self-operating-computer"), hits)
        } finally { root.deleteRecursively() }
    }

    @Test fun an_rdp_session_is_recognised_from_the_environment() {
        assertTrue(RemoteControlDetector.isRdpSession(mapOf("SESSIONNAME" to "RDP-Tcp#3")))
        assertFalse(RemoteControlDetector.isRdpSession(mapOf("SESSIONNAME" to "Console")))
        assertFalse(RemoteControlDetector.isRdpSession(emptyMap()))
    }

    @Test fun the_banner_shows_only_in_strict_mode_and_a_dismissal_holds_until_the_set_changes() {
        val tv = RemoteControlDetector.Hit("teamviewer", "TeamViewer", Category.REMOTE_CONTROL)
        val ad = RemoteControlDetector.Hit("anydesk", "AnyDesk", Category.REMOTE_CONTROL)
        RemoteControlWatch.preview(listOf(tv))
        StrictMode.preview(StrictMode.Values())
        assertEquals(emptyList<Any>(), RemoteControlWatch.visible)
        StrictMode.preview(StrictMode.Values(enabled = true))
        assertEquals(listOf(tv), RemoteControlWatch.visible)
        RemoteControlWatch.dismiss()
        assertEquals(emptyList<Any>(), RemoteControlWatch.visible)
        // a new program appears: shown again (preview() resets the dismissal, so set hits by hand)
        val f = RemoteControlWatch::class.java.getDeclaredField("hits\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(RemoteControlWatch) as androidx.compose.runtime.MutableState<List<RemoteControlDetector.Hit>>).value = listOf(tv, ad)
        assertEquals(listOf(tv, ad), RemoteControlWatch.visible)
    }
}
