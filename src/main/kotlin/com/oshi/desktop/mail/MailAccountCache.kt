package com.oshi.desktop.mail

import com.oshi.desktop.store.SealedSmallJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * __MAIL_OFFLINE_DESKTOP_2026_11_25__ The last [MailClient.Account] the server confirmed for
 * this identity, sealed at rest under [com.oshi.desktop.store.LocalDataKeys.MAIL_ACCOUNT].
 *
 * Why it exists: [com.oshi.desktop.ui.state.MailModel] only learnt "this person has a
 * mailbox" from a successful `GET /mail/v1/account`. A launch without network therefore
 * looked exactly like "no mailbox yet", and the Mail screen offered the claim-an-address form
 * to someone who already owned an address (owner report, 25/11).
 *
 * Rules:
 *  - written on every successful account fetch;
 *  - cleared ONLY on a definite "no mailbox" (the server's 404) or when the identity is
 *    deleted ([com.oshi.desktop.app.OshiClient.deleteAccount]) — never on a network error;
 *  - bound to [owner] (the identity's public key): a record written for another identity
 *    is ignored, so a new identity in the same home never inherits someone else's address.
 */
class MailAccountCache(
    private val file: File,
    /** Seals the file; null = plaintext (tests only). */
    private val atRestKey: ByteArray?,
    private val owner: String,
) {
    private val lock = Any()

    fun load(): MailClient.Account? = synchronized(lock) {
        val json = runCatching { SealedSmallJson.load(file, atRestKey, PURPOSE).json }.getOrNull() ?: return null
        runCatching {
            val o = JSONObject(json)
            if (o.optString("owner") != owner) return null
            decode(o.getJSONObject("account"))
        }.getOrNull()
    }

    fun save(account: MailClient.Account) = synchronized(lock) {
        val o = JSONObject().put("owner", owner).put("account", encode(account))
        runCatching { SealedSmallJson.save(file, atRestKey, PURPOSE, o.toString()) }
        Unit
    }

    fun clear() = synchronized(lock) {
        runCatching { if (file.exists()) file.delete() }
        Unit
    }

    companion object {
        const val FILE_NAME = "mail-account.json"
        private const val PURPOSE = com.oshi.desktop.store.LocalDataKeys.MAIL_ACCOUNT

        private fun encode(a: MailClient.Account) = JSONObject()
            .put("address", a.address).put("localpart", a.localpart)
            .put("aliases", JSONArray(a.aliases)).put("aliasLimit", a.aliasLimit)
            .put("storageUsed", a.storageUsed).put("storageQuota", a.storageQuota)
            .put("messages", a.messages).put("sent", a.sent)
            .put("sendQuota", a.sendQuota).put("sendRemaining", a.sendRemaining)
            .put("driveUsed", a.driveUsed).put("driveQuota", a.driveQuota).put("driveFiles", a.driveFiles)

        private fun decode(o: JSONObject): MailClient.Account {
            val aliases = o.optJSONArray("aliases") ?: JSONArray()
            return MailClient.Account(
                address = o.getString("address"),
                localpart = o.getString("localpart"),
                aliases = (0 until aliases.length()).map { aliases.getString(it) },
                aliasLimit = o.optInt("aliasLimit", 10),
                storageUsed = o.optLong("storageUsed", 0),
                storageQuota = o.optLong("storageQuota", 0),
                messages = o.optInt("messages", 0),
                sent = o.optInt("sent", 0),
                sendQuota = o.optInt("sendQuota", 0),
                sendRemaining = o.optInt("sendRemaining", 0),
                driveUsed = o.optLong("driveUsed", 0),
                driveQuota = o.optLong("driveQuota", 0),
                driveFiles = o.optInt("driveFiles", 0),
            )
        }
    }
}
