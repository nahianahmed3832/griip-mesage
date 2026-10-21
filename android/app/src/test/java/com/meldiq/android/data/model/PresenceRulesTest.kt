package com.meldiq.android.data.model

import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.PresenceReq
import com.meldiq.android.data.api.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch B6b: presence choices and a group's photo in the API. */
class PresenceRulesTest {
    @Test
    fun `only idle and do not disturb are kept with a person, online and offline come from the online flag`() {
        assertEquals("idle", PresenceRules.stored("idle"))
        assertEquals("dnd", PresenceRules.stored("dnd"))
        assertNull(PresenceRules.stored("online"))
        assertNull(PresenceRules.stored("offline"))
        assertNull(PresenceRules.stored(null))
    }

    @Test
    fun `a timed choice ends by itself, and do not disturb pauses notifications for as long, or the longest allowed`() {
        assertEquals("auto", PresenceRules.current("idle", until = 1000, now = 2000))
        assertEquals("idle", PresenceRules.current("idle", until = 3000, now = 2000))
        assertEquals("invisible", PresenceRules.current("invisible", until = null, now = 2000))
        assertEquals("auto", PresenceRules.current(null, null, 0))
        assertEquals(5000L, PresenceRules.pauseUntil(5000, now = 0))
        assertEquals(PresenceRules.PAUSE_MAX_MS, PresenceRules.pauseUntil(null, now = 0))
        assertTrue(PresenceRules.DURATIONS.filterNotNull().all { it <= PresenceRules.PAUSE_MAX_MS })
        assertEquals(listOf("auto", "idle", "dnd", "invisible"), PresenceRules.MODES)
    }

    @Test
    fun `the API carries presence and the group photo, and older servers' answers still read`() {
        val u = GriipJson.decodeFromString(UserDto.serializer(), """{"id":"u1","username":"a","displayName":"A","online":true,"presence":"dnd"}""")
        assertEquals("dnd", u.presence)
        assertNull(GriipJson.decodeFromString(UserDto.serializer(), """{"id":"u1","username":"a","displayName":"A"}""").presence)
        assertEquals(7L, GriipJson.decodeFromString(ConversationDto.serializer(), """{"id":"g1","type":"GROUP","photoRev":7}""").photoRev)
        assertEquals("""{"mode":"auto"}""", GriipJson.encodeToString(PresenceReq.serializer(), PresenceReq("auto")))
    }
}
