package com.meldiq.android.ui.screens.workspace

import com.meldiq.android.data.api.AppDetailRes
import com.meldiq.android.data.api.AppSummaryDto
import com.meldiq.android.data.api.CapabilityDto
import com.meldiq.android.data.api.ConnectionDto
import com.meldiq.android.data.api.DirectoryRes
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.ResourceDto
import com.meldiq.android.data.api.RolesRes
import com.meldiq.android.ui.screens.workspace.apps.AppRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRulesTest {
    private fun app(id: String, state: String, actions: List<String> = emptyList(), featured: Boolean = false, category: String = "files", caps: List<CapabilityDto> = emptyList(), account: ConnectionDto? = null) =
        AppSummaryDto(id, id.replaceFirstChar { it.uppercase() }, category = category, state = state, actions = actions, featured = featured, capabilities = caps, myAccount = account, installState = if (state in setOf("installed", "connected", "error")) "installed" else null)

    @Test
    fun theDirectoryLeadsWithEssentialAppsAndNeverListsPlannedOnes() {
        val apps = listOf(
            app("github", "available").copy(tier = "essential"),
            app("zoom", "available", featured = true).copy(tier = "optional"),
            app("drive", "connected").copy(tier = "essential"),
            app("gmail", "coming_soon").copy(tier = "planned", availability = "coming_soon"),
        )
        val (recommended, more) = AppRules.recommended(apps)
        assertEquals(listOf("drive", "github"), recommended.map { it.id })
        assertEquals(listOf("zoom"), more.map { it.id })
        // An older server sends no tier: its featured apps lead instead.
        val (oldRecommended, oldMore) = AppRules.recommended(listOf(app("a", "available", featured = true), app("b", "available")))
        assertEquals(listOf("a"), oldRecommended.map { it.id })
        assertEquals(listOf("b"), oldMore.map { it.id })
    }

    @Test
    fun picksTheOneMostUsefulAction() {
        assertEquals("approve", AppRules.primaryAction(listOf("decline", "approve")))
        assertEquals("install", AppRules.primaryAction(listOf("install")))
        assertEquals("reconnect", AppRules.primaryAction(listOf("disconnect", "reconnect", "configure")))
        assertEquals("configure", AppRules.primaryAction(listOf("disconnect", "configure", "uninstall")))
        assertNull(AppRules.primaryAction(listOf("disconnect", "uninstall")))
        assertNull(AppRules.primaryAction(emptyList()))
    }

    @Test
    fun sortsUsableAppsFirstAndPlannedOnesLast() {
        val list = listOf(app("soon", "coming_soon"), app("drive", "connected"), app("jira", "available", featured = true), app("cal", "needs_configuration"), app("hub", "installed"))
        assertEquals(listOf("drive", "hub", "jira", "cal", "soon"), AppRules.filter(list, "", null).map { it.id })
        assertEquals(listOf("drive"), AppRules.filter(list, "DRI", null).map { it.id })
        assertEquals(emptyList<String>(), AppRules.filter(list, "", "crm").map { it.id })
    }

    @Test
    fun sharesOnlyFromConnectedAppsWithAGrantedReadCapability() {
        val read = CapabilityDto("files.search", "Search", granted = true)
        val notify = CapabilityDto("notify.channels", "Notify", kind = "notify", accountType = "workspace", granted = true)
        val list = listOf(
            app("a", "connected", caps = listOf(read)),
            app("b", "connected", caps = listOf(read.copy(granted = false))),
            app("c", "installed", caps = listOf(read)),
            app("d", "connected", caps = listOf(notify)),
        )
        assertEquals(listOf("a"), AppRules.shareable(list).map { it.id })
    }

    @Test
    fun buildsALinkMessageWithACardOnlyForHttps() {
        val r = ResourceDto("1", "document", "Q4 plan", url = "https://docs.google.com/document/d/abc/edit")
        val (text, card) = AppRules.linkMessage(r, "Google Drive", "Document · Pat")!!
        assertEquals("Q4 plan\nhttps://docs.google.com/document/d/abc/edit", text)
        assertEquals("Google Drive", card!!.siteName)
        assertEquals("Document · Pat", card.description)
        assertNull(AppRules.linkMessage(r.copy(url = null), "Drive", "")) // nothing to share
        assertNull(AppRules.linkMessage(r.copy(url = "javascript:alert(1)"), "Drive", ""))
        assertNull(AppRules.linkMessage(r.copy(url = "http://example.test/x"), "Drive", "")!!.second) // text only, no card
    }

    @Test
    fun describesResourcesWithWhatExists() {
        val r = ResourceDto("1", "issue", "ENG-7 Crash", status = "In Progress", detail = "Pat", container = "Engineering", modifiedAt = 5)
        assertEquals("Issue · In Progress · Pat · Engineering · t5", AppRules.subtitle(r, "Issue", { "${it}B" }, { "t$it" }))
    }

    @Test
    fun readsSignInOutcomes() {
        assertEquals(AppRules.FlowOutcome.Waiting, AppRules.outcome("pending", null))
        assertEquals(AppRules.FlowOutcome.Connected, AppRules.outcome("connected", null))
        assertEquals(AppRules.FlowOutcome.Denied, AppRules.outcome("denied", null))
        assertEquals(AppRules.FlowOutcome.Expired, AppRules.outcome("expired", null))
        assertEquals(AppRules.FlowOutcome.Failed("x"), AppRules.outcome("failed", "x"))
    }

    @Test
    fun keepsAskingUntilTheAppFinishesAnApprovedSignIn() {
        assertEquals(AppRules.FlowOutcome.Approved, AppRules.outcome("approved", null))
        assertTrue(AppRules.stillOpen(AppRules.outcome("approved", null)))
        assertTrue(AppRules.stillOpen(AppRules.outcome("pending", null)))
        assertFalse(AppRules.stillOpen(AppRules.outcome("connected", null)))
        assertEquals(AppRules.FlowOutcome.ConnectedPartly("Some features won't work"), AppRules.outcome("connected", null, "Some features won't work"))
        assertEquals(AppRules.FlowOutcome.Connected, AppRules.outcome("connected", null, " "))
    }

    @Test
    fun opensOnlySafeSignInLinks() {
        assertTrue(AppRules.safeBeginUrl("https://msg.example.com/v1/integrations/oauth/begin/f?t=x", "https://msg.example.com"))
        assertTrue(AppRules.safeBeginUrl("http://localhost:18097/v1/integrations/oauth/begin/f?t=x", "http://localhost:18097"))
        assertFalse(AppRules.safeBeginUrl("http://evil.example/begin", "http://localhost:18097"))
        assertFalse(AppRules.safeBeginUrl("intent://x", "https://msg.example.com"))
    }

    @Test
    fun asksTheServerOnlyAboutKnownProviderLinks() {
        assertTrue(AppRules.mightUnfurl("https://docs.google.com/spreadsheets/d/abc/edit"))
        assertTrue(AppRules.mightUnfurl("https://meldiq.atlassian.net/browse/ENG-1"))
        assertTrue(AppRules.mightUnfurl("https://contoso.sharepoint.com/:x:/s/f/abc"))
        assertFalse(AppRules.mightUnfurl("https://example.com/private-doc"))
        assertFalse(AppRules.mightUnfurl("http://github.com/a/b"))
        assertFalse(AppRules.mightUnfurl("https://evil-github.com.example/x"))
    }

    @Test
    fun decodesTheServersAnswers() {
        val dir = GriipJson.decodeFromString(
            DirectoryRes.serializer(),
            """{"apps":[{"id":"google-drive","name":"Google Drive","state":"needs_configuration","actions":[],"capabilities":[{"key":"files.search","label":"Search","kind":"read","accountType":"member","scopes":["openid"],"default":true,"granted":false,"requested":false}],"futureField":1}],"policy":{"blockUnlisted":false,"allowedConnectors":[],"requestsEnabled":true,"memberAccounts":true},"permissions":{"canInstall":true}}""",
        )
        assertEquals("needs_configuration", dir.apps.single().state)
        assertTrue(dir.permissions.canInstall)
        val detail = GriipJson.decodeFromString(
            AppDetailRes.serializer(),
            """{"app":{"id":"github","name":"GitHub","supports":{"search":true,"workspaceLink":true},"eventTypes":[{"key":"pull_request.opened","label":"PR opened"}]},"state":"installed","actions":["connect"],"install":{"id":"ins_1","state":"installed","rev":3,"grantedCapabilities":["issues.search"],"config":{"access":{"mode":"roles","roleIds":["rl_1"]}}},"workspaceConnections":[]}""",
        )
        assertEquals("roles", detail.install!!.config.access.mode)
        assertTrue(detail.app.supports.workspaceLink)
        val roles = GriipJson.decodeFromString(
            RolesRes.serializer(),
            """{"catalog":[{"key":"roles.view","category":"roles","label":"View roles"}],"baseRoles":[{"key":"OWNER","name":"Owner","protected":true}],"roles":[],"me":{"base":"ADMIN","permissions":["roles.view"],"canManage":true}}""",
        )
        assertTrue(roles.baseRoles.single().isProtected)
        assertTrue(roles.me.canManage)
    }
}
