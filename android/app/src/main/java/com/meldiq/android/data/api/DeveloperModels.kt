package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── Developer platform (servers with `developer-api`, docs/DEVELOPER_API.md, round 7 slice C6) ─────────

@Serializable data class DevCatalogRes(val scopes: List<String>, val events: List<String>)

@Serializable data class DevPerson(val id: String, val name: String? = null)

@Serializable data class ApiKeyDto(
    val id: String, val name: String, val hint: String, val scopes: List<String>, val createdBy: DevPerson? = null,
    val createdAt: Long, val expiresAt: Long, val lastUsedAt: Long? = null, val revoked: Boolean = false,
    val expired: Boolean = false, val ownerAllowed: Boolean = true,
)
@Serializable data class ApiKeysRes(val keys: List<ApiKeyDto>)
@Serializable data class ApiKeyReq(val name: String, val scopes: List<String>, val expiresInDays: Int)
/** The token is in this answer only; the server keeps a hash. */
@Serializable data class ApiKeyCreatedRes(val key: ApiKeyDto, val token: String)

@Serializable data class WebhookStatsDto(val delivered: Int = 0, val failed: Int = 0, val waiting: Int = 0)
@Serializable data class WebhookDto(
    val id: String, val name: String, val url: String, val events: List<String>, val enabled: Boolean = true,
    val disabledReason: String? = null, val verified: Boolean = false, val consecutiveFailures: Int = 0,
    val deliveries: WebhookStatsDto = WebhookStatsDto(), val createdBy: DevPerson? = null, val createdAt: Long = 0,
    val ownerAllowed: Boolean = true,
)
@Serializable data class WebhooksRes(val webhooks: List<WebhookDto>)
@Serializable data class WebhookRes(val webhook: WebhookDto)
@Serializable data class WebhookReq(val name: String, val url: String, val events: List<String>, val enabled: Boolean? = null)
/** The signing secret is in this answer only. */
@Serializable data class WebhookCreatedRes(val webhook: WebhookDto, val secret: String)
@Serializable data class WebhookSecretRes(val secret: String)
@Serializable data class WebhookDeliveryDto(
    val id: String, val eventId: String, val event: String, val state: String, val attempts: Int = 0,
    val lastStatus: Int? = null, val lastError: String? = null, val createdAt: Long, val deliveredAt: Long? = null,
)
@Serializable data class WebhookDeliveriesRes(val deliveries: List<WebhookDeliveryDto>)
