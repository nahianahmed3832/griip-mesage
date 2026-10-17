package com.griipbuddy.orbit.data.api

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
    // Added 2026-10-16 (server-info v1); absent on older servers.
    val infoVersion: Int? = null, val api: Int? = null, val minClientApi: Int? = null,
    val instanceName: String? = null, val registration: String? = null,
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
    // Federation (2026-10-16): set for people on another server (their username is then "name:server").
    val homeServer: String? = null, val address: String? = null,
    // Profile (server ≥ 2026-10-17, docs/PROFILE_AND_PRIVACY.md). Absent: not set, or not visible to you.
    val firstName: String? = null, val lastName: String? = null, val birthday: String? = null, val phone: String? = null,
    val photoRev: Long? = null,
    /** Your own privacy settings (only in your own profile): field → "everyone" | "contacts" | "nobody". */
    val privacy: Map<String, String>? = null,
)
@Serializable data class ScopedRole(val id: String, val role: String)
@Serializable data class MeRes(val user: UserDto, val deviceId: String)
@Serializable data class UserRes(val user: UserDto)
@Serializable data class UsersRes(val users: List<UserDto> = emptyList(), val members: List<UserDto> = emptyList())
/** Profile edit: null fields are left out (unchanged); an empty string clears a field. */
@Serializable data class UpdateMeReq(
    val displayName: String? = null, val statusText: String? = null, val avatarColor: Int? = null,
    val firstName: String? = null, val lastName: String? = null, val birthday: String? = null, val phone: String? = null,
    val username: String? = null,
)
@Serializable data class PrivacyRes(val privacy: Map<String, String>)
@Serializable data class PhotoRes(val photoRev: Long? = null)
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
    /** Whether this server lets you use @everyone here (false from older servers, which don't support it). */
    val canMentionAll: Boolean = false,
    val federation: ConversationFederationDto? = null,
)
/** Servers of the people in a conversation who are on another server, as this server sees them. */
@Serializable data class ConversationFederationDto(val servers: List<FederationServerDto> = emptyList(), val calls: Boolean = false)
@Serializable data class FederationServerDto(val serverId: String, val domain: String? = null, val state: String = "unknown", val pending: Int = 0)
@Serializable data class ResolvedServerDto(
    val serverId: String, val domain: String, val state: String, val verifiedBy: Int = 0, val source: String = "quorum", val identityChanged: Boolean = false,
)
@Serializable data class ResolveRes(val user: UserDto, val server: ResolvedServerDto)
@Serializable data class FederationInfoRes(val enabled: Boolean, val domain: String, val serverId: String, val address: String)
@Serializable data class BlockReq(val userId: String)
@Serializable data class BlocksRes(val userIds: List<String> = emptyList())
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
    /** Asks the server to attest @everyone (null: omitted). The server checks who may and how often. */
    val mentionAll: Boolean? = null,
)
@Serializable data class SendMessageRes(val messageId: String, val serverTs: Long, val recipientCount: Int, val duplicate: Boolean = false)
@Serializable data class QueuePayload(
    val v: Int? = null, val cls: String? = null, val sentAt: Long? = null, val serverTs: Long? = null, val nonce: String? = null,
    val ciphertext: String? = null, val signature: String? = null, val enc: String? = null, val wrappedKey: String? = null,
    val status: String? = null, val at: Long? = null,
    /** The server attested @everyone on this copy (servers from 2026-10-17). */
    val mentionAll: Boolean? = null,
)
@Serializable data class QueueItem(
    val id: Long, val kind: String, val conversationId: String, val messageId: String, val senderUserId: String,
    val senderDeviceId: String, val createdAt: Long, val expiresAt: Long, val payload: QueuePayload,
)
@Serializable data class QueueRes(val items: List<QueueItem>, val more: Boolean)
@Serializable data class AckReq(val ids: List<Long>)
@Serializable data class AttachmentUploadRes(val id: String, val size: Long, val expiresAt: Long)

// ───────── Orgs ─────────
// slug defaults to "": servers before 2026-10-16 left it out of the invite preview, and requiring it made the app
// reject a valid invite with "Unexpected server response" (physical-device test).
@Serializable data class OrgDto(
    val id: String, val name: String, val slug: String = "", val description: String? = null, val myRole: String? = null,
    val memberCount: Int = 0, val createdAt: Long = 0,
)
@Serializable data class OrgsRes(val orgs: List<OrgDto>)
@Serializable data class OrgRes(val org: OrgDto)
/** `alreadyMember`: this person had already joined through the invite (a retry); the server used nothing up. */
@Serializable data class AcceptInviteRes(val org: OrgDto, val alreadyMember: Boolean = false)
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
/** [requestId]: one per create form, so a retry or double tap returns the same workspace (server ≥ 2026-10-16). */
@Serializable data class CreateOrgReq(val name: String, val description: String? = null, val requestId: String? = null)
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
    /** First time a recipient device confirmed it is ringing (server ≥ 2026-10-16; null on older servers). */
    val alertedAt: Long? = null,
    val participants: List<CallParticipantDto> = emptyList(),
    /** Ended calls (server ≥ 2026-10-17): one entry per person who connected. Null from older servers: not known. */
    val attended: List<CallAttendanceDto>? = null,
)
@Serializable data class CallAttendanceDto(val userId: String, val joinedAt: Long, val leftAt: Long, val seconds: Long)
@Serializable data class MediaGrant(val url: String, val token: String, val room: String)
@Serializable data class CallRes(val call: CallDto, val media: MediaGrant? = null, val joinedExisting: Boolean = false)
@Serializable data class CallsRes(val calls: List<CallDto>)
@Serializable data class StartCallReq(val conversationId: String, val video: Boolean)
@Serializable data class DeclineCallReq(val reason: String)

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
