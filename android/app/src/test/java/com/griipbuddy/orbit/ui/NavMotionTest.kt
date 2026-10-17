package com.griipbuddy.orbit.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMotionTest {
    @Test
    fun tabSwitchesFadeEverythingElseSlides() {
        assertTrue(NavMotionRules.isTabSwitch(Routes.CHATS, Routes.CONTACTS))
        // Workspace is no longer a tab (2026-10-17): it opens from Contacts, so it slides in like any other screen.
        assertFalse(NavMotionRules.isTabSwitch(Routes.CONTACTS, Routes.WORKSPACE))
        assertTrue(NavMotionRules.isTabSwitch(Routes.HOME, Routes.CALLS)) // Home is the Chats tab
        assertFalse(NavMotionRules.isTabSwitch(Routes.CHATS, "conversation/{id}?thread={thread}"))
        assertFalse(NavMotionRules.isTabSwitch("conversation/{id}?thread={thread}", Routes.CHATS)) // Back from a chat slides
        assertFalse(NavMotionRules.isTabSwitch(null, Routes.CHATS))
    }

    @Test
    fun noMotionWhenTheSystemTurnedAnimationsOff() {
        assertFalse(NavMotionRules.animationsEnabled(0f))
        assertTrue(NavMotionRules.animationsEnabled(0.5f))
        assertTrue(NavMotionRules.animationsEnabled(1f))
    }
}
