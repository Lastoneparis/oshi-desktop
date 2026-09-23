package com.oshi.desktop.net

import com.oshi.desktop.DesktopEnvelope
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.devsync.DesktopDevSync
import com.oshi.messenger.network.v2.devsync.DevSyncKeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ `ServerPatches/per_device_mailbox/device_vectors.json`
 * (RFC 8032 seeds): deviceId and the three signatures, byte for byte, BEFORE any network
 * (CLIENT_SPEC.md §2). The values are embedded AND, when the monorepo is present, re-read from
 * the file, so a server-side change to the vectors turns this red instead of drifting.
 */
class DeviceAuthVectorsTest {

    private val identity = "ERERERERERERERERERERERERERERERERERERERERERE="
    private val dk = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
    private val deviceId = "8a62844af4e6e7850a98c731308278e1"
    private val deviceSeed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val deviceDsk = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
    private val approverSeed = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
    private val approverDsk = "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw="
    private val ts = "1790000000000"

    @Test
    fun `deviceId is the devsync id of dk`() {
        val raw = Base64.getDecoder().decode(dk)
        assertEquals(deviceId, DeviceAuth.deviceIdFor(raw))
        // One device, one id: the mailbox and devsync must derive the same one.
        assertEquals(DevSyncKeys.deviceIdHex(raw), DeviceAuth.deviceIdFor(raw))
    }

    @Test
    fun `the device key vault entry is devsync's`() {
        assertEquals(DesktopDevSync.DEVICE_KEY_ACCOUNT, DeviceMailbox.DEVICE_KEY_ACCOUNT)
    }

    @Test
    fun `dsk public keys match the RFC 8032 seeds`() {
        assertEquals(deviceDsk, b64(DeviceAuth.publicKey(deviceSeed)))
        assertEquals(approverDsk, b64(DeviceAuth.publicKey(approverSeed)))
    }

    @Test
    fun `register proof`() {
        val s = DeviceAuth.registerProofString(identity, deviceId, dk, deviceDsk, ts)
        assertEquals(
            "OSHI-DEVICE-REGISTER/1\nERERERERERERERERERERERERERERERERERERERERERE=\n8a62844af4e6e7850a98c731308278e1\n" +
                "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\n11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=\n1790000000000",
            s,
        )
        assertEquals(
            "xgbz9LBRI7OTGiujcAlZ/MBCdJeGLJCJct6tB/QIpqSA47aWUJgI493AaNW/RRrmeq8aTDAQZZIzEipZUK8mAw==",
            DeviceAuth.sign(deviceSeed, s),
        )
    }

    @Test
    fun `approval is signed by the approver`() {
        val s = DeviceAuth.approvalString(identity, deviceId, deviceDsk, ts)
        assertEquals(
            "OSHI-DEVICE-APPROVE/1\nERERERERERERERERERERERERERERERERERERERERERE=\n8a62844af4e6e7850a98c731308278e1\n" +
                "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=\n1790000000000",
            s,
        )
        val sig = DeviceAuth.sign(approverSeed, s)
        assertEquals(
            "hMuiDohMyNnTCCGGbv+9zRl+u1+shr5MKD7wQj6D09gQ7v5nTo/5WOch7doqK6Ezw4xudvd+Sh9y2WZjz6mmAQ==",
            sig,
        )
        assertTrue(DeviceAuth.verify(approverDsk, s, sig))
        assertFalse("signed by the approver, not the new device", DeviceAuth.verify(deviceDsk, s, sig))
    }

    @Test
    fun `device request over the ack route, path percent-encoded as sent`() {
        val body = "{\"upToSeq\":42}".toByteArray()
        val hash = DesktopV2Signer.sha256Hex(body)
        assertEquals("6848d1f78f9c2479db6416480bbd2a6dbb34bc6cdd1981b721e06a4b949da256", hash)
        val path = "/v2/messages/${DesktopV2Signer.encodeIdentity(identity)}/ack"
        val s = DeviceAuth.deviceRequestString(deviceId, "POST", path, hash, ts)
        assertEquals(
            "OSHI-DEVICE/1\n8a62844af4e6e7850a98c731308278e1\nPOST\n" +
                "/v2/messages/ERERERERERERERERERERERERERERERERERERERERERE%3D/ack\n" +
                "6848d1f78f9c2479db6416480bbd2a6dbb34bc6cdd1981b721e06a4b949da256\n1790000000000",
            s,
        )
        assertEquals(
            "4d+sTmeDVLZKNQmNdx2bspBhdQ7ENy64Y+VdlLq3vHSZYK10dBbVHDwzxlKzKP6C65AkNLjWOek/tz/Km0DqCw==",
            DeviceAuth.sign(deviceSeed, s),
        )
    }

