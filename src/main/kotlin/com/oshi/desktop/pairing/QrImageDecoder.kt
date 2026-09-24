package com.oshi.desktop.pairing

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import javax.imageio.ImageIO

/** Decode a contact QR from a user-selected image, with bounded image decoding. */
object QrImageDecoder {
    private const val MAX_PIXELS = 32_000_000L

    fun decode(file: File): Result<String> = runCatching {
        val image = ImageIO.read(file) ?: error("This file is not a readable image.")
        require(image.width > 0 && image.height > 0) { "This image has invalid dimensions." }
        require(image.width.toLong() * image.height <= MAX_PIXELS) { "This image is too large to scan safely." }
        try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))).text
        } catch (_: NotFoundException) {
            error("No QR code was found in this image.")
        }
    }
}
