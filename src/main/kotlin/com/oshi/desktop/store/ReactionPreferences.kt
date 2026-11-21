package com.oshi.desktop.store

import java.io.File
import org.json.JSONObject

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ "Animated OSHI reactions": whether the O-bubble mascots on
 * reaction chips and in the reaction picker MOVE (on hover, and once when a chip appears).
 * Off, they are the still frame. DEFAULT TRUE — it is a look, nothing about it leaves this
 * machine, and the operating system's reduce-motion setting is honoured on top of it
 * ([com.oshi.desktop.ui.MotionPreference]).
 */
class ReactionPreferences(private val file: File) {
    data class Values(val animatedMascots: Boolean = true)

    @Synchronized fun load(): Values = try {
        if (!file.isFile) Values()
        else Values(JSONObject(file.readText(Charsets.UTF_8)).optBoolean(KEY_ANIMATED, true))
    } catch (_: Exception) {
        Values()
    }

    @Synchronized fun save(values: Values) {
        AtomicFile.write(
            file,
            JSONObject().put(KEY_ANIMATED, values.animatedMascots).toString().toByteArray(Charsets.UTF_8),
        )
    }

    companion object {
        const val FILE_NAME = "reaction-preferences.json"
        const val KEY_ANIMATED = "animatedOshiReactions"
    }
}
