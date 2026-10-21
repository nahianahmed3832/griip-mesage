package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.AssignRes
import com.meldiq.android.data.api.AuditRes
import com.meldiq.android.data.api.CreateRoleReq
import com.meldiq.android.data.api.CustomRoleDto
import com.meldiq.android.data.api.MemberPermissionsRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.data.api.PatchRoleReq
import com.meldiq.android.data.api.RoleMembersRes
import com.meldiq.android.data.api.RoleRes
import com.meldiq.android.data.api.RolesRes
import com.meldiq.android.data.api.TransferOwnershipReq
import com.meldiq.android.data.api.TransferOwnershipRes
import com.meldiq.android.data.api.UsageRes
import java.net.URLEncoder

/**
 * Custom workspace roles, ownership transfer, usage figures and the audit log (docs/WORKSPACE_ROLES.md). Every call is
 * authorised on the server; the app only hides what the server would refuse.
 */
class RoleRepository(private val api: ApiClient) {
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    suspend fun roles(orgId: String, includeArchived: Boolean = false): RolesRes =
        api.get("/v1/orgs/${enc(orgId)}/roles" + if (includeArchived) "?includeArchived=true" else "")

    suspend fun create(orgId: String, req: CreateRoleReq): CustomRoleDto = api.post<CreateRoleReq, RoleRes>("/v1/orgs/${enc(orgId)}/roles", req).role

    suspend fun update(roleId: String, req: PatchRoleReq): CustomRoleDto = api.patch<PatchRoleReq, RoleRes>("/v1/roles/${enc(roleId)}", req).role

    suspend fun delete(roleId: String) = api.delete<OkRes>("/v1/roles/${enc(roleId)}")

    suspend fun members(roleId: String) = api.get<RoleMembersRes>("/v1/roles/${enc(roleId)}/members").members

    suspend fun assign(roleId: String, userId: String): AssignRes = api.put<Map<String, String>, AssignRes>("/v1/roles/${enc(roleId)}/members/${enc(userId)}", emptyMap())

    suspend fun unassign(roleId: String, userId: String): AssignRes = api.delete("/v1/roles/${enc(roleId)}/members/${enc(userId)}")

    suspend fun memberPermissions(orgId: String, userId: String): MemberPermissionsRes = api.get("/v1/orgs/${enc(orgId)}/members/${enc(userId)}/permissions")

    suspend fun transferOwnership(orgId: String, userId: String, confirmName: String): TransferOwnershipRes =
        api.post("/v1/orgs/${enc(orgId)}/ownership", TransferOwnershipReq(userId, confirmName))

    suspend fun usage(orgId: String): UsageRes = api.get("/v1/orgs/${enc(orgId)}/usage")

    /** A page of the audit log, newest first; [before] is the last id of the previous page. */
    suspend fun audit(orgId: String, before: Long? = null, action: String? = null, actorId: String? = null): AuditRes {
        val q = listOfNotNull(before?.let { "before=$it" }, action?.let { "action=${enc(it)}" }, actorId?.let { "actorId=${enc(it)}" }, "limit=50").joinToString("&")
        return api.get("/v1/orgs/${enc(orgId)}/audit?$q")
    }
}
