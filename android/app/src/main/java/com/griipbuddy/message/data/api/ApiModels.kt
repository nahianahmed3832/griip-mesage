package com.griipbuddy.message.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ───────── Auth ─────────
@Serializable data class ChallengeRes(val nonce: String, val expiresAt: Long)
@Serializable data class DeviceReg(val name: String, val signingKey: String, val encryptionKey: String, val certificate: String)
@Serializable data class RegisterReq(
    val username: String, val displayName: String, val identityKey: String, val avatarColor: Int,
    val device: DeviceReg, val nonce: String, val signature: String,
    /** Required by invite-only servers (the default): a registration invite or an org invite code. */
    val inviteCode: String? = null,
)
@Serializable data class LoginReq(val deviceId: String, val nonce: String, val signature: String)
@Serializable data class AddDeviceReq(val username: String, val device: DeviceReg, val nonce: String, val signature: String)
@Serializable data class SessionRes(val userId: String, val deviceId: String, val sessionToken: String, val expiresAt: Long)
@Serializable data class UsernameAvailableRes(val available: Boolean, val username: String? = null, val reason: String? = null)
@Serializable data class ServerInfo(
    val name: String, val version: String, val calling: Boolean, val queueTtlSeconds: Long,
    val attachmentMaxBytes: Long, val attachmentTtlSeconds: Long, val encryption: String,
)

@Serializable data class PairingStartReq(val deviceName: String, val signingKey: String, val encryptionKey: String, val transferKey: String)
@Serializable data class PairingStartRes(val pairingId: String, val code: String, val expiresAt: Long)
@Serializable data class PairingPollRes(
    val status: String, val username: String? = null, val identityKey: String? = null, val certificate: String? = null,
    val sealedIdentity: String? = null, val sealedEnc: String? = null,
)
@Serializable data class PairingRequestInfo(val deviceName: String, val signingKey: String, val encryptionKey: String, val transferKey: String, val createdAt: Long)
@Serializable data class PairingApproveReq(val certificate: String, val sealedIdentity: String, val sealedEnc: String)

// ───────── Users & devices ─────────
@Serializable data class UserDto(
    val id: String, val username: String, val displayName: String, val avatarColor: Int = 0, val statusText: String? = null,
    val online: Boolean? = null, val lastSeenAt: Long? = null, val identityKey: String? = null, val fingerprint: String? = null,
    val role: String? = null, val orgRole: String? = null, val title: String? = null,
    val branches: List<ScopedRole> = emptyList(), val teams: List<ScopedRole> = emptyList(),
)
@Serializable data class ScopedRole(val id: String, val role: String)
@Serializable data class MeRes(val user: UserDto, val deviceId: String)
@Serializable data class UserRes(val user: UserDto)
@Serializable data class UsersRes(val users: List<UserDto> = emptyList(), val members: List<UserDto> = emptyList())
@Serializable data class UpdateMeReq(val displayName: String? = null, val statusText: String? = null, val avatarColor: Int? = null)
@Serializable data class DeviceKeyDto(val id: String, val signingKey: String, val encryptionKey: String, val certificate: String)
@Serializable data class UserKeysDto(val userId: String, val identityKey: String, val devices: List<DeviceKeyDto>)
@Serializable data class KeysRes(val users: List<UserKeysDto>)
@Serializable data class UserIdsReq(val userIds: List<String>)
@Serializable data class MyDeviceDto(
    val id: String, val name: String, val signingKey: String, val createdAt: Long, val lastSeenAt: Long? = null,
    val revokedAt: Long? = null, val current: Boolean = false,
)
@Serializable data class DevicesRes(val devices: List<MyDeviceDto>)
@Serializable data class PresenceDto(val userId: String, val online: Boolean, val lastSeenAt: Long? = null)
@Serializable data class PresenceRes(val presence: List<PresenceDto>)

