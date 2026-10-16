package com.griipbuddy.orbit.ui.screens.calls

import com.griipbuddy.orbit.call.CallPhase
import com.griipbuddy.orbit.ui.screens.calls.CallTiles.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Group-call participant tiles (2026-10-17). */
class CallTilesTest {
    @Test
    fun groupCallsAlwaysShowTilesEvenWithOnePersonOrNoCameras() {
        // The defect: a group call with one other person fell back to the caller screen.
        assertEquals(Mode.Grid, CallTiles.mode(isGroup = true, phase = CallPhase.Connected, remoteCount = 1, anyRemoteVideo = false, anyRemoteScreen = false))
        assertEquals(Mode.Grid, CallTiles.mode(true, CallPhase.Connected, 3, anyRemoteVideo = false, anyRemoteScreen = false)) // all audio-only
        assertEquals(Mode.Grid, CallTiles.mode(true, CallPhase.Connected, 3, anyRemoteVideo = true, anyRemoteScreen = false))
        assertEquals(Mode.Grid, CallTiles.mode(true, CallPhase.Reconnecting, 3, true, false)) // tiles stay while reconnecting
        assertEquals(Mode.Hero, CallTiles.mode(true, CallPhase.Connected, 0, false, false)) // nobody else yet
        assertEquals(Mode.Hero, CallTiles.mode(true, CallPhase.Connecting, 2, true, false))
    }

    @Test
    fun aScreenShareInAGroupKeepsEveryoneVisible() {
        // The defect: the screen replaced every tile.
        assertEquals(Mode.ScreenWithStrip, CallTiles.mode(true, CallPhase.Connected, 3, true, anyRemoteScreen = true))
        assertEquals(Mode.OneToOne, CallTiles.mode(false, CallPhase.Connected, 1, true, anyRemoteScreen = true)) // 1:1 unchanged
    }

    @Test
    fun oneToOneCallsAreUnchanged() {
        assertEquals(Mode.OneToOne, CallTiles.mode(false, CallPhase.Connected, 1, anyRemoteVideo = true, anyRemoteScreen = false))
        assertEquals(Mode.Hero, CallTiles.mode(false, CallPhase.Connected, 1, anyRemoteVideo = false, anyRemoteScreen = false)) // voice call
    }

    private data class P(val id: String, val local: Boolean = false, val tag: String = "")

    @Test
    fun tilesAreOnePerIdentityInArrivalOrderWithYouLast() {
        val seen = mapOf("c" to 0L, "a" to 1L, "b" to 2L)
        val ordered = CallTiles.order(
            listOf(P("me", local = true), P("b"), P("a"), P("c"), P("a", tag = "dup"), P("me", tag = "leftover"), P("")),
            identity = { it.id }, isLocal = { it.local }, firstSeen = { seen[it] ?: Long.MAX_VALUE },
        )
        assertEquals(listOf("c", "a", "b", "me"), ordered.map { it.id })
        assertEquals("", ordered.first { it.id == "a" }.tag) // the first entry for an identity wins; the duplicate is dropped
        assertTrue(ordered.single { it.id == "me" }.local) // your leftover remote connection never shows as a second "you"
        // Someone joining doesn't move anyone already there; someone leaving closes the gap.
        val later = CallTiles.order(listOf(P("me", true), P("d"), P("a"), P("b"), P("c")), { it.id }, { it.local }) { (seen + ("d" to 3L))[it] ?: Long.MAX_VALUE }
        assertEquals(listOf("c", "a", "b", "d", "me"), later.map { it.id })
        assertEquals(listOf("c", "b", "me"), CallTiles.order(listOf(P("me", true), P("b"), P("c")), { it.id }, { it.local }) { seen[it] ?: 9 }.map { it.id })
    }

    @Test
    fun theGridFitsEveryoneInPortraitAndLandscape() {
        fun g(n: Int, land: Boolean) = CallTiles.grid(n, land).let { it.columns to it.rows }
        assertEquals(1 to 1, g(1, false))
        assertEquals(1 to 2, g(2, false)); assertEquals(2 to 1, g(2, true))
        assertEquals(2 to 2, g(3, false)); assertEquals(2 to 2, g(4, true))
        assertEquals(2 to 3, g(6, false)); assertEquals(3 to 2, g(6, true))
        assertEquals(3 to 3, g(9, false))
        assertEquals(3 to 4, g(12, false)); assertEquals(4 to 3, g(12, true))
        for (n in 1..12) for (land in listOf(false, true)) {
            val (cols, rows) = g(n, land)
            assertTrue("$n people fit", cols * rows >= n && cols * (rows - 1) < n) // no empty row, nobody off-screen
        }
    }
}
