package com.oshi.desktop.net

import org.json.JSONObject

/** The server's answer to a deletion — a partial report, not a boolean. */
data class AccountDeletionReceipt(
    val complete: Boolean,
    val prekeysErased: Boolean,
    val relayEnvelopes: Int,
    val syncItems: Int,
    val inboundBlobs: Int,
    val outboundBlobsLeft: Int,
    val unreachable: List<String>,
)

/**
 * `DELETE /v2/account` — erase this identity server-side. Desktop port of Android
 * `V2AccountClient`.
 *
 * Two details that are easy to get wrong and both silent:
 *  - the body-to-hash is the TWO-BYTE string `""` (a verify-first route), and
 *  - **207 is success**: the server answers 207 when it erased some stores and could not
 *    reach others, and the receipt names which. Treating 207 as a failure would have the
 *    app tell a user their deletion failed when most of it succeeded — and treating it as
 *    a plain success would tell them it finished when data is still out there. Hence a
 *    receipt rather than a Boolean.
 */
class V2AccountClient(private val http: V2Http) {

    fun deleteAccount(): Result<AccountDeletionReceipt> {
        val resp = http.delete("/v2/account")
        if (!resp.isSuccessOrPartial) {
            return Result.failure(RuntimeException("account delete HTTP ${resp.code}: ${resp.body.take(200)}"))
        }
        return Result.success(parse(resp.body))
    }

    private fun parse(body: String): AccountDeletionReceipt {
        val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val erased = json.optJSONObject("erased")
        val relay = erased?.optJSONObject("relay")
        val sync = erased?.optJSONObject("sync")
        val blobs = erased?.optJSONObject("blobs")
        val remote = json.optJSONObject("remote")
        val unreachable = mutableListOf<String>()
        if (remote != null) {
            for (key in remote.keys()) {
                if (remote.optJSONObject(key)?.optBoolean("ok", false) != true) unreachable.add(key)
            }
            unreachable.sort()
        }
        return AccountDeletionReceipt(
            complete = json.optBoolean("complete", unreachable.isEmpty()),
            prekeysErased = erased?.optBoolean("prekeys", false) ?: false,
            relayEnvelopes = relay?.optInt("envelopes", 0) ?: 0,
            syncItems = sync?.optInt("items", 0) ?: 0,
            inboundBlobs = blobs?.optInt("deleted", 0) ?: 0,
            outboundBlobsLeft = blobs?.optInt("outboundLeft", 0) ?: 0,
            unreachable = unreachable,
        )
    }
}