// ───────── Conversations ─────────
@Serializable data class ActiveCallDto(val id: String, val video: Boolean, val state: String, val startedAt: Long)
@Serializable data class ConversationDto(
    val id: String, val type: String, val orgId: String? = null, val scope: String? = null, val scopeId: String? = null,
    val scopeName: String? = null, val kind: String? = null, val name: String? = null, val topic: String? = null,
    val archived: Boolean = false, val createdAt: Long = 0, val updatedAt: Long = 0, val memberCount: Int = 0,
    val members: List<UserDto>? = null, val joined: Boolean = true, val myRole: String? = null,
    val canPost: Boolean = true, val canManage: Boolean = false, val activeCall: ActiveCallDto? = null,
)
@Serializable data class ConversationsRes(val conversations: List<ConversationDto>)
@Serializable data class ConversationRes(val conversation: ConversationDto)
@Serializable data class ChannelsRes(val channels: List<ConversationDto>)
@Serializable data class ChannelRes(val channel: ConversationDto)
@Serializable data class DmReq(val userId: String)
@Serializable data class GroupReq(val name: String, val memberIds: List<String>)
@Serializable data class PatchConversationReq(val name: String? = null, val topic: String? = null, val archived: Boolean? = null)
@Serializable data class RoleReq(val role: String)

// ───────── Messages & queue ─────────
@Serializable data class WireKeyWrap(val deviceId: String, val enc: String, val wrappedKey: String)
@Serializable data class SendMessageReq(
    val conversationId: String, val messageId: String, val cls: String, val sentAt: Long, val nonce: String,
    val ciphertext: String, val signature: String, val recipients: List<WireKeyWrap>,
)
@Serializable data class SendMessageRes(val messageId: String, val serverTs: Long, val recipientCount: Int, val duplicate: Boolean = false)
@Serializable data class QueuePayload(
    val v: Int? = null, val cls: String? = null, val sentAt: Long? = null, val serverTs: Long? = null, val nonce: String? = null,
    val ciphertext: String? = null, val signature: String? = null, val enc: String? = null, val wrappedKey: String? = null,
    val status: String? = null, val at: Long? = null,
)
@Serializable data class QueueItem(
    val id: Long, val kind: String, val conversationId: String, val messageId: String, val senderUserId: String,
    val senderDeviceId: String, val createdAt: Long, val expiresAt: Long, val payload: QueuePayload,
)
@Serializable data class QueueRes(val items: List<QueueItem>, val more: Boolean)
@Serializable data class AckReq(val ids: List<Long>)
@Serializable data class AttachmentUploadRes(val id: String, val size: Long, val expiresAt: Long)

