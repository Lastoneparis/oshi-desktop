package com.oshi.desktop.app

import com.oshi.desktop.group.GroupIdentity
import com.oshi.desktop.media.VideoNoteWire
import com.oshi.desktop.store.MediaType
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __VIDEO_NOTE_2026_09_24__ A round video note crosses the REAL send and receive paths
 * (in-process relay, real ratchet, real blob store) and arrives as a note — 1:1 and group —
 * while an ordinary MP4 still arrives as an ordinary video.
 */
class VideoNoteClientTest {
    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    private fun mp4(dir: File, name: String) = File(dir, name).apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }

    @Test
    fun `a 1-to-1 video note arrives flagged with its duration, a plain clip does not`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val dir = Files.createTempDirectory("oshi-vn-1to1").toFile()
        try {
            val note = mp4(dir, VideoNoteWire.filename(1758711234567L))
            assertEquals(OshiClient.SendOutcome.SENT, a.sendFile(b.address, note, MediaType.VIDEO, VideoNoteWire.Meta(12_480)))
            val mine = a.messages.messages(b.address).last()
            assertTrue("our own bubble is not a note", mine.videoNote)
            assertEquals(12_480L, mine.mediaDurationMs)

            assertEquals(1, b.router.poll())
            val got = b.messages.messages(a.address).last()
            assertEquals(MediaType.VIDEO, got.mediaType)
            assertTrue("the receiver lost the videoNote flag", got.videoNote)
            assertEquals(12_480L, got.mediaDurationMs)
            assertTrue(b.mediaVault.readBytes(File(got.mediaRef!!), Long.MAX_VALUE).contentEquals(note.readBytes()))

            // An ordinary clip stays an ordinary video (spec §5).
            assertEquals(OshiClient.SendOutcome.SENT, a.sendFile(b.address, mp4(dir, "clip.mp4")))
            assertEquals(1, b.router.poll())
            val clip = b.messages.messages(a.address).last()
            assertEquals(MediaType.VIDEO, clip.mediaType)
            assertFalse(clip.videoNote)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a group video note arrives flagged at every member`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("notes", listOf(b.address))
        repeat(3) { b.router.poll() }
        assertNotNull("b never learnt the group", b.groups.get(g.groupId))
        val dir = Files.createTempDirectory("oshi-vn-group").toFile()
        try {
            val report = a.sendGroupFile(g.groupId, mp4(dir, VideoNoteWire.filename(2L)), videoNote = VideoNoteWire.Meta(9_030))
            assertNotNull(report)
            repeat(3) { b.router.poll() }
            val gid = GroupIdentity.canonicalGroupId(g.groupId)
            val got = b.messages.messages(gid).lastOrNull { it.mediaType == MediaType.VIDEO }
            assertNotNull("the group note never arrived", got)
            assertTrue(got!!.videoNote)
            assertEquals(9_030L, got.mediaDurationMs)
        } finally {
            dir.deleteRecursively()
        }
    }
}
