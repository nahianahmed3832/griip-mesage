package com.meldiq.android.ui.screens.workspace

import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.data.api.OrgPermissions
import com.meldiq.android.data.api.OrgStats
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceExtrasTest {
    @Test
    fun setupProgressCountsOnlyWhatIsReallyDone() {
        val d = OrgDetailRes(
            OrgDto("o1", "Night Sky Club", description = "Astronomy", rules = "Be kind"),
            OrgStats(members = 1, channels = 2), OrgPermissions(canManageOrg = true),
        )
        val steps = WorkspaceSetupRules.steps(d)
        assertEquals(listOf("describe" to true, "photo" to false, "rules" to true, "invite" to false, "channels" to true), steps.map { it.key to it.done })
        assertEquals(0.6f, WorkspaceSetupRules.progress(steps), 0.001f)
    }
}
