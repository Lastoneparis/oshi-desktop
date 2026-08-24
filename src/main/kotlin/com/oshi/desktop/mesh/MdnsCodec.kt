package com.oshi.desktop.mesh

import java.io.ByteArrayOutputStream

/**
 * A small, exact DNS wire codec — enough of RFC 1035 + RFC 6762 (mDNS) + RFC 6763
 * (DNS-SD) to advertise one service and browse for one service type.
 *
 * WHY THIS EXISTS INSTEAD OF A LIBRARY. The desktop client's whole reason to be
 * (PLAN.md) is that a third implementation of a protocol drifts from the other two in
 * silence. Discovery is the one place where the peers are not OSHI at all: the code on
 * the other side is Apple's mDNSResponder and Android's NsdManager, and what they accept
 * is defined by two RFCs rather than by this repository. A pure-JDK codec means the
 * bytes are ours to test, on a byte level, against captures from Apple's own responder
 * (see MdnsCodecTest) — and it means the discovery layer adds no dependency to a build
 * that is pinned to exactly what OSHI-Android uses.
 *
 * Deliberately not implemented, with the consequence stated in each case:
 *   - **Name compression on ENCODE.** Legal to omit (RFC 1035 §4.1.4 makes it optional
 *     for senders). Costs ~60 bytes per announcement; our packets stay far under the
 *     1500-byte MTU. Compression on DECODE is mandatory and IS implemented — Apple
 *     compresses everything.
 *   - **Probing before claiming a name** (RFC 6762 §8.1). We take the instance name
 *     `OSHI-<8 chars of the public key>` without asking whether it is free. A collision
 *     needs two peers whose base64 public keys share a 48-bit prefix; the cost if it
 *     happens is a confused browser, not data loss. The hostname we claim is derived
 *     from a hash of the key and is never the machine's own `.local.` name, so we cannot
 *     collide with the OS responder over the host record — which would break the whole
 *     machine's name resolution, not just OSHI.
 *   - **Known-answer suppression** (§7.1) and **duplicate-answer suppression** (§7.2).
 *     Costs redundant traffic on a busy network; changes nothing about correctness.
 *   - **IPv6 / AAAA.** iOS's resolver explicitly prefers IPv4 and skips any address
 *     containing ':' (OSHI/CrossPlatformMesh.swift:1592), so an AAAA-only desktop peer
 *     would be discovered and then never connected to. IPv4 only, on purpose.
 */
object MdnsCodec {

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33
    const val TYPE_ANY = 255

    const val CLASS_IN = 1

    /** Top bit of an rrclass in a RESPONSE: "flush anything cached for this name+type". */
    const val CACHE_FLUSH = 0x8000

    /** Top bit of a qclass in a QUERY: "answer me by unicast". We never set it, but peers do. */
    const val UNICAST_RESPONSE = 0x8000

    const val FLAGS_QUERY = 0x0000

    /** QR=1 (response) + AA=1 (authoritative). mDNS responses are always both. */
    const val FLAGS_RESPONSE = 0x8400

    /**
     * A DNS name as its LABELS, not as a dotted string.
     *
     * DNS-SD instance names are arbitrary UTF-8 and routinely contain dots ("Hugo's
     * MacBook Pro.local" is one label, not two), so a dotted string is a lossy
     * representation and splitting one is a decoding bug waiting to happen. Comparison
     * is ASCII-case-insensitive, as DNS requires — which is worth knowing when the label
     * embeds base64: two public keys differing only in the case of their first eight
     * characters produce names DNS considers identical.
     */
    data class Name(val labels: List<String>) {
        override fun toString(): String = labels.joinToString(".", postfix = ".")

        fun equalsIgnoreCase(other: Name): Boolean =
            labels.size == other.labels.size &&
                labels.indices.all { labels[it].equals(other.labels[it], ignoreCase = true) }

