package com.meldiq.android.data.account

import com.meldiq.android.data.api.ApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceRepositoryTest {
    private val personal = WorkspaceDto("personal", "personal", "Personal", "OWNER")
    private val acme = WorkspaceDto("o_1", "organization", "Acme", "MEMBER", "acme")

    private class Api(var list: WorkspacesRes, var refuse: Boolean = false) : AccountApi by FakeAccountApi() {
        var selects = 0
        override suspend fun workspaces() = list
        override suspend fun selectWorkspace(id: String): String {
            selects++
            if (refuse) throw ApiException(404, "workspace_not_found", "Workspace not found")
            return id
        }
    }

    @Test
    fun `lists the personal workspace and organisations with the server's choice`() = runBlocking {
        val repo = WorkspaceRepository(Api(WorkspacesRes(listOf(personal, acme), "o_1")))
        repo.refresh()
        assertEquals("o_1", repo.state.value.selected?.id)
        assertEquals(listOf(acme), repo.state.value.organizations)
    }

    @Test
    fun `choosing the personal workspace before the list loaded still tells the server`() = runBlocking {
        val api = Api(WorkspacesRes(listOf(personal, acme), "o_1"))
        WorkspaceRepository(api).select("personal")
        assertEquals(1, api.selects)
    }

    @Test
    fun `switching shows at once and is undone when the server refuses`() = runBlocking {
        val api = Api(WorkspacesRes(listOf(personal, acme), "personal"))
        val repo = WorkspaceRepository(api)
        repo.refresh()
        repo.select("o_1")
        assertEquals("o_1", repo.state.value.selectedId)
        api.refuse = true
        api.list = WorkspacesRes(listOf(personal), "personal")
        try {
            repo.select("personal")
            fail("expected a refusal")
        } catch (e: ApiException) {
            assertEquals("workspace_not_found", e.code)
        }
        // Back to what the server has: the organisation is gone from the list.
        assertEquals("personal", repo.state.value.selectedId)
        assertEquals(listOf(personal), repo.state.value.workspaces)
    }
}
