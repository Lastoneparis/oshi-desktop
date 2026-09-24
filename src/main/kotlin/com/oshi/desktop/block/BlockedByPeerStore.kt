package com.oshi.desktop.block

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.store.SealedJsonFile
import org.json.JSONObject
import java.io.File

/**
 * __BLOCKED_BY_PEER_2026_09_24__ "This peer told me I am blocked" — the caller-side half of
 * "a blocked contact leaves no missed-call trace" (owner decision 2026-09-24).
 *
 * The callee's device answers anything a blocked peer sends with the bodiless `🚫BLOCKED🚫`
 * notice ([BlockedNoticeGate], once a day). This client records that here and, from then on,
 * never dials that peer: no offer, no `POST /api/call/signal`, hence no VoIP push, so the
 * blocker's iPhone is never woken into the CallKit report PushKit forces (the source of a
 * Recents line there). The caller is shown "Contact unavailable" locally.
 *
 * The record is lifted by the first sign that the peer takes our traffic again: a delivery or
 * read receipt (a device that still blocks us never acks), any real 1:1 row from them, or an
 * incoming call from them. Mechanics (typing, profile/wallpaper broadcasts, group sync JSON)
 * say nothing. Nothing is told to the server: who blocks whom stays between the two devices.
 *
 * Same rules and the same evidence table as iOS (`BlockedByPeerStore` in
 * BlockedContactsManager.swift) and Android (`BlockedByPeerStore.kt`). Sealed at rest: it is a
 * list of people who blocked this user.
 */
class BlockedByPeerStore(
    private val file: File?,
    private val key: ByteArray?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Evidence { BLOCKED, UNBLOCKED, NONE }

    companion object {
        private const val PURPOSE = "blocked-by-peer-v1"

        /** What an inbound 1:1 plaintext says about "this peer blocks me". */
        fun evidence(text: String?): Evidence {
            if (text == null) return Evidence.NONE
            val prefix = ControlPrefix.match(text)
            if (prefix == ControlPrefix.BLOCKED_NOTICE) return Evidence.BLOCKED
            if (prefix == ControlPrefix.DELIVERY_RECEIPT || prefix == ControlPrefix.READ_RECEIPT) return Evidence.UNBLOCKED
            // Same "worth waking someone" test the notice gate uses: receipts aside, machinery
            // is not a person writing to us.
            return if (BlockedNoticeGate.isWorthANotice(text)) Evidence.UNBLOCKED else Evidence.NONE
        }
    }

    private val stamps = HashMap<String, Long>()
    private var loaded = false

    /** Feed one decrypted inbound 1:1 plaintext from [peer]. */
    fun observe(peer: String, text: String?) {
        when (evidence(text)) {
            Evidence.BLOCKED -> noteBlocked(peer)
            Evidence.UNBLOCKED -> clear(peer)
            Evidence.NONE -> Unit
        }
    }

    @Synchronized
    fun noteBlocked(peer: String) {
        if (peer.isBlank()) return
        load()
        val k = BlockPolicy.normalizeKey(peer)
        if (stamps.containsKey(k)) return
        stamps[k] = clock()
        save()
    }

    @Synchronized
    fun clear(peer: String) {
        if (peer.isBlank()) return
        load()
        if (stamps.remove(BlockPolicy.normalizeKey(peer)) != null) save()
    }

    @Synchronized
    fun isBlockedBy(peer: String): Boolean {
        if (peer.isBlank()) return false
        load()
        return stamps.containsKey(BlockPolicy.normalizeKey(peer))
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        val read = runCatching { SealedJsonFile.read(f, key, PURPOSE) }.getOrNull() ?: return
        val o = runCatching { JSONObject(read.json) }.getOrNull() ?: return
        for (k in o.keys()) stamps[k] = o.optLong(k, 0L)
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        for ((k, v) in stamps) o.put(k, v)
        runCatching { SealedJsonFile.write(f, key, PURPOSE, o.toString()) }
    }
}
