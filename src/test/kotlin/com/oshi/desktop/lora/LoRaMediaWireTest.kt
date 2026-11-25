package com.oshi.desktop.lora

import com.oshi.desktop.store.MediaType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * Media over the radio — the wrapper both phones read, and the ceiling that makes it honest.
 *
 * The bytes do not ride the envelope's own fields: the wire form strips the plaintext
 * sidecar, so media travels INSIDE the ciphertext as this JSON. Getting the wrapper wrong
 * does not throw — it delivers an empty bubble, which is the failure iOS's LAN mesh carries
 * a named fix for.
 */
class LoRaMediaWireTest {

    private val bytes = ByteArray(64) { (it * 3).toByte() }

    // ---------------------------------------------------------------- what we write

    @Test
    fun `we write the iOS wrapper, because that is the one both phones read`() {
        val o = JSONObject(LoRaMediaWire.encode(LoRaMediaWire.Media(MediaType.IMAGE, bytes, "cat.jpg")))
        assertEquals("media", o.getString("type"))
        assertEquals("image", o.getString("mediaType"))
        assertEquals("cat.jpg", o.getString("fileName"))
        assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(o.getString("content")))
    }

    @Test
    fun `a round trip keeps the bytes, the kind and the name`() {
        val m = LoRaMediaWire.Media(MediaType.AUDIO, bytes, "voice.ogg")
        assertEquals(m, LoRaMediaWire.decode(LoRaMediaWire.encode(m)))
    }

    @Test
    fun `a nameless attachment round-trips without inventing one`() {
        val m = LoRaMediaWire.Media(MediaType.DOCUMENT, bytes, null)
        assertEquals(m, LoRaMediaWire.decode(LoRaMediaWire.encode(m)))
    }

    // ---------------------------------------------------------------- what we read

    /** Android writes the kind in `type` and the bytes in `data`. Refusing it loses a photo. */
    @Test
    fun `the Android convention is read too`() {
        val android = JSONObject()
            .put("type", "IMAGE")
            .put("data", java.util.Base64.getEncoder().encodeToString(bytes))
            .toString()
        val decoded = LoRaMediaWire.decode(android)!!
        assertEquals(MediaType.IMAGE, decoded.type)
        assertArrayEquals(bytes, decoded.bytes)
    }

    /** `photo` is a shipped Android alias for `image`; an UNKNOWN here has no renderer. */
    @Test
    fun `the photo alias resolves to image`() {
        val wrapper = JSONObject()
            .put("type", "photo")
            .put("data", java.util.Base64.getEncoder().encodeToString(bytes))
            .toString()
        assertEquals(MediaType.IMAGE, LoRaMediaWire.decode(wrapper)!!.type)
    }

    // ---------------------------------------------------------------- what is NOT media

    /**
     * Most payloads are text, so this runs on every inbound message. It must never throw and
     * must never claim text is media — a false positive files a message as an attachment
     * with no words.
     */
    @Test
    fun `ordinary text is not media`() {
        assertNull(LoRaMediaWire.decode("hello"))
        assertNull(LoRaMediaWire.decode(""))
        assertNull(LoRaMediaWire.decode("   "))
    }

    @Test
    fun `text that merely looks like JSON is not media`() {
        assertNull(LoRaMediaWire.decode("{not json at all"))
        assertNull(LoRaMediaWire.decode("""{"hello":"world"}"""))
        assertNull("a wrapper with no payload is not media", LoRaMediaWire.decode("""{"type":"media","mediaType":"image"}"""))
    }

    /** `type:"media"` with no `mediaType` names no kind — it is not a usable attachment. */
    @Test
    fun `a wrapper that names no kind is refused`() {
        val wrapper = JSONObject()
            .put("type", "media")
            .put("content", java.util.Base64.getEncoder().encodeToString(bytes))
            .toString()
        assertNull(LoRaMediaWire.decode(wrapper))
    }

    @Test
    fun `an empty or unreadable payload is refused rather than stored as a zero-byte file`() {
        val empty = JSONObject().put("type", "IMAGE").put("data", "").toString()
        assertNull(LoRaMediaWire.decode(empty))
        val garbage = JSONObject().put("type", "IMAGE").put("data", "!!!not base64!!!").toString()
        assertNull(LoRaMediaWire.decode(garbage))
    }

    // ---------------------------------------------------------------- the ceiling

    /**
     * The number has to be small, and it has to be DERIVED. 12 frames × 180 bytes is the
     * envelope budget; base64 twice over and two AEADs eat most of it. If this ever reports
     * something a photo would fit in, the derivation is wrong.
     */
    @Test
    fun `the ceiling is a couple of hundred bytes, not a photograph`() {
        val max = LoRaMediaWire.maxMediaBytes()
        // Three compounding base64 layers over a 2160-byte envelope budget. If this ever
        // reports something a photo would fit in, the derivation has drifted.
        assertTrue("$max is not even an icon", max > 80)
        assertTrue("$max would let a photo through a 2160-byte budget", max < 500)
    }

    /**
     * THE PROPERTY THAT MATTERS: something at the ceiling must actually fit in the frames.
     * A ceiling that is merely a plausible number, rather than one the wire agrees with, is
     * how a message leaves and cannot be reassembled.
     */
    @Test
    fun `media at the ceiling still fits inside the frame budget`() {
        val max = LoRaMediaWire.maxMediaBytes()
        val wrapper = LoRaMediaWire.encode(LoRaMediaWire.Media(MediaType.IMAGE, ByteArray(max) { 7 }, "x.jpg"))
        // The wrapper is base64'd into the ratchet JSON, sealed, then base64'd into the
        // envelope. Model the two inflations and the AEAD the way the real path applies them.
        val ratchetJson = (wrapper.length + 28) * 4 / 3 + 200   // sealed, base64, header
        val envelopeCiphertext = (ratchetJson + 28) * 4 / 3
        val envelopeTotal = envelopeCiphertext + 700            // the envelope's own JSON
        assertTrue(
            "a $max-byte attachment produces $envelopeTotal bytes, over the ${LoRaFrame.MAX_SEND_BYTES}-byte budget",
            envelopeTotal <= LoRaFrame.MAX_SEND_BYTES,
        )
    }
}
