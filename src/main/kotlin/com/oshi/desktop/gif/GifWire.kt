package com.oshi.desktop.gif

import com.oshi.desktop.store.MediaType

/**
 * The `mediaType` label a file goes out with — `__GIF_PACK_2026_09_23__`.
 *
 * A GIF is labelled `gif`, as iOS stamps it (`MediaManager.gifWireType`, set by
 * `processGIF`). That is safe on every LIVE peer, checked in their code rather than assumed:
 *  - Android maps `gif` to IMAGE (`MediaType.fromWireLabel`) and animates from the bytes;
 *  - iOS 1.0.43 has no `.gif` case, so `MediaType(rawValue:)` is nil and it falls back to the
 *    MIME, `image/gif` → `.image` (`MessageManager+V2.swift:2177-2178` at the 1.0.43 tag);
 *  - this client maps `gif` to IMAGE on receive and animates from the `GIF8` header.
 * Everything else keeps its own label.
 */
object GifWire {
    fun label(mediaType: MediaType, mime: String): String =
        if (mediaType == MediaType.IMAGE && mime.equals("image/gif", ignoreCase = true)) "gif" else mediaType.wire
}
