package com.oshi.desktop.media

/**
 * __FFMPEG_UNTRUSTED_INPUT_2026_11_20__ What ffmpeg / libavformat may do with a file a peer
 * sent. Without a protocol whitelist, a "voice note" that is really an HLS `.m3u8` or a concat
 * list makes ffmpeg fetch every URL it names — the receiver's IP to the sender's server, and
 * requests to internal addresses — before any audio is decoded.
 */
object FfmpegInputPolicy {
    /** Local files only. */
    const val PROTOCOLS = "file"

    val CLI_INPUT_ARGS: Array<String> = arrayOf("-protocol_whitelist", PROTOCOLS)
}
