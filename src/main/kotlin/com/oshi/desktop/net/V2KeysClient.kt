package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.store.PrekeyStore
import com.oshi.messenger.network.v2.FetchedBundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * `/v2/keys` — publish our X3DH prekey bundle, fetch a peer's, count what the server
 * still holds. Desktop port of Android `V2KeysClient`, which is itself a port of iOS
 * `V2Client`.
 *
 * TWO THINGS HERE ARE SECURITY, NOT PLUMBING, and both are checked on every fetch:
 *
 *  1. **The signed-prekey signature.** Ed25519 over the RAW 32 bytes of the SPK public
 *     key — not over a JSON wrapper, not over its base64 text. Verified against the
 *     peer's signing key from the same bundle.
 *  2. **TOFU: `identityKey` must equal the address we asked for.** The address IS the
 *     X25519 identity in OSHI, so a bundle that answers with a different identity key is
 *     a key substitution by whoever served it. Compared as the EXACT wire strings —
 *     deliberately NOT through any base64 normalisation, because iOS and Android
 *     normalise differently (one re-pads and validates, the other strips padding) and
 *     neither equivalence relation belongs in an identity check (PLAN.md §4.9).
 *
 * AND ONE THING IS ECONOMICS: **every `GET /v2/keys/:peer` POPS a one-time prekey
 * server-side.** Probing capability and then sending burns two of the peer's OPKs unless
 * the bundle is cached between the two — that cache lives in the router, not here, but
 * it is the reason this class never fetches speculatively.
 */
