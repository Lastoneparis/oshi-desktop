package com.oshi.messenger.network.v2

import android.util.Base64
import android.util.Log
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.network.encryption.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A peer's X3DH prekey bundle after signature-verification + TOFU. */
data class FetchedBundle(
    val identityKey: ByteArray,   // peer X25519 identity (== their address)
    val signingKey: ByteArray,    // peer Ed25519 signing pub
    val signedPreKeyId: String,
    val signedPreKey: ByteArray,  // peer X25519 SPK pub (signature verified)
    val oneTimePreKeyId: String?, // null when the server's OPK pool is drained
    val oneTimePreKey: ByteArray?,
)

/**
 * OSHI V2 prekey endpoints (/v2/keys), byte-compatible with iOS V2Client.
 * Publishing a bundle is what makes iOS treat this Android install as v2-capable
 * (it fetches our bundle and routes V2 instead of the legacy IPFS path).
 */
@Singleton
class V2KeysClient @Inject constructor(
    private val signer: V2Signer,
    private val cryptoManager: CryptoManager,
    private val prekeyStore: V2PrekeyStore,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** Publish (or republish) our bundle. Returns the server's remaining OPK count, or null on failure. */
    suspend fun publish(oneTimeCount: Int = 20): Int? = withContext(Dispatchers.IO) {
        try {
            val userKey = cryptoManager.getPublicKeyString()          // b64 X25519 identity
            val signingKey = b64(cryptoManager.getSigningPublicKey())  // b64 Ed25519
            val spk = prekeyStore.currentSignedPreKey()
            val spkSig = b64(cryptoManager.sign(spk.pair.pub))         // Ed25519 over raw SPK pub

            val body = JSONObject().apply {
                put("userKey", userKey)
                put("identityKey", userKey)   // == identity (X3DH IK is the legacy X25519 identity)
                put("signingKey", signingKey)
                put("signedPreKey", JSONObject().apply {
                    put("keyId", spk.keyId)
                    put("key", b64(spk.pair.pub))
                    put("signature", spkSig)
                })
                put("oneTimePreKeys", JSONArray().apply {
                    for ((id, pub) in prekeyStore.generateOneTimePreKeys(oneTimeCount)) {
                        put(JSONObject().apply { put("keyId", id); put("key", b64(pub)) })
                    }
                })
            }
            val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
            val path = "/v2/keys/publish"
            val headers = signer.sign("POST", path, bodyBytes)  // hash = the exact bytes sent
            val req = Request.Builder()
                .url("${BuildConfig.VPS_URL}$path")
                .post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) {
                    Log.w("V2KeysClient", "publish HTTP ${resp.code}"); return@withContext null
                }
                JSONObject(resp.body?.string().orEmpty()).optInt("remaining", oneTimeCount)
            }
        } catch (e: Exception) {
            Log.e("V2KeysClient", "publish failed: ${e.message}", e); null
        }
    }

    /** Fetch a peer's bundle. Verifies the SPK signature + TOFU (identity==address) before returning. */
    suspend fun fetchBundle(peerUserKey: String): FetchedBundle? = withContext(Dispatchers.IO) {
        try {
            val path = "/v2/keys/${signer.encodeIdentity(peerUserKey)}"
            val headers = signer.sign("GET", path, ByteArray(0))  // GET keys hashes 0 bytes
            val req = Request.Builder().url("${BuildConfig.VPS_URL}$path").get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return@withContext null
                val json = JSONObject(resp.body?.string().orEmpty())
                val identityKey = unb64(json.getString("identityKey"))
                val signingKey = unb64(json.getString("signingKey"))
                val spk = json.getJSONObject("signedPreKey")
                val spkPub = unb64(spk.getString("key"))
                val spkSig = unb64(spk.getString("signature"))

                // (a) SPK signature must verify against the peer's signing key.
                if (!cryptoManager.verify(spkPub, spkSig, signingKey)) {
                    Log.w("V2KeysClient", "SPK signature invalid for $peerUserKey"); return@withContext null
                }
                // (b) TOFU: the address IS the X25519 identity — a mismatch is MITM.
                if (b64(identityKey) != peerUserKey) {
                    Log.w("V2KeysClient", "identityKey != address — possible key substitution"); return@withContext null
                }
                val opk = json.optJSONObject("oneTimePreKey")
                FetchedBundle(
                    identityKey = identityKey,
                    signingKey = signingKey,
                    signedPreKeyId = spk.optString("keyId"),
                    signedPreKey = spkPub,
                    oneTimePreKeyId = opk?.optString("keyId"),
                    oneTimePreKey = opk?.getString("key")?.let { unb64(it) },
                )
            }
        } catch (e: Exception) {
            Log.e("V2KeysClient", "fetchBundle failed: ${e.message}", e); null
        }
    }

    /** How many one-time prekeys the server still holds for us. Refill when low. */
    suspend fun remainingCount(): Int? = withContext(Dispatchers.IO) {
        try {
            val userKey = cryptoManager.getPublicKeyString()
            val path = "/v2/keys/${signer.encodeIdentity(userKey)}/count"
            val headers = signer.sign("GET", path, ByteArray(0))
            val req = Request.Builder().url("${BuildConfig.VPS_URL}$path").get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) null
                else JSONObject(resp.body?.string().orEmpty()).optInt("remaining", 0)
            }
        } catch (e: Exception) { null }
    }
}
