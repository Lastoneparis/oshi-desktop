package com.oshi.desktop.lora

/**
 * __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ A LoRa channel as a CONVERSATION, the way
 * Meshtastic shows one.
 *
 * Third copy of a decision made on Android (`service/lora/LoRaChannelThread.kt`,
 * commit `d606a977`) and ported to iOS (`OSHI/LoRaChannelThread.swift`). The keys
 * MUST agree across the three, because a user with a phone and this client sees
 * one channel's history on both, and two spellings would be two rooms.
 *
 * ## Why this exists
 *
 * A LoRa channel is a room: everyone in radio range on the same channel hears
 * everyone else, and several people talking at once is the entire point. OSHI
 * stored none of that — every inbound packet was filed as "that node → me",
 * which is a PRIVATE conversation. Three people on LongFast therefore produced
 * three separate one-to-one threads that each looked like a direct message, and
 * the channel itself appeared nowhere.
 *
 * ## How a channel becomes a thread
 *
 * The broadcast is stored with the CHANNEL as the conversation and the real
 * author kept as the sender, so every speaker stays individually identifiable
 * with no new field anywhere.
 *
 * A DIRECT packet stays a private thread: it was addressed to this node alone
 * and nobody else heard it. Telling the two apart is the whole reason
 * [LoRaProto.Incoming] now carries `to` — without it this client cannot
 * distinguish a public broadcast from someone writing to it privately, which is
 * why everything was filed as private.
 *
 * ## Why not a group
 *
 * Because a LoRa channel is not one. An OSHI group has a member list, an admin,
 * invitations and end-to-end encryption; a public LoRa channel has no membership
 * at all, and on the default key no meaningful encryption either — anyone in
 * range is in it. Modelling it as a group would mean inventing a roster nobody
 * agreed to and implying a privacy guarantee that does not exist.
 */
object LoRaChannelThread {

    /**
     * A NAMED channel: `lorachan!manif`.
     *
     * The name follows the `!`, so whatever the user typed cannot be mistaken for
     * the slot form below.
     */
    const val PREFIX = "lorachan!"

    /**
     * An UNNAMED channel, by slot: `lorachan#3`.
     *
     * A separate prefix and not `PREFIX + "#3"`, because a channel the user NAMED
     * "#3" would otherwise produce exactly the same key as the unnamed slot 3 and
     * the two rooms would merge silently. Android's own test caught that before it
     * shipped; the two spellings differ at character nine and cannot overlap.
     */
    const val PREFIX_SLOT = "lorachan#"

    /**
     * The stock name every Meshtastic install gives slot 0 when it is unnamed.
     * DISPLAY ONLY, for the same reason [LoRaAdmin.Channel.DEFAULT_NAME] says so:
     * writing it as an actual name produces a different channel hash from every
     * default node out there.
     */
    const val DEFAULT_NAME = "LongFast"

    /**
     * The thread key for a channel.
     *
     * Keyed on the NAME when there is one, because that is the part two radios
     * actually agree on: `MeshPacket.channel` is an index into the sending node's
     * own table and the proto says so out loud — *"the index is local to each
     * node, so it has no meaning when sent between nodes"*. Keying on the index
     * alone would move every message to a different room the moment a user
     * reordered their channels.
     *
     * Falls back to the index when the name is blank, which is the stock unnamed
     * primary channel. Lower-cased so one channel does not split into two rooms
     * over capitalisation.
     */
    fun keyFor(channelIndex: Int, channelName: String?): String {
        val name = channelName?.trim().orEmpty()
        return if (name.isEmpty()) "$PREFIX_SLOT$channelIndex" else PREFIX + name.lowercase()
    }

    /** True for a key produced by [keyFor]. */
    fun isChannelKey(key: String): Boolean =
        key.startsWith(PREFIX) || key.startsWith(PREFIX_SLOT)

    /**
     * What to call the room on screen.
     *
     * An unnamed primary channel shows as "LongFast" rather than "#0", because
     * that is the name every stock install uses for it. A secondary slot with no
     * name keeps its number: there is nothing truer to call it.
     */
    fun displayName(key: String): String {
        if (key.startsWith(PREFIX)) return key.removePrefix(PREFIX)
        if (!key.startsWith(PREFIX_SLOT)) return key
        val index = key.removePrefix(PREFIX_SLOT).toIntOrNull() ?: return key
        return if (index == 0) DEFAULT_NAME else "#$index"
    }

    /**
     * __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ The radio's own channel table, so a NAMED
     * channel keys the same here as on the phones.
     *
     * Without it every room falls back to its slot form, and a channel the user called
     * "manif" would be `lorachan#0` on this client and `lorachan!manif` on their phone —
     * two rooms for one channel, which is the divergence [keyFor]'s own note warns
     * about.
     *
     * `FromRadio.channel` is field 10, and the phones were ignoring it too until
     * 2026-11-04: the node sends its eight slots unprompted during the handshake, so
     * there is nothing to ask for. `Channel { index = 1, settings = 2, role = 3 }`,
     * `ChannelSettings { psk = 2, name = 3 }`, `Role { DISABLED = 0, PRIMARY = 1,
     * SECONDARY = 2 }` — read off the published protos, not from memory.
     */
    class Table {

        private val names = java.util.concurrent.ConcurrentHashMap<Int, String>()

        /**
         * Learn one slot from a `FromRadio` frame. Returns true when it carried one.
         *
         * A DISABLED slot is removed rather than stored: most nodes report seven of them,
         * and a disabled slot is not a channel anyone is on. Keeping its name would let a
         * room survive the user turning the channel off.
         */
        fun observeFromRadio(fromRadio: ByteArray): Boolean {
            val channel = LoRaProto.field(fromRadio, 10, wire = 2)?.payload ?: return false
            val index = LoRaProto.field(channel, 1, wire = 0)?.varintValue?.toInt() ?: 0
            val role = LoRaProto.field(channel, 3, wire = 0)?.varintValue ?: 0L
            if (role == 0L) {
                names.remove(index)
                return true
            }
            val settings = LoRaProto.field(channel, 2, wire = 2)?.payload
            val name = settings
                ?.let { LoRaProto.field(it, 3, wire = 2)?.payload }
                ?.toString(Charsets.UTF_8)
                ?.trim()
                .orEmpty()
            // A blank name IS the stock unnamed channel, and storing "" would be
            // indistinguishable from "not reported yet" at the call site. Absent is the
            // honest record, and `keyFor` already falls back to the slot form.
            if (name.isEmpty()) names.remove(index) else names[index] = name
            return true
        }

        /** The name of a slot, or null when it is unnamed or not yet reported. */
        fun name(index: Int): String? = names[index]

        fun clear() = names.clear()

        /** Every named slot, for a pane that lists the rooms this radio can hear. */
        fun named(): Map<Int, String> = names.toMap()
    }
}
