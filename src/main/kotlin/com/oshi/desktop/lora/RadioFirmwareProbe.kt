package com.oshi.desktop.lora

import java.io.IOException
import java.io.InputStream

/**
 * __MESHCORE_INTEROP_DESKTOP_2026_11_25__ Which firmware is on the radio plugged into this port, so the
 * person clicks ONE "Connect" and never has to know whether their board runs MeshCore or Meshtastic.
 *
 * Both answer on the same USB serial device and both ignore what the other speaks, which is what makes
 * asking safe:
 *
 *  1. MeshCore first: CMD_APP_START wrapped as '<' + len LE16 + frame. A MeshCore USB companion answers
 *     '>' + len + RESP_SELF_INFO (code 5, ≥ 36 bytes: it carries the radio's 32-byte public key).
 *     Meshtastic's stream parser waits for 0x94 0xC3 and drops these bytes.
 *  2. Then Meshtastic: `want_config` in its own 0x94 0xC3 framing. A Meshtastic node answers with a
 *     `FromRadio` frame in the same framing. MeshCore's serial reader waits for '<' and drops it.
 *
 * Nothing either answer to is a setting change: APP_START only introduces an app, want_config only asks
 * for the configuration. The probe connection is closed before the real link opens the port again.
 *
 * Reads are polled with `available()`, never blocking: a thread left blocked in `read` on this tty would
 * keep consuming bytes after the probe closed, stealing them from the link that comes next.
 */
object RadioFirmwareProbe {

    enum class Firmware {
        MESHCORE,
        MESHTASTIC,
        /** The port opened and nothing recognisable came back (a BLE-only MeshCore build, a busy radio, …). */
        UNKNOWN,
        /** The port could not be opened at all. */
        UNREACHABLE,
    }

    const val MESHCORE_WAIT_MS = 2_500L
    const val MESHTASTIC_WAIT_MS = 3_500L

    fun detect(
        channel: LoRaChannel,
        log: (String) -> Unit = {},
        meshCoreWaitMs: Long = MESHCORE_WAIT_MS,
        meshtasticWaitMs: Long = MESHTASTIC_WAIT_MS,
    ): Firmware {
        val conn = try { channel.open() } catch (e: Exception) {
            log("probe: could not open ${channel.label}: ${e.message}")
            return Firmware.UNREACHABLE
        }
        try {
            val seen = java.io.ByteArrayOutputStream()
            conn.output.write(MeshCore.Serial.encode(MeshCore.appStart("OSHI")))
            conn.output.flush()
            if (waitFor(conn.input, seen, meshCoreWaitMs) { isMeshCore(it) }) return Firmware.MESHCORE
            val configId = (System.nanoTime().toInt() and 0x7FFFFFFF).toUInt()
            conn.output.write(LoRaAttach.StreamFraming.encode(LoRaProto.wantConfig(configId)))
            conn.output.flush()
            if (waitFor(conn.input, seen, meshtasticWaitMs) { isMeshtastic(it) }) return Firmware.MESHTASTIC
            // A late MeshCore answer (a radio busy flushing its queue) still counts.
            return if (isMeshCore(seen.toByteArray())) Firmware.MESHCORE else Firmware.UNKNOWN
        } catch (e: IOException) {
            log("probe: ${channel.label}: ${e.message}")
            return Firmware.UNKNOWN
        } finally {
            conn.close()
        }
    }

    private fun waitFor(input: InputStream, seen: java.io.ByteArrayOutputStream, ms: Long, done: (ByteArray) -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        val chunk = ByteArray(1024)
        while (System.currentTimeMillis() < end) {
            val a = input.available()
            if (a > 0) {
                val n = input.read(chunk, 0, minOf(a, chunk.size))
                if (n < 0) throw IOException("stream closed")
                seen.write(chunk, 0, n)
                if (seen.size() > 64 * 1024) {
                    val tail = seen.toByteArray().let { it.copyOfRange(it.size - 4096, it.size) }
                    seen.reset(); seen.write(tail)
                }
                if (done(seen.toByteArray())) return true
            } else Thread.sleep(20)
        }
        return false
    }

    /** A complete '>'-framed RESP_SELF_INFO somewhere in [bytes]. */
    fun isMeshCore(bytes: ByteArray): Boolean {
        var buf = bytes
        while (true) {
            val d = MeshCore.Serial.decode(buf) ?: return false
            if (MeshCore.selfPublicKey(d.payload) != null) return true
            buf = buf.copyOfRange(d.consumed, buf.size)
        }
    }

    /** A complete 0x94 0xC3 frame that parses as a protobuf with at least one field. */
    fun isMeshtastic(bytes: ByteArray): Boolean {
        var at = 0
        while (at < bytes.size) {
            val d = LoRaAttach.StreamFraming.decode(bytes, at) ?: return false
            if (d.protobuf.isNotEmpty() && LoRaProto.parseFields(d.protobuf).isNotEmpty()) return true
            at += d.consumed
        }
        return false
    }
}
