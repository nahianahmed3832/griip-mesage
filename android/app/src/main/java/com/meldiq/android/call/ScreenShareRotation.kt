package com.meldiq.android.call

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import livekit.org.webrtc.ScreenCapturerAndroid

/**
 * Keeps a screen share's capture the shape of the display (round 7, bug 4). LiveKit resizes the capture only from
 * motion-sensor events; a game that turns the display to landscape by itself sends none, so its frames were drawn into
 * a portrait capture and the receiver couldn't see the whole game. This follows the display itself (DisplayManager
 * reports every rotation) and resizes the capture to [CallMediaPolicy.orientedCaptureSize].
 *
 * The capture still shows exactly what Android's consent covered; nothing here starts or extends a capture.
 */
class ScreenShareRotation(context: Context, private val log: (String) -> Unit) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var capturer: ScreenCapturerAndroid? = null
    private var fps = CallMediaPolicy.SCREEN_FPS
    private var last: Pair<Int, Int>? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) update()
        }
    }

    /** Starts following the default display for [screenCapturer]; the size is checked once at once. */
    fun start(screenCapturer: ScreenCapturerAndroid, maxFps: Int = CallMediaPolicy.SCREEN_FPS) {
        stop()
        capturer = screenCapturer
        fps = maxFps
        displays?.registerDisplayListener(listener, main)
        update()
    }

    fun stop() {
        runCatching { displays?.unregisterDisplayListener(listener) }
        capturer = null
        last = null
    }

    private fun update() {
        val c = capturer ?: return
        val size = displaySize() ?: return
        val target = CallMediaPolicy.orientedCaptureSize(size.first, size.second)
        if (target == last) return
        last = target
        runCatching { c.changeCaptureFormat(target.first, target.second, fps) }
            .onSuccess { log("screen capture resized to ${target.first}×${target.second} for a ${size.first}×${size.second} display") }
            .onFailure { Log.w("ScreenShareRotation", "capture resize failed: ${it.javaClass.simpleName}") }
    }

    private fun displaySize(): Pair<Int, Int>? {
        val d = displays?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }
}
