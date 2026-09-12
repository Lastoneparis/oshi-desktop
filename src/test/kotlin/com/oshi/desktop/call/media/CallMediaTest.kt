package com.oshi.desktop.call.media

import javax.sound.sampled.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 2.1 — the media frame, the nonce scheme, and what the JDK can be asked
 * about audio hardware without touching it.
 *
 * The directional-salt tests are the important ones in this file. The bug they pin was
 * SHIPPED and fixed in the phones (`VoiceCallManager.swift:12761-12763`), it is invisible
 * in every single-direction test, and it destroys the confidentiality of the whole call.
 */
class CallMediaTest {

    private val key = ByteArray(32) { (it * 7).toByte() }
    private val salt = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    private fun pcm(n: Int = CallAudio.BYTES_PER_FRAME) = ByteArray(n) { (it % 251).toByte() }

    // ============================================================== the nonce scheme

    /** `responderNonceSalt` — base with byte 0 XOR 0xA5 (`VoiceCallManager.swift:3047-3052`). */
    @Test
    fun `responder salt flips the first byte with 0xA5 and leaves the rest`() {
        val r = CallMediaFrame.responderSalt(salt)
        assertEquals((0x11 xor 0xA5).toByte(), r[0])
        assertEquals(0x22.toByte(), r[1])
        assertEquals(0x33.toByte(), r[2])
        assertEquals(0x44.toByte(), r[3])
    }

    /**
     * **The shipped crypto bug, pinned.**
     *
     * With ONE salt both directions emit identical nonces under identical keys at equal
     * counters — a total GCM break. The two transmit salts must never be equal.
     */
    @Test
    fun `the two directions never transmit under the same salt`() {
        val caller = CallMediaFrame.txSalt(salt, isCaller = true)
        val callee = CallMediaFrame.txSalt(salt, isCaller = false)
        assertFalse("identical salts = identical nonces under one key", caller.contentEquals(callee))
        assertArrayEquals("the initiator transmits under the base salt", salt, caller)
    }

    /** Same counter, opposite directions, different nonces. The property that matters. */
    @Test
    fun `the same sequence number produces different nonces in each direction`() {
        val a = CallMediaFrame.nonce(CallMediaFrame.txSalt(salt, true), 42)
        val b = CallMediaFrame.nonce(CallMediaFrame.txSalt(salt, false), 42)
        assertNotEquals(a.toList(), b.toList())
    }

    /** `txSalt(4) ‖ seq(8 BE)` — `VoiceCallManager.swift:12757-12768`. */
    @Test
    fun `nonce is four salt bytes then a big-endian counter`() {
        val n = CallMediaFrame.nonce(salt, 1)
        assertEquals(12, n.size)
        assertArrayEquals(salt, n.copyOfRange(0, 4))
        assertEquals("counter is BIG-endian: the 1 goes last", 1, n[11].toInt())
        assertEquals(0, n[4].toInt())
    }

    // ============================================================== the frame

    /** `[type][seq BE8][nonce(12) ‖ ct ‖ tag(16)]` — `VoiceCallManager.swift:10160-10172`. */
    @Test
    fun `frame layout is type, sequence, nonce, ciphertext and tag`() {
        val body = pcm()
        val f = CallMediaFrame.encode(key, salt, true, 1, CallMediaFrame.TYPE_PCM_48K, body)
        assertEquals(CallMediaFrame.TYPE_PCM_48K, f[0].toInt() and 0xFF)
        assertEquals("sequence is big-endian", 1, f[8].toInt())
        assertEquals(9 + 12 + body.size + 16, f.size)
        assertArrayEquals(CallMediaFrame.nonce(salt, 1), f.copyOfRange(9, 21))
    }

    @Test
    fun `raw pcm 48k is the type this client emits`() {
        assertEquals(0x15, CallMediaFrame.TYPE_PCM_48K)
        assertEquals(0x05, CallMediaFrame.TYPE_AAC_ELD)
        assertEquals(0x16, CallMediaFrame.TYPE_OSHI_CODEC)
    }

