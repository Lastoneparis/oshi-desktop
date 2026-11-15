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
        write(read().put(KEY, enabled))
    }

    /** __OHTTP_RELAY_ADDRESS_2026_11_10__ the relay the user entered; "" = none. */
    @Synchronized fun loadRelayUrl(): String = read().optString(KEY_RELAY_URL, "")

    @Synchronized fun saveRelayUrl(url: String) {
        val j = read()
        if (url.isBlank()) j.remove(KEY_RELAY_URL) else j.put(KEY_RELAY_URL, url)
        write(j)
    }

    private fun read(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    private fun write(j: JSONObject) {
        AtomicFile.write(file, j.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val FILE_NAME = "privacy-relay.json"
        /** Same name Android persists under (`OhttpSettings.KEY_ENABLED`). */
        const val KEY = "ohttp_relay_enabled"
        /** Same name Android persists under (`OhttpSettings.KEY_RELAY_URL`). */
        const val KEY_RELAY_URL = "ohttp_relay_url"
    }
}
