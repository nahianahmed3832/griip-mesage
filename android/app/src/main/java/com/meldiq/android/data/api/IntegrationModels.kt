package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── Apps and integrations (servers with `integrations`, docs/INTEGRATIONS.md) ─────────

/** What an app can do: only capabilities the server's code implements are ever listed. */
@Serializable data class CapabilityDto(
    val key: String,
    val label: String,
    val description: String = "",
    /** read, write or notify */
    val kind: String = "read",
    /** member (your own account) or workspace (a shared connection) */
    val accountType: String = "member",
    val scopes: List<String> = emptyList(),
    val default: Boolean = false,
    val granted: Boolean = false,
    val requested: Boolean = false,
)

@Serializable data class ConnectionDto(
    val id: String,
    val ownerType: String = "member",
    val status: String = "active",
    /** The provider account (an email, a GitHub login): shown to its owner and integration admins only. */
    val label: String? = null,
    val scopes: List<String> = emptyList(),
    val connectedBy: String = "",
    val connectedByName: String? = null,
    val connectedAt: Long = 0,
    val lastValidatedAt: Long? = null,
    val lastError: String? = null,
)

@Serializable data class AppSummaryDto(
    val id: String,
    val name: String,
    val publisher: String = "",
    val category: String = "automation",
    val icon: String = "apps",
    val summary: String = "",
    val featured: Boolean = false,
    /** available, coming_soon or sandbox */
    val availability: String = "available",
    val sensitivity: String = "standard",
    /** coming_soon, needs_configuration, blocked, available, approval_required, disabled, installed, connected, error */
    val state: String = "available",
    val actions: List<String> = emptyList(),
    val capabilities: List<CapabilityDto> = emptyList(),
    val installState: String? = null,
    val myAccount: ConnectionDto? = null,
)

@Serializable data class AppPolicyDto(
    val blockUnlisted: Boolean = false, val allowedConnectors: List<String> = emptyList(),
    val requestsEnabled: Boolean = true, val memberAccounts: Boolean = true,
)

@Serializable data class AppPermissionsDto(
    val canInstall: Boolean = false, val canRequest: Boolean = false, val canConfigure: Boolean = false, val canConnect: Boolean = false,
    val canConnectWorkspace: Boolean = false, val canUninstall: Boolean = false, val canViewActivity: Boolean = false, val canManagePolicy: Boolean = false,
)

@Serializable data class DirectoryRes(val apps: List<AppSummaryDto> = emptyList(), val policy: AppPolicyDto = AppPolicyDto(), val permissions: AppPermissionsDto = AppPermissionsDto())

@Serializable data class AppAccess(val mode: String = "everyone", val roleIds: List<String> = emptyList())
@Serializable data class InstallConfigDto(val access: AppAccess = AppAccess())

@Serializable data class InstallDto(
    val id: String,
    val state: String,
    val version: Int = 1,
    val rev: Int = 1,
    val requestedCapabilities: List<String> = emptyList(),
    val grantedCapabilities: List<String> = emptyList(),
    val requestedByName: String? = null,
    val requestNote: String? = null,
    val installedByName: String? = null,
    val decidedByName: String? = null,
    val decidedAt: Long? = null,
    val config: InstallConfigDto = InstallConfigDto(),
    val lastEventAt: Long? = null,
    val lastError: String? = null,
)

@Serializable data class EventTypeDto(val key: String, val label: String)
@Serializable data class AppActionDto(val key: String, val label: String, val capability: String)
@Serializable data class AppSupports(val search: Boolean = false, val browse: Boolean = false, val details: Boolean = false, val unfurl: Boolean = false, val workspaceLink: Boolean = false)

@Serializable data class AppInfoDto(
    val id: String,
    val name: String,
    val publisher: String = "",
    val category: String = "automation",
    val icon: String = "apps",
    val summary: String = "",
    val description: String = "",
    val availability: String = "available",
    val sensitivity: String = "standard",
    val version: Int = 1,
    val dataAccess: List<String> = emptyList(),
    val reviewNote: String? = null,
    val docsUrl: String? = null,
    val setup: List<String> = emptyList(),
    val capabilities: List<CapabilityDto> = emptyList(),
    val eventTypes: List<EventTypeDto> = emptyList(),
    val actions: List<AppActionDto> = emptyList(),
    val supports: AppSupports = AppSupports(),
)

@Serializable data class AppDetailRes(
    val app: AppInfoDto,
    val state: String,
    val actions: List<String> = emptyList(),
    val install: InstallDto? = null,
    val myAccount: ConnectionDto? = null,
    val workspaceConnections: List<ConnectionDto> = emptyList(),
    val memberConnections: List<ConnectionDto> = emptyList(),
    val memberConnectionCount: Int = 0,
)

@Serializable data class InstallReq(val capabilities: List<String>? = null)
@Serializable data class RequestAppReq(val note: String? = null, val capabilities: List<String>? = null)
@Serializable data class ConfigureAppReq(val baseRev: Int, val grantedCapabilities: List<String>? = null, val access: AppAccess? = null, val enabled: Boolean? = null)

@Serializable data class FlowCapability(val key: String, val label: String, val description: String = "", val kind: String = "read")
@Serializable data class ConnectStartRes(val flowId: String, val beginUrl: String, val expiresAt: Long = 0, val scopes: List<String> = emptyList(), val capabilities: List<FlowCapability> = emptyList())
/** pending, approved (the app still has to finish it), connected, denied, failed or expired; [note] for a narrower grant */
@Serializable data class FlowStatusRes(
    val flowId: String,
    val connectorId: String = "",
    val orgId: String = "",
    val status: String = "pending",
    val error: String? = null,
    val note: String? = null,
    val accountId: String? = null,
)
@Serializable data class FinishFlowReq(val code: String)

