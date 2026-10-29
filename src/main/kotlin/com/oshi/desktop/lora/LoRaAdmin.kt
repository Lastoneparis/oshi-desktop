package com.oshi.desktop.lora

/**
 * Configuring the attached node — region, modem preset, and which channel it is on.
 *
 * __DESKTOP_LORA_ADMIN_2026_10_28__ The last LoRa gap against the phones, and the one with
 * teeth. Port of `MeshtasticManager.setRegion` (`OSHI/MeshtasticManager.swift:838`) and
 * `setChannel` (`:1010`), with their warnings carried over rather than summarised, because
 * every one of them describes a way to leave a working radio silent.
 *
 * ============================================================ WHY REGION MATTERS AT ALL
 *
 * Straight from the flasher a node boots with `region=UNSET` and will not put anything on
 * the air:
 *
 *     INFO | Wanted region 0, using UNSET
 *
 * It pairs, it reports connected, and it silently transmits nothing. Until region is
 * written, the node cannot send or receive a single message. That is why this exists, and
 * also why writing it badly is worse than not offering it.
 *
 * ============================================================ THE WHOLE CONFIG IS REPLACED
 *
 * ⚠️ Protobuf omits defaults, so **any field left out arrives as zero**. Leaving `usePreset`
 * out sends `false`, which switches the radio to a manual bandwidth and spread factor of 0
 * and produces a node that is configured, on the air, and unable to talk to anyone. Every
 * field that matters is therefore written explicitly, including the ones whose value
 * happens to equal the default.
 *
 * The same rule governs the channel: a `psk` left out is "no encryption at all", not "keep
 * what you had", and an empty name is written deliberately because blank with
 * [Channel.DEFAULT_PSK] IS the stock LongFast channel — which is how "put me back on the
 * normal mesh" stays reachable.
 *
 * ============================================================ 33 AND 34 ARE NOT
 * INTERCHANGEABLE
 *
 * `AdminMessage.set_channel` is field 33 and carries a `Channel`; `set_config` is field 34
 * and carries a `Config`. Sending a Config under 33 makes the node try to parse it as a
 * Channel and throw it away — so "Set region" silently does nothing, on the one screen whose
 * entire purpose is to get a node out of `region=UNSET`. iOS records that it hit exactly
 * this and verified the numbers against the Meshtastic protobuf descriptors rather than
 * from memory; these are the same numbers, pinned by a byte-level test.
 *
 * Everything here is PURE: it returns the bytes of an `AdminMessage` and sends nothing.
 * The caller addresses it to its OWN node — this configures the radio on the other end of
 * the cable, never a peer on the mesh.
 */
object LoRaAdmin {

    /** `PortNum.ADMIN_APP`. */
    const val PORT_ADMIN = 6L

    /** `Config.LoRaConfig.hop_limit` — Meshtastic's own default. */
    const val DEFAULT_HOP_LIMIT = 3L

    /**
     * `Config.LoRaConfig.RegionCode`, the firmware's own numbering.
     *
     * [UNSET] is listed because the firmware has it and a reader of a node's current config
     * will see it; it is never WRITTEN, because writing it is the state this file exists to
     * get a radio out of.
     */
    enum class Region(val wire: Long, val label: String) {
        UNSET(0, "Unset"),
        US(1, "United States"),
        EU433(2, "EU 433 MHz"),
        EU868(3, "EU 868 MHz"),
        CN(4, "China"),
        JP(5, "Japan"),
        ANZ(6, "Australia / New Zealand"),
        KR(7, "Korea"),
        TW(8, "Taiwan"),
        RU(9, "Russia"),
        IN(10, "India"),
        NZ865(11, "New Zealand 865 MHz"),
        TH(12, "Thailand"),
        LORA24(13, "2.4 GHz"),
        UA433(14, "Ukraine 433 MHz"),
        UA868(15, "Ukraine 868 MHz"),
        MY433(16, "Malaysia 433 MHz"),
        MY919(17, "Malaysia 919 MHz"),
        SG923(18, "Singapore 923 MHz"),
        PH433(19, "Philippines 433 MHz"),
        PH868(20, "Philippines 868 MHz"),
        PH915(21, "Philippines 915 MHz"),
        ANZ433(22, "Australia / NZ 433 MHz"),
        KZ433(23, "Kazakhstan 433 MHz"),
        KZ863(24, "Kazakhstan 863 MHz"),
        NP865(25, "Nepal 865 MHz"),
        BR902(26, "Brazil 902 MHz");

