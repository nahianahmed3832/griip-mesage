package com.meldiq.android.ui.screens.workspace.roles

import com.meldiq.android.data.api.CustomRoleDto
import com.meldiq.android.data.api.PermissionDto

/**
 * The role editor's rules, mirroring the server's `src/permissions/rules.ts` (docs/WORKSPACE_ROLES.md) so the app only
 * offers what the server would accept. The server decides again on every save. Unit-tested in `RoleEditorRulesTest`.
 */
object RoleEditorRules {
    /** Why a permission's switch is locked for this person, or [None]. */
    enum class Lock { None, NotYours, NeedsDelegate, OwnerOnly, NotRestrictable }

    const val DELEGATE = "roles.delegate"
    const val MANAGE = "roles.manage"

    /** Categories in the order the editor shows them. */
    val CATEGORY_ORDER = listOf("workspace", "members", "roles", "teams", "channels", "messaging", "integrations", "security")

    fun grantLock(p: PermissionDto, mine: Set<String>, canDelegate: Boolean): Lock = when {
        p.ownerOnly -> Lock.OwnerOnly
        p.key !in mine -> Lock.NotYours
        p.admin && !canDelegate -> Lock.NeedsDelegate
        else -> Lock.None
    }

    fun denyLock(p: PermissionDto, mine: Set<String>): Lock = when {
        !p.restrictable -> Lock.NotRestrictable
        p.key !in mine -> Lock.NotYours
        else -> Lock.None
    }

    /**
     * Whether this person may save the role with [grants] and [denies]: they need `roles.manage`, may only give or restrict
     * what they hold, need `roles.delegate` for admin powers, and may not edit a role that is already beyond them.
     */
    fun canSave(
        name: String,
        grants: Set<String>,
        denies: Set<String>,
        catalog: List<PermissionDto>,
        mine: Set<String>,
        before: CustomRoleDto? = null,
    ): Boolean {
        if (name.isBlank() || MANAGE !in mine) return false
        if (grants.intersect(denies).isNotEmpty()) return false
        val byKey = catalog.associateBy { it.key }
        val canDelegate = DELEGATE in mine
        fun ok(g: Collection<String>, d: Collection<String>): Boolean =
            g.all { k -> byKey[k]?.let { grantLock(it, mine, canDelegate) == Lock.None } == true } &&
                d.all { k -> byKey[k]?.let { denyLock(it, mine) == Lock.None } == true }
        return ok(grants, denies) && (before == null || ok(before.permissions, before.denies))
    }

    /** The catalog grouped by category, in the editor's order; unknown categories last. */
    fun grouped(catalog: List<PermissionDto>): List<Pair<String, List<PermissionDto>>> =
        catalog.groupBy { it.category }.toList().sortedBy { (cat, _) -> CATEGORY_ORDER.indexOf(cat).let { if (it < 0) Int.MAX_VALUE else it } }

    /** "#RRGGBB" as an ARGB int, or null for anything else. */
    fun parseColor(hex: String?): Int? {
        if (hex == null || !Regex("^#[0-9A-Fa-f]{6}$").matches(hex)) return null
        return (0xFF000000 or hex.substring(1).toLong(16)).toInt()
    }

    /** The colours the editor offers (warm, within the Cocoa palette's family). */
    val SWATCHES = listOf("#E39B7B", "#D98BA7", "#C9A27A", "#B58FD1", "#8FB59A", "#7FA7C9", "#E0B45C", "#9A9A9A")
}
