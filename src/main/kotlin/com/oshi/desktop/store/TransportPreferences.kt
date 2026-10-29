package com.oshi.desktop.store

import org.json.JSONObject
import java.io.File

/**
 * The two radio switches, persisted. Non-secret, so plain JSON like [ReceiptPreferences].
 *
 * __DESKTOP_RADIO_LANE_2026_10_28__ The phones expose these in the LoRa screen
 * (`OSHI/LoRaNodeSetupView.swift`); this is the desktop's copy of the same two questions.
 * See [com.oshi.desktop.lora.DesktopTransportPolicy] for why they are two and not one.
 *
 * Both DEFAULT OFF, which is the behaviour this client had before the radio was a lane at
 * all: the relay first, and the internet never forbidden. A preference file that cannot be
 * read is treated as both-off for the same reason — the defaults must be the old behaviour,
 * so a corrupt file cannot silently change where someone's messages go.
 */
class TransportPreferences(private val file: File) {

    data class Values(
        /** Try the radio before the relay. An ORDER, not a ban. */
        val preferRadio: Boolean = false,
        /** Never hand a message to the relay. A BAN, and it outranks [preferRadio]. */
        val neverOverInternet: Boolean = false,
        /**
         * __DESKTOP_LORA_MEDIA_2026_10_28__ Let media ride the radio. OFF, as on iOS.
         *
         * Off is not timidity: the frame budget works back to about a kilobyte of media
         * (see [com.oshi.desktop.lora.LoRaMediaWire.maxMediaBytes]), and iOS calls this
         * lane "a true last resort — opt-in, minutes on the air". Someone who has not asked
         * for it should not discover their photo spending the mesh.
         */
        val mediaOverLoRa: Boolean = false,
    )

    @Synchronized
    fun load(): Values = try {
        if (!file.isFile) Values() else JSONObject(file.readText(Charsets.UTF_8)).let {
            Values(
                preferRadio = it.optBoolean("preferRadioOverRelay", false),
                neverOverInternet = it.optBoolean("neverOverInternet", false),
                mediaOverLoRa = it.optBoolean("mediaOverLoRa", false),
            )
        }
    } catch (_: Exception) {
        Values()
    }

    @Synchronized
    fun save(values: Values) {
        AtomicFile.write(
            file,
            JSONObject()
                .put("preferRadioOverRelay", values.preferRadio)
                .put("neverOverInternet", values.neverOverInternet)
                .put("mediaOverLoRa", values.mediaOverLoRa)
                .toString().toByteArray(Charsets.UTF_8),
        )
    }

    companion object { const val FILE_NAME = "transport-preferences.json" }
}
