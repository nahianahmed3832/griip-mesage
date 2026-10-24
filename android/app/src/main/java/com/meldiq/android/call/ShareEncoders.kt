package com.meldiq.android.call

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** This phone's video encoders, read once, for [CallMediaPolicy.canShareHigh]. */
object ShareEncoders {
    val highSupported: Boolean by lazy { runCatching { CallMediaPolicy.canShareHigh(encoders()) }.getOrDefault(false) }

    private fun encoders(): List<CallMediaPolicy.Encoder> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder }
        .flatMap { info -> info.supportedTypes.map { mime -> describe(info, mime) } }

    private fun describe(info: MediaCodecInfo, mime: String): CallMediaPolicy.Encoder {
        val video = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
        val does = video != null && runCatching { video.areSizeAndRateSupported(1920, 1080, 60.0) || video.areSizeAndRateSupported(1080, 1920, 60.0) }.getOrDefault(false)
        return CallMediaPolicy.Encoder(mime.lowercase(), hardware(info), does)
    }

    private fun hardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
        else info.name.lowercase().let { !it.startsWith("omx.google.") && !it.startsWith("c2.android.") }
}