class V2KeysClient(
    private val http: V2Http,
    private val identity: DesktopIdentity,
    private val prekeys: PrekeyStore,
) {

    /** Publish (or republish) our bundle. Returns the server's remaining OPK count, or null on failure. */
    fun publish(oneTimeCount: Int = 20): Int? {
        val bytes = bundleBody(prekeys, oneTimeCount, deviceId = null)
        val resp = http.postJson("/v2/keys/publish", bytes)
        if (!resp.isSuccess) return null
        prekeys.markPublished()
        return runCatching { JSONObject(resp.body).optInt("remaining", oneTimeCount) }.getOrNull() ?: oneTimeCount
    }

    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ Publish THIS DEVICE's bundle (CLIENT_SPEC.md §3.2
     * step 4): the same body as the account bundle plus `deviceId`, from this install's own
     * signed pre-key and one-time pre-keys ([devicePrekeys], a separate vault entry), sent
     * account-signed AND device-signed. It never touches the account bundle server-side.
     */
    fun publishDevice(deviceId: String, devicePrekeys: PrekeyStore, oneTimeCount: Int): V2DeviceCall<Int> {
        val bytes = bundleBody(devicePrekeys, oneTimeCount, deviceId)
        val resp = http.postJson("/v2/keys/publish", bytes, device = true)
        if (!resp.isSuccess) return V2DeviceCall(resp.code, null, resp.body)
        devicePrekeys.markPublished()
        return V2DeviceCall(resp.code, runCatching { JSONObject(resp.body).optInt("remaining", oneTimeCount) }.getOrDefault(oneTimeCount), resp.body)
    }

    /** One-time pre-keys the server still holds for [deviceId]'s bundle. Null on failure. */
    fun deviceRemainingCount(deviceId: String): Int? {
        val path = "/v2/keys/${DesktopV2Signer.encodeIdentity(identity.userKey)}/count"
        val resp = http.get(path, query = "device=$deviceId")
        if (!resp.isSuccess) return null
        return runCatching { JSONObject(resp.body).optInt("remaining", 0) }.getOrNull()
    }

    /**
     * `GET /v2/keys/<peer>/devices` → the peer's devices holding a bundle. Null = the request
     * failed (NOT "no devices": a caller that conflated them would send one account envelope
     * to a peer whose devices then never see it). `enabled:false` ⇒ empty list.
     */
    fun peerDevices(peerUserKey: String): List<String>? {
        val resp = http.get("/v2/keys/${DesktopV2Signer.encodeIdentity(peerUserKey)}/devices")
        if (!resp.isSuccess) return null
        return runCatching {
            val o = JSONObject(resp.body)
            if (!o.optBoolean("enabled", false)) return emptyList()
            val a = o.optJSONArray("devices") ?: JSONArray()
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optString("deviceId")?.takeIf(DeviceAuth::isDeviceId) }
        }.getOrNull()
    }

    /**
     * One device's bundle (`?device=`), verified exactly like the account bundle: the SPK
     * signature, and TOFU — the identity key must be the address asked for. Pops one of that
     * device's one-time pre-keys. Null = no usable bundle for that device (treat it as stale).
     */
    fun fetchDeviceBundle(peerUserKey: String, deviceId: String): FetchedBundle? {
        val path = "/v2/keys/${DesktopV2Signer.encodeIdentity(peerUserKey)}"
        val resp = http.get(path, query = "device=$deviceId")
        if (!resp.isSuccess) return null
        return parseBundle(resp.body, peerUserKey)?.takeIf {
            runCatching { JSONObject(resp.body).optString("deviceId") }.getOrNull() == deviceId
        }
    }

    /**
     * The account bundle's live signed pre-key, as the server hands it out. Used to decide
     * whether THIS install is the one whose account generation is live (`holdsAccountBundle`)
     * before it publishes over it. NOTE it pops one account one-time pre-key, so callers
     * throttle it.
     */
    sealed class LiveSpk {
        object None : LiveSpk()                      // 404: nobody publishes an account bundle
        data class Key(val keyB64: String) : LiveSpk()
        object Unknown : LiveSpk()                   // could not ask
    }

    fun liveAccountSignedPreKey(): LiveSpk {
        val resp = http.get("/v2/keys/${DesktopV2Signer.encodeIdentity(identity.userKey)}")
        if (resp.code == 404) return LiveSpk.None
        if (!resp.isSuccess) return LiveSpk.Unknown
        return runCatching { LiveSpk.Key(JSONObject(resp.body).getJSONObject("signedPreKey").getString("key")) as LiveSpk }
            .getOrDefault(LiveSpk.Unknown)
    }

    private fun bundleBody(prekeys: PrekeyStore, oneTimeCount: Int, deviceId: String?): ByteArray {
        val spk = prekeys.currentSignedPreKey()
        val body = JSONObject().apply {
            put("userKey", identity.userKey)
            // identityKey == userKey: the X3DH identity key IS the contact address.
            put("identityKey", identity.userKey)
            put("signingKey", b64(identity.signingPub))
            put("signedPreKey", JSONObject().apply {
                put("keyId", spk.keyId)
                put("key", b64(spk.pair.pub))
                // Signed over the RAW public key bytes.
                put("signature", b64(identity.sign(spk.pair.pub)))
            })
            put("oneTimePreKeys", JSONArray().apply {
                for ((id, pub) in prekeys.generateOneTimePreKeys(oneTimeCount)) {
                    put(JSONObject().apply { put("keyId", id); put("key", b64(pub)) })
                }
            })
            deviceId?.let { put("deviceId", it) }
            // __LEGACY_COMPAT_2026_09_23__ "reads v2 groups": phones skip the legacy group copy for us.
            put("caps", V2ConfigGate.CAP_GROUP_V2)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /** Fetch a peer's bundle, verified. Null means "do not start a V2 session with them". */
    fun fetchBundle(peerUserKey: String): FetchedBundle? {
        val path = "/v2/keys/${DesktopV2Signer.encodeIdentity(peerUserKey)}"
        val resp = http.get(path)
        if (!resp.isSuccess) return null
        return parseBundle(resp.body, peerUserKey)
    }

    private fun parseBundle(body: String, peerUserKey: String): FetchedBundle? {
        return try {
            val json = JSONObject(body)
            val identityKey = unb64(json.getString("identityKey"))
            val signingKey = unb64(json.getString("signingKey"))
            val spk = json.getJSONObject("signedPreKey")
            val spkPub = unb64(spk.getString("key"))
            val spkSig = unb64(spk.getString("signature"))

            if (!DesktopIdentity.verify(spkPub, spkSig, signingKey)) return null
            if (b64(identityKey) != peerUserKey) return null      // TOFU, exact string

            val opk = json.optJSONObject("oneTimePreKey")
            FetchedBundle(
                identityKey = identityKey,
                signingKey = signingKey,
                signedPreKeyId = spk.optString("keyId"),
                signedPreKey = spkPub,
                oneTimePreKeyId = opk?.optString("keyId")?.takeIf { it.isNotEmpty() },
                oneTimePreKey = opk?.optString("key")?.takeIf { it.isNotEmpty() }?.let { unb64(it) },
            )
        } catch (_: Exception) {
            null
        }
    }

    /** How many one-time prekeys the server still holds for us. Refill when this gets low. */
    fun remainingCount(): Int? {
        val path = "/v2/keys/${DesktopV2Signer.encodeIdentity(identity.userKey)}/count"
        val resp = http.get(path)
        if (!resp.isSuccess) return null
        return runCatching { JSONObject(resp.body).optInt("remaining", 0) }.getOrNull()
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}
