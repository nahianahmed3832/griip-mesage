package com.meldiq.android.ui.screens.calls

/**
 * Connects one video renderer to whichever track its tile currently shows, in the order WebRTC needs:
 * - the renderer is a sink of the track only while the screen is started (frames into a destroyed surface crashed natively);
 * - switching tracks (camera ↔ screen share) removes the old sink before adding the new one;
 * - [release] removes the sink first and releases the renderer exactly once, and only when the tile goes away.
 *
 * The renderer's lifetime is owned here rather than by a Compose effect keyed on renderer state: that effect
 * released the renderer straight after creating it, which left every video tile black while frames still decoded.
 * Plain Kotlin so the ordering is unit-tested (`VideoSinkBindingTest`).
 */
internal class VideoSinkBinding<T : Any, R : Any>(
    private val addSink: (T, R) -> Unit,
    private val removeSink: (T, R) -> Unit,
    private val releaseRenderer: (R) -> Unit,
) {
    private var renderer: R? = null
    private var track: T? = null
    private var started = false
    private var attached: T? = null

    var isReleased = false
        private set

    fun setRenderer(r: R) {
        if (isReleased || renderer === r) return
        check(renderer == null) { "a binding owns one renderer" }
        renderer = r
        sync()
    }

    /** The track the tile shows now, or null to show none. Never releases the renderer. */
    fun bind(track: T?) {
        this.track = track
        sync()
    }

    fun setStarted(started: Boolean) {
        this.started = started
        sync()
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        sync()
        renderer?.let(releaseRenderer)
        renderer = null
    }

    private fun sync() {
        val r = renderer ?: return
        val want = track?.takeIf { started && !isReleased }
        val current = attached
        if (current === want) return
        if (current != null) {
            attached = null
            removeSink(current, r)
        }
        if (want != null) {
            addSink(want, r)
            attached = want
        }
    }
}
