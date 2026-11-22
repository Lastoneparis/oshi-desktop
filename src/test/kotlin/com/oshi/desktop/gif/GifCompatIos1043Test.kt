package com.oshi.desktop.gif

import com.oshi.desktop.store.MediaType
import com.oshi.messenger.network.v2.V2FileKeyMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __GIF_COMPAT_IOS1043_2026_11_21__ A 1:1 GIF / OSHI sticker from this client must render on
 * iOS 1.0.43 (the build on sale, commit 4befbea0), and one from any peer must still animate here.
 *
 * 1.0.43's blob decoder (`v2DeliverFile`) is `MediaType(rawValue: label) ?? v2MediaType(forMime:)`
 * over a type set with no `gif`; its `MediaMessageView.loadMedia()` draws nothing for a nil
 * type. Modelled here and fed the EXACT key-message JSON this client emits.
 */
class GifCompatIos1043Test {

    private val ios1043Types = setOf("image", "video", "audio", "document", "contact")

    private fun ios1043Blob(keyJson: String): String? {
        val o = JSONObject(keyJson)
        val label = o.optString("mediaType").ifEmpty { null }?.takeIf { it in ios1043Types }
        if (label != null) return label
        val mime = o.getString("mime")
        return when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            mime.contains("vcard") -> "contact"
            else -> "document"
        }
    }

    /** The key message exactly as `OshiClient.attemptMedia` builds it for a GifOutbox file. */
    private fun keyFor(filename: String, mime: String, mediaType: MediaType): String =
        String(
            V2FileKeyMessage(
                blobId = "blob-1",
                fileKey = ByteArray(32),
                fileNonce = ByteArray(8),
                chunkCount = 1,
                manifest = ByteArray(16),
                filename = filename,
                mime = mime,
                mediaType = GifWire.label(mediaType, mime),
            ).toBytes(),
            Charsets.UTF_8,
        )

    @Test
    fun `a GIF key message says image, keeps image gif and the gif name`() {
        val key = keyFor("1a2b3c4d-oshi_ok.gif", "image/gif", MediaType.IMAGE)
        val o = JSONObject(key)
        assertEquals("image", o.getString("mediaType"))
        assertEquals("image/gif", o.getString("mime"))
        assertTrue(o.getString("filename").endsWith(".gif"))
        assertEquals("image", ios1043Blob(key))
    }

    @Test
    fun `non GIF media keep the labels that shipped`() {
        assertEquals("image", JSONObject(keyFor("a.jpg", "image/jpeg", MediaType.IMAGE)).getString("mediaType"))
        assertEquals("video", JSONObject(keyFor("a.mp4", "video/mp4", MediaType.VIDEO)).getString("mediaType"))
        assertEquals("audio", JSONObject(keyFor("a.m4a", "audio/m4a", MediaType.AUDIO)).getString("mediaType"))
        assertEquals("document", JSONObject(keyFor("a.pdf", "application/pdf", MediaType.DOCUMENT)).getString("mediaType"))
        assertEquals("document", GifWire.label(MediaType.DOCUMENT, "image/gif"))
    }

    /** What older peers (Android ≤ 1.7.1, desktop ≤ 1.6.2, new iOS) still send: still an IMAGE here. */
    @Test
    fun `an old peer's gif labelled key message still files as IMAGE`() {
        val z = { n: Int -> java.util.Base64.getEncoder().encodeToString(ByteArray(n)) }
        val old = """{"kind":"v2file","blobId":"b","fileKey":"${z(32)}","fileNonce":"${z(8)}",""" +
            """"chunkCount":1,"manifest":"${z(16)}","filename":"a.gif","mime":"image/gif","mediaType":"gif"}"""
        val k = V2FileKeyMessage.parse(old)
        assertNotNull(k)
        assertEquals(MediaType.IMAGE, MediaType.fromWire(k!!.mediaType!!))
        assertEquals(MediaType.IMAGE, MediaType.fromWire("image"))
    }

    /** The bubble decides from the bytes: an `image` row whose file is a GIF animates. */
    @Test
    fun `an image row holding GIF bytes is drawn as a GIF whatever its name`() {
        val dir = kotlin.io.path.createTempDirectory("gif-compat").toFile()
        try {
            val disguised = File(dir, "photo.jpg").apply { writeBytes("GIF89a\u0001\u0000\u0001\u0000".toByteArray()) }
            val jpeg = File(dir, "real.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)) }
            assertTrue(com.oshi.desktop.ui.components.GifBubble.isGifFile(disguised.absolutePath))
            assertFalse(com.oshi.desktop.ui.components.GifBubble.isGifFile(jpeg.absolutePath))
            assertTrue(AnimatedGif.isGif("GIF87a".toByteArray()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
