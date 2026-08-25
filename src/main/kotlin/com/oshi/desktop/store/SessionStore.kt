package com.oshi.desktop.store

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHIRatchetV2
import com.oshi.messenger.network.v2.V2Session
import org.json.JSONObject
import java.util.Base64

/**
 * Double-Ratchet state, per peer, persisted — desktop port of Android `V2SessionStore`,
 * using the SAME JSON shape so one record is legible across implementations.
 *
 * THE RULE THIS STORE EXISTS TO ENFORCE: **the advanced ratchet is saved BEFORE the
 * network send.** Two sends that both re-derive the same message key produce the same
 * (key, nonce) for different plaintexts, and AES-256-GCM nonce reuse does not degrade
 * gracefully — it leaks the XOR of the plaintexts and lets the authenticator be forged.
 * Saving only on a successful send rewinds `cks`/`ns` on failure and walks straight into
 * it. Saving before costs, at worst, one skipped sequence number, which the peer ignores.
 *
 * Sessions live in the [KeyVault] rather than in a file of their own: a ratchet root key
 * is exactly as sensitive as the identity, and Android shipped these in plain XML long
 * enough to need a migration for it.
 */
class SessionStore(private val vault: KeyVault) {

    enum class Role { INITIATOR, RESPONDER }

    data class Record(
        val peerUserKey: String,
        val role: Role,
        val ratchet: OSHIRatchetV2.State,
        val peerIdentityKey: ByteArray?,
        val peerSigningKey: ByteArray?,
        /**
         * The X3DH header to attach to message #1, and to NOTHING else.
         *
         * Re-attaching it on message #2 names an ephemeral and a one-time prekey the
         * responder already burned; the recomputed shared secret differs, the recovery
         * path throws `unknownOneTimePreKey`, and the conversation never recovers.
         * Cleared inside the same locked read-modify-write that sends the first message.
         */
        var pendingInitiatorHeader: V2Session.X3DHHeader?,
    )

    @Synchronized
    fun load(peerUserKey: String): Record? {
        val raw = vault.get(key(peerUserKey)) ?: return null
        return try {
            deserialize(JSONObject(String(raw, Charsets.UTF_8)))
        } catch (e: Exception) {
            // A session that will not deserialize must NOT read as "no session": starting
            // a fresh X3DH would leave the peer decrypting against a ratchet that no
            // longer exists, and every message between them would fail with no error the
            // user can act on. Surface it instead.
            throw SessionStoreException("session record for ${peerUserKey.take(12)}… is unreadable", e)
        }
    }

    @Synchronized
    fun save(rec: Record) = vault.put(key(rec.peerUserKey), serialize(rec).toString().toByteArray(Charsets.UTF_8))

    @Synchronized
    fun has(peerUserKey: String): Boolean = vault.get(key(peerUserKey)) != null

    @Synchronized
    fun peers(): List<String> = vault.accounts().filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }

    @Synchronized
    fun delete(peerUserKey: String) = vault.delete(key(peerUserKey))

    /** Account deletion. Every session, gone. */
    @Synchronized
    fun clear() = peers().forEach { delete(it) }

    private fun key(peer: String) = PREFIX + peer

    // ---- serialization (field for field with Android's V2SessionStore) ----

    private fun serialize(rec: Record): JSONObject = JSONObject().apply {
        put("peerUserKey", rec.peerUserKey)
        put("role", rec.role.name)
        put("ratchet", serializeRatchet(rec.ratchet))
        rec.peerIdentityKey?.let { put("peerIdentityKey", b64(it)) }
        rec.peerSigningKey?.let { put("peerSigningKey", b64(it)) }
        rec.pendingInitiatorHeader?.let {
            put("pendingInitiatorHeader", JSONObject().apply {
                put("identityKey", b64(it.identityKey))
                put("ephemeralKey", b64(it.ephemeralKey))
                put("signedPreKeyId", it.signedPreKeyId)
                it.oneTimePreKeyId?.let { id -> put("oneTimePreKeyId", id) }
            })
        }
    }

    private fun serializeRatchet(s: OSHIRatchetV2.State): JSONObject = JSONObject().apply {
        put("dhsPriv", b64(s.dhs.priv))
        put("dhsPub", b64(s.dhs.pub))
        s.dhr?.let { put("dhr", b64(it)) }
        put("rk", b64(s.rk))
        s.cks?.let { put("cks", b64(it)) }
        s.ckr?.let { put("ckr", b64(it)) }
        put("ns", s.ns); put("nr", s.nr); put("pn", s.pn)
        put("skipped", JSONObject().apply { s.skipped.forEach { (k, v) -> put(k, b64(v)) } })
    }

    private fun deserialize(o: JSONObject): Record {
        val ph = o.optJSONObject("pendingInitiatorHeader")?.let {
            V2Session.X3DHHeader(
                identityKey = unb64(it.getString("identityKey")),
                ephemeralKey = unb64(it.getString("ephemeralKey")),
                signedPreKeyId = it.optString("signedPreKeyId"),
                oneTimePreKeyId = if (it.has("oneTimePreKeyId")) it.optString("oneTimePreKeyId") else null,
            )
        }
        return Record(
            peerUserKey = o.getString("peerUserKey"),
            role = Role.valueOf(o.getString("role")),
            ratchet = deserializeRatchet(o.getJSONObject("ratchet")),
            peerIdentityKey = o.optString("peerIdentityKey", "").ifEmpty { null }?.let { unb64(it) },
            peerSigningKey = o.optString("peerSigningKey", "").ifEmpty { null }?.let { unb64(it) },
            pendingInitiatorHeader = ph,
        )
    }

    private fun deserializeRatchet(o: JSONObject): OSHIRatchetV2.State {
        val skipped = HashMap<String, ByteArray>()
        o.optJSONObject("skipped")?.let { sk -> sk.keys().forEach { skipped[it] = unb64(sk.getString(it)) } }
        return OSHIRatchetV2.State(
            dhs = OSHICryptoV2.X25519Pair(unb64(o.getString("dhsPriv")), unb64(o.getString("dhsPub"))),
            dhr = o.optString("dhr", "").ifEmpty { null }?.let { unb64(it) },
            rk = unb64(o.getString("rk")),
            cks = o.optString("cks", "").ifEmpty { null }?.let { unb64(it) },
            ckr = o.optString("ckr", "").ifEmpty { null }?.let { unb64(it) },
            ns = o.optLong("ns"), nr = o.optLong("nr"), pn = o.optLong("pn"),
            skipped = skipped,
        )
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object { const val PREFIX = "v2.session." }
}

class SessionStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
