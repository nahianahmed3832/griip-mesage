package com.griipbuddy.orbit.ui.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads an invite QR code from an image picked with Android's photo picker (a screenshot of the invite, or a photo of
 * it). Decoded on the phone, never uploaded; the decoded text then goes through [InviteCodes.fromScan] like a camera scan.
 */
object QrImageImport {
    sealed interface Result {
        data class Code(val code: String) : Result
        /** A QR code was found, but it isn't a Griip invite (a web link, Wi-Fi details…). Nothing is opened. */
        data object NotInvite : Result
        data object NoCode : Result
        data object Unreadable : Result
    }

    /** Long edges to try: a screenshot reads best near full size, a large photo after scaling down. */
    private val SIZES = intArrayOf(2048, 1024)

    suspend fun read(context: Context, uri: Uri): Result = withContext(Dispatchers.Default) {
        var anyImage = false
        for (size in SIZES) {
            val bmp = runCatching { load(context, uri, size) }.getOrNull() ?: continue
            anyImage = true
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val text = QrDecoder.decodeArgb(pixels, bmp.width, bmp.height)
            bmp.recycle()
            if (text != null) return@withContext InviteCodes.fromScan(text)?.let { Result.Code(it) } ?: Result.NotInvite
        }
        if (anyImage) Result.NoCode else Result.Unreadable
    }

    /** A software bitmap whose long edge is at most [maxEdge] (never the full photo in memory). */
    private fun load(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // getPixels needs a software bitmap
                val long = maxOf(info.size.width, info.size.height)
                if (long > maxEdge) {
                    val f = maxEdge.toFloat() / long
                    decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}
