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
        }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val resp = http.postJson("/v2/keys/publish", bytes)
        if (!resp.isSuccess) return null
        prekeys.markPublished()
        return runCatching { JSONObject(resp.body).optInt("remaining", oneTimeCount) }.getOrNull() ?: oneTimeCount
    }

    /** Fetch a peer's bundle, verified. Null means "do not start a V2 session with them". */
    fun fetchBundle(peerUserKey: String): FetchedBundle? {
        val path = "/v2/keys/${DesktopV2Signer.encodeIdentity(peerUserKey)}"
        val resp = http.get(path)
        if (!resp.isSuccess) return null
        return try {
            val json = JSONObject(resp.body)
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
