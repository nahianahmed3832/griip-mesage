package com.meldiq.android.ui.screens.calls

import com.meldiq.android.call.CallPhase

/**
 * Which participants a call shows and how (2026-10-17 group-call fix; unit-tested in `CallTilesTest`).
 *
 * What was wrong: a group call with one other person never showed tiles (it fell back to the caller screen); a screen
 * share in a group hid every participant; and the grid was a two-column lazy grid with a fixed tile shape, so in
 * landscape (or with more people) tiles fell off the screen.
 */
object CallTiles {
    enum class Mode {
        /** Ringing, connecting, or nobody else there yet: the caller/callee screen. */
        Hero,
        /** A one-to-one call with video: the other person full screen, your camera floating. */
        OneToOne,
        /** Someone shares their screen: the screen large, everyone in a strip. */
        ScreenWithStrip,
        /** A group call: one tile per person, video or not. */
        Grid,
    }

    fun mode(isGroup: Boolean, phase: CallPhase, remoteCount: Int, anyRemoteVideo: Boolean, anyRemoteScreen: Boolean): Mode = when {
        phase != CallPhase.Connected && phase != CallPhase.Reconnecting -> Mode.Hero
        remoteCount == 0 -> Mode.Hero
        anyRemoteScreen && isGroup -> Mode.ScreenWithStrip
        anyRemoteScreen -> Mode.OneToOne // one-to-one screen share: the screen full size, as before
        isGroup -> Mode.Grid
        anyRemoteVideo -> Mode.OneToOne
        else -> Mode.Hero
    }

    /**
     * One entry per identity, in a stable order: other people in the order they were first seen, then you. A remote
     * entry with your own identity (a leftover connection of this device) is dropped, so you never appear twice.
     */
    fun <T> order(entries: List<T>, identity: (T) -> String, isLocal: (T) -> Boolean, firstSeen: (String) -> Long): List<T> {
        val local = entries.firstOrNull(isLocal)
        val localId = local?.let(identity)
        val remotes = entries.filter { !isLocal(it) && identity(it).isNotEmpty() && identity(it) != localId }
            .distinctBy(identity)
            .sortedWith(compareBy<T> { firstSeen(identity(it)) }.thenBy { identity(it) })
        return remotes + listOfNotNull(local)
    }

    data class Grid(val columns: Int, val rows: Int)

    /** Columns and rows for [count] tiles: everyone visible at once up to 12 (the media server's limit). */
    fun grid(count: Int, landscape: Boolean): Grid {
        val n = count.coerceAtLeast(1)
        val cols = when {
            n == 1 -> 1
            n == 2 -> if (landscape) 2 else 1
            n <= 4 -> 2
            n <= 6 -> if (landscape) 3 else 2
            n <= 9 -> 3
            else -> if (landscape) 4 else 3
        }
        return Grid(cols, (n + cols - 1) / cols)
    }
}
