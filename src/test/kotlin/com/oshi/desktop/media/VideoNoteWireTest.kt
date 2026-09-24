package com.oshi.desktop.media

import com.oshi.desktop.group.GroupMessageWire
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import com.oshi.messenger.network.v2.V2FileKeyMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __VIDEO_NOTE_2026_09_24__ The round-video-note wire (docs/VIDEO_NOTE_SPEC.md) as this client
 * reads and writes it: the shared cross-platform vectors (`docs/fixtures/videonote/`), the
 * key-message and GroupMessage round trips, and the local store.
 */
class VideoNoteWireTest {

    @Test
    fun `every shared vector decodes to the expected note and still parses as media`() {
        val vectors = JSONObject(locate().readText()).getJSONArray("vectors")
        assertTrue("the fixture lost its vectors", vectors.length() >= 10)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val name = v.getString("name")
            val plaintext = v.getString("plaintext")
            // Rule 4: a malformed note field never costs the message.
            assertNotNull("$name: the key message no longer parses", V2FileKeyMessage.parse(plaintext))
            val meta = VideoNoteWire.fromFileKeyJson(plaintext)
            assertEquals("$name: isVideoNote", v.getBoolean("isVideoNote"), meta != null)
            val want = if (v.isNull("durationMs")) null else v.getLong("durationMs")
            if (meta != null) assertEquals("$name: durationMs", want, meta.durationMs)
        }
    }

    @Test
    fun `a key message built by this client carries both keys at the top level, and a plain video carries neither`() {
        fun key(note: VideoNoteWire.Meta?) = V2FileKeyMessage(
            blobId = "b_1", fileKey = ByteArray(32), fileNonce = ByteArray(8), chunkCount = 1,
            manifest = ByteArray(16), filename = VideoNoteWire.filename(1758711234567L), mime = VideoNoteWire.MIME,
            mediaType = "video", videoNote = if (note != null) true else null, durationMs = note?.wireDurationMs,
        )
        val json = JSONObject(key(VideoNoteWire.Meta(12480)).toJson())
        assertEquals(true, json.get("videoNote"))
        assertEquals(12480, json.getInt("durationMs"))
        assertEquals("videonote_1758711234567.mp4", json.getString("filename"))
        assertEquals(VideoNoteWire.Meta(12480), VideoNoteWire.fromFileKeyJson(key(VideoNoteWire.Meta(12480)).toJson()))

        val plain = key(null).toJson()
        assertFalse("a plain video must stay byte-identical (no false, no duration)", plain.contains("videoNote"))
        assertFalse(plain.contains("durationMs"))
        assertNull(VideoNoteWire.fromFileKeyJson(plain))

        // Unknown duration: the flag alone.
        val noDur = JSONObject(key(VideoNoteWire.Meta(null)).toJson())
        assertTrue(noDur.getBoolean("videoNote"))
        assertFalse(noDur.has("durationMs"))
    }

    @Test
    fun `a group note is flagged in the GroupMessage too, and either location is enough`() {
        val payload = GroupMessageWire.GroupMessagePayload(
            messageId = "M1", groupId = "G1", senderPublicKey = "c2VuZGVy", body = "",
            timestampUnixMillis = 1_758_711_234_567L, mediaType = GroupMessageWire.GroupMediaType.VIDEO,
            mediaFileName = "videonote_1.mp4", videoNote = VideoNoteWire.Meta(9030),
        )
        val encoded = JSONObject(GroupMessageWire.encode(payload))
        assertEquals(true, encoded.get("videoNote"))
        assertEquals(9030, encoded.getInt("durationMs"))
        assertEquals(VideoNoteWire.Meta(9030), GroupMessageWire.decode(encoded.toString())!!.videoNote)

        // Never on a non-video, never `false` on an ordinary video.
        val photo = GroupMessageWire.encode(payload.copy(mediaType = GroupMessageWire.GroupMediaType.PHOTO))
        assertFalse(photo.contains("videoNote"))
        val plain = GroupMessageWire.encode(payload.copy(videoNote = null))
        assertFalse(plain.contains("videoNote"))

        // Inner-only: an older sender of the key message, a note inside.
        val key = V2FileKeyMessage(
            blobId = "b", fileKey = ByteArray(32), fileNonce = ByteArray(8), chunkCount = 1, manifest = ByteArray(16),
            filename = "videonote_1.mp4", mime = "video/mp4", mediaType = "video",
            groupMessage = GroupMessageWire.encodeForEnvelope(payload),
        )
        assertEquals(VideoNoteWire.Meta(9030), VideoNoteWire.fromKey(key))
    }

    @Test
    fun `the local store keeps the flag and the duration, and omits them for everything else`() {
        val m = Message(
            id = "v1", conversationId = "peer", senderAddress = "peer", recipientAddress = "me", fromMe = false,
            content = "videonote_1.mp4", mediaType = MediaType.VIDEO, mediaRef = "/x/videonote_1.mp4",
            sentAtMs = 1_758_711_234_567L, sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            deliveryStatus = DeliveryStatus.DELIVERED, videoNote = true, mediaDurationMs = 12480,
        )
        val back = Message.fromJson(m.toJson())
        assertTrue(back.videoNote)
        assertEquals(12480L, back.mediaDurationMs)
        assertTrue(back.isDuplicateOf(m))
        val plain = m.copy(videoNote = false, mediaDurationMs = null)
        assertFalse(plain.toJson().has("videoNote"))
        assertFalse(plain.toJson().has("mediaDurationMs"))
    }

    @Test
    fun `durations are clamped to the contract whatever the source`() {
        assertEquals(VideoNoteWire.MAX_DURATION_MS, 15_000L)
        assertNull(VideoNoteWire.Meta(null).wireDurationMs)
        assertEquals(15_000, VideoNoteWire.Meta(15_000).wireDurationMs)
    }

    private fun locate(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "docs/fixtures/videonote/video_note_v1.json")
            if (f.isFile) return f
            d = d.parentFile
        }
        error("docs/fixtures/videonote/video_note_v1.json not found above ${System.getProperty("user.dir")}")
    }
}