        fun endsWith(suffix: Name): Boolean =
            labels.size >= suffix.labels.size &&
                Name(labels.takeLast(suffix.labels.size)).equalsIgnoreCase(suffix)

        companion object {
            /** For CONSTANTS ONLY — splits on dots, which is exactly what an instance name may not do. */
            fun of(dotted: String): Name =
                Name(dotted.trimEnd('.').split('.').filter { it.isNotEmpty() })
        }
    }

    data class Question(val name: Name, val type: Int, val qclass: Int) {
        val wantsUnicastResponse: Boolean get() = (qclass and UNICAST_RESPONSE) != 0
    }

    sealed class Record {
        abstract val name: Name
        abstract val ttl: Int

        /** True for a "goodbye" — RFC 6762 §10.1: TTL 0 means the record is going away. */
        val isGoodbye: Boolean get() = ttl == 0

        data class Ptr(override val name: Name, override val ttl: Int, val target: Name) : Record()
        data class Srv(
            override val name: Name, override val ttl: Int,
            val priority: Int, val weight: Int, val port: Int, val target: Name,
        ) : Record()
        data class Txt(override val name: Name, override val ttl: Int, val entries: List<String>) : Record() {
            /**
             * TXT as a map. RFC 6763 §6.4: the key is up to the first '=', a repeated key
             * keeps the FIRST occurrence, and an entry with no '=' is a boolean attribute.
             * Keys are case-insensitive; both OSHI platforms use lowercase `pk`, `name`,
             * `platform`.
             */
            fun asMap(): Map<String, String> {
                val out = LinkedHashMap<String, String>()
                for (e in entries) {
                    val i = e.indexOf('=')
                    val k = (if (i < 0) e else e.substring(0, i)).lowercase()
                    val v = if (i < 0) "" else e.substring(i + 1)
                    if (k.isNotEmpty() && !out.containsKey(k)) out[k] = v
                }
                return out
            }
        }
        data class A(override val name: Name, override val ttl: Int, val address: ByteArray) : Record() {
            val ipString: String get() = address.joinToString(".") { (it.toInt() and 0xFF).toString() }
            override fun equals(other: Any?): Boolean =
                other is A && name == other.name && ttl == other.ttl && address.contentEquals(other.address)
            override fun hashCode(): Int = (name.hashCode() * 31 + ttl) * 31 + address.contentHashCode()
        }
        /** Anything else on the wire. Kept, never interpreted — a browser must not choke on a neighbour's AAAA. */
        data class Other(
            override val name: Name, val type: Int, val rrclass: Int,
            override val ttl: Int, val rdata: ByteArray,
        ) : Record() {
            override fun equals(other: Any?): Boolean =
                other is Other && name == other.name && type == other.type &&
                    rrclass == other.rrclass && ttl == other.ttl && rdata.contentEquals(other.rdata)
            override fun hashCode(): Int = ((name.hashCode() * 31 + type) * 31 + ttl) * 31 + rdata.contentHashCode()
        }
    }

    data class Message(
        val id: Int,
        val flags: Int,
        val questions: List<Question>,
        val answers: List<Record>,
        val authority: List<Record>,
        val additional: List<Record>,
    ) {
        val isResponse: Boolean get() = (flags and 0x8000) != 0
        /** Answers and additionals together — DNS-SD responders put SRV/TXT/A in either. */
        val allRecords: List<Record> get() = answers + authority + additional
    }

    // ---------------------------------------------------------------- decode