    /** Caller seals, callee opens — the direction a real call runs in. */
    @Test
    fun `a frame sealed by the caller opens for the callee`() {
        val body = pcm()
        val f = CallMediaFrame.encode(key, salt, true, 7, CallMediaFrame.TYPE_PCM_48K, body)
        val d = CallMediaFrame.decode(key, f)!!
        assertArrayEquals(body, d.pcm)
        assertEquals(7L, d.seq)
        assertEquals(CallMediaFrame.TYPE_PCM_48K, d.audioType)
    }

    /**
     * The decoder reads the nonce OFF THE WIRE rather than reconstructing it, matching
     * iOS (`:12789`, whose `rxNonceSalt` is explicitly diagnostics-only). That is what
     * makes interop survive a peer whose salt derivation differs from ours.
     */
    @Test
    fun `decoding uses the wire nonce, so a responder frame opens without knowing the direction`() {
        val body = pcm()
        val f = CallMediaFrame.encode(key, salt, isCaller = false, seq = 3, audioType = 0x15, pcm = body)
        val d = CallMediaFrame.decode(key, f)!!
        assertArrayEquals(body, d.pcm)
        assertEquals("the authenticated counter comes from the nonce", 3L, d.seq)
    }

    /** A tampered frame is null. Never a throw, never played. */
    @Test
    fun `a tampered frame decodes to null`() {
        val f = CallMediaFrame.encode(key, salt, true, 1, 0x15, pcm())
        f[f.size - 1] = (f[f.size - 1].toInt() xor 0xFF).toByte()
        assertNull(CallMediaFrame.decode(key, f))
    }

    @Test
    fun `a frame from another call's key decodes to null`() {
        val f = CallMediaFrame.encode(key, salt, true, 1, 0x15, pcm())
        assertNull(CallMediaFrame.decode(ByteArray(32) { 0x5A }, f))
    }

    @Test
    fun `a truncated frame decodes to null without throwing`() {
        assertNull(CallMediaFrame.decode(key, ByteArray(20)))
        assertNull(CallMediaFrame.decode(key, ByteArray(0)))
    }

    /** Counter 0 would be the all-zero nonce; the sequence starts at 1. */
    @Test
    fun `encoding refuses sequence zero`() {
        try {
            CallMediaFrame.encode(key, salt, true, 0, 0x15, pcm())
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sequence"))
        }
    }

    // ============================================================== the counter

    @Test
    fun `media sequence starts at one and never repeats`() {
        val s = MediaSequence()
        assertEquals(1L, s.next())
        assertEquals(2L, s.next())
        assertEquals(3L, s.next())
    }

