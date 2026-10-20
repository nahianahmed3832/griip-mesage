package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── Unified Inbox (servers with `unified-inbox`, docs/UNIFIED_INBOX.md, round 8) ─────────
// Customer messages from Messenger, Instagram and WhatsApp are NOT end-to-end encrypted: the provider delivers them to
// the server, which keeps them sealed at rest. The screens say so.

@Serializable data class InboxProviderDto(
    val id: String,
    val name: String,
    /** ready | needs_configuration | awaiting_access */
    val state: String,
    val missing: List<String> = emptyList(),
    val eligibility: String = "",
    val outbound: List<String> = emptyList(),
    val templates: Boolean = false,
    val textLimit: Int = 0,
    val freeReplyWindowMs: Long = 0,
    val humanAgentTag: Boolean = false,
    val webhookUrl: String? = null,
)
@Serializable data class InboxProvidersRes(val providers: List<InboxProviderDto>)

@Serializable data class InboxAccountDto(
    val id: String, val provider: String, val providerName: String, val externalId: String, val name: String,
    val number: String? = null, val status: String, val lastError: String? = null, val lastEventAt: Long? = null,
    val tokenExpiresAt: Long? = null, val createdAt: Long = 0,
)
@Serializable data class InboxAccountsRes(val accounts: List<InboxAccountDto>)
@Serializable data class InboxAccountRes(val account: InboxAccountDto)
/** [token]: the business's API token from Meta (never a password). */
@Serializable data class InboxConnectReq(val provider: String, val externalId: String, val token: String, val businessAccountId: String? = null)

@Serializable data class InboxQueueDto(val id: String, val name: String, val memberIds: List<String> = emptyList())
@Serializable data class InboxQueuesRes(val queues: List<InboxQueueDto>)
@Serializable data class InboxQueueRes(val queue: InboxQueueDto? = null)
@Serializable data class InboxQueueReq(val name: String, val memberIds: List<String>)

@Serializable data class InboxSettingsDto(
    val aiMode: String = "off",
    val knowledgeDocumentIds: List<String> = emptyList(),
    val autoIntents: List<String> = emptyList(),
    val autoMinConfidence: Int = 80,
    val autoMaxPerConversationPerHour: Int = 3,
    val autoFallback: String = "",
    val autoPaused: Boolean = false,
    val responseTargetMinutes: Int = 60,
    val retentionDays: Int = 0,
    val defaultQueueId: String? = null,
)
@Serializable data class InboxSettingsRes(val settings: InboxSettingsDto, val canManage: Boolean = false)

@Serializable data class InboxCustomerDto(val id: String, val name: String? = null, val handle: String? = null)
@Serializable data class InboxAccountRefDto(val id: String, val name: String, val status: String)
@Serializable data class InboxConversationDto(
    val id: String,
    val orgId: String = "",
    val provider: String,
    val account: InboxAccountRefDto,
    val customer: InboxCustomerDto,
    val status: String,
    val priority: String = "normal",
    val assigneeId: String? = null,
    val queueId: String? = null,
    val handling: String = "human",
    val escalationReason: String? = null,
    val tags: List<String> = emptyList(),
    val unread: Int = 0,
    val preview: String = "",
    val previewFrom: String? = null,
    val lastMessageAt: Long = 0,
    val lastInboundAt: Long? = null,
    val overdue: Boolean = false,
    val rev: Int = 1,
)
@Serializable data class InboxCountsDto(val open: Int = 0, val unassigned: Int = 0, val mine: Int = 0, val escalated: Int = 0)
@Serializable data class InboxListRes(val conversations: List<InboxConversationDto>, val next: Long? = null, val counts: InboxCountsDto = InboxCountsDto())

@Serializable data class InboxAttachmentDto(val n: Int, val type: String, val mime: String? = null)
@Serializable data class InboxMessageDto(
    val id: String,
    /** in | out | note (a note is internal: never sent to the customer) */
    val direction: String,
    val kind: String = "text",
    val text: String? = null,
    val attachments: List<InboxAttachmentDto> = emptyList(),
    val authorId: String? = null,
    /** human | ai_auto | ai_draft */
    val origin: String = "human",
    val status: String = "received",
    val errorKind: String? = null,
    val at: Long = 0,
)
@Serializable data class InboxMessageRes(val message: InboxMessageDto)
/** [allowed] false: [reason] is window_closed, template_required, no_customer_message or unsupported. */
@Serializable data class InboxReplyRuleDto(val allowed: Boolean, val mode: String? = null, val reason: String? = null)
@Serializable data class InboxReplyDto(val rule: InboxReplyRuleDto, val canReply: Boolean = false, val templates: Boolean = false, val textLimit: Int = 0, val windowEndsAt: Long? = null)
@Serializable data class InboxDetailRes(val conversation: InboxConversationDto, val messages: List<InboxMessageDto>, val reply: InboxReplyDto)
@Serializable data class InboxConversationRes(val conversation: InboxConversationDto)

@Serializable data class InboxUpdateReq(
    val rev: Int,
    val assigneeId: String? = null,
    val queueId: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val tags: List<String>? = null,
    val handling: String? = null,
)
@Serializable data class InboxNoteReq(val text: String)
@Serializable data class InboxTextReplyReq(val kind: String = "text", val text: String, val fromAiDraft: Boolean = false)
@Serializable data class InboxTemplateReplyReq(val kind: String = "template", val name: String, val language: String)
@Serializable data class InboxTemplateDto(val name: String, val language: String, val category: String? = null)
@Serializable data class InboxTemplatesRes(val templates: List<InboxTemplateDto>)

@Serializable data class InboxDraftDto(val text: String, val provider: AiProviderDto)
@Serializable data class InboxDraftRes(val result: InboxDraftDto)

@Serializable data class InboxReportMessagesDto(val received: Int = 0, val sentByPeople: Int = 0, val sentAutomatically: Int = 0, val sentFromAiDrafts: Int = 0, val failed: Int = 0)
@Serializable data class InboxReportConversationsDto(val started: Int = 0, val resolved: Int = 0, val open: Int = 0, val unassigned: Int = 0)
@Serializable data class InboxReportTimesDto(val medianFirstResponseMs: Long? = null, val medianResolutionMs: Long? = null)
@Serializable data class InboxReportDto(
    val days: Int,
    val conversations: InboxReportConversationsDto = InboxReportConversationsDto(),
    val messages: InboxReportMessagesDto = InboxReportMessagesDto(),
    val times: InboxReportTimesDto = InboxReportTimesDto(),
    val escalations: Int = 0,
)
@Serializable data class InboxReportRes(val report: InboxReportDto)

@Serializable data class InboxAgentDto(val id: String, val name: String, val canReply: Boolean = false)
@Serializable data class InboxAgentsRes(val agents: List<InboxAgentDto>)
