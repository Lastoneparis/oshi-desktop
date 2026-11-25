package com.oshi.desktop.store

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __SECOND_LAUNCH_DID_NOTHING_2026_11_19__ A second launch on a profile whose window is hidden
 * in the tray used to exit silently. It must reach the running instance, and only with the token.
 */
class InstanceActivationTest {
    private val home: File = Files.createTempDirectory("oshi-activation").toFile()

    @After
    fun tearDown() { InstanceActivation.stopForTest(); home.deleteRecursively() }

    @Test
    fun `a second launch reaches the running instance and is acknowledged`() {
        val hit = CountDownLatch(1)
        InstanceActivation.onActivate { hit.countDown() }
        assertTrue(InstanceActivation.listen(home))
        assertTrue("the running instance must acknowledge", InstanceActivation.signal(home))
        assertTrue(hit.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a request that arrives before the window exists is replayed when it registers`() {
        assertTrue(InstanceActivation.listen(home))
        assertTrue(InstanceActivation.signal(home))
        val n = AtomicInteger()
        Thread.sleep(300)
        InstanceActivation.onActivate { n.incrementAndGet() }
        assertEquals(1, n.get())
    }

    @Test
    fun `a wrong token does nothing and is not acknowledged`() {
        val n = AtomicInteger()
        InstanceActivation.onActivate { n.incrementAndGet() }
        assertTrue(InstanceActivation.listen(home))
        val port = File(home, InstanceActivation.FILE_NAME).readText().trim().split(' ')[0]
        File(home, InstanceActivation.FILE_NAME).writeText("$port 00000000000000000000000000000000")
        assertFalse(InstanceActivation.signal(home))
        Thread.sleep(300)
        assertEquals(0, n.get())
    }

    @Test
    fun `no running instance is a plain false, never an exception`() {
        assertFalse(InstanceActivation.signal(home))
        File(home, InstanceActivation.FILE_NAME).writeText("1 abc")
        assertFalse(InstanceActivation.signal(home, timeoutMs = 300))
    }

    @Test
    fun `a second launch for an oshi link hands the link to the running window`() {
        val got = java.util.concurrent.LinkedBlockingQueue<String>()
        InstanceActivation.onActivate { link -> got.add(link ?: "<none>") }
        assertTrue(InstanceActivation.listen(home))
        assertTrue(InstanceActivation.signal(home, link = "oshi://add?key=Zm9v+YmFy/"))
        assertEquals("oshi://add?key=Zm9v+YmFy/", got.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a link that started the app waits for the window`() {
        InstanceActivation.request("oshi://group/join?gid=1")
        val got = ArrayList<String?>()
        InstanceActivation.onActivate { got += it }
        assertEquals(listOf<String?>("oshi://group/join?gid=1"), got)
    }
}

/** __PROTOCOL_ARG_INJECTION_2026_11_20__ */
class LinkLaunchArgsTest {
    @org.junit.Test
    fun `an injected quote cannot add flags to a link launch`() {
        // What CreateProcess yields for: "OSHI.exe" "oshi://add?key=x" --server https://evil --home \\h\s ""
        val argv = arrayOf("oshi://add?key=x", "--server", "https://evil", "--home", "\\\\h\\s", "--client")
        org.junit.Assert.assertArrayEquals(arrayOf("oshi://add?key=x"), InstanceActivation.linkLaunchArgs(argv))
    }

    @org.junit.Test
    fun `no link means no link launch`() {
        org.junit.Assert.assertNull(InstanceActivation.linkLaunchArgs(arrayOf("--ui", "--home", "x")))
    }
}
