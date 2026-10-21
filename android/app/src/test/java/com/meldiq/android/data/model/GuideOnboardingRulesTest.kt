package com.meldiq.android.data.model

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.GuideDto
import com.meldiq.android.data.api.GuideItemDto
import com.meldiq.android.data.api.MeetingReq
import com.meldiq.android.data.api.OnboardingOptionDto
import com.meldiq.android.data.api.OnboardingPromptDto
import com.meldiq.android.data.api.OrgDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.DayOfWeek

/** Batch B8b: the community guide's progress, answering onboarding questions, and repeating events. */
class GuideOnboardingRulesTest {
    private val guide = GuideDto("Hi", tasks = listOf(GuideItemDto("t1", "Say hi", "c1"), GuideItemDto("t2", "Read rules", "c2")))

    @Test
    fun `opening a task's channel ticks it once, other channels change nothing`() {
        val once = GuideRules.visit(emptyMap(), "o1", guide, "c1")
        assertEquals(listOf("t1"), once["o1"])
        assertSame(once, GuideRules.visit(once, "o1", guide, "c1"))
        assertSame(once, GuideRules.visit(once, "o1", guide, "c9"))
        assertEquals(1 to 2, GuideRules.progress(guide, once["o1"].orEmpty()))
    }

    private val single = OnboardingPromptDto("q1", "Why?", single = true, required = true, options = listOf(OnboardingOptionDto("a", "Art"), OnboardingOptionDto("b", "Books")))
    private val multi = OnboardingPromptDto("q2", "Likes?", options = listOf(OnboardingOptionDto("c", "Cats"), OnboardingOptionDto("d", "Dogs")))

    @Test
    fun `a single-answer question replaces its choice, others toggle`() {
        assertEquals(setOf("b"), OnboardingRules.toggle(setOf("a"), single, "b"))
        assertEquals(emptySet<String>(), OnboardingRules.toggle(setOf("a"), single, "a"))
        assertEquals(setOf("c", "d"), OnboardingRules.toggle(setOf("c"), multi, "d"))
    }

    @Test
    fun `required questions need an answer before sending`() {
        assertEquals("q1", OnboardingRules.missing(listOf(single, multi), setOf("c"))?.id)
        assertNull(OnboardingRules.missing(listOf(single, multi), setOf("a")))
    }

    @Test
    fun `a repeating event names its weekday and which one of the month`() {
        // Wednesday 15 October 2025, 09:00 UTC: the third Wednesday.
        assertEquals(DayOfWeek.WEDNESDAY to 3, EventRepeatRules.weekdayAndNth(1_760_518_800_000, "UTC"))
        assertNull(EventRepeatRules.weekdayAndNth(0, "Not/AZone"))
        assertEquals(listOf("none", "weekly", "biweekly", "monthly", "yearly"), EventRepeatRules.CHOICES)
    }

    @Test
    fun `the new fields read from the server, and older answers still parse`() {
        val o = GriipJson.decodeFromString(OrgDto.serializer(), """{"id":"o1","name":"C","onboarding":true,"guide":{"welcome":"Hi","tasks":[{"id":"t1","title":"x","channelId":"c1"}]}}""")
        assertEquals("t1", o.guide?.tasks?.single()?.id)
        val old = GriipJson.decodeFromString(OrgDto.serializer(), """{"id":"o1","name":"C"}""")
        assertNull(old.guide)
        assertFalse(old.onboarding)
        assertFalse(GriipJson.encodeToString(MeetingReq.serializer(), MeetingReq("r", "t", null, 1, 2, "UTC")).contains("repeat"))
    }
}
