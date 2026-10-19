package com.meldiq.android.ui.screens.calls

import com.meldiq.android.call.AudioRoute
import com.meldiq.android.call.AudioRouteKind
import com.meldiq.android.call.CallPhase
import com.meldiq.android.call.CallUiState
import com.meldiq.android.call.MediaFlow
import com.meldiq.android.call.ParticipantUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-003: the same controls in every kind of call; the output control is never dropped. */
class CallControlsRulesTest {
    private fun person(id: String, local: Boolean = false, mic: Boolean = true) =
        ParticipantUi(id, id, id, isLocal = local, speaking = false, micOn = mic, camera = null, screen = null)

    private val me = person("me", local = true)
    private val speaker = AudioRoute(AudioRouteKind.Speaker)
    private val earpiece = AudioRoute(AudioRouteKind.Earpiece)

    @Test
    fun `group calls with others are left, one-to-one calls are ended`() {
        assertEquals("End", CallControlsRules.endLabel(CallUiState(phase = CallPhase.Connected, participants = listOf(person("b"), me))))
        assertEquals("Leave", CallControlsRules.endLabel(CallUiState(phase = CallPhase.Connected, isGroup = true, participants = listOf(person("b"), me))))
        assertEquals("End", CallControlsRules.endLabel(CallUiState(phase = CallPhase.Connected, isGroup = true, participants = listOf(me))))
    }

    @Test
    fun `sharing starts once connected and can always be stopped`() {
        assertTrue(CallControlsRules.canShare(CallUiState(phase = CallPhase.Connected)))
        assertFalse(CallControlsRules.canShare(CallUiState(phase = CallPhase.Outgoing)))
        assertTrue(CallControlsRules.canShare(CallUiState(phase = CallPhase.Reconnecting, screenSharing = true)))
    }

    @Test
    fun `the output button lists outputs when a headset, an error or no list is there`() {
        assertFalse(CallControlsRules.opensPicker(CallUiState(audioRoutes = listOf(speaker, earpiece))))
        assertTrue(CallControlsRules.opensPicker(CallUiState(audioRoutes = listOf(speaker, earpiece, AudioRoute(AudioRouteKind.Bluetooth, "Buds")))))
        assertTrue(CallControlsRules.opensPicker(CallUiState(audioRoutes = emptyList())))
        assertTrue(CallControlsRules.opensPicker(CallUiState(audioRoutes = listOf(speaker, earpiece), routeError = "x")))
    }

    @Test
    fun `missing sound is reported only when someone's microphone is on and nothing arrives`() {
        val base = CallUiState(phase = CallPhase.Connected, participants = listOf(person("b"), me))
        assertTrue(CallControlsRules.noSoundArriving(base.copy(remoteAudio = MediaFlow.Stalled)))
        assertFalse(CallControlsRules.noSoundArriving(base.copy(remoteAudio = MediaFlow.Flowing)))
        assertFalse(CallControlsRules.noSoundArriving(base.copy(remoteAudio = MediaFlow.Stalled, participants = listOf(person("b", mic = false), me))))
        assertFalse(CallControlsRules.noSoundArriving(base.copy(phase = CallPhase.Reconnecting, remoteAudio = MediaFlow.Stalled)))
    }

    @Test
    fun `the minimised panel says what is true now`() {
        val t0 = 1_000_000L
        val live = CallUiState(phase = CallPhase.Connected, connectedAt = t0, media = MediaFlow.Flowing, participants = listOf(person("b"), me))
        assertEquals("1:05", MiniCallText.status(live, t0 + 65_000))
        assertEquals("1:05 · muted", MiniCallText.status(live.copy(micOn = false), t0 + 65_000))
        assertEquals("1:05 · sharing your screen", MiniCallText.status(live.copy(screenSharing = true), t0 + 65_000))
        assertEquals("Reconnecting…", MiniCallText.status(live.copy(phase = CallPhase.Reconnecting), t0))
        assertEquals("Waiting for them to reconnect…", MiniCallText.status(live.copy(peerReconnecting = true), t0))
        assertEquals("Ringing…", MiniCallText.status(CallUiState(phase = CallPhase.Outgoing, alerted = true), t0))
        assertEquals("Calling…", MiniCallText.status(CallUiState(phase = CallPhase.Outgoing), t0))
        val group = live.copy(isGroup = true, participants = listOf(person("b"), person("c"), me))
        assertEquals("1:05 · 3 in call", MiniCallText.status(group, t0 + 65_000))
        assertEquals("Waiting for others…", MiniCallText.status(group.copy(participants = listOf(me)), t0))
        assertEquals("Reconnecting media…", MiniCallText.status(live.copy(media = MediaFlow.Stalled), t0))
    }
}

class PreviewCornerTest {
    @Test
    fun `a dropped preview settles in the nearest corner`() {
        assertEquals(PreviewCorner.TopStart, PreviewCorner.nearest(100f, 200f, 1080f, 2400f))
        assertEquals(PreviewCorner.TopEnd, PreviewCorner.nearest(900f, 200f, 1080f, 2400f))
        assertEquals(PreviewCorner.BottomStart, PreviewCorner.nearest(100f, 2000f, 1080f, 2400f))
        assertEquals(PreviewCorner.BottomEnd, PreviewCorner.nearest(900f, 2000f, 1080f, 2400f))
        assertTrue(PreviewCorner.TopEnd.top && !PreviewCorner.TopEnd.start)
    }
}
