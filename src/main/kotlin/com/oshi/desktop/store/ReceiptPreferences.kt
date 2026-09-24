package com.oshi.desktop.store

import java.io.File
import org.json.JSONObject

/** Small non-secret counterpart to the phones' receipt preferences. */
class ReceiptPreferences(private val file: File) {
    data class Values(val delivery: Boolean = true, val read: Boolean = true)

    @Synchronized fun load(): Values = try {
        if (!file.isFile) Values() else JSONObject(file.readText(Charsets.UTF_8)).let {
            Values(it.optBoolean("deliveryReceiptsEnabled", true), it.optBoolean("readReceiptsEnabled", true))
        }
    } catch (_: Exception) { Values() }

    @Synchronized fun save(values: Values) {
        AtomicFile.write(file, JSONObject()
            .put("deliveryReceiptsEnabled", values.delivery)
            .put("readReceiptsEnabled", values.read)
            .toString().toByteArray(Charsets.UTF_8))
    }

    companion object { const val FILE_NAME = "receipt-preferences.json" }
}
