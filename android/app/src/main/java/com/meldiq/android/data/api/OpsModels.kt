package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── Platform health (platform admins only, docs/OPERATIONS.md, round 7 slice C1) ─────────
// Mirrors server/src/ops/health.ts. A check the server can't measure comes back with available = false and a reason;
// the screen says so instead of showing a number.

@Serializable data class PlatformMeRes(val platformAdmin: Boolean = false)

@Serializable data class HealthRes(val health: HealthDto)

@Serializable data class HealthDto(
    val version: String,
    val generatedAt: Long,
    val uptimeSeconds: Long,
    val checks: HealthChecksDto,
    val process: HealthProcessDto,
    val realtime: HealthRealtimeDto,
    val queues: HealthQueuesDto,
    val metrics: HealthMetricsDto,
    val warnings: List<String> = emptyList(),
)

@Serializable data class HealthChecksDto(
    val database: MeasuredDto,
    val mongodb: MeasuredDto,
    val livekit: MeasuredDto,
    val storage: MeasuredDto,
)

/** One measured check: which fields are set depends on the check. */
@Serializable data class MeasuredDto(
    val available: Boolean,
    val reason: String? = null,
    val ok: Boolean? = null,
    val sizeBytes: Long? = null,
    val configured: Boolean? = null,
    val backend: String? = null,
    val reachable: Boolean? = null,
    val errorKind: String? = null,
    val jobsQueued: Int? = null,
    val jobsDead: Int? = null,
    val deliveriesFailed24h: Int? = null,
)

@Serializable data class HealthProcessDto(val rssBytes: Long, val heapUsedBytes: Long, val eventLoopDelayP99Ms: Long? = null)
@Serializable data class HealthRealtimeDto(val onlineDevices: Int)
@Serializable data class HealthQueuesDto(val delivery: HealthDeliveryDto, val reminders: HealthRemindersDto, val integrations: MeasuredDto)
@Serializable data class HealthDeliveryDto(val items: Int, val oldestAgeSeconds: Long? = null)
@Serializable data class HealthRemindersDto(val overdueUndelivered: Int, val retrying: Int)

@Serializable data class HealthMetricsDto(
    val since: Long,
    val window: HealthWindowDto,
    val routes: List<HealthRouteDto> = emptyList(),
    val jobs: List<HealthJobDto> = emptyList(),
    val providers: List<HealthProviderDto> = emptyList(),
)

@Serializable data class HealthWindowDto(val minutes: Int, val requests: Int, val serverErrors: Int, val errorRate: Double? = null, val p95Ms: Int? = null)
@Serializable data class HealthRouteDto(val route: String, val count: Int, val clientErrors: Int, val serverErrors: Int, val avgMs: Int, val p95Ms: Int? = null, val maxMs: Int)
@Serializable data class HealthJobDto(
    val name: String, val runs: Int, val failures: Int,
    val lastRunAt: Long? = null, val lastDurationMs: Long? = null, val lastFailureAt: Long? = null, val lastErrorKind: String? = null,
)
@Serializable data class HealthProviderDto(
    val name: String, val ok: Int, val failed: Int,
    val lastOkAt: Long? = null, val lastFailureAt: Long? = null, val lastErrorKind: String? = null,
)
