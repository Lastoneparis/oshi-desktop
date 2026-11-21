package com.oshi.desktop.update

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** __DESKTOP_AUTO_UPDATE_2026_11_21__ snooze, the download's verification, and the controller's flow. */
class UpdateFlowTest {

    private val tmp: File = Files.createTempDirectory("oshi-update-test").toFile()
    private var server: HttpServer? = null

    @After fun cleanup() {
        server?.stop(0)
        tmp.deleteRecursively()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A loopback server answering [path] with [body] (and 404 elsewhere). */
    private fun serve(path: String, body: ByteArray, status: Int = 200): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val ok = ex.requestURI.path == path
            val b = if (ok) body else ByteArray(0)
            ex.sendResponseHeaders(if (ok) status else 404, if (b.isEmpty()) -1 else b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}$path"
    }

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    // ------------------------------------------------------------------ snooze

    @Test fun `Later snoozes that version for 3 days, and only that version`() {
        val p = UpdatePreferences(File(tmp, UpdatePreferences.FILE_NAME))
        val now = 1_000_000_000_000L
        assertFalse(p.isSnoozed(Version(1, 6, 1), now))
        p.snooze(Version(1, 6, 1), now)
        assertTrue(p.isSnoozed(Version(1, 6, 1), now + 1))
        assertTrue(p.isSnoozed(Version(1, 6, 1), now + UpdatePreferences.SNOOZE_MS - 1))
        assertFalse(p.isSnoozed(Version(1, 6, 1), now + UpdatePreferences.SNOOZE_MS))
        assertFalse("a newer release is offered at once", p.isSnoozed(Version(1, 6, 2), now + 1))
        // Survives a restart.
        assertTrue(UpdatePreferences(File(tmp, UpdatePreferences.FILE_NAME)).isSnoozed(Version(1, 6, 1), now + 1))
        p.clear()
        assertFalse(p.isSnoozed(Version(1, 6, 1), now + 1))
    }

    @Test fun `a corrupt snooze file reads as nothing snoozed`() {
        val f = File(tmp, UpdatePreferences.FILE_NAME).apply { writeText("{not json") }
        assertNull(UpdatePreferences(f).snooze())
        assertFalse(UpdatePreferences(f).isSnoozed(Version(1, 6, 1), 0))
    }

    // ------------------------------------------------------------------ download

    @Test fun `a download that matches the signed size and hash is kept`() {
        val body = ByteArray(300_000) { (it * 31).toByte() }
        val url = serve("/OSHI-1.6.1.msi", body)
        val dest = File(tmp, "dl/oshi-desktop-1.6.1.msi")
        var last = 0L
        val f = UpdateDownloader.download(http, UpdateFile(url, sha(body), body.size.toLong()), dest, { d, _ -> last = d })
        assertEquals(dest, f)
        assertTrue(f.readBytes().contentEquals(body))
        assertEquals(body.size.toLong(), last)
        assertFalse(File(tmp, "dl/oshi-desktop-1.6.1.msi.part").exists())
    }

    @Test fun `different bytes of the same size - refused and deleted`() {
        val body = "the real installer".toByteArray()
        val url = serve("/x.deb", "the evil installer".toByteArray())
        val dest = File(tmp, "x.deb")
        try {
            UpdateDownloader.download(http, UpdateFile(url, sha(body), body.size.toLong()), dest)
            fail("installed a file that does not match")
        } catch (_: UpdateDownloader.VerificationFailed) {}
        assertFalse(dest.exists())
        assertFalse(File(tmp, "x.deb.part").exists())
    }

    @Test fun `longer or shorter than signed - refused and deleted`() {
        val body = ByteArray(5000) { 7 }
        val url = serve("/x.rpm", body)
        for (claimed in listOf(4000L, 6000L)) {
            val dest = File(tmp, "x.rpm")
            try {
                UpdateDownloader.download(http, UpdateFile(url, sha(body), claimed), dest)
                fail("accepted size $claimed for ${body.size} bytes")
            } catch (_: UpdateDownloader.VerificationFailed) {}
            assertFalse(dest.exists())
        }
    }

