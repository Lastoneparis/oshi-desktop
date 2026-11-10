package com.oshi.desktop.store

import java.io.File
import org.json.JSONObject

/**
 * __OHTTP_DESKTOP_2026_11_10__ The "Privacy relay (beta)" choice, persisted like
 * [NotificationPreferences]. OFF by default, and a corrupt file reads as OFF — the same
 * value the switch then shows, so what the person sees is what the client does.
 * Effective only while the build carries a relay URL (`OhttpConfig.isConfigured()`).
 */
class PrivacyRelayPreferences(private val file: File) {

    @Synchronized fun load(): Boolean = try {
        if (!file.isFile) false
        else JSONObject(file.readText(Charsets.UTF_8)).optBoolean(KEY, false)
    } catch (_: Exception) {
        false
    }

    @Synchronized fun save(enabled: Boolean) {
        AtomicFile.write(file, JSONObject().put(KEY, enabled).toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val FILE_NAME = "privacy-relay.json"
        /** Same name Android persists under (`OhttpSettings.KEY_ENABLED`). */
        const val KEY = "ohttp_relay_enabled"
    }
}