// ───────── Orgs ─────────
@Serializable data class OrgDto(
    val id: String, val name: String, val slug: String, val description: String? = null, val myRole: String? = null,
    val memberCount: Int = 0, val createdAt: Long = 0,
)
@Serializable data class OrgsRes(val orgs: List<OrgDto>)
@Serializable data class OrgRes(val org: OrgDto)
@Serializable data class BranchDto(
    val id: String, val name: String, val description: String? = null, val location: String? = null, val myRole: String? = null,
    val canAccess: Boolean = false, val canManage: Boolean = false, val memberCount: Int = 0,
)
@Serializable data class DepartmentDto(val id: String, val branchId: String, val name: String, val description: String? = null)
@Serializable data class TeamDto(
    val id: String, val name: String, val description: String? = null, val branchId: String? = null, val departmentId: String? = null,
    val crossBranch: Boolean = false, val myRole: String? = null, val canAccess: Boolean = false, val canManage: Boolean = false,
    val memberCount: Int = 0,
)
@Serializable data class OrgStats(val members: Int = 0, val teams: Int = 0, val channels: Int = 0, val branches: Int = 0)
@Serializable data class OrgPermissions(
    val canManageOrg: Boolean = false, val canInvite: Boolean = false, val canCreateChannels: Boolean = false,
    val canViewDirectory: Boolean = false, val isOwner: Boolean = false,
)
@Serializable data class OrgDetailRes(
    val org: OrgDto, val branches: List<BranchDto>, val departments: List<DepartmentDto>, val teams: List<TeamDto>,
    val stats: OrgStats, val permissions: OrgPermissions,
)
@Serializable data class CreateOrgReq(val name: String, val description: String? = null)
@Serializable data class CreateBranchReq(val name: String, val description: String? = null, val location: String? = null)
@Serializable data class CreateDepartmentReq(val name: String, val description: String? = null)
@Serializable data class CreateTeamReq(val name: String, val description: String? = null, val branchId: String? = null, val departmentId: String? = null)
@Serializable data class PatchScopeReq(val name: String? = null, val description: String? = null, val archived: Boolean? = null)
/** JSON nulls are omitted by [GriipJson], so fields that may be cleared are sent as a [kotlinx.serialization.json.JsonElement] (`JsonNull` clears). */
@Serializable data class UpdateDepartmentReq(val name: String, val description: kotlinx.serialization.json.JsonElement)
@Serializable data class TeamDepartmentReq(val departmentId: kotlinx.serialization.json.JsonElement)
@Serializable data class CreateChannelReq(
    val name: String, val topic: String? = null, val scope: String, val scopeId: String? = null, val kind: String,
    val autoJoin: Boolean? = null, val memberIds: List<String> = emptyList(),
)
@Serializable data class OrgRoleReq(val role: String? = null, val title: String? = null)
@Serializable data class InviteReq(val role: String, val branchId: String? = null, val teamId: String? = null, val maxUses: Int, val expiresInHours: Int)
@Serializable data class InviteDto(
    val id: String, val code: String? = null, val hint: String? = null, val role: String, val branchId: String? = null,
    val teamId: String? = null, val maxUses: Int, val uses: Int = 0, val createdAt: Long = 0, val expiresAt: Long,
    val revokedAt: Long? = null, val active: Boolean = true,
)
@Serializable data class InviteRes(val invite: InviteDto)
@Serializable data class InvitesRes(val invites: List<InviteDto>)
@Serializable data class CodeReq(val code: String)
@Serializable data class NamedRef(val id: String, val name: String)
@Serializable data class InvitePreviewRes(val org: OrgDto, val role: String, val branch: NamedRef? = null, val team: NamedRef? = null, val alreadyMember: Boolean = false)
@Serializable data class ActivityDto(
    val id: Long, val at: Long, val action: String, val actorId: String? = null, val actorName: String? = null,
    val target: String? = null, val detail: String? = null,
)
@Serializable data class ActivityRes(val activity: List<ActivityDto>)
@Serializable data class MoveReq(val toTeamId: String)

// ───────── Calls ─────────
@Serializable data class CallParticipantDto(val userId: String, val deviceId: String, val joinedAt: Long)
@Serializable data class CallDto(
    val id: String, val conversationId: String, val conversationType: String? = null, val createdBy: String, val video: Boolean,
    val state: String, val createdAt: Long, val answeredAt: Long? = null, val endedAt: Long? = null, val endReason: String? = null,
    val participants: List<CallParticipantDto> = emptyList(),
)
@Serializable data class MediaGrant(val url: String, val token: String, val room: String)
@Serializable data class CallRes(val call: CallDto, val media: MediaGrant? = null, val joinedExisting: Boolean = false)
@Serializable data class CallsRes(val calls: List<CallDto>)
@Serializable data class StartCallReq(val conversationId: String, val video: Boolean)

@Serializable data class ApiErrorBody(val error: ApiErrorDetail? = null)
@Serializable data class ApiErrorDetail(val code: String = "", val message: String = "", val details: JsonElement? = null)
@Serializable data class OkRes(val ok: Boolean = true)

/** Raw realtime frame; fields depend on `t`. */
@Serializable data class RealtimeFrame(
    val t: String,
    val items: List<QueueItem>? = null,
    val more: Boolean? = null,
    val backlog: Boolean? = null,
    val c: String? = null,
    val u: String? = null,
    val on: Boolean? = null,
    val userId: String? = null,
    val online: Boolean? = null,
    val lastSeenAt: Long? = null,
    val event: String? = null,
    val call: CallDto? = null,
    val from: JsonObject? = null,
    val conversationId: String? = null,
    val orgId: String? = null,
    val deviceId: String? = null,
)