        companion object {
            fun fromWire(w: Long): Region? = entries.firstOrNull { it.wire == w }

            /**
             * __DESKTOP_LORA_REGION_UI_2026_10_29__ The regions a PERSON may be offered.
             *
             * [UNSET] is excluded, and that exclusion is the whole point of this list
             * existing rather than each caller filtering: UNSET is the state a radio has to
             * be rescued from, [setConfigFrame] refuses to write it, and a picker that
             * offered it would be offering to silence a working node. The region picker in
             * `LoRaPane` and `/lora region` in the console both read this, so the rule is
             * asserted in one place instead of being retyped per caller.
             */
            val selectable: List<Region> get() = entries.filter { it != UNSET }
        }
    }

    /** `Config.LoRaConfig.ModemPreset`. The two the phones offer, and no more. */
    enum class ModemPreset(val wire: Long, val label: String) {
        LONG_FAST(0, "Long range, faster"),
        LONG_SLOW(1, "Long range, slower");
    }

    object Channel {
        /** The firmware's `ChannelSettings.name` is a `char[12]`: 11 bytes + NUL. */
        const val NAME_MAX_BYTES = 11

        /**
         * What a stock node calls its unnamed primary channel. **DISPLAY ONLY** — writing
         * this as the name would produce a different channel hash from every default node
         * out there, the exact opposite of what "LongFast by default" asks for.
         */
        const val DEFAULT_NAME = "LongFast"

        /** One byte, value 1: the key index meaning "the public default key". */
        val DEFAULT_PSK: ByteArray get() = byteArrayOf(1)

        /** Key sizes the firmware accepts: an index byte, AES-128, or AES-256. */
        private val PSK_SIZES = setOf(1, 16, 32)

        /**
         * [raw] clamped to [NAME_MAX_BYTES] UTF-8 bytes, never cutting a code point in half.
         *
         * Blank stays blank — that is the LongFast default, not an error. Counting BYTES and
         * not characters is the whole point: one emoji is four of the eleven.
         */
        fun clampName(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.toByteArray(Charsets.UTF_8).size <= NAME_MAX_BYTES) return trimmed

            // GRAPHEME CLUSTERS, not chars and not code points.
            //
            // `for (ch in trimmed)` iterates UTF-16 code UNITS, so one emoji is two Chars
            // and clamping between them emits half a surrogate pair — eleven bytes of
            // mojibake, written to the radio without complaint. Code points fix that half
            // and leave the other: a ZWJ sequence clamped between its parts ends in a
            // dangling joiner. iOS iterates Swift `Character`s, which ARE grapheme
            // clusters, so BreakIterator is what reproduces it rather than approximates it.
            val out = StringBuilder()
            var used = 0
            for (cluster in graphemes(trimmed)) {
                val n = cluster.toByteArray(Charsets.UTF_8).size
                if (used + n > NAME_MAX_BYTES) break
                out.append(cluster)
                used += n
            }
            return out.toString()
        }

        /** Zero-width joiner: what makes 👨‍👩‍👦 ONE character rather than three. */
        private const val ZWJ = '\u200D'

        /**
         * [s] split into grapheme clusters, the same way on EVERY JDK.
         *
         * `BreakIterator` alone was not that. Its Unicode data is the JDK's, and the
         * emoji ZWJ rule (UAX #29 GB11) only arrived in java.text with JDK 20 — so on
         * **JDK 17, which is what `desktop-ci.yml` runs on all three platforms and what a
         * release built there would bundle**, `BreakIterator` hands back 👨, ZWJ, 👩, ZWJ,
         * 👦 as five clusters. `clampName` then packed 👨 + ZWJ + 👩 into exactly eleven
         * bytes and wrote **a dangling joiner to the radio** — which is, word for word,
         * the outcome the comment above says BreakIterator was chosen to prevent. The
         * test that says so has been red in CI and green on this Mac (JDK 21) since the
         * day it was written, and the difference was never the OS.
         *
         * So the joins are applied here rather than hoped for: a cluster that ends with a
         * ZWJ absorbs the next one, and a cluster that begins with a joiner, a variation
         * selector, a skin-tone modifier or an enclosing keycap belongs to the one before
         * it. Everything else is left to `BreakIterator`, which is right about scripts
         * this will never enumerate.
         */
        internal fun graphemes(s: String): List<String> {
            val bi = java.text.BreakIterator.getCharacterInstance()
            bi.setText(s)
            val raw = ArrayList<String>()
            var start = bi.first()
            var end = bi.next()
            while (end != java.text.BreakIterator.DONE) {
                raw.add(s.substring(start, end))
                start = end
                end = bi.next()
            }
            val out = ArrayList<String>(raw.size)
            for (piece in raw) {
                val prev = out.lastOrNull()
                if (prev != null && (prev.endsWith(ZWJ) || attachesToPrevious(piece))) {
                    out[out.size - 1] = prev + piece
                } else {
                    out.add(piece)
                }
            }
            return out
        }

