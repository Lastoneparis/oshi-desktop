package com.oshi.desktop.group

import com.oshi.desktop.msg.WireClock
import com.oshi.messenger.network.v2.OSHICryptoV2

/**
 * An ordered JSON writer that can nest — the row-0.17 sibling of
 * [com.oshi.desktop.msg.ControlJson], and not a copy of it.
 *
 * ============================================================ WHY A SECOND WRITER
 *
 * `ControlJson` is a FLAT writer by construction: `str`/`bool`/`num`/`optional` and nothing
 * else, because no row-0.18 payload nests. Row 0.17's do — `members` is an array of objects
 * inside the group definition, `replyTo` is an object inside a reply envelope — so this one
 * adds [arr] and [obj] and nothing more.
 *
 * What it does NOT own is the two things that would actually be duplication:
 *
 *  - **Escaping** goes through `OSHICryptoV2.jsonEscape`, the SHARED escaper compiled out of
 *    the Android tree, exactly as `ControlJson` and `DesktopEnvelope` do. A group name is
 *    arbitrary user text; there is no upside to a second implementation of that.
 *  - **Numbers** go through [WireClock.jsonNumber], so a whole-valued Double still prints
 *    `773100000` and not `7.731E8`. PLAN.md §3's key-order hazard and its number-format twin
 *    both apply here unchanged.
 *
 * ============================================================ KEY ORDER
 *
 * Same argument as `ControlJson`'s: no shipped client cares, and a byte-for-byte diff
 * against an Android payload does. The order used by [GroupUpdateWire] is Android's `put`
 * order in `GroupManager.buildIosMessageGroupJson` (`GroupManager.kt:198-224`), which is
 * itself Swift's `CodingKeys` declaration order (`OSHI/GroupMessaging.swift:271-277`) with
 * the optional blobs moved to the end because they are conditional. Matching Android's
 * emitter — rather than Swift's struct — is the deliberate choice: Android's is the one
 * with a shipped test asserting literal bytes, so it is the one a diff can be run against.
 */
internal class GroupJson {

    private val sb = StringBuilder("{")
    private var first = true

    private fun sep() {
        if (first) first = false else sb.append(',')
    }

    private fun key(k: String) {
        sep()
        sb.append('"').append(OSHICryptoV2.jsonEscape(k)).append("\":")
    }

    fun str(k: String, v: String): GroupJson = apply {
        key(k); sb.append('"').append(OSHICryptoV2.jsonEscape(v)).append('"')
    }

    fun bool(k: String, v: Boolean): GroupJson = apply { key(k); sb.append(v) }

    fun num(k: String, v: Double): GroupJson = apply { key(k); sb.append(WireClock.jsonNumber(v)) }

    fun int(k: String, v: Int): GroupJson = apply { key(k); sb.append(v) }

    /** Omits the key entirely when null. Never writes JSON `null` — PLAN.md §4.4. */
    fun optional(k: String, v: String?): GroupJson = apply { if (v != null) str(k, v) }

    /** Omits the key entirely when null. */
    fun optionalInt(k: String, v: Int?): GroupJson = apply { if (v != null) int(k, v) }

    /** A nested object, already rendered. */
    fun obj(k: String, rendered: String): GroupJson = apply { key(k); sb.append(rendered) }

    /** An array of already-rendered elements. */
    fun arr(k: String, rendered: List<String>): GroupJson = apply {
        key(k); sb.append('[').append(rendered.joinToString(",")).append(']')
    }

    /** An array of strings. */
    fun strArr(k: String, values: List<String>): GroupJson =
        arr(k, values.map { "\"" + OSHICryptoV2.jsonEscape(it) + "\"" })

    fun build(): String = "$sb}"
}