    @Test fun `a 404 is a download failure, not a verification failure, and leaves nothing`() {
        val url = serve("/x.msi", ByteArray(10), status = 404)
        val dest = File(tmp, "x.msi")
        try {
            UpdateDownloader.download(http, UpdateFile(url, "a".repeat(64), 10), dest)
            fail()
        } catch (e: UpdateDownloader.VerificationFailed) {
            fail("404 reported as tampering")
        } catch (_: java.io.IOException) {}
        assertFalse(dest.exists())
    }

    @Test fun `check over HTTP - manifest and signature fetched, verified, parsed`() {
        val k = UpdateManifestTest.Companion.Keys()
        val body = UpdateManifestTest.manifestJson("1.6.1").toString().toByteArray()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = AtomicInteger()
        s.createContext("/") { ex ->
            hits.incrementAndGet()
            assertNull("no query string", ex.requestURI.rawQuery)
            val b = when (ex.requestURI.path) { "/m.json" -> body; "/m.json.sig" -> k.sigFile(body); else -> null }
            ex.sendResponseHeaders(if (b == null) 404 else 200, b?.size?.toLong() ?: -1)
            ex.responseBody.use { if (b != null) it.write(b) }
        }
        s.start(); server = s
        val ch = UpdateChannel("http://127.0.0.1:${s.address.port}/m.json", k.pub, "oshi-messenger.com", isTest = true)
        val r = UpdateCheck.check(http, ch, Version(1, 6, 0))
        assertTrue(r.toString(), r is CheckResult.Available)
        assertEquals(2, hits.get())
        val gone = UpdateChannel("http://127.0.0.1:${s.address.port}/missing.json", k.pub, null, isTest = true)
        assertTrue(UpdateCheck.check(http, gone, Version(1, 6, 0)) is CheckResult.Failed)
    }

    // ------------------------------------------------------------------ controller

    private fun manifest(v: String = "1.6.1") = UpdateManifest.parse(UpdateManifestTest.manifestJson(v).toString().toByteArray(), "oshi-messenger.com")

    private fun controller(
        result: () -> CheckResult = { CheckResult.Available(manifest()) },
        isMac: Boolean = false,
        current: Version? = Version(1, 6, 0),
        paused: Boolean = false,
        now: Long = 5_000_000L,
    ) = UpdateController(
        current = current, isMac = isMac, channel = UpdateChannel.PRODUCTION,
        prefs = UpdatePreferences(File(tmp, UpdatePreferences.FILE_NAME)), updatesDir = File(tmp, "updates"),
        check = { _, _ -> result() }, privacyPaused = { paused }, clock = { now },
        hardExit = {},
    )

    @Test fun `an automatic check that finds a release opens the prompt, Later closes it and snoozes`() {
        val c = controller()
        c.runCheck(manual = false)
        assertEquals(UpdateController.Phase.AVAILABLE, c.state.phase)
        assertTrue(c.state.prompt)
        c.later()
        assertFalse(c.state.prompt)
        // The 12-hour check finds the same version: stays closed.
        c.runCheck(manual = false)
        assertFalse(c.state.prompt)
        // "Check for updates" by hand ignores the snooze.
        c.runCheck(manual = true)
        assertTrue(c.state.prompt)
    }

    @Test fun `a failed automatic check is silent, a manual one says so`() {
        val c = controller(result = { CheckResult.Failed("down") })
        c.runCheck(manual = false)
        assertEquals(UpdateController.Phase.IDLE, c.state.phase)
        assertFalse(c.state.prompt)
        c.runCheck(manual = true)
        assertEquals(UpdateController.Phase.CHECK_FAILED, c.state.phase)
        assertFalse(c.state.prompt)
    }

