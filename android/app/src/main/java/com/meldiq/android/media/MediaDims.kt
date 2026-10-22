package com.meldiq.android.media

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Batch B16: a photo's or video's size as it's shown (rotation applied), sent inside the encrypted message so the chat can
 * keep the picture's space before it has downloaded or decoded (no jump when it appears).
 */
object MediaDims {
    /** The largest edge a sender may claim; anything else is ignored (the size comes from another phone). */
    private const val MAX_EDGE = 20_000

    /** Width and height as shown, or null when [file] isn't a photo or video this phone can read. */
    fun of(file: File, mime: String): Pair<Int, Int>? = runCatching {
        val m = mime.lowercase()
        when {
            m.startsWith("image/") -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val degrees = exifDegrees(orientation)
                oriented(bounds.outWidth, bounds.outHeight, degrees)
            }
            m.startsWith("video/") -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.path)
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val degrees = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    oriented(w, h, degrees)
                } finally {
                    runCatching { r.release() }
                }
            }
            else -> null
        }
    }.getOrNull()

    /** The turn an EXIF orientation applies (the mirrored ones turn too: 5 and 7 are quarter turns). */
    fun exifDegrees(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }

    /** Width and height after turning by [degrees] (a quarter turn swaps them); null when either is unknown. */
    fun oriented(width: Int, height: Int, degrees: Int): Pair<Int, Int>? {
        if (!valid(width, height)) return null
        return if (((degrees % 360) + 360) % 180 == 90) height to width else width to height
    }

    /** Whether a received size is usable (both edges set and believable). */
    fun valid(width: Int?, height: Int?): Boolean = width != null && height != null && width in 1..MAX_EDGE && height in 1..MAX_EDGE
}
