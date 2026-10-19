package com.meldiq.android.ui.screens.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSinkBindingTest {
    private val events = mutableListOf<String>()
    private val binding = VideoSinkBinding<String, String>(
        addSink = { t, r -> events += "add $t->$r" },
        removeSink = { t, r -> events += "remove $t->$r" },
        releaseRenderer = { r -> events += "release $r" },
    )

    @Test
    fun attachesOnlyOnceRendererTrackAndStartedAreAllPresent() {
        binding.bind("camera")
        binding.setStarted(true)
        assertEquals(emptyList<String>(), events)
        binding.setRenderer("view")
        assertEquals(listOf("add camera->view"), events)
    }

    @Test
    fun rendererStaysAliveWhileTheTileIsShown() {
        // Regression: the tile released its renderer right after creating it, so decoded frames were never drawn.
        binding.setRenderer("view")
        binding.setStarted(true)
        binding.bind("camera")
        binding.setRenderer("view")
        binding.bind("camera")
        binding.setStarted(true)
        assertEquals(listOf("add camera->view"), events)
        assertTrue(!binding.isReleased)
    }

    @Test
    fun switchingToScreenShareRemovesTheCameraSinkFirst() {
        binding.setRenderer("view")
        binding.setStarted(true)
        binding.bind("camera")
        binding.bind("screen")
        binding.bind("camera")
        assertEquals(
            listOf("add camera->view", "remove camera->view", "add screen->view", "remove screen->view", "add camera->view"),
            events,
        )
    }

    @Test
    fun stoppedScreenDetachesAndRestartReattaches() {
        binding.setRenderer("view")
        binding.bind("camera")
        binding.setStarted(true)
        binding.setStarted(false) // e.g. app backgrounded while sharing
        binding.setStarted(true)
        assertEquals(listOf("add camera->view", "remove camera->view", "add camera->view"), events)
    }

    @Test
    fun unbindingDetachesWithoutReleasingTheRenderer() {
        binding.setRenderer("view")
        binding.setStarted(true)
        binding.bind("camera")
        binding.bind(null) // remote stopped publishing
        assertEquals(listOf("add camera->view", "remove camera->view"), events)
        assertTrue(!binding.isReleased)
    }

    @Test
    fun releaseDetachesBeforeReleasingAndOnlyOnce() {
        binding.setRenderer("view")
        binding.setStarted(true)
        binding.bind("screen")
        binding.release()
        binding.release()
        assertEquals(listOf("add screen->view", "remove screen->view", "release view"), events)
        assertTrue(binding.isReleased)
    }

    @Test
    fun nothingAttachesAfterRelease() {
        binding.setRenderer("view")
        binding.release()
        binding.setStarted(true)
        binding.bind("camera")
        binding.setRenderer("view")
        assertEquals(listOf("release view"), events)
    }

    @Test
    fun releaseWithoutRendererIsANoOp() {
        binding.bind("camera")
        binding.setStarted(true)
        binding.release()
        assertEquals(emptyList<String>(), events)
    }
}
