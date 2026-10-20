package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── AI (servers with `ai`, docs/AI.md, round 7 slice C8) ─────────

@Serializable data class AiProviderDto(val id: String, val name: String, val model: String)
@Serializable data class AiMonthlyDto(val used: Int = 0, val limit: Int? = null)

/** [reason]: null when AI can be used; else not_configured, workspace_off or guest. */
@Serializable data class AiStatusDto(
    val available: Boolean = false,
    val reason: String? = null,
    val enabled: Boolean = false,
    val canManage: Boolean = false,
    val provider: AiProviderDto? = null,
    val providers: List<AiProviderDto> = emptyList(),
    val chosenProvider: String? = null,
    val monthly: AiMonthlyDto = AiMonthlyDto(),
)
@Serializable data class AiStatusRes(val ai: AiStatusDto)
/** [provider] null: the server's default. */
@Serializable data class AiPolicyReq(val enabled: Boolean, val provider: String?)

@Serializable data class AiAnswerDto(val text: String, val provider: AiProviderDto)
@Serializable data class AiAnswerRes(val result: AiAnswerDto)

@Serializable data class AiConsentReq(val consent: Boolean)
@Serializable data class AiWriteReq(val consent: Boolean, val mode: String, val text: String)
@Serializable data class AiCatchUpMessage(val author: String, val text: String, val at: Long? = null)
@Serializable data class AiCatchUpReq(val consent: Boolean, val channelId: String, val messages: List<AiCatchUpMessage>)

@Serializable data class AiAskReq(val consent: Boolean, val question: String)
@Serializable data class AiSourceDto(val n: Int, val id: String, val title: String)
@Serializable data class AiAskDto(val text: String? = null, val provider: AiProviderDto? = null, val sources: List<AiSourceDto> = emptyList(), val found: Boolean = false)
@Serializable data class AiAskRes(val result: AiAskDto)

@Serializable data class AiDraftReq(val consent: Boolean, val description: String, val timeZone: String)
/** [draft] is never saved by the server; it opens in the workflow editor, switched off. */
@Serializable data class AiDraftDto(val draft: WfInput? = null, val problems: List<String> = emptyList(), val provider: AiProviderDto)
@Serializable data class AiDraftRes(val result: AiDraftDto)
