package com.meldiq.android.ui.screens.workspace.workflows

import com.meldiq.android.data.api.WfAction
import com.meldiq.android.data.api.WfCatalogDto
import com.meldiq.android.data.api.WfCondition
import com.meldiq.android.data.api.WfInput
import com.meldiq.android.data.api.WfSchedule
import com.meldiq.android.data.api.WfTemplateDto
import com.meldiq.android.data.api.WfTrigger

/**
 * The workflow editor's rules (round 7 slice C3, docs/WORKFLOWS.md); pure, unit-tested in `WorkflowRulesTest`. The
 * server validates everything again: these only keep the Save button honest and fill templates in.
 */
object WorkflowRules {
    const val MAX_ACTIONS = 5
    const val MAX_CONDITIONS = 5
    const val MAX_TEXT = 300
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    val RECURRENCES = listOf("daily", "weekdays", "weekly", "monthly")
    val OPS = listOf("is", "is_not", "contains")
    val ACTION_TYPES = listOf("post_channel", "add_to_channels", "email_admins")

    /** A template with its placeholders filled: the channel the person picked, today's date and their time zone. */
    fun fromTemplate(t: WfTemplateDto, channelId: String?, today: String, timeZone: String): WfInput {
        fun fill(s: String?) = s?.replace("{channel}", channelId.orEmpty())
        val trigger = t.workflow.trigger.let { tr ->
            tr.copy(schedule = tr.schedule?.copy(anchorDate = tr.schedule.anchorDate.replace("{today}", today), timeZone = tr.schedule.timeZone.replace("{timeZone}", timeZone)))
        }
        return WfInput(
            name = t.name,
            trigger = trigger,
            conditions = t.workflow.conditions,
            actions = t.workflow.actions.map { a ->
                a.copy(channelId = fill(a.channelId)?.ifEmpty { null }, channelIds = a.channelIds?.mapNotNull { fill(it)?.ifEmpty { null } })
            },
        )
    }

    /** A fresh workflow: someone joins, post in a channel to be picked. */
    fun blank(): WfInput = WfInput(name = "", trigger = WfTrigger("member.joined"), actions = listOf(WfAction("post_channel", text = "")))

    fun defaultSchedule(today: String, timeZone: String) = WfSchedule("weekly", "09:00", today, timeZone)

    /** Condition fields that apply to [trigger], from the server's catalog. */
    fun fieldsFor(catalog: WfCatalogDto?, trigger: String): List<String> =
        catalog?.conditions?.filter { trigger in it.triggers }?.map { it.field }.orEmpty()

    /** Which actions fit [trigger]: adding to channels needs a member trigger. */
    fun actionsFor(trigger: String): List<String> =
        if (trigger == "member.joined" || trigger == "member.role_changed") ACTION_TYPES else ACTION_TYPES - "add_to_channels"

    /** A blank action of [type]. */
    fun newAction(type: String): WfAction = when (type) {
        "add_to_channels" -> WfAction(type, channelIds = emptyList())
        "email_admins" -> WfAction(type, subject = "", text = "")
        else -> WfAction(type, text = "")
    }

    /**
     * Changing the trigger drops what no longer fits it (conditions on other fields, adding to channels without a member)
     * and sets or clears the schedule.
     */
    fun withTrigger(input: WfInput, catalog: WfCatalogDto?, trigger: String, today: String, timeZone: String): WfInput {
        val fields = fieldsFor(catalog, trigger).toSet()
        val actions = input.actions.filter { it.type in actionsFor(trigger) }
        return input.copy(
            trigger = WfTrigger(trigger, if (trigger == "schedule") input.trigger.schedule ?: defaultSchedule(today, timeZone) else null),
            conditions = input.conditions.filter { it.field in fields },
            actions = actions.ifEmpty { listOf(newAction("post_channel")) },
        )
    }

    /** What still needs filling in before Save, in the order it appears; empty when the server can be asked. */
    fun problems(input: WfInput): List<String> = buildList {
        if (input.name.isBlank()) add("Give the workflow a name")
        input.trigger.schedule?.let { if (!TIME.matches(it.localTime)) add("Set the time as HH:MM, for example 09:00") }
        if (input.trigger.type == "schedule" && input.trigger.schedule == null) add("Set the schedule")
        input.conditions.forEachIndexed { i, c -> if (c.value.isBlank()) add("Condition ${i + 1} needs a value") }
        if (input.actions.isEmpty()) add("Add an action")
        input.actions.forEachIndexed { i, a ->
            val n = i + 1
            when (a.type) {
                "post_channel" -> {
                    if (a.channelId.isNullOrBlank()) add("Action $n: pick a channel")
                    if (a.text.isNullOrBlank()) add("Action $n: write the message")
                }
                "add_to_channels" -> if (a.channelIds.isNullOrEmpty()) add("Action $n: pick at least one channel")
                "email_admins" -> {
                    if (a.subject.isNullOrBlank()) add("Action $n: write a subject")
                    if (a.text.isNullOrBlank()) add("Action $n: write the email")
                }
            }
            if ((a.text?.length ?: 0) > MAX_TEXT) add("Action $n: at most $MAX_TEXT characters")
        }
    }

    /** "is not" for the "is_not" operator. */
    fun opLabel(op: String): String = op.replace('_', ' ')

    /** "member role" for "member.role". */
    fun fieldLabel(field: String): String = when (field) {
        // Round 8 inbox conditions read as what they are.
        "inbox.provider" -> "platform"
        "inbox.queue" -> "queue"
        else -> field.replace('.', ' ').replace('_', ' ')
    }

    /** A short line for the list: "3 actions · runs daily at 09:00". */
    fun summary(input: WfInput): String = buildString {
        append(if (input.actions.size == 1) "1 action" else "${input.actions.size} actions")
        if (input.conditions.isNotEmpty()) append(" · ${input.conditions.size} condition${if (input.conditions.size == 1) "" else "s"}")
        input.trigger.schedule?.let { append(" · ${it.recurrence} at ${it.localTime}") }
    }

    fun condition(field: String): WfCondition = WfCondition(field, "is", "")
}