    /**
     * Parse a packet. Returns null on anything malformed.
     *
     * Null rather than throw, and a decoder that never trusts a length: this reads
     * unauthenticated UDP from the local network, where a hostile or merely buggy
     * neighbour is the normal case. Every read is bounds-checked, compression pointers
     * are followed at most [MAX_POINTER_JUMPS] times (a self-referential pointer is the
     * classic mDNS decompression bomb), and a record whose rdata runs past its stated
     * length is dropped rather than guessed at.
     */
    fun decode(packet: ByteArray, length: Int = packet.size): Message? = try {
        val r = Reader(packet, length)
        val id = r.u16()
        val flags = r.u16()
        val qd = r.u16(); val an = r.u16(); val ns = r.u16(); val ar = r.u16()
        val questions = ArrayList<Question>(qd)
        repeat(qd) {
            val n = r.name()
            questions.add(Question(n, r.u16(), r.u16()))
        }
        val answers = ArrayList<Record>(an); repeat(an) { r.record()?.let(answers::add) }
        val authority = ArrayList<Record>(ns); repeat(ns) { r.record()?.let(authority::add) }
        val additional = ArrayList<Record>(ar); repeat(ar) { r.record()?.let(additional::add) }
        Message(id, flags, questions, answers, authority, additional)
    } catch (_: Exception) {
        null
    }

    private const val MAX_POINTER_JUMPS = 64

    private class Reader(val buf: ByteArray, val limit: Int) {
        var pos = 0

        fun u8(): Int {
            if (pos >= limit) throw IndexOutOfBoundsException("mdns: read past end")
            return buf[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Int = (u16() shl 16) or u16()

        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > limit) throw IndexOutOfBoundsException("mdns: rdata past end")
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun name(): Name {
            val labels = ArrayList<String>()
            var jumps = 0
            var cursor = pos
            var advanced = false
            while (true) {
                if (cursor >= limit) throw IndexOutOfBoundsException("mdns: name past end")
                val len = buf[cursor].toInt() and 0xFF
                when {
                    len == 0 -> {
                        cursor++
                        if (!advanced) pos = cursor
                        return Name(labels)
                    }
                    (len and 0xC0) == 0xC0 -> {
                        if (cursor + 1 >= limit) throw IndexOutOfBoundsException("mdns: pointer past end")
                        val ptr = ((len and 0x3F) shl 8) or (buf[cursor + 1].toInt() and 0xFF)
                        if (!advanced) { pos = cursor + 2; advanced = true }
                        if (++jumps > MAX_POINTER_JUMPS) throw IllegalStateException("mdns: pointer loop")
                        if (ptr >= limit) throw IndexOutOfBoundsException("mdns: pointer out of range")
                        cursor = ptr
                    }
                    (len and 0xC0) != 0 -> throw IllegalStateException("mdns: reserved label type")
                    else -> {
                        if (cursor + 1 + len > limit) throw IndexOutOfBoundsException("mdns: label past end")
                        labels.add(String(buf, cursor + 1, len, Charsets.UTF_8))
                        cursor += 1 + len
                    }
                }
            }
        }

        /** One resource record. Returns null for a type we keep but do not model. */
        fun record(): Record? {
            val name = name()
            val type = u16()
            val rrclass = u16()
            val ttl = u32()
            val rdLength = u16()
            val rdStart = pos
            val rec: Record? = try {
                when (type) {
                    TYPE_PTR -> Record.Ptr(name, ttl, name())
                    TYPE_SRV -> Record.Srv(name, ttl, u16(), u16(), u16(), name())
                    TYPE_TXT -> {
                        val end = rdStart + rdLength
                        val entries = ArrayList<String>()
                        while (pos < end) {
                            val n = u8()
                            if (pos + n > end) break
                            if (n > 0) entries.add(String(bytes(n), Charsets.UTF_8))
                        }
                        Record.Txt(name, ttl, entries)
                    }
                    TYPE_A -> {
                        if (rdLength != 4) Record.Other(name, type, rrclass, ttl, bytes(rdLength))
                        else Record.A(name, ttl, bytes(4))
                    }
                    else -> Record.Other(name, type, rrclass, ttl, bytes(rdLength))
                }
            } catch (_: Exception) {
                null
            }
            // Always resynchronise on the STATED rdlength, whatever the parse did. A
            // record we mis-modelled must not cost us the records that follow it.
            pos = rdStart + rdLength
            if (pos > limit) throw IndexOutOfBoundsException("mdns: rdlength past end")
            return rec
        }
    }

