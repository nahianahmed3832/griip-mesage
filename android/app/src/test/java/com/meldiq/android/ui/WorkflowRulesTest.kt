package com.meldiq.android.ui

import com.meldiq.android.data.api.WfAction
import com.meldiq.android.data.api.WfCatalogDto
import com.meldiq.android.data.api.WfCondition
import com.meldiq.android.data.api.WfConditionFieldDto
import com.meldiq.android.data.api.WfSchedule
import com.meldiq.android.data.api.WfTemplateBody
import com.meldiq.android.data.api.WfTemplateDto
import com.meldiq.android.data.api.WfTrigger
import com.meldiq.android.ui.screens.workspace.workflows.WorkflowRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRulesTest {
    private val catalog = WfCatalogDto(
        triggers = emptyList(), actions = emptyList(),
        conditions = listOf(WfConditionFieldDto("member.role", listOf("member.joined", "member.role_changed")), WfConditionFieldDto("channel.kind", listOf("channel.created"))),
    )

    @Test
    fun `a template gets the picked channel, today and the time zone`() {
        val t = WfTemplateDto(
            "weekly", "Weekly", "", listOf("channel", "schedule"),
            WfTemplateBody(WfTrigger("schedule", WfSchedule("weekly", "09:00", "{today}", "{timeZone}")), actions = listOf(WfAction("post_channel", channelId = "{channel}", text = "Hi"))),
        )
        val w = WorkflowRules.fromTemplate(t, "c_1", "2026-10-20", "Asia/Dhaka")
        assertEquals("c_1", w.actions[0].channelId)
        assertEquals(WfSchedule("weekly", "09:00", "2026-10-20", "Asia/Dhaka"), w.trigger.schedule)
        assertTrue(WorkflowRules.problems(w).isEmpty())
        // No channel picked yet: Save says so.
        val unpicked = WorkflowRules.fromTemplate(t, null, "2026-10-20", "UTC")
        assertNull(unpicked.actions[0].channelId)
        assertEquals(listOf("Action 1: pick a channel"), WorkflowRules.problems(unpicked))
    }

    @Test
    fun `changing the trigger drops what no longer fits`() {
        val w = WorkflowRules.blank().copy(
            name = "x",
            conditions = listOf(WfCondition("member.role", "is", "MEMBER")),
            actions = listOf(WfAction("add_to_channels", channelIds = listOf("c")), WfAction("post_channel", channelId = "c", text = "hi")),
        )
        val changed = WorkflowRules.withTrigger(w, catalog, "channel.created", "2026-10-20", "UTC")
        assertTrue(changed.conditions.isEmpty())
        assertEquals(listOf("post_channel"), changed.actions.map { it.type })
        val scheduled = WorkflowRules.withTrigger(w, catalog, "schedule", "2026-10-20", "UTC")
        assertEquals("09:00", scheduled.trigger.schedule?.localTime)
        assertNull(WorkflowRules.withTrigger(scheduled, catalog, "member.left", "2026-10-20", "UTC").trigger.schedule)
    }

    @Test
    fun `problems are listed in order`() {
        val w = WorkflowRules.blank().copy(trigger = WfTrigger("schedule", WfSchedule("daily", "9am", "2026-10-20", "UTC")))
        assertEquals(
            listOf("Give the workflow a name", "Set the time as HH:MM, for example 09:00", "Action 1: pick a channel", "Action 1: write the message"),
            WorkflowRules.problems(w),
        )
        assertEquals("2 actions · 1 condition · daily at 09:00", WorkflowRules.summary(
            w.copy(trigger = WfTrigger("schedule", WfSchedule("daily", "09:00", "x", "UTC")), conditions = listOf(WfCondition("a", "is", "b")), actions = listOf(WfAction("a"), WfAction("b"))),
        ))
    }

    @Test
    fun `condition fields read naturally, inbox ones included`() {
        assertEquals("member role", WorkflowRules.fieldLabel("member.role"))
        assertEquals("platform", WorkflowRules.fieldLabel("inbox.provider"))
        assertEquals("queue", WorkflowRules.fieldLabel("inbox.queue"))
    }
}
