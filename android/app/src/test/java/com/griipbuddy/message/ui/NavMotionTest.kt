package com.griipbuddy.message.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMotionTest {
    @Test
    fun tabSwitchesFadeEverythingElseSlides() {
        assertTrue(NavMotionRules.isTabSwitch(Routes.CHATS, Routes.WORKSPACE))
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