        /** True when [piece] can never start a character of its own. */
        private fun attachesToPrevious(piece: String): Boolean {
            if (piece.isEmpty()) return false
            val cp = piece.codePointAt(0)
            return cp == 0x200D ||                       // ZWJ
                cp == 0xFE0F || cp == 0xFE0E ||          // variation selectors
                cp == 0x20E3 ||                          // combining enclosing keycap
                cp in 0x1F3FB..0x1F3FF                   // skin-tone modifiers
        }

        /**
         * The key to write, or null when [raw] is not one.
         *
         * Blank gives [DEFAULT_PSK]. Both base64 alphabets are accepted, because a key
         * copied out of the Meshtastic app arrives in whichever one that build used, and
         * rejecting the other spelling would read as "your key is wrong".
         */
        fun pskOrNull(raw: String): ByteArray? {
            val t = raw.trim()
            if (t.isEmpty()) return DEFAULT_PSK
            val standard = t.replace('-', '+').replace('_', '/')
            val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
            val bytes = runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull() ?: return null
            return if (bytes.size in PSK_SIZES) bytes else null
        }
    }

    /**
     * `AdminMessage{ set_config: Config{ lora: LoRaConfig } }`.
     *
     * Refuses [Region.UNSET]: that is the state a radio has to be rescued FROM, and writing
     * it deliberately would silence a working node.
     */
    fun setConfigFrame(
        region: Region,
        preset: ModemPreset = ModemPreset.LONG_FAST,
        hopLimit: Long = DEFAULT_HOP_LIMIT,
    ): ByteArray? {
        if (region == Region.UNSET) return null

        val lora = ProtoWriter()
            .varint(1, 1)                 // use_preset = true  — NOT optional, see the class doc
            .varint(2, preset.wire)       // modem_preset
            .varint(7, region.wire)       // region
            .varint(8, hopLimit)          // hop_limit
            .varint(9, 1)                 // tx_enabled = true
            .data

        val config = ProtoWriter().bytes(6, lora).data       // Config.lora
        return ProtoWriter().bytes(34, config).data          // AdminMessage.set_config — 34, not 33
    }

    /**
     * `AdminMessage{ set_channel: Channel{ index: 0, settings, role: PRIMARY } }`.
     *
     * ```
     *   Channel          { index = 1, settings = 2, role = 3 }
     *   ChannelSettings  { psk = 2, name = 3 }
     *   Channel.Role     { DISABLED = 0, PRIMARY = 1, SECONDARY = 2 }
     * ```
     *
     * Index 0 with role PRIMARY is the slot a node's main channel lives in, and the slot the
     * Meshtastic app edits when it says "Channel". Both halves are written explicitly,
     * INCLUDING an empty name, because this replaces the channel wholesale.
     */
    /**
     * __DESKTOP_LORA_BEACON_FIXED_2026_10_28__ `AdminMessage{ set_fixed_position: Position }`
     * — field **41**, a `Position`.
     *
     * This exists because the obvious approach does not work, and the measurement is worth
     * keeping: sending a POSITION_APP MeshPacket to the broadcast address — which is what
     * `broadcastPosition` does on both phones and what `loraBroadcastPosition` did here —
     * is accepted by the radio and **never reaches another node**. Measured with two
     * ThinkNode M1s in the same room (`LiveBeaconDiagnosisBench`): a plain interop TEXT
     * broadcast sent from the same radio in the same second arrived at the second radio in
     * 3 seconds, while ten position frames over four minutes produced nothing, on a mesh
     * where that same receiver was reading positions from 36 other nodes correctly. The
     * portnum is the only difference between the two sends.
     *
     * The firmware owns a node's position. A client does not announce one on its behalf; it
     * tells the node where it is, and the node's PositionModule broadcasts on its own
     * schedule (`position_broadcast_secs`). That is also why the phones' 15-minute policy is
     * the wrong shape for this lane — the radio already has one.
     *
     * Field numbers: `set_owner` 32, `set_channel` 33, `set_config` 34 are already here and
     * already proven against a real radio, and 41 continues that same enumeration
     * (`remove_fixed_position` is 42, a bool).
     *
     * ⚠️ This WRITES THE NODE'S CONFIGURATION. Unlike a broadcast, it persists on the
     * device and outlives this process — a radio told it is in Geneva stays in Geneva for
     * every app that connects to it afterwards, until something clears it.
     */
    fun setFixedPositionFrame(latitude: Double, longitude: Double): ByteArray? {
        if (latitude == 0.0 && longitude == 0.0) return null
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null
        val position = ProtoWriter()
            .fixed32(1, Math.round(latitude * 1e7).toInt())   // Position.latitude_i
            .fixed32(2, Math.round(longitude * 1e7).toInt())  // Position.longitude_i
            .data
        return ProtoWriter().bytes(41, position).data         // AdminMessage.set_fixed_position
    }

    /**
     * __DESKTOP_LORA_SMART_BROADCAST_2026_10_28__ `AdminMessage{ set_config: Config{
     * position: <payload> } }` — set_config is 34, `Config.position` is 2.
     *
     * Takes a COMPLETE `PositionConfig` payload rather than building one from arguments,
     * and that is the whole safety story. Protobuf omits defaults and the firmware replaces
     * the submessage wholesale, so a config assembled from the two fields a caller happens
     * to care about arrives with every other field zeroed — `position_broadcast_secs = 0`,
     * `gps_mode = 0`. This repository already has that scar in [setConfigFrame]: "leaving
     * `use_preset` out sends false, which produces a node that is configured, on the air,
     * and unable to talk to anyone".
     *
     * So the only supported way to change one setting is: READ the node's current config
     * (it arrives in the handshake burst), change one field of those exact bytes with
     * [withVarintField], and send the result back.
     */
    fun setPositionConfigFrame(positionConfig: ByteArray): ByteArray {
        val config = ProtoWriter().bytes(2, positionConfig).data   // Config.position
        return ProtoWriter().bytes(34, config).data                // AdminMessage.set_config
    }

    /**
     * Rewrite ONE varint field of a protobuf payload, preserving every other field exactly.
     *
     * Returns null when the payload cannot be re-emitted byte-for-byte — which is checked
     * by rebuilding it UNCHANGED first and comparing. That check is not ceremony: this
     * function's output is written into a radio's flash, and a field this parser does not
     * understand would be silently dropped on the way through. Refusing is the only safe
     * answer to "I do not fully understand these bytes".
     */
    fun withVarintField(payload: ByteArray, field: Int, value: Long): ByteArray? {
        fun rebuild(overrideField: Int?, overrideValue: Long): ByteArray? {
            val out = ProtoWriter()
            for (f in LoRaProto.parseFields(payload)) {
                when (f.wire) {
                    0 -> out.varint(f.number, if (f.number == overrideField) overrideValue else f.varintValue)
                    2 -> out.bytes(f.number, f.payload)
                    5 -> out.fixed32(f.number, f.asInt32())
                    else -> return null   // a wire type this cannot reproduce
                }
            }
            return out.data
        }
        // Round-trip the original first. If that is not identical, this parser does not
        // understand the payload well enough to be allowed to rewrite it.
        val identity = rebuild(null, 0) ?: return null
        if (!identity.contentEquals(payload)) return null
        return rebuild(field, value)
    }

    /**
     * __DESKTOP_LORA_REBOOT_2026_10_28__ `AdminMessage{ reboot_seconds: n }` — field 97.
     *
     * Here for ONE reason, and it is a test reason: `PositionModule::runOnce` sends
     * immediately when `lastGpsSend == 0`, which is true only on a fresh boot. A node with
     * `fixed_position` is otherwise clamped to a six-hour announcement floor
     * (`default_position_stationary_broadcast_secs`), so a reboot is the only way to
     * observe its position going out inside a test's lifetime.
     *
     * Not exposed in the UI. Rebooting someone's radio is not a feature a messaging app
     * should offer behind a button.
     */
    fun rebootFrame(seconds: Int = 5): ByteArray =
        ProtoWriter().varint(97, seconds.toLong()).data

    /**
     * __DESKTOP_LORA_SET_OWNER_2026_10_29__ `AdminMessage{ set_owner: User }` — field 32.
     *
     * Naming the radio. Both phones have had this (`setOwner` on each
     * `MeshtasticManager`) and this client had nothing: a desktop owner could set the
     * node's region and its channel but not its NAME, so their radio kept whatever the
     * factory gave it on every other client's list and on public mesh maps.
     *
     * `User.id` is field 1 and is the `!%08x` node id; `long_name` is 2; `short_name` is 3.
     *
     * ⚠️ `short_name` is **FOUR BYTES, not four characters** — see [clampShortName]. Android
     * records the bug this caused (`__SHORT_NAME_IS_BYTES_2026_10_21__`): `take(4)` counted
     * UTF-16 chars, so two emoji sailed through as eight bytes and a surrogate pair could
     * be cut in half.
     */
    fun setOwnerFrame(nodeNum: UInt, longName: String, shortName: String): ByteArray {
        val user = ProtoWriter()
            .bytes(1, nodeIdString(nodeNum).toByteArray(Charsets.UTF_8))   // User.id
            .bytes(2, longName.toByteArray(Charsets.UTF_8))                // User.long_name
            .bytes(3, clampShortName(shortName).toByteArray(Charsets.UTF_8))
            .data
        return ProtoWriter().bytes(32, user).data                          // AdminMessage.set_owner
    }

    /** `"!a1b2c3d4"` — the id every Meshtastic client spells a node with. */
    fun nodeIdString(nodeNum: UInt): String = "!" + nodeNum.toString(16).padStart(8, '0')

    /**
     * Truncate to at most four BYTES without splitting a character.
     *
     * Four bytes is what the node's own display and other clients' lists hold. Counting
     * anything else is the Android bug named above; counting bytes but cutting mid-sequence
     * would be worse, because the radio would then carry invalid UTF-8 and show a replacement
     * glyph to everyone on the mesh. Whole code points only, which is why this walks the
     * string instead of slicing the ByteArray.
     */
    fun clampShortName(raw: String): String {
        val trimmed = raw.trim()
        var bytes = 0
        val out = StringBuilder()
        for (cp in trimmed.codePoints()) {
            val piece = String(Character.toChars(cp))
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + size > SHORT_NAME_MAX_BYTES) break
            out.append(piece)
            bytes += size
        }
        return out.toString()
    }

    /** Four bytes — the node display's width, and other clients' column. */
    const val SHORT_NAME_MAX_BYTES = 4

    /**
     * The name this client gives a radio that is still wearing its factory default —
     * iOS's `adoptedName`, same shape so a node renamed by either looks the same.
     *
     * The hex suffix stays: anyone carrying two nodes has to tell them apart, and a mesh
     * map with a dozen identical "OSHI Mesh" pins is useless.
     */
    fun adoptedName(nodeNum: UInt): String = "OSHI Mesh %04X".format((nodeNum.toLong() and 0xFFFF))

    /** `AdminMessage{ remove_fixed_position: true }` — field 42, a bool. */
    fun removeFixedPositionFrame(): ByteArray =
        ProtoWriter().varint(42, 1).data

    fun setChannelFrame(name: String, psk: ByteArray = Channel.DEFAULT_PSK): ByteArray {
        val settings = ProtoWriter()
            .bytes(2, psk)                                              // ChannelSettings.psk
            .bytes(3, Channel.clampName(name).toByteArray(Charsets.UTF_8))  // .name (<= 11 BYTES)
            .data

        val channel = ProtoWriter()
            .varint(1, 0)                 // Channel.index = 0 (primary slot)
            .bytes(2, settings)           // Channel.settings
            .varint(3, 1)                 // Channel.role = PRIMARY
            .data

        return ProtoWriter().bytes(33, channel).data         // AdminMessage.set_channel — 33, not 34
    }
}
