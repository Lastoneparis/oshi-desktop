package com.oshi.desktop.block

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.store.SealedJsonFile
import org.json.JSONObject
import java.io.File

/**
 * __BLOCKED_NOTICE_SWITCH_2026_09_23__ Desktop half of "the blocked person is told".
 *
 * Both phones answer something from a blocked key with the bodiless sentinel
 * `🚫BLOCKED🚫` (iOS `MessageManager.noticeBlockedSender`, Android
 * `MessageRepository.noticeBlockedSender`). Desktop dropped silently, so a phone user
 * blocked FROM a desktop never learned it — the owner asked for the opposite. Same three
 * rules as the phones, which is the whole reason this is a separate, testable gate:
 *
 *  1. never answer machinery (receipts, typing, profile/wallpaper, group sync JSON, the
 *     notice itself — the last one is what stops two mutual blockers ping-ponging);
 *  2. once per peer per [INTERVAL_MS], stamped BEFORE the send and persisted, so the
 *     answers cannot become a heartbeat telling a harasser when the blocker is online;
 *  3. [ENABLED] is the product switch (Signal/WhatsApp deliberately stay silent).
 *
 * The stamps are keyed by the normalized address and sealed at rest like contacts: the
 * file is, after all, a list of people this user blocked.
 */
class BlockedNoticeGate(
    private val file: File?,
    private val key: ByteArray?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Owner's decision (2026-09): ON. iOS `MessageManager.blockedNoticeEnabled`, Android `BLOCKED_NOTICE_ENABLED`. */
        const val ENABLED = true
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val PURPOSE = "blocked-notice-v1"

        /**
         * Rule 1. [text] null = a non-message gesture (a call) — always worth one notice.
         * Mirrors iOS `OSHIControlPayload.suppressesPush`: SILENT kinds except the call
         * signal (someone trying to ring IS someone trying to reach you), the no-push list,
         * the notice itself, and the bare-JSON group sync traffic.
         */
        fun isWorthANotice(text: String?): Boolean {
            if (text == null) return true
            val prefix = ControlPrefix.match(text)
            if (prefix == ControlPrefix.BLOCKED_NOTICE) return false
            if (ControlPrefix.suppressesPush(text)) return false
            if (prefix != null && prefix != ControlPrefix.CALL_SIGNAL &&
                ControlPrefix.kindOf(text) == ControlPrefix.Kind.SILENT) return false
            val t = text.trimStart()
            if (t.startsWith("{") && (t.contains("\"member_sync") || t.contains("\"sync_request") ||
                    t.contains("\"group_update"))) return false
            return true
        }
    }

    private val stamps = HashMap<String, Long>()
    private var loaded = false

    /**
     * True when a notice should go out to [peer] now; the stamp is recorded (and persisted)
     * BEFORE returning true, so a send that hangs or throws cannot open the door to a second.
     */
    @Synchronized
    fun claim(peer: String, text: String?, self: String): Boolean {
        if (!ENABLED) return false
        if (peer.isBlank()) return false
        val k = BlockPolicy.normalizeKey(peer)
        if (k == BlockPolicy.normalizeKey(self)) return false
        if (!isWorthANotice(text)) return false
        load()
        val now = clock()
        val last = stamps[k]
        if (last != null && now - last in 0 until INTERVAL_MS) return false
        stamps[k] = now
        save()
        return true
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
        // Only stamps still inside the window matter; older ones would only grow the file.
        val cutoff = clock() - INTERVAL_MS
        for ((k, v) in stamps) if (v >= cutoff) o.put(k, v)
        runCatching { SealedJsonFile.write(f, key, PURPOSE, o.toString()) }
    }
}
