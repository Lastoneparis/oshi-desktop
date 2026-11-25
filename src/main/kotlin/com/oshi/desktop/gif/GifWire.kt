package com.oshi.desktop.gif

import com.oshi.desktop.store.MediaType

/**
 * The `mediaType` label a file goes out with.
 *
 * __GIF_COMPAT_IOS1043_2026_11_21__ A GIF is labelled `image` — NOT `gif` — on the wire; its
 * MIME stays `image/gif` and its file name keeps `.gif`.
 *
 * `gif` (`__GIF_PACK_2026_09_23__`) was safe on the V2 blob path this client uses: iOS 1.0.43
 * (the build on sale, commit 4befbea0) has no `.gif` case, so `MediaType(rawValue:)` is nil
 * and `v2DeliverFile` falls back to the MIME, `image/gif` → `.image`. But 1.0.43's two other
 * 1:1 decoders (`v2ParseEmbeddedMediaJSON`, `parseAndStoreMedia`) have no MIME fallback and
 * turned `gif` into a nil type — an EMPTY bubble — and every other client (Android, iOS) now
 * labels a GIF `image` so that no path can do that. One label everywhere, and no receiver
 * loses anything by it:
 *  - Android maps `gif` and `image` to the same IMAGE (`MediaType.fromWireLabel`) and Coil
 *    animates from the bytes;
 *  - iOS ≥ the GIF work animates from the `GIF8` header (`MediaManager.isGIFData`, `renderType`);
 *  - iOS 1.0.43 shows the first frame (`UIImage(data:)`), which is the best it can do;
 *  - this client maps `gif` to IMAGE on receive and animates from the `GIF8` header
 *    (`GifBubble.isGifFile`), never from the label.
 */
object GifWire {
    /** What a GIF says it is on the wire. The MIME, not this, is what still says "GIF". */
    const val GIF_LABEL = "image"

    @Suppress("UNUSED_PARAMETER")
    fun label(mediaType: MediaType, mime: String): String =
        if (mediaType == MediaType.IMAGE && mime.equals("image/gif", ignoreCase = true)) GIF_LABEL else mediaType.wire
}
