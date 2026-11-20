package com.oshi.desktop.store

import java.io.File
import org.json.JSONObject

/**
 * __DESKTOP_PREVIEW_PARITY_2026_10_27__ Whether a shell notification may name the
 * sender and quote the message.
 *
 * Desktop hard-coded "New encrypted message" and nothing else, while both phones
 * have offered this as a user choice for a while
 * (`notification_preview` on Android, the same switch on iOS). The phones' answer
 * is the right one and it is the one copied here: the capability exists, the
 * person decides, and it is OFF until they say otherwise.
 *
 * DEFAULT FALSE, and that is the whole safety of it. An OS notification is written
 * to notification history, mirrored to other devices and shown on a lock screen —
 * which is exactly why `DesktopNotifier` and `ChatShellModel` both carry comments
 * saying they pass no decrypted text or sender metadata. Those comments stay true
 * for anyone who never touches the switch.
 */
class NotificationPreferences(private val file: File) {
    /**
     * __NOTIFICATION_OPTIONS_2027__ [enabled] and [sound] are iOS's "Enable Notifications"
     * and "Sound" (`settings.notifications.enable` / `.sound`), both ON by default as there.
     */
    data class Values(val preview: Boolean = false, val enabled: Boolean = true, val sound: Boolean = true)

    @Synchronized fun load(): Values = try {
        if (!file.isFile) Values()
        else JSONObject(file.readText(Charsets.UTF_8)).let {
            Values(it.optBoolean("notificationPreview", false), it.optBoolean("notificationsEnabled", true), it.optBoolean("notificationSound", true))
        }
    } catch (_: Exception) {
        // A corrupt file must fall back to the PRIVATE setting, never the talkative one.
        Values()
    }

    @Synchronized fun save(values: Values) {
        AtomicFile.write(
            file,
            JSONObject()
                .put("notificationPreview", values.preview)
                .put("notificationsEnabled", values.enabled)
                .put("notificationSound", values.sound)
                .toString().toByteArray(Charsets.UTF_8),
        )
    }

    companion object { const val FILE_NAME = "notification-preferences.json" }
}