/** An external item, normalised across providers: metadata only, never a file's content. */
@Serializable data class ResourceDto(
    val id: String,
    val kind: String = "other",
    val title: String,
    val url: String? = null,
    val mimeType: String? = null,
    val modifiedAt: Long? = null,
    val owner: String? = null,
    val size: Long? = null,
    val container: String? = null,
    val detail: String? = null,
    val status: String? = null,
    val startAt: Long? = null,
    val endAt: Long? = null,
    val browsable: Boolean = false,
)
@Serializable data class ResourcePage(val items: List<ResourceDto> = emptyList(), val cursor: String? = null)
@Serializable data class ResourceRes(val resource: ResourceDto)
@Serializable data class DetailRow(val label: String, val value: String)
@Serializable data class DetailSection(val title: String, val rows: List<DetailRow> = emptyList())
@Serializable data class ResourceDetailsRes(val resource: ResourceDto, val sections: List<DetailSection> = emptyList())
@Serializable data class UnfurlReq(val url: String)
@Serializable data class UnfurlRes(val connectorId: String? = null, val resource: ResourceDto? = null, val reason: String? = null)
@Serializable data class ActionReq(val resourceId: String? = null, val fields: Map<String, String> = emptyMap())

@Serializable data class LinkTargetDto(val id: String, val label: String)
@Serializable data class LinkTargetsRes(val targets: List<LinkTargetDto> = emptyList(), val linkedTo: LinkTargetDto? = null, val installUrl: String? = null)
@Serializable data class LinkReq(val targetId: String)
@Serializable data class WatchablePage(val items: List<LinkTargetDto> = emptyList(), val cursor: String? = null)

@Serializable data class SubscriptionDto(
    val id: String, val connectorId: String = "", val conversationId: String, val channelName: String? = null, val resource: String,
    val events: List<String> = emptyList(), val active: Boolean = true, val createdAt: Long = 0, val lastDeliveryAt: Long? = null, val lastError: String? = null,
)
@Serializable data class SubscriptionsRes(val subscriptions: List<SubscriptionDto> = emptyList())
@Serializable data class SubscriptionRes(val subscription: SubscriptionDto)
@Serializable data class CreateSubscriptionReq(val conversationId: String, val resource: String, val events: List<String>)
@Serializable data class TestEventReq(val resource: String? = null)

/** A notification an app posted into a channel. Not end-to-end encrypted: the server received it from the provider. */
@Serializable data class ChannelAppEventDto(
    val id: String, val connectorId: String, val appName: String = "", val icon: String = "apps", val kind: String = "",
    val title: String, val url: String? = null, val summary: String? = null, val actor: String? = null, val occurredAt: Long = 0, val createdAt: Long = 0,
)
@Serializable data class ChannelAppEventsRes(val events: List<ChannelAppEventDto> = emptyList())

@Serializable data class AppAuditDto(val id: Long, val at: Long, val action: String, val actorName: String? = null, val target: String? = null, val detail: String? = null)
@Serializable data class AppNotificationDto(val id: String, val connectorId: String, val conversationId: String, val channelName: String? = null, val title: String, val createdAt: Long = 0)
@Serializable data class AppHealthDto(val connectorId: String, val state: String, val lastEventAt: Long? = null, val lastError: String? = null, val lastErrorAt: Long? = null)
@Serializable data class AppActivityRes(val audit: List<AppAuditDto> = emptyList(), val notifications: List<AppNotificationDto> = emptyList(), val health: List<AppHealthDto> = emptyList())

@Serializable data class PolicyRes(val policy: AppPolicyDto = AppPolicyDto(), val rev: Int = 0)
@Serializable data class PutPolicyReq(val baseRev: Int, val policy: AppPolicyDto)

/** One "when this happens, post there": an app's events into a channel, or an incoming webhook. */
@Serializable data class WorkflowDto(
    val id: String, val kind: String, val connectorId: String = "", val appName: String = "", val trigger: String = "", val action: String = "post_to_channel",
    val conversationId: String = "", val channelName: String? = null, val ownerName: String? = null, val active: Boolean = true,
    val lastRunAt: Long? = null, val lastError: String? = null, val createdAt: Long = 0,
)
@Serializable data class WorkflowsRes(val workflows: List<WorkflowDto> = emptyList())

/** An incoming webhook: its URL is shareable with the tool; the secret is only ever in [HookCreatedRes]. */
@Serializable data class HookDto(
    val id: String, val name: String, val conversationId: String, val channelName: String? = null, val url: String,
    val active: Boolean = true, val createdAt: Long = 0, val lastDeliveryAt: Long? = null, val lastError: String? = null,
)
@Serializable data class HooksRes(val hooks: List<HookDto> = emptyList())
@Serializable data class CreateHookReq(val conversationId: String, val name: String)
@Serializable data class HookCreatedRes(val hook: HookDto, val secret: String)

@Serializable data class SearchAllGroup(val connectorId: String, val appName: String = "", val icon: String = "apps", val items: List<ResourceDto> = emptyList(), val error: String? = null)
@Serializable data class SearchAllRes(val results: List<SearchAllGroup> = emptyList())

/** What this workspace can use (servers with `integrations`); no plan exists yet, and no upgrade is offered. */
@Serializable data class PlanCapabilityDto(val key: String, val label: String = "", val limit: Int? = null, val used: Int = 0, val available: Boolean = true)
@Serializable data class PlanRes(val plan: String? = null, val source: String = "none", val capabilities: List<PlanCapabilityDto> = emptyList())
