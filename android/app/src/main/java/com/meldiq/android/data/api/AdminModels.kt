package com.meldiq.android.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ───────── Custom roles (servers with `custom-roles`, docs/WORKSPACE_ROLES.md) ─────────

/** One power a workspace role can carry, as the server's catalog describes it (labels come from the server). */
@Serializable data class PermissionDto(
    val key: String,
    val category: String,
    val label: String,
    val description: String = "",
    /** An administrative power: putting it into a role needs `roles.delegate`. */
    val admin: Boolean = false,
    val ownerOnly: Boolean = false,
    val guestAllowed: Boolean = false,
    val restrictable: Boolean = false,
)

@Serializable data class RoleTemplateDto(
    val key: String, val name: String, val description: String = "", val color: String? = null, val icon: String? = null,
    val permissions: List<String> = emptyList(), val denies: List<String> = emptyList(),
)

@Serializable data class BaseRoleDto(
    val key: String, val name: String, val permissions: List<String> = emptyList(), val memberCount: Int = 0,
    @kotlinx.serialization.SerialName("protected") val isProtected: Boolean = true,
)

@Serializable data class CustomRoleDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val permissions: List<String> = emptyList(),
    val denies: List<String> = emptyList(),
    val template: String? = null,
    val position: Int = 0,
    val rev: Int = 1,
    val memberCount: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** What the caller may do with it (the server checks again on every call). */
    val canEdit: Boolean = false,
    val canAssign: Boolean = false,
)

@Serializable data class RolesMe(
    val base: String = "MEMBER", val permissions: List<String> = emptyList(),
    val canManage: Boolean = false, val canAssign: Boolean = false, val canDelegate: Boolean = false,
)

@Serializable data class RoleLimits(val activeRoles: Int = 50, val rolesPerMember: Int = 20, val nameMax: Int = 40, val descriptionMax: Int = 200)

@Serializable data class RolesRes(
    val catalog: List<PermissionDto> = emptyList(),
    val templates: List<RoleTemplateDto> = emptyList(),
    val icons: List<String> = emptyList(),
    val baseRoles: List<BaseRoleDto> = emptyList(),
    val roles: List<CustomRoleDto> = emptyList(),
    val limits: RoleLimits = RoleLimits(),
    val me: RolesMe = RolesMe(),
)

@Serializable data class RoleRes(val role: CustomRoleDto)

/** Create a role: from a template, a clone, or from scratch; explicit fields override what is copied. */
@Serializable data class CreateRoleReq(
    val name: String? = null, val description: String? = null, val color: String? = null, val icon: String? = null,
    val permissions: List<String>? = null, val denies: List<String>? = null, val template: String? = null, val cloneOf: String? = null,
)

/** Edit a role at revision [baseRev]; a stale revision answers 409 with the current role. */
@Serializable data class PatchRoleReq(
    val baseRev: Int, val name: String? = null, val description: JsonElement? = null, val color: JsonElement? = null, val icon: JsonElement? = null,
    val permissions: List<String>? = null, val denies: List<String>? = null, val archived: Boolean? = null,
)

@Serializable data class RoleMemberDto(val id: String, val username: String = "", val displayName: String = "", val orgRole: String = "MEMBER", val assignedAt: Long = 0)
@Serializable data class RoleMembersRes(val members: List<RoleMemberDto> = emptyList())
@Serializable data class AssignRes(val ok: Boolean = true, val permissions: List<String> = emptyList())

@Serializable data class RoleRef(val id: String, val name: String, val color: String? = null)

@Serializable data class MemberPermissionsRes(
    val base: String = "MEMBER", val roles: List<RoleRef> = emptyList(), val effective: List<String> = emptyList(),
    val sources: Map<String, List<String>> = emptyMap(), val restricted: List<String> = emptyList(),
)

@Serializable data class TransferOwnershipReq(val userId: String, val confirmName: String)
@Serializable data class TransferOwnershipRes(val ok: Boolean = true, val newOwner: String = "", val yourRole: String = "ADMIN")

// ───────── Usage and the audit log ─────────
@Serializable data class UsageMembers(val total: Int = 0, val owners: Int = 0, val admins: Int = 0, val members: Int = 0, val guests: Int = 0)
@Serializable data class UsageStructure(val branches: Int = 0, val departments: Int = 0, val teams: Int = 0, val channels: Int = 0, val archivedChannels: Int = 0)
@Serializable data class UsageFiles(val count: Int = 0, val bytes: Long = 0)
@Serializable data class UsageApps(val installed: Int = 0, val pendingRequests: Int = 0, val connectedAccounts: Int = 0, val workspaceConnections: Int = 0, val notifications: Int = 0)
@Serializable data class UsageCount(val active: Int = 0, val custom: Int = 0)
@Serializable data class UsageRes(
    val members: UsageMembers = UsageMembers(), val structure: UsageStructure = UsageStructure(), val files: UsageFiles = UsageFiles(),
    val invitations: UsageCount = UsageCount(), val roles: UsageCount = UsageCount(), val apps: UsageApps = UsageApps(),
)

@Serializable data class AuditRes(val entries: List<ActivityDto> = emptyList(), val nextBefore: Long? = null)
