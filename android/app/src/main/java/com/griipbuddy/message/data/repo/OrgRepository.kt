package com.griipbuddy.message.data.repo

import com.griipbuddy.message.data.api.AcceptInviteRes
import com.griipbuddy.message.data.api.ActivityRes
import com.griipbuddy.message.data.api.ApiClient
import com.griipbuddy.message.data.api.ChannelRes
import com.griipbuddy.message.data.api.ChannelsRes
import com.griipbuddy.message.data.api.CodeReq
import com.griipbuddy.message.data.api.CreateBranchReq
import com.griipbuddy.message.data.api.CreateChannelReq
import com.griipbuddy.message.data.api.CreateDepartmentReq
import com.griipbuddy.message.data.api.CreateOrgReq
import com.griipbuddy.message.data.api.CreateTeamReq
import com.griipbuddy.message.data.api.GriipJson
import com.griipbuddy.message.data.api.InvitePreviewRes
import com.griipbuddy.message.data.api.InviteReq
import com.griipbuddy.message.data.api.InviteRes
import com.griipbuddy.message.data.api.InvitesRes
import com.griipbuddy.message.data.api.MoveReq
import com.griipbuddy.message.data.api.OkRes
import com.griipbuddy.message.data.api.OrgDetailRes
import com.griipbuddy.message.data.api.OrgRes
import com.griipbuddy.message.data.api.OrgRoleReq
import com.griipbuddy.message.data.api.OrgsRes
import com.griipbuddy.message.data.api.PatchScopeReq
import com.griipbuddy.message.data.api.RoleReq
import com.griipbuddy.message.data.api.TeamDepartmentReq
import com.griipbuddy.message.data.api.UpdateDepartmentReq
import com.griipbuddy.message.data.api.UsersRes
import com.griipbuddy.message.data.db.GriipDatabase
import com.griipbuddy.message.data.db.KvEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.StateFlow

