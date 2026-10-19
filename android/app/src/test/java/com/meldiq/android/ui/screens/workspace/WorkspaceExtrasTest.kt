package com.meldiq.android.ui.screens.workspace

import com.meldiq.android.data.api.AppSummaryDto
import com.meldiq.android.data.api.CapabilityDto
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.data.api.OrgPermissions
import com.meldiq.android.data.api.OrgStats
import com.meldiq.android.ui.screens.workspace.apps.MessageActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceExtrasTest {
    @Test
    fun offersOnlyGrantedWriteActionsOfConnectedApps() {
        val create = CapabilityDto("issues.create", "Create issues", kind = "write", granted = true)
        val apps = listOf(
            AppSummaryDto("jira", "Jira", state = "connected", capabilities = listOf(create)),
            AppSummaryDto("jira2", "Jira (not connected)", state = "installed", capabilities = listOf(create)),
            AppSummaryDto("cal", "Calendar", state = "connected", capabilities = listOf(CapabilityDto("events.create", "Create", kind = "write", granted = false))),
            AppSummaryDto("drive", "Drive", state = "connected", capabilities = listOf(CapabilityDto("files.search", "Search", granted = true))),
        )
        val actions = MessageActions.available(apps)
        assertEquals(listOf("jira" to "create_issue"), actions.map { it.appId to it.action })
    }

    @Test
    fun readsLocalTimesOnlyInTheShownFormat() {
        assertTrue(MessageActions.parseLocal("2026-10-22 15:30")!!.endsWith("Z"))
        assertNull(MessageActions.parseLocal("tomorrow at 3"))
        assertNull(MessageActions.parseLocal("2026-13-01 10:00"))
    }

    @Test
    fun setupProgressCountsOnlyWhatIsReallyDone() {
        val d = OrgDetailRes(
            OrgDto("o1", "Acme", description = "Our team"), emptyList(), emptyList(), emptyList(),
            OrgStats(members = 1, teams = 0, channels = 2), OrgPermissions(canManageOrg = true),
        )
        val steps = WorkspaceSetupRules.steps(d, installedApps = null)
        assertEquals(listOf("describe" to true, "photo" to false, "invite" to false, "channels" to true, "teams" to false), steps.map { it.key to it.done })
        assertEquals(0.4f, WorkspaceSetupRules.progress(steps), 0.001f)
        assertEquals(6, WorkspaceSetupRules.steps(d, installedApps = 0).size)
    }
}
