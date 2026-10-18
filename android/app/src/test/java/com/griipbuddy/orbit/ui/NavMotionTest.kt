package com.griipbuddy.orbit.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMotionTest {
    @Test
    fun tabSwitchesFadeEverythingElseSlides() {
        // The three tabs (UI-002): Home, DMs (the chats route) and Activity fade through.
        assertTrue(NavMotionRules.isTabSwitch(Routes.HOME, Routes.CHATS))
        assertTrue(NavMotionRules.isTabSwitch(Routes.CHATS, Routes.ACTIVITY))
        assertTrue(NavMotionRules.isTabSwitch(Routes.ACTIVITY, Routes.HOME))
        // Calls, Contacts, Settings and Workspace are no longer tabs: they slide in, with Back.
        assertFalse(NavMotionRules.isTabSwitch(Routes.HOME, Routes.CALLS))
        assertFalse(NavMotionRules.isTabSwitch(Routes.CHATS, Routes.CONTACTS))
        assertFalse(NavMotionRules.isTabSwitch(Routes.HOME, Routes.PROFILE))
        assertFalse(NavMotionRules.isTabSwitch(Routes.HOME, Routes.WORKSPACE))
        assertFalse(NavMotionRules.isTabSwitch(Routes.ACTIVITY, "activity/{orgId}")) // one workspace's log is a screen
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