/** Organisation hierarchy operations. Every call is authorised server-side; UI hides what the user can't do. */
class OrgRepository(
    private val api: ApiClient,
    private val db: GriipDatabase,
    private val conversations: ConversationRepository,
    private val users: UserRepository,
) {
    private val _selectedOrg = MutableStateFlow<String?>(null)
    val selectedOrg: StateFlow<String?> = _selectedOrg
    private val _selectedBranch = MutableStateFlow<String?>(null)
    val selectedBranch: StateFlow<String?> = _selectedBranch

    suspend fun loadSelection() {
        _selectedOrg.value = db.kv().get("org")
        _selectedBranch.value = db.kv().get("branch")
    }

    suspend fun select(orgId: String?, branchId: String? = null) {
        _selectedOrg.value = orgId
        _selectedBranch.value = branchId
        db.kv().put(KvEntity("org", orgId ?: ""))
        db.kv().put(KvEntity("branch", branchId ?: ""))
    }

    suspend fun orgs() = api.get<OrgsRes>("/v1/orgs").orgs.also { list ->
        db.kv().put(KvEntity("cache.orgs", GriipJson.encodeToString(OrgsRes.serializer(), OrgsRes(list))))
    }

    suspend fun cachedOrgs() = db.kv().get("cache.orgs")?.let { runCatching { GriipJson.decodeFromString(OrgsRes.serializer(), it).orgs }.getOrNull() }

    suspend fun detail(orgId: String): OrgDetailRes = api.get<OrgDetailRes>("/v1/orgs/$orgId").also {
        db.kv().put(KvEntity("cache.org.$orgId", GriipJson.encodeToString(OrgDetailRes.serializer(), it)))
    }

    suspend fun cachedDetail(orgId: String): OrgDetailRes? =
        db.kv().get("cache.org.$orgId")?.let { runCatching { GriipJson.decodeFromString(OrgDetailRes.serializer(), it) }.getOrNull() }

    suspend fun create(name: String, description: String?, requestId: String? = null) =
        api.post<CreateOrgReq, OrgRes>("/v1/orgs", CreateOrgReq(name, description, requestId)).org
    suspend fun rename(orgId: String, name: String, description: String?) =
        api.patch<CreateOrgReq, OrgRes>("/v1/orgs/$orgId", CreateOrgReq(name, description)).org

    suspend fun createBranch(orgId: String, name: String, location: String?) =
        api.post<CreateBranchReq, kotlinx.serialization.json.JsonObject>("/v1/orgs/$orgId/branches", CreateBranchReq(name, null, location))
    suspend fun updateBranch(id: String, name: String?, description: String?, archived: Boolean? = null) =
        api.patch<PatchScopeReq, OkRes>("/v1/branches/$id", PatchScopeReq(name, description, archived))
    suspend fun createDepartment(branchId: String, name: String) =
        api.post<CreateDepartmentReq, kotlinx.serialization.json.JsonObject>("/v1/branches/$branchId/departments", CreateDepartmentReq(name))
    suspend fun createTeam(orgId: String, name: String, description: String?, branchId: String?, departmentId: String?) =
        api.post<CreateTeamReq, kotlinx.serialization.json.JsonObject>("/v1/orgs/$orgId/teams", CreateTeamReq(name, description, branchId, departmentId))
    suspend fun updateDepartment(id: String, name: String, description: String?) =
        api.patch<UpdateDepartmentReq, OkRes>("/v1/departments/$id", UpdateDepartmentReq(name, description?.let { JsonPrimitive(it) } ?: JsonNull))
    /** Its teams stay in the branch, ungrouped (D-15). */
    suspend fun deleteDepartment(id: String) = api.delete<OkRes>("/v1/departments/$id")
    /** Puts a team in one of its branch's departments, or in none ([departmentId] null). */
    suspend fun setTeamDepartment(teamId: String, departmentId: String?) =
        api.patch<TeamDepartmentReq, OkRes>("/v1/teams/$teamId", TeamDepartmentReq(departmentId?.let { JsonPrimitive(it) } ?: JsonNull))
    suspend fun updateTeam(id: String, name: String?, description: String?, archived: Boolean? = null) =
        api.patch<PatchScopeReq, OkRes>("/v1/teams/$id", PatchScopeReq(name, description, archived))

    suspend fun members(orgId: String, q: String = "") =
        api.get<UsersRes>("/v1/orgs/$orgId/members" + if (q.isNotBlank()) "?q=${java.net.URLEncoder.encode(q, "UTF-8")}" else "").members.also { it.forEach { u -> users.remember(u) } }
    suspend fun teamMembers(teamId: String) = api.get<UsersRes>("/v1/teams/$teamId/members").members
    suspend fun branchMembers(branchId: String) = api.get<UsersRes>("/v1/branches/$branchId/members").members

    suspend fun setOrgRole(orgId: String, userId: String, role: String) = api.patch<OrgRoleReq, OkRes>("/v1/orgs/$orgId/members/$userId", OrgRoleReq(role = role))
    suspend fun removeFromOrg(orgId: String, userId: String) = api.delete<OkRes>("/v1/orgs/$orgId/members/$userId")
    suspend fun setBranchMember(branchId: String, userId: String, role: String?) =
        if (role == null) api.delete<OkRes>("/v1/branches/$branchId/members/$userId") else api.put<RoleReq, OkRes>("/v1/branches/$branchId/members/$userId", RoleReq(role))
    suspend fun setTeamMember(teamId: String, userId: String, role: String?) =
        if (role == null) api.delete<OkRes>("/v1/teams/$teamId/members/$userId") else api.put<RoleReq, OkRes>("/v1/teams/$teamId/members/$userId", RoleReq(role))
    suspend fun moveMember(fromTeam: String, userId: String, toTeam: String) = api.post<MoveReq, OkRes>("/v1/teams/$fromTeam/members/$userId/move", MoveReq(toTeam))

    suspend fun createInvite(orgId: String, role: String, branchId: String?, teamId: String?, maxUses: Int, hours: Int) =
        api.post<InviteReq, InviteRes>("/v1/orgs/$orgId/invites", InviteReq(role, branchId, teamId, maxUses, hours)).invite
    suspend fun invites(orgId: String) = api.get<InvitesRes>("/v1/orgs/$orgId/invites").invites
    suspend fun revokeInvite(id: String) = api.delete<OkRes>("/v1/invites/$id")
    suspend fun previewInvite(code: String) = api.post<CodeReq, InvitePreviewRes>("/v1/invites/preview", CodeReq(code))
    suspend fun acceptInvite(code: String) = api.post<CodeReq, AcceptInviteRes>("/v1/invites/accept", CodeReq(code)).also { runCatching { conversations.refresh() } }

    suspend fun channels(orgId: String, q: String = "") =
        api.get<ChannelsRes>("/v1/orgs/$orgId/channels" + if (q.isNotBlank()) "?q=${java.net.URLEncoder.encode(q, "UTF-8")}" else "").channels
    suspend fun createChannel(orgId: String, req: CreateChannelReq) =
        api.post<CreateChannelReq, ChannelRes>("/v1/orgs/$orgId/channels", req).channel.also { conversations.upsertFromServer(it) }

    suspend fun activity(orgId: String) = api.get<ActivityRes>("/v1/orgs/$orgId/activity").activity
}
