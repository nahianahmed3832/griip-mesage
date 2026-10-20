package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── Round 7 slice C5 (docs/WORKSPACE_C5.md): templates, groups, guests, meetings ─────────

@Serializable data class WsTemplateChannelDto(val name: String, val topic: String = "", val kind: String = "PUBLIC", val voice: Boolean = false)
@Serializable data class WsTemplateDto(val id: String, val name: String, val description: String, val channels: List<WsTemplateChannelDto> = emptyList())
@Serializable data class WsTemplatesRes(val templates: List<WsTemplateDto>)
@Serializable data class ApplyTemplateReq(val templateId: String)
@Serializable data class AppliedDto(val channels: List<String> = emptyList(), val document: String? = null)
@Serializable data class ApplyTemplateRes(val applied: AppliedDto)

@Serializable data class GroupMemberDto(val id: String, val name: String? = null, val username: String? = null)
@Serializable data class GroupDto(val id: String, val orgId: String, val name: String, val handle: String, val description: String? = null, val members: List<GroupMemberDto> = emptyList())
@Serializable data class GroupsRes(val groups: List<GroupDto>)
@Serializable data class GroupRes(val group: GroupDto)
@Serializable data class UserGroupReq(val name: String, val description: String? = null, val memberIds: List<String>)
@Serializable data class AddGroupReq(val groupId: String)
@Serializable data class AddGroupRes(val added: Int = 0, val skipped: Int = 0)

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
)
@Serializable data class MeetingsRes(val meetings: List<MeetingDto>)
@Serializable data class MeetingRes(val meeting: MeetingDto)
@Serializable data class MeetingReq(
    val roomId: String, val title: String, val description: String? = null, val startsAt: Long, val endsAt: Long,
    val timeZone: String, val participantIds: List<String> = emptyList(), val groupIds: List<String> = emptyList(), val rev: Int? = null,
)
@Serializable data class MeetingRespondReq(val response: String)
