package com.griipbuddy.message.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * What a scanned QR code may contain (T4.2). Only Griip invite codes are accepted: the link the
 * invite screen draws (`griip://invite/CODE`), the shared invite text that contains it, or a bare
 * code. Anything else, web links included, is refused, so a QR code can never make the app open a URL.
 */
object InviteCodes {
    private val LINK = Regex("""griip://invite/([A-Za-z0-9-]{1,40})""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""[A-Za-z0-9]{4}(-?[A-Za-z0-9]{4}){2,4}""")

    /** The invite code in [scanned], formatted `XXXX-XXXX-XXXX`, or null if it isn't a Griip invite. */
    fun fromScan(scanned: String): String? {
        val text = scanned.trim()
        val raw = LINK.find(text)?.groupValues?.get(1) ?: text.takeIf { BARE.matches(it) } ?: return null
        val code = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        if (code.length !in 12..20) return null
        return code.chunked(4).joinToString("-")
    }
}

/** Decodes a QR code from a camera frame's luminance (Y) plane. Pure zxing, so it runs on the JVM too. */
object QrDecoder {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) {
        null // no code in this frame, or it couldn't be read
    }
}
