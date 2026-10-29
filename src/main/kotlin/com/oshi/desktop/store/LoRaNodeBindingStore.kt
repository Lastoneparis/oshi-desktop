package com.oshi.desktop.store

import com.oshi.desktop.lora.LoRaNodeBindings
import org.json.JSONObject
import java.io.File

/**
 * Radio node → contact identity, across restarts.
 *
 * __DESKTOP_LORA_MANUAL_LINK_2026_10_28__ [LoRaNodeBindings] already had `snapshot()` and
 * `restore()` and **nothing called either of them**, so every binding this client learned
 * or was told died with the process. That is survivable for a learned binding, which can be
 * re-proved by the next envelope, and not for a MANUAL link: a person is not going to
 * re-pick their own radio out of a list every time the app starts.
 *
 * ============================================================ SEALED, LIKE CONTACTS
 *
 * This file states which radio belongs to which person — the same social graph
 * [ContactStore] holds, so it gets the same treatment: a [SealedJsonFile] envelope under
 * [LocalDataKeys.LORA_BINDINGS] when the client has an at-rest key, plain JSON when it has
 * none, and its own purpose string so one store's key cannot open another's.
 *
 * ============================================================ ABSENT MEANS PROVED
 *
 * `manual` is written only when it is known, and read back as null when the key is absent.
 * A binding persisted before manual links existed was proof, so it must decode as proof —
 * writing `false` for "unknown" would read identically to "explicitly not manual" and the
 * two have different meanings for the UI. See [LoRaNodeBindings.Binding].
 *
 * Rewritten wholesale on every change, by [ContactStore]'s argument: the set is bounded by
 * how many radios a person has, not by how long they have run the app.
 */
class LoRaNodeBindingStore(
    private val file: File = DesktopPaths.file(FILE_NAME),
    private val atRestKey: ByteArray? = null,
) {

    @Synchronized
    fun load(): Map<String, LoRaNodeBindings.Binding> {
        val read = try {
            SealedJsonFile.read(file, atRestKey, LocalDataKeys.LORA_BINDINGS) ?: return emptyMap()
        } catch (_: Exception) {
            // A binding map is a convenience, never the only copy of anything: an
            // unreadable one must not stop the client from starting. Learned bindings
            // re-prove themselves; a manual link has to be re-made, and the person finds
            // out by seeing the fallback thread rather than by a failed launch.
            return emptyMap()
        }
        return try {
            val root = JSONObject(read.json)
            val nodes = root.optJSONObject("bindings") ?: return emptyMap()
            val out = LinkedHashMap<String, LoRaNodeBindings.Binding>()
            for (key in nodes.keys()) {
                val o = nodes.optJSONObject(key) ?: continue
                val publicKey = o.optString("publicKey", "")
                if (publicKey.isEmpty()) continue
                out[key] = LoRaNodeBindings.Binding(
                    publicKey = publicKey,
                    learnedAtMillis = o.optLong("learnedAtMillis", 0L),
                    ambiguous = o.optBoolean("ambiguous", false),
                    // Absent → null → PROVED. Not false. See the class doc.
                    manual = if (o.has("manual")) o.optBoolean("manual") else null,
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    fun save(snapshot: Map<String, LoRaNodeBindings.Binding>) {
        val nodes = JSONObject()
        // Sorted, so a diff of this file is a diff of what changed rather than of hash-map
        // iteration order — ContactStore's reason, applied here.
        for (key in snapshot.keys.sorted()) {
            val b = snapshot.getValue(key)
            val o = JSONObject()
                .put("publicKey", b.publicKey)
                .put("learnedAtMillis", b.learnedAtMillis)
                .put("ambiguous", b.ambiguous)
            if (b.manual != null) o.put("manual", b.manual)
            nodes.put(key, o)
        }
        val json = JSONObject().put("v", 1).put("bindings", nodes).toString()
        SealedJsonFile.write(file, atRestKey, LocalDataKeys.LORA_BINDINGS, json)
    }

    companion object {
        const val FILE_NAME = "lora-node-bindings.json"
    }
}
