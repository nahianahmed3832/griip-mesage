package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── Workspace workflows (servers with `workflows`, docs/WORKFLOWS.md, round 7 slice C3) ─────────
// Mirrors server/src/workflows/model.ts. Labels and descriptions of triggers and actions come from the server's catalog.

@Serializable data class WfSchedule(val recurrence: String, val localTime: String, val anchorDate: String, val timeZone: String)
@Serializable data class WfTrigger(val type: String, val schedule: WfSchedule? = null)
@Serializable data class WfCondition(val field: String, val op: String, val value: String)

/** One action; which fields are set depends on [type] (post_channel, add_to_channels, email_admins). */
@Serializable data class WfAction(
    val type: String,
    val channelId: String? = null,
    val channelIds: List<String>? = null,
    val subject: String? = null,
    val text: String? = null,
)

@Serializable data class WfInput(
    val name: String,
    val trigger: WfTrigger,
    val conditions: List<WfCondition> = emptyList(),
    val actions: List<WfAction>,
    val enabled: Boolean = true,
    val rev: Int? = null,
)

@Serializable data class WfOwner(val id: String, val name: String? = null, val allowed: Boolean = true)
@Serializable data class WfLastRun(val state: String, val at: Long, val finishedAt: Long? = null, val errorKind: String? = null)

@Serializable data class WorkspaceWorkflowDto(
    val id: String,
    val name: String,
    val trigger: WfTrigger,
    val conditions: List<WfCondition> = emptyList(),
    val actions: List<WfAction> = emptyList(),
    val enabled: Boolean = true,
    val owner: WfOwner,
    val nextRunAt: Long? = null,
    val rev: Int,
    val lastRun: WfLastRun? = null,
)

@Serializable data class WorkspaceWorkflowsRes(val workflows: List<WorkspaceWorkflowDto>)
@Serializable data class WorkspaceWorkflowRes(val workflow: WorkspaceWorkflowDto)

@Serializable data class WfOutcome(val type: String, val status: String, val detail: String)
@Serializable data class WfTestDto(val conditionsHoldForSample: Boolean, val nextRunAt: Long? = null, val outcomes: List<WfOutcome>)
@Serializable data class WfTestRes(val test: WfTestDto)

@Serializable data class WfRunName(val name: String? = null)
@Serializable data class WfRunEvent(val type: String, val member: WfRunName? = null, val channel: WfRunName? = null, val occurrenceAt: Long? = null)
@Serializable data class WfRunDto(
    val id: String, val state: String, val attempts: Int = 0, val createdAt: Long, val finishedAt: Long? = null,
    val errorKind: String? = null, val outcomes: List<WfOutcome> = emptyList(), val event: WfRunEvent? = null,
)
@Serializable data class WfRunsRes(val runs: List<WfRunDto>)

@Serializable data class WfKindDto(val type: String, val label: String, val description: String = "")
@Serializable data class WfConditionFieldDto(val field: String, val triggers: List<String>)
@Serializable data class WfCatalogDto(val triggers: List<WfKindDto>, val actions: List<WfKindDto>, val conditions: List<WfConditionFieldDto>)
@Serializable data class WfTemplateBody(val trigger: WfTrigger, val conditions: List<WfCondition> = emptyList(), val actions: List<WfAction>)
@Serializable data class WfTemplateDto(val id: String, val name: String, val description: String, val needs: List<String> = emptyList(), val workflow: WfTemplateBody)
@Serializable data class WfCatalogRes(val catalog: WfCatalogDto, val templates: List<WfTemplateDto>)
