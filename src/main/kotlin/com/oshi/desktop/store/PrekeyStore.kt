package com.oshi.desktop.store

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * The X3DH prekey PRIVATES — desktop port of Android `V2PrekeyStore`.
 *
 * What gets published is public: the signed prekey, its signature, and a batch of
 * one-time prekey publics. What lives here is the other half, and it is what lets us
 * answer as the RESPONDER when a peer starts a conversation with us: their first
 * envelope names a `signedPreKeyId` and possibly a `oneTimePreKeyId`, and without those
 * privates the shared secret cannot be recomputed — the first message from that contact
 * is undecryptable, forever.
 *
 * Three rules, each with its consequence:
 *
 *  1. **A one-time prekey is single-use and is burned only AFTER the message verifies.**
 *     [oneTimePreKeyPrivPeek] then [consumeOneTimePreKeyPriv], never one consuming
 *     lookup — burning on a message that then fails to authenticate destroys the key a
 *     legitimate retry needs.
 *  2. **The OPK map is capped**, oldest generations dropped, freshly minted ids always
 *     kept. Android shipped an append-only version: twenty more privates per launch,
 *     forever, re-serialised in full on every write.
 *  3. **Rotating the signed prekey drops the old private.** Deliberate, and it matches
 *     both shipped platforms — keeping it would widen the window in which a compromised
 *     device can open old first-messages. The cost is that an envelope in flight across
 *     a rotation cannot be opened, which is why nothing rotates automatically here.
 *
 * Stored inside the [KeyVault] as one JSON blob, so it inherits the same AES-256-GCM
 * protection as the identity instead of sitting in a plain file the way Android's did
 * until its migration.
 */
class PrekeyStore(
    private val vault: KeyVault,
    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ Which vault entry holds this pool. The ACCOUNT bundle
     * uses [VAULT_ACCOUNT]; this install's PER-DEVICE bundle uses [DEVICE_VAULT_ACCOUNT] — a
     * separate signed pre-key and a separate one-time pool, because a sender's X3DH against
     * the device bundle must never be answerable with (or burn) an account pre-key.
     */
    private val vaultAccount: String = VAULT_ACCOUNT,
) {

    data class SignedPreKey(val keyId: String, val pair: OSHICryptoV2.X25519Pair)

    @Synchronized
    fun currentSignedPreKey(): SignedPreKey {
        val spk = read().optJSONObject(SPK)
        if (spk != null) {
            return SignedPreKey(
                spk.getString("id"),
                OSHICryptoV2.X25519Pair(unb64(spk.getString("priv")), unb64(spk.getString("pub"))),
            )
        }
        return rotateSignedPreKey()
    }

    @Synchronized
    fun rotateSignedPreKey(): SignedPreKey {
        val pair = OSHICryptoV2.generateX25519()
        val id = UUID.randomUUID().toString()
        val o = read()
        o.put(SPK, JSONObject().put("id", id).put("priv", b64(pair.priv)).put("pub", b64(pair.pub)))
        write(o)
        return SignedPreKey(id, pair)
    }

    /** The private for a signed-prekey id, or null once it has been rotated away. */
    @Synchronized
    fun signedPreKeyPriv(keyId: String): ByteArray? {
        val spk = read().optJSONObject(SPK) ?: return null
        return if (spk.optString("id") == keyId) unb64(spk.getString("priv")) else null
    }

    /** Mint `count` one-time prekeys, keep the privates, hand back the publics to publish. */
    @Synchronized
    fun generateOneTimePreKeys(count: Int): List<Pair<String, ByteArray>> {
        val o = read()
        val map = o.optJSONObject(OPK) ?: JSONObject()
        val out = ArrayList<Pair<String, ByteArray>>(count)
        repeat(count) {
            val pair = OSHICryptoV2.generateX25519()
            val id = UUID.randomUUID().toString()
            map.put(id, b64(pair.priv))
            out.add(id to pair.pub)
        }
        val fresh = out.map { it.first }.toSet()
        if (map.length() > OPK_CAP) {
            val droppable = map.keys().asSequence().filterNot { it in fresh }.toMutableList()
            var excess = map.length() - OPK_CAP
            while (excess > 0 && droppable.isNotEmpty()) { map.remove(droppable.removeAt(0)); excess-- }
        }
        o.put(OPK, map)
        write(o)
        return out
    }

    /** Look up an OPK private WITHOUT consuming it. */
    @Synchronized
    fun oneTimePreKeyPrivPeek(keyId: String): ByteArray? {
        val map = read().optJSONObject(OPK) ?: return null
        return if (map.has(keyId)) unb64(map.getString(keyId)) else null
    }

    /** Burn it. Only after the first message has AEAD-verified. */
    @Synchronized
    fun consumeOneTimePreKeyPriv(keyId: String): ByteArray? {
        val o = read()
        val map = o.optJSONObject(OPK) ?: return null
        if (!map.has(keyId)) return null
        val priv = unb64(map.getString(keyId))
        map.remove(keyId)
        o.put(OPK, map)
        write(o)
        return priv
    }

    @Synchronized
    fun oneTimePreKeyCount(): Int = read().optJSONObject(OPK)?.length() ?: 0

    @Synchronized
    fun hasPublished(): Boolean = read().optBoolean(PUBLISHED, false)

    @Synchronized
    fun markPublished() { write(read().put(PUBLISHED, true)) }

    @Synchronized
    fun clear() = vault.delete(vaultAccount)

    private fun read(): JSONObject =
        vault.get(vaultAccount)?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: JSONObject()

    private fun write(o: JSONObject) = vault.put(vaultAccount, o.toString().toByteArray(Charsets.UTF_8))

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object {
        const val VAULT_ACCOUNT = "v2.prekeys"
        const val DEVICE_VAULT_ACCOUNT = "v2.device-prekeys"
        const val SPK = "spk"
        const val OPK = "opk"
        const val PUBLISHED = "published"
        const val OPK_CAP = 200
    }
}