    /** Concurrency matters: capture runs on its own thread and a repeat is a GCM break. */
    @Test
    fun `media sequence issues no duplicates under concurrent use`() {
        val s = MediaSequence()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val threads = (0 until 8).map {
            Thread { repeat(2_000) { seen.add(s.next()) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("every counter must be unique", 16_000, seen.size)
    }

    // ============================================================== replay

    @Test
    fun `a replayed sequence number is refused`() {
        val w = ReplayWindow()
        assertTrue(w.accept(1))
        assertFalse("a replay must not be played", w.accept(1))
    }

    /**
     * Out-of-order arrival is ACCEPTED. Media is UDP; a strict high-water mark throws away
     * most of a lossy cellular leg. iOS allows it for the same reason (`:12786-12800`).
     */
    @Test
    fun `an out-of-order frame inside the window is still played`() {
        val w = ReplayWindow()
        assertTrue(w.accept(10))
        assertTrue("reordering is normal on UDP, not an attack", w.accept(7))
        assertTrue(w.accept(11))
    }

    @Test
    fun `a frame from far behind the window is refused`() {
        val w = ReplayWindow(window = 8)
        assertTrue(w.accept(100))
        assertFalse(w.accept(50))
    }

    @Test
    fun `sequence zero is never accepted`() {
        assertFalse(ReplayWindow().accept(0))
        assertFalse(ReplayWindow().accept(-1))
    }

    // ============================================================== the audio format

    /**
     * **Big-endian.** `javax.sound.sampled` defaults to little-endian on a PCM line, and
     * every multi-byte field in this protocol is big-endian. Getting it wrong is not
     * silence — it is white noise, because every sample has its bytes swapped.
     */
    @Test
    fun `the pcm format is 48k mono signed 16-bit BIG-endian`() {
        val f = CallAudio.FORMAT
        assertEquals(48_000f, f.sampleRate, 0f)
        assertEquals(16, f.sampleSizeInBits)
        assertEquals(1, f.channels)
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, f.encoding)
        assertTrue("little-endian here is white noise on the far end", f.isBigEndian)
    }

    /** 20 ms at 48 kHz mono 16-bit = 960 samples = 1 920 bytes, 50 fps. */
    @Test
    fun `frame arithmetic matches the twenty millisecond frame both platforms use`() {
        assertEquals(20, CallAudio.FRAME_MS)
        assertEquals(960, CallAudio.SAMPLES_PER_FRAME)
        assertEquals(1_920, CallAudio.BYTES_PER_FRAME)
        assertEquals(50, CallAudio.FRAMES_PER_SECOND)
    }

    /**
     * The bandwidth is stated rather than hidden. iOS measured 1 957 bytes on the wire per
     * frame and left the number in a diagnostic (`:10193-10199`); our framing overhead is
     * 37 bytes on top of 1 920, which reproduces it exactly. ~780 kbit/s one-way.
     */
    @Test
    fun `a sealed pcm frame is the 1957 bytes iOS measured`() {
        val f = CallMediaFrame.encode(key, salt, true, 1, CallMediaFrame.TYPE_PCM_48K, pcm())
        assertEquals("9 header + 12 nonce + 1920 pcm + 16 tag", 1_957, f.size)
        assertEquals("782 kbit/s in ONE direction", 782, f.size * 8 * CallAudio.FRAMES_PER_SECOND / 1000)
    }

    // ============================================================== the playback buffer

    /** 60 ms, the depth both phones use today (`CALL_V2_PLAN.md` §B). */
    @Test
    fun `the playback buffer holds three twenty millisecond frames`() {
        assertEquals(3, PlaybackBuffer.MAX_FRAMES)
        assertEquals(60, PlaybackBuffer.MAX_FRAMES * CallAudio.FRAME_MS)
    }

    /**
     * On overflow the OLDEST frame is dropped, never the newest.
     *
     * In a live call the freshest audio is the only audio worth hearing; discarding it to
     * preserve a backlog makes the call lag further behind with every overrun.
     */
    @Test
    fun `an overflowing buffer drops the oldest frame and keeps the newest`() {
        val b = PlaybackBuffer(maxFrames = 2)
        b.offer(byteArrayOf(1))
        b.offer(byteArrayOf(2))
        b.offer(byteArrayOf(3))
        assertEquals(2, b.size())
        assertArrayEquals(byteArrayOf(2), b.take(1))
        assertArrayEquals(byteArrayOf(3), b.take(1))
    }

    /** An empty buffer yields null, which the render loop plays as silence. */
    @Test
    fun `an empty buffer returns null rather than blocking forever`() {
        assertNull(PlaybackBuffer().take(5))
    }

    // ============================================================== hardware, unverified

    /**
     * `isAvailable` must ANSWER on any machine, including one with no sound card — it asks
     * the mixer rather than opening a line.
     *
     * **This is the honest limit of what can be tested here.** No microphone is opened by
     * any test in this suite, on any machine that has run it. In the spirit of PARITY.md
     * row 0.27's radio link: the framing and the arithmetic are exercised, and the device
     * has never moved a sample.
     */
    @Test
    fun `audio availability can be queried without opening a device`() {
        val available = CallAudio.isAvailable()
        assertTrue("must return a value, not throw, on a machine with no audio", available || !available)
        // Enumeration must also be safe on a headless box.
        CallAudio.captureDevices()
    }
}
