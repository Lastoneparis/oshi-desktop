package com.oshi.desktop.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * __DESKTOP_PRIVACY_PARITY_2027__ The four privacy switches the phones have and this window
 * lacked, with the phones' defaults and the phones' meaning:
 *
 *  * **Last Seen** (iOS `lastSeenEnabled`, Android `last_seen_enabled`, default ON). Read when
 *    this client writes its `📸PROFILE_UPDATE📸`: ON puts the current time in `lastSeen` (Unix
 *    SECONDS, a Double, Android `ProfileUpdateWire.lastSeenValue`), OFF puts `0`, which every
 *    receiver reads as "not shared". The key itself is always written — iOS decodes it as a
 *    required field.
 *  * **Profile Photo** (iOS `profilePhotoVisible`, default ON). OFF: this client's outgoing
 *    profile update carries no picture ([outgoingProfilePhoto] returns null). On iOS the switch
 *    exists with no reader and Android removed it for that reason; here it has one. Neither
 *    phone has an everyone/contacts/nobody choice: a profile only ever goes to people we have
 *    written to (`OshiClient.hasSentUserMessage`), so ON = those contacts, OFF = nobody.
 *    Flipping it re-announces the profile (ChatShellModel), so the change reaches people who
 *    already hold the picture: `profileImageData` absent ⇒ iOS clears the avatar.
 *  * **Calls from Contacts Only** (iOS `callsFromContactsOnly`, default OFF). A caller with no
 *    conversation on this computer does not ring; nothing is sent back, as on iOS
 *    (`VoiceCallManager.shouldRejectUnknownCaller`).
 *  * **Block Forwarding & Copying** (iOS `MessagePrivacy.blockForwardAndCopy`, Android
 *    `block_forward_copy`, default OFF). Removes Forward and Copy from every message on this
 *    computer. It cannot bind the recipient — the footer says so, in the phones' words.
 *
 * Snapshot state, so the settings page and every message row recompose when a switch moves.
 * A missing or corrupt file reads as the defaults; nothing here is secret.
 */
object PrivacyPreferences {

    data class Values(
        val lastSeen: Boolean = true,
        val profilePhotoVisible: Boolean = true,
        val callsFromContactsOnly: Boolean = false,
        val blockForwardCopy: Boolean = false,
    )

    var values: Values by mutableStateOf(Values())
        private set

    @Volatile private var file: File? = null

    fun attach(home: File) {
        val f = File(home, FILE_NAME)
        file = f
        values = read(f)
    }

    fun update(change: (Values) -> Values) {
        val old = values
        values = change(values)
        file?.let { write(it, values) }
        if (old != values) listeners.forEach { l -> runCatching { l(old, values) } }
    }

    /**
     * __MERGE_AB_PROFILE_PHOTO_PRIVACY__ Called after a user change (not after [attach] or
     * [preview]). The chat model uses it to re-announce the profile when Profile Photo flips,
     * so contacts drop (or get back) the picture they already hold.
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Values, Values) -> Unit>()
    fun addChangeListener(l: (old: Values, new: Values) -> Unit) { listeners += l }
    fun removeChangeListener(l: (old: Values, new: Values) -> Unit) { listeners -= l }

    /** Test / renderer seam: set without a file. */
    fun preview(v: Values) { values = v }

    /** `lastSeen` for an outgoing profile update. */
    fun lastSeenWireValue(nowMs: Long = System.currentTimeMillis(), v: Values = values): Double =
        if (v.lastSeen) nowMs / 1000.0 else 0.0

    /** The picture an outgoing profile update may carry: [photoBase64], or null when hidden. */
    fun outgoingProfilePhoto(photoBase64: String?, v: Values = values): String? =
        if (v.profilePhotoVisible) photoBase64 else null

    /**
     * Should an incoming call from [caller] ring? [hasConversation] answers "have we a
     * conversation with this address" — iOS's definition of a contact here.
     */
    fun mayRing(caller: String, hasConversation: (String) -> Boolean, v: Values = values): Boolean =
        !v.callsFromContactsOnly || hasConversation(caller)

    fun read(f: File): Values = try {
        if (!f.isFile) Values() else JSONObject(f.readText(Charsets.UTF_8)).let {
            Values(
                lastSeen = it.optBoolean(KEY_LAST_SEEN, true),
                profilePhotoVisible = it.optBoolean(KEY_PHOTO, true),
                callsFromContactsOnly = it.optBoolean(KEY_CALLS, false),
                blockForwardCopy = it.optBoolean(KEY_FORWARD, false),
            )
        }
    } catch (_: Exception) { Values() }

    fun write(f: File, v: Values) {
        runCatching {
            AtomicFile.write(
                f,
                JSONObject()
                    .put(KEY_LAST_SEEN, v.lastSeen)
                    .put(KEY_PHOTO, v.profilePhotoVisible)
                    .put(KEY_CALLS, v.callsFromContactsOnly)
                    .put(KEY_FORWARD, v.blockForwardCopy)
                    .toString().toByteArray(Charsets.UTF_8),
            )
        }
    }

    const val FILE_NAME = "privacy-preferences.json"
    // The iOS UserDefaults names, so a reader of either platform finds the same word.
    private const val KEY_LAST_SEEN = "lastSeenEnabled"
    private const val KEY_PHOTO = "profilePhotoVisible"
    private const val KEY_CALLS = "callsFromContactsOnly"
    private const val KEY_FORWARD = "blockForwardAndCopy"
}
