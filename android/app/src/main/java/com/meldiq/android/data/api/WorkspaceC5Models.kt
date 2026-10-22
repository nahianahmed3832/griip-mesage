package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── Round 7 slice C5 (docs/WORKSPACE_C5.md): guests, meetings (templates and user groups were retired) ─────────

@Serializable data class GuestPolicyReq(val guestsAllowed: Boolean, val guestMaxDays: Int? = null)
@Serializable data class GuestExpiryReq(val expiresAt: Long?)

@Serializable data class MeetingPersonDto(val id: String, val name: String? = null, val response: String? = null)
@Serializable data class MeetingRoomDto(val id: String, val name: String? = null)
@Serializable data class MeetingDto(
    val id: String,
    val orgId: String,
    val title: String,
    val description: String? = null,
    val room: MeetingRoomDto? = null,
    val startsAt: Long,
    val endsAt: Long,
    val timeZone: String,
    val organizer: MeetingPersonDto,
    val participants: List<MeetingPersonDto> = emptyList(),
    val myResponse: String? = null,
    val cancelled: Boolean = false,
    val canEdit: Boolean = false,
    val rev: Int,
    /** Batch B8b (servers with `event-repeat`): "weekly", "biweekly", "monthly" (same weekday) or "yearly"; null: once. */
    val repeat: String? = null,
)
@Serializable data class MeetingsRes(val meetings: List<MeetingDto>)
@Serializable data class MeetingRes(val meeting: MeetingDto)
@Serializable data class MeetingReq(
    val roomId: String, val title: String, val description: String? = null, val startsAt: Long, val endsAt: Long,
    val timeZone: String, val participantIds: List<String> = emptyList(), val rev: Int? = null,
    /** Batch B8b: how it repeats, or "none". Left out (older servers) when the feature isn't there. */
    val repeat: String? = null,
)
@Serializable data class MeetingRespondReq(val response: String)
