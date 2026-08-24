package com.oshi.desktop.mesh

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The TCP framing: a 4-byte BIG-ENDIAN unsigned length, then that many bytes of JSON.
 *
 * There is no other structure on this socket — no magic, no version, no type byte. Which
 * means a single desynced frame is unrecoverable: the next four bytes read are JSON text
 * interpreted as a length, and every later frame from that peer is garbage. Android
 * learned this the expensive way (CrossPlatformMesh.kt:570): its old 1 MiB cap did a
 * `continue` WITHOUT draining the body, so one oversized iPhone photo permanently broke
 * the stream. Hence [readFrame]'s contract: on any length outside the accepted range it
 * throws, and the caller must close the connection rather than try to resynchronise.
 *
 * Writes are synchronized on the stream. Three senders can target one peer concurrently
 * (a direct send, a relay, and the 30 s announce), and two interleaved writes corrupt the
 * length header of both.
 */
object MeshFraming {

    /**
     * Read one frame. Blocks.
     *
     * @throws EOFException the peer closed cleanly — normal.
     * @throws IOException the length was out of range. The stream is now unusable; the
     *         caller must close the socket, not skip and continue.
     */
    fun readFrame(input: InputStream): ByteArray {
        val din = input as? DataInputStream ?: DataInputStream(input)
        val length = din.readInt()   // throws EOFException at a clean close
        if (length <= 0 || length > MeshProtocol.MAX_FRAME_BYTES) {
            throw IOException(
                "mesh frame length=$length out of range (0, ${MeshProtocol.MAX_FRAME_BYTES}] " +
                    "— stream is desynced, connection must be reset"
            )
        }
        val buf = ByteArray(length)
        din.readFully(buf)
        return buf
    }

    /**
     * Write one frame, header and body under a single lock.
     *
     * Note what a successful return does NOT prove: the kernel accepts writes into a
     * socket whose peer is already gone, so this returning normally is not delivery.
     * [MeshNode] checks the socket's liveness before calling and treats a write failure
     * as an eviction, which is the closest a TCP sender gets to the truth.
     */
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size in 1..MeshProtocol.MAX_FRAME_BYTES) {
            "refusing to send a ${payload.size}-byte frame; peers reject anything outside " +
                "(0, ${MeshProtocol.MAX_FRAME_BYTES}]"
        }
        val dout = output as? DataOutputStream ?: DataOutputStream(output)
        synchronized(dout) {
            dout.writeInt(payload.size)
            dout.write(payload)
            dout.flush()
        }
    }
}
