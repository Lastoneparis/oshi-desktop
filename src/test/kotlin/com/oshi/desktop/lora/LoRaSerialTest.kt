package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The serial attachment's checkable half.
 *
 * What a test can prove without a radio: the validation boundary, the argv `stty` is given,
 * discovery, and that a failed configure ABORTS the open instead of continuing on a cooked
 * tty. What it cannot prove is that a board answers — that is the live check recorded in
 * PARITY.md row 0.27, and nothing here should be read as standing in for it.
 */
class LoRaSerialTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "lora-dev-${System.nanoTime()}").apply { mkdirs() }

    // ---------------------------------------------------------------- the path boundary

    @Test
    fun `a real device path is accepted`() {
        assertEquals("/dev/cu.usbmodem21201", LoRaSerial.devicePath("/dev/cu.usbmodem21201"))
        assertEquals("/dev/ttyACM0", LoRaSerial.devicePath("/dev/ttyACM0"))
    }

    /**
     * The path becomes an argument to a PROCESS. These are rejected by construction rather
     * than escaped, so the guarantee survives someone changing how the process is started.
     */
    @Test
    fun `anything that is not a plain device name under dev is refused`() {
        assertNull("traversal", LoRaSerial.devicePath("/dev/../etc/passwd"))
        assertNull("not directly in /dev", LoRaSerial.devicePath("/dev/sub/dir"))
        assertNull("not /dev at all", LoRaSerial.devicePath("/tmp/cu.fake"))
        assertNull("relative", LoRaSerial.devicePath("cu.usbmodem1"))
        assertNull("shell metacharacters", LoRaSerial.devicePath("/dev/cu.x; rm -rf /"))
        assertNull("NUL", LoRaSerial.devicePath("/dev/cu.x\u0000"))
        assertNull("newline", LoRaSerial.devicePath("/dev/cu.x\n115200"))
        assertNull("substitution", LoRaSerial.devicePath("/dev/cu.x\$(id)"))
        assertNull("untrimmed", LoRaSerial.devicePath(" /dev/cu.x"))
        assertNull("empty name", LoRaSerial.devicePath("/dev/"))
        assertNull("leading dot", LoRaSerial.devicePath("/dev/.hidden"))
    }

    // ---------------------------------------------------------------- the stty argv

    @Test
    fun `stty reads the device from -f on macOS and -F on Linux`() {
        assertEquals("-f", LoRaSerial.deviceFlag("Mac OS X"))
        assertEquals("-F", LoRaSerial.deviceFlag("Linux"))
    }

    /**
     * Each of these flags is a byte-corrupting default this protocol cannot absorb, and the
     * reason each one is here is in [LoRaSerial]'s doc. Asserting them individually is the
     * point: `raw` alone is not identically defined on the two platforms, and a future edit
     * that drops `-ixon` would silently start eating 0x11 out of protobufs.
     */
    @Test
    fun `the stty argv disables every line-discipline default that corrupts a frame`() {
        val argv = LoRaSerial.sttyCommand("/dev/cu.usbmodem21201", 115_200, "Mac OS X")
        assertEquals("stty", argv.first())
        assertEquals(listOf("-f", "/dev/cu.usbmodem21201", "115200"), argv.subList(1, 4))
        for (flag in listOf("raw", "-echo", "-ixon", "-ixoff", "-crtscts", "clocal", "cs8", "-parenb")) {
            assertTrue("the radio depends on $flag", argv.contains(flag))
        }
        // read() must block for a byte rather than return 0 in a spin.
        assertEquals(listOf("min", "1", "time", "0"), argv.takeLast(4))
    }

    @Test
    fun `the device path is one argv element, never a shell string`() {
        val argv = LoRaSerial.sttyCommand("/dev/cu.usbmodem21201")
        assertEquals(1, argv.count { it == "/dev/cu.usbmodem21201" })
        assertFalse("a space would mean the argv was joined", argv.any { it.contains(' ') })
    }

    // ---------------------------------------------------------------- discovery

    @Test
    fun `discovery offers radios and not every character device in dev`() {
        val dir = tempDir()
        try {
            listOf(
                "cu.usbmodem21201", "cu.usbserial-10", "ttyACM0", "ttyUSB3",
                "cu.Bluetooth-Incoming-Port", "null", "disk0s1", "tty.usbmodem21201",
            ).forEach { File(dir, it).writeText("") }

            val found = LoRaSerial.listDevices(dir).map { it.name }
            assertEquals(listOf("cu.usbmodem21201", "cu.usbserial-10", "ttyACM0", "ttyUSB3"), found)
            // `tty.` blocks in open() until carrier detect; offering it reads as a dead radio.
            assertFalse(found.any { it.startsWith("tty.") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `discovery is sorted so a refreshed picker does not reshuffle`() {
        val dir = tempDir()
        try {
            listOf("ttyUSB1", "cu.usbmodem9", "ttyACM0").forEach { File(dir, it).writeText("") }
            assertEquals(LoRaSerial.listDevices(dir), LoRaSerial.listDevices(dir))
            assertEquals(listOf("cu.usbmodem9", "ttyACM0", "ttyUSB1"), LoRaSerial.listDevices(dir).map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a missing dev directory is empty, not an exception`() {
        assertEquals(emptyList<LoRaSerial.Device>(), LoRaSerial.listDevices(File("/no/such/place")))
    }

    @Test
    fun `serial is refused on Windows rather than failing at open time`() {
        assertFalse(LoRaSerial.isSupported("Windows 11"))
        assertTrue(LoRaSerial.isSupported("Mac OS X"))
        assertTrue(LoRaSerial.isSupported("Linux"))
    }

    // ---------------------------------------------------------------- open-then-configure

    /**
     * THE RULE THIS FILE EXISTS FOR. A cooked tty does not fail loudly: it eats 0x11, swaps
     * 0x0D for 0x0A and echoes our own frames back into the parser. So a configure failure
     * must abort the attach, and the descriptor must not be left open behind it.
     */
    @Test
    fun `a failed configure aborts the open`() {
        // __POSIX_ONLY_OPEN_2026_10_29__ `LoRaChannel.Serial.open()` refuses up front
        // where serial is unsupported ("serial attach is POSIX-only on this build"), which
        // is the behaviour `serial is refused on Windows rather than failing at open time`
        // asserts two tests above. So on Windows the configure callback below is never
        // reached and this assertion read that refusal instead of its own message — the
        // production code was right and the test was asserting a POSIX-only ordering on a
        // platform that deliberately never gets there. Skipped rather than loosened: the
        // open-then-configure rule is real and must keep failing loudly where it applies.
        assumeTrue(LoRaSerial.isSupported())
        val f = File.createTempFile("lora-serial", ".bin")
        try {
            LoRaChannel.Serial(f.path, configure = { _, _ -> false }).open()
            fail("a cooked tty must not be handed back as a working link")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("stty failed"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `a configure that throws also aborts, and keeps its reason`() {
        // __POSIX_ONLY_OPEN_2026_10_29__ `LoRaChannel.Serial.open()` refuses up front
        // where serial is unsupported ("serial attach is POSIX-only on this build"), which
        // is the behaviour `serial is refused on Windows rather than failing at open time`
        // asserts two tests above. So on Windows the configure callback below is never
        // reached and this assertion read that refusal instead of its own message — the
        // production code was right and the test was asserting a POSIX-only ordering on a
        // platform that deliberately never gets there. Skipped rather than loosened: the
        // open-then-configure rule is real and must keep failing loudly where it applies.
        assumeTrue(LoRaSerial.isSupported())
        val f = File.createTempFile("lora-serial", ".bin")
        try {
            LoRaChannel.Serial(f.path, configure = { _, _ -> throw RuntimeException("no stty here") }).open()
            fail("a throwing configure must not be swallowed")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("no stty here"))
        } finally {
            f.delete()
        }
    }

    /**
     * Both streams come off ONE descriptor. Two `open(2)` calls on a tty would give two
     * independent termios states, so configuring one would leave the other cooked — which
     * is why this asserts a write through the channel reaches the device.
     */
    @Test
    fun `the two streams share one descriptor, and configure gets the path and baud`() {
        // __POSIX_ONLY_OPEN_2026_10_29__ `LoRaChannel.Serial.open()` refuses up front
        // where serial is unsupported ("serial attach is POSIX-only on this build"), which
        // is the behaviour `serial is refused on Windows rather than failing at open time`
        // asserts two tests above. So on Windows the configure callback below is never
        // reached and this assertion read that refusal instead of its own message — the
        // production code was right and the test was asserting a POSIX-only ordering on a
        // platform that deliberately never gets there. Skipped rather than loosened: the
        // open-then-configure rule is real and must keep failing loudly where it applies.
        assumeTrue(LoRaSerial.isSupported())
        val f = File.createTempFile("lora-serial", ".bin")
        try {
            var seen: Pair<String, Int>? = null
            val conn = LoRaChannel.Serial(f.path, 57_600, configure = { p, b -> seen = p to b; true }).open()
            assertEquals(f.path to 57_600, seen)
            conn.output.write(byteArrayOf(0x94.toByte(), 0xC3.toByte(), 1, 2))
            conn.output.flush()
            conn.close()
            assertEquals(
                listOf(0x94.toByte(), 0xC3.toByte(), 1.toByte(), 2.toByte()),
                f.readBytes().toList(),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun `the channel label is the device, so the log and thread name name it`() {
        assertEquals("/dev/cu.usbmodem21201", LoRaChannel.Serial("/dev/cu.usbmodem21201").label)
    }

    // ---------------------------------------------------------------- packet ids

    @Test
    fun `a packet id is never zero`() {
        // The firmware treats an absent id and a zero id alike, so a zero would make the
        // packet undedupable. 2000 draws is enough to catch an `and` where an `or` belongs.
        repeat(2_000) { assertTrue(LoRaProto.randomPacketId() != 0u) }
    }

    @Test
    fun `the interop text limit is the number iOS ships`() {
        // OSHI/MeshtasticManager.swift:230. A desktop that allowed more would produce text
        // a phone refuses to send and a stock client receives in fragments.
        assertEquals(200, LoRaProto.INTEROP_TEXT_LIMIT)
    }
}