    @Test fun `up to date - no prompt`() {
        val c = controller(result = { CheckResult.UpToDate(Version(1, 6, 0)) })
        c.runCheck(manual = true)
        assertEquals(UpdateController.Phase.UP_TO_DATE, c.state.phase)
        assertFalse(c.state.prompt)
    }

    @Test fun `macOS and an unversioned build never check`() {
        assertFalse(controller(isMac = true).supported)
        assertFalse(controller(current = null).supported)
        val c = controller(isMac = true)
        c.start(firstDelayMs = 0)
        c.checkNow()
        Thread.sleep(200)
        assertEquals(UpdateController.Phase.IDLE, c.state.phase)
    }

    private fun waitFor(c: UpdateController, vararg phases: UpdateController.Phase) {
        val end = System.currentTimeMillis() + 5_000
        while (c.state.phase !in phases && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("phase ${c.state.phase}, wanted ${phases.toList()}", c.state.phase in phases)
    }

    private class FakeInstaller(val linux: UpdateController.LinuxOutcome = UpdateController.LinuxOutcome.INSTALLED) : UpdateController.Installer {
        val calls = mutableListOf<String>()
        override fun startWindows(method: UpdateInstall.Method.WindowsMsi, msi: File, log: File, result: File): Boolean { calls += "windows:${msi.name}"; return true }
        override fun installLinux(method: UpdateInstall.Method, pkg: File, log: File): UpdateController.LinuxOutcome { calls += "linux:${pkg.name}"; return linux }
        override fun relaunchLinux(method: UpdateInstall.Method) { calls += "relaunch" }
        override fun manualCommand(pkg: File, method: UpdateInstall.Method) = "sudo apt-get install ${pkg.path}"
    }

    @Test fun `Update now on an MSI install downloads the MSI, starts it and quits`() {
        val c = controller()
        c.runCheck(manual = false)
        val quit = AtomicInteger()
        c.onQuit = { quit.incrementAndGet() }
        val fake = FakeInstaller()
        var asked: UpdateFile? = null
        c.updateNow(
            detect = { UpdateInstall.Method.WindowsMsi(File("C:\\OSHI"), File("C:\\OSHI\\OSHI.exe")) },
            download = { f, dest, _, _ -> asked = f; dest.parentFile.mkdirs(); dest.writeText("x"); dest },
            installer = fake,
        )
        waitFor(c, UpdateController.Phase.RESTARTING)
        assertEquals("https://oshi-messenger.com/downloads/OSHI-1.6.1.msi", asked!!.url)
        assertEquals(listOf("windows:oshi-desktop-1.6.1.msi"), fake.calls)
        val end = System.currentTimeMillis() + 5_000
        while (quit.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(1, quit.get())
    }

    @Test fun `Linux - password dialog dismissed - the person gets the folder and the command`() {
        val c = controller()
        c.runCheck(manual = false)
        var opened: File? = null
        c.openFolder = { opened = it }
        c.onQuit = { fail("must not quit when nothing was installed") }
        c.updateNow(
            detect = { UpdateInstall.Method.LinuxDeb(File("/opt/oshi-desktop/oshi-desktop/bin/OSHI")) },
            download = { _, dest, _, _ -> dest.parentFile.mkdirs(); dest.writeText("x"); dest },
            installer = FakeInstaller(UpdateController.LinuxOutcome.REFUSED),
        )
        waitFor(c, UpdateController.Phase.MANUAL)
        assertTrue(c.state.manualCommand!!.startsWith("sudo apt-get install"))
        assertTrue(c.state.manualCommand!!.endsWith("oshi-desktop-1.6.1.deb"))
        assertEquals(File(tmp, "updates"), opened)
        assertTrue("the verified package stays for the person", File(tmp, "updates/oshi-desktop-1.6.1.deb").isFile)
    }

    @Test fun `Linux - installed - relaunch then quit, package removed`() {
        val c = controller()
        c.runCheck(manual = false)
        val quit = AtomicInteger()
        c.onQuit = { quit.incrementAndGet() }
        val fake = FakeInstaller()
        c.updateNow(
            detect = { UpdateInstall.Method.LinuxRpm(File("/opt/oshi-desktop/oshi-desktop/bin/OSHI")) },
            download = { _, dest, _, _ -> dest.parentFile.mkdirs(); dest.writeText("x"); dest },
            installer = fake,
        )
        waitFor(c, UpdateController.Phase.RESTARTING)
        val end = System.currentTimeMillis() + 5_000
        while (quit.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(listOf("linux:oshi-desktop-1.6.1.rpm", "relaunch"), fake.calls)
        assertFalse(File(tmp, "updates/oshi-desktop-1.6.1.rpm").exists())
        assertEquals(1, quit.get())
    }

    @Test fun `a download that fails verification never reaches an installer`() {
        val c = controller()
        c.runCheck(manual = false)
        val fake = FakeInstaller()
        c.updateNow(
            detect = { UpdateInstall.Method.LinuxDeb(File("/opt/x/bin/OSHI")) },
            download = { _, _, _, _ -> throw UpdateDownloader.VerificationFailed("sha256 differs") },
            installer = fake,
        )
        waitFor(c, UpdateController.Phase.VERIFY_FAILED)
        assertTrue(fake.calls.isEmpty())
        c.dismiss()
        assertFalse(c.state.prompt)
    }

    @Test fun `a network failure offers a retry, a cancel goes back to the offer`() {
        val c = controller()
        c.runCheck(manual = false)
        c.updateNow(detect = { UpdateInstall.Method.LinuxDeb(File("/opt/x/bin/OSHI")) },
            download = { _, _, _, _ -> throw java.io.IOException("reset") }, installer = FakeInstaller())
        waitFor(c, UpdateController.Phase.DOWNLOAD_FAILED)
        c.updateNow(detect = { UpdateInstall.Method.LinuxDeb(File("/opt/x/bin/OSHI")) },
            download = { _, _, _, _ -> throw UpdateDownloader.Cancelled() }, installer = FakeInstaller())
        waitFor(c, UpdateController.Phase.AVAILABLE)
        assertTrue(c.state.prompt)
    }

    @Test fun `not installed by an OSHI installer - the website instead`() {
        val c = controller()
        c.runCheck(manual = false)
        var url: String? = null
        c.openUrl = { url = it }
        c.updateNow(detect = { UpdateInstall.Method.Unsupported("AppImage") },
            download = { _, _, _, _ -> fail("must not download"); error("") }, installer = FakeInstaller())
        waitFor(c, UpdateController.Phase.UNSUPPORTED)
        c.openDownloadPage()
        assertEquals(UpdateController.DOWNLOAD_PAGE, url)
    }

    @Test fun `a failed Windows install is reported once at the next start`() {
        File(tmp, "updates").mkdirs()
        File(tmp, "updates/${UpdateController.LAST_RESULT}").writeText("1603\r\n")
        val c = controller(result = { CheckResult.UpToDate(Version(1, 6, 0)) })
        c.start(firstDelayMs = 60_000)
        assertTrue(c.state.lastInstallFailed)
        assertFalse(File(tmp, "updates/${UpdateController.LAST_RESULT}").exists())
        c.stop()
        File(tmp, "updates/${UpdateController.LAST_RESULT}").writeText("3010")
        val ok = controller()
        ok.start(firstDelayMs = 60_000)
        assertFalse("3010 = installed, reboot suggested", ok.state.lastInstallFailed)
        ok.stop()
    }

    @Test fun `automatic checks pause under the privacy relay - the schedule does not call out`() {
        val calls = AtomicInteger()
        val c = controller(result = { calls.incrementAndGet(); CheckResult.Available(manifest()) }, paused = true)
        c.start(firstDelayMs = 0)
        Thread.sleep(300)
        c.stop()
        assertEquals(0, calls.get())
        assertTrue(c.automaticChecksPaused())
        // ...but a check the person asks for still runs.
        c.runCheck(manual = true)
        assertEquals(1, calls.get())
    }
}
