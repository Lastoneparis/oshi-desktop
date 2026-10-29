package com.oshi.desktop.mail

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * __DESKTOP_WEB_SESSION_WATCH_2026_10_29__ Notices when a browser is signed in to this
 * account, so that signing one in cannot be silent.
 *
 * ## The attack this answers, and it is not hypothetical
 *
 * iOS's `LinkedDeviceWatch` states it, with a source: Netzpolitik published documents in 2026
 * showing Germany's Customs Office reading Signal, WhatsApp and Telegram messages **without
 * breaking any encryption**. The method is the linked-device feature — brief access to an
 * unlocked phone, link a machine you control, and receive everything from then on in
 * plaintext on your own computer. The cryptography is untouched and works exactly as designed.
 *
 * Every one of those apps already shows a list of linked devices. **A list is not a defence:
 * the method works because nobody opens that screen.** The defence is the app saying so,
 * unprompted, at the moment it happens.
 *
 * ## What was already here, and what was not
 *
 * This client's DEVICE-MAILBOX lane was already covered: `DeviceMailbox` carries
 * `newDeviceAlerts`, and both the window and the REPL say *"new device(s) you did not approve
 * here"*. The MAIL browser-session lane was not. `MailModel.loadBrowserSessions` fetched the
 * list and `revokeBrowserSession` could sign one out — a list and a revoke, and nothing that
 * ever told anybody. That is the same structural hole in the second of the two lanes, which
 * is exactly how it survived: the first lane being right makes the second look handled.
 *
 * ## `null` AND AN EMPTY SET MEAN DIFFERENT THINGS
 *
 * The one rule in here worth stating twice, copied from the phones: `null` is "we have never
 * looked", empty is "last time there were none", **and only the second makes an appearing
 * session newsworthy.** Conflating them gives a choice of two bad behaviours — either the
 * first run reports every existing session as an intrusion, which fires a security alert at
 * every user on upgrade day and teaches them to dismiss it, or an appearance after a genuinely
 * empty check is treated as a seed and never reported, which is the alert that matters most.
 *
 * ## The baseline is NOT a secret
 *
 * A plain JSON file beside the other preferences, not the vault. These are session
 * identifiers the server hands out, not secrets — and iOS gives the second half of the
 * reason: a baseline that survived a reinstall would be one the user could not reset when
 * they deliberately start over.
 */
object WebSessionWatch {

    sealed interface Outcome {
        /** The first check ever: the baseline is written and nothing is reported. */
        data class Seeded(val count: Int) : Outcome

        /** Nothing has appeared since the last check. */
        data object Unchanged : Outcome

        /** These ids are present now and were not before. */
        data class Linked(val sessionIds: List<String>) : Outcome
    }

    /**
     * The ids present now and absent from [known].
     *
     * @param known ids remembered from the previous check, or null on the very first one.
     *   See the class note on why those two are not the same.
     */
    fun newlyLinked(fetched: List<String>, known: Set<String>?): List<String> {
        if (known == null) return emptyList()
        return fetched.filter { it !in known }
    }

    fun classify(fetched: List<String>, known: Set<String>?): Outcome {
        if (known == null) return Outcome.Seeded(fetched.size)
        val new = newlyLinked(fetched, known)
        return if (new.isEmpty()) Outcome.Unchanged else Outcome.Linked(new)
    }

    /**
     * The remembered baseline.
     *
     * Reading returns null when no check has ever run, which the classifier needs in order to
     * tell a first run from an empty one — so a missing file, an unreadable one and a corrupt
     * one all read as null. **That direction is deliberate**: an unreadable baseline must
     * re-seed rather than treat every session as new, because the noisy failure is the one
     * that trains people to ignore the alert.
     */
    class Baseline(private val file: File) {

        fun load(): Set<String>? {
            val text = runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
                ?: return null
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val arr = o.optJSONArray(KEY) ?: return null
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
        }

        fun save(ids: List<String>) {
            val payload = JSONObject()
                .put(KEY, JSONArray().also { a -> ids.sorted().forEach { a.put(it) } })
                .toString()
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(payload, Charsets.UTF_8)
                tmp.setReadable(false, false); tmp.setReadable(true, true)
                tmp.setWritable(false, false); tmp.setWritable(true, true)
                if (!tmp.renameTo(file)) { file.writeText(payload, Charsets.UTF_8); tmp.delete() }
            }
        }

        /** Forget the baseline, so the next check seeds again. */
        fun reset() { runCatching { file.delete() } }
    }

    const val KEY = "known"
    const val FILE_NAME = "web-sessions-known.json"
}