    @Test
    fun `the vectors file, when the monorepo is here, says the same`() {
        val f = findUp("ServerPatches/per_device_mailbox/device_vectors.json") ?: return   // standalone repo: embedded values only
        val v = JSONObject(f.readText())
        assertEquals(identity, v.getString("identity"))
        assertEquals(dk, v.getString("dk"))
        assertEquals(deviceId, v.getString("deviceId"))
        val seed = hex(v.getJSONObject("device").getString("ed25519Seed"))
        val aSeed = hex(v.getJSONObject("approver").getString("ed25519Seed"))
        val t = v.getString("ts")
        val rp = v.getJSONObject("registerProof")
        assertEquals(rp.getString("string"), DeviceAuth.registerProofString(v.getString("identity"), v.getString("deviceId"), v.getString("dk"), v.getJSONObject("device").getString("dsk"), t))
        assertEquals(rp.getString("signature"), DeviceAuth.sign(seed, rp.getString("string")))
        val ap = v.getJSONObject("approval")
        assertEquals(ap.getString("string"), DeviceAuth.approvalString(v.getString("identity"), v.getString("deviceId"), v.getJSONObject("device").getString("dsk"), t))
        assertEquals(ap.getString("signature"), DeviceAuth.sign(aSeed, ap.getString("string")))
        val dr = v.getJSONObject("deviceRequest")
        assertEquals(dr.getString("bodySha256"), DesktopV2Signer.sha256Hex(dr.getString("body").toByteArray()))
        assertEquals(dr.getString("signature"), DeviceAuth.sign(seed, dr.getString("string")))
    }

    @Test
    fun `identity normalisation matches the server's`() {
        assertEquals(identity, DeviceAuth.normalizeIdentity("ERERERERERERERERERERERERERERERERERERERERERE"))
        assertEquals("+/+/" + identity.drop(4), DeviceAuth.normalizeIdentity("-_-_" + identity.drop(4)))
    }

    @Test
    fun `an envelope without device fields is byte-identical to before`() {
        val e = DesktopEnvelope("m", "a", "b", header = ByteArray(40), ciphertext = ByteArray(3), ts = 1)
        val bytes = String(e.toWireBytes())
        assertFalse(bytes.contains("Device"))
        assertTrue(bytes.endsWith("\"ts\":1}"))
        val d = e.copy(toDevice = deviceId, fromDevice = "0".repeat(32))
        val back = DesktopEnvelope.fromJson(JSONObject(String(d.toWireBytes())))
        assertEquals(deviceId, back.toDevice)
        assertEquals("0".repeat(32), back.fromDevice)
        assertNull(DesktopEnvelope.fromJson(JSONObject(bytes)).toDevice)
    }

    @Test
    fun `sent-copy round trip, second-precision ISO time`() {
        val bytes = SentCopy.encode(SentCopy.conversation1to1("peerKey"), "hi", "id-1", 1_790_000_000_123L)
        val o = JSONObject(String(bytes))
        assertEquals("2026-09-21T14:13:20Z", o.getString("sentAt"))
        val c = SentCopy.parse(bytes)!!
        assertEquals("peerKey", c.peer); assertEquals("hi", c.message); assertEquals("id-1", c.msgId)
        assertEquals(1_790_000_000_000L, c.sentAtMs)
        assertNull(SentCopy.parse("{\"kind\":\"state\"}".toByteArray()))
        val g = SentCopy.parse(SentCopy.encode(SentCopy.conversationGroup("G"), "b64", "id-2", 0))!!
        assertEquals("G", g.groupId)
    }

    private fun findUp(rel: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) { File(d, rel).takeIf { it.isFile }?.let { return it }; d = d.parentFile }
        return null
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