    // ---------------------------------------------------------------- encode

    fun encodeQuery(questions: List<Question>): ByteArray {
        val out = ByteArrayOutputStream()
        writeHeader(out, 0, FLAGS_QUERY, questions.size, 0, 0, 0)
        for (q in questions) {
            writeName(out, q.name)
            writeU16(out, q.type)
            writeU16(out, q.qclass)
        }
        return out.toByteArray()
    }

    /**
     * A response. `answers` go in the answer section; `additional` in the additional
     * section — DNS-SD browsers read both, and putting SRV/TXT/A alongside the PTR is
     * what lets a peer resolve us from a single packet instead of three round trips.
     */
    fun encodeResponse(answers: List<Record>, additional: List<Record> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        writeHeader(out, 0, FLAGS_RESPONSE, 0, answers.size, 0, additional.size)
        for (r in answers) writeRecord(out, r)
        for (r in additional) writeRecord(out, r)
        return out.toByteArray()
    }

    private fun writeHeader(out: ByteArrayOutputStream, id: Int, flags: Int, qd: Int, an: Int, ns: Int, ar: Int) {
        writeU16(out, id); writeU16(out, flags)
        writeU16(out, qd); writeU16(out, an); writeU16(out, ns); writeU16(out, ar)
    }

    private fun writeRecord(out: ByteArrayOutputStream, rec: Record) {
        writeName(out, rec.name)
        val rdata = ByteArrayOutputStream()
        val type: Int
        var rrclass = CLASS_IN
        when (rec) {
            is Record.Ptr -> {
                type = TYPE_PTR
                // NO cache-flush on a PTR: it is a SHARED record (RFC 6762 §10.2). Setting
                // the flush bit here tells every browser to forget the OTHER OSHI peers it
                // knows about the moment we answer — one node would erase the mesh's view
                // of the rest.
                writeName(rdata, rec.target)
            }
            is Record.Srv -> {
                type = TYPE_SRV; rrclass = rrclass or CACHE_FLUSH
                writeU16(rdata, rec.priority); writeU16(rdata, rec.weight); writeU16(rdata, rec.port)
                writeName(rdata, rec.target)
            }
            is Record.Txt -> {
                type = TYPE_TXT; rrclass = rrclass or CACHE_FLUSH
                if (rec.entries.isEmpty()) {
                    // A DNS-SD TXT is never truly empty: RFC 6763 §6.1 requires a single
                    // zero-length string, because an rdata of length 0 is not a legal TXT.
                    rdata.write(0)
                } else {
                    for (e in rec.entries) {
                        val b = e.toByteArray(Charsets.UTF_8)
                        require(b.size <= 255) { "mdns: TXT entry over 255 bytes: ${e.take(24)}…" }
                        rdata.write(b.size); rdata.write(b)
                    }
                }
            }
            is Record.A -> {
                type = TYPE_A; rrclass = rrclass or CACHE_FLUSH
                require(rec.address.size == 4) { "mdns: A record needs 4 bytes" }
                rdata.write(rec.address)
            }
            is Record.Other -> { type = rec.type; rrclass = rec.rrclass; rdata.write(rec.rdata) }
        }
        writeU16(out, type)
        writeU16(out, rrclass)
        writeU32(out, rec.ttl)
        val rd = rdata.toByteArray()
        writeU16(out, rd.size)
        out.write(rd)
    }

    private fun writeName(out: ByteArrayOutputStream, name: Name) {
        for (label in name.labels) {
            val b = label.toByteArray(Charsets.UTF_8)
            require(b.isNotEmpty() && b.size <= 63) { "mdns: label must be 1..63 bytes, got ${b.size}" }
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }
}
