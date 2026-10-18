package com.griipbuddy.orbit.data.account

import com.griipbuddy.orbit.data.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The workspaces the account can switch between, and which one is open. */
data class WorkspaceState(
    val workspaces: List<WorkspaceDto> = emptyList(),
    val selectedId: String = WorkspaceDto.PERSONAL_ID,
    val loading: Boolean = false,
    val error: String? = null,
    /** False until the server's list arrived: until then [selectedId] is only a default, not the server's choice. */
    val loaded: Boolean = false,
) {
    val selected: WorkspaceDto? get() = workspaces.firstOrNull { it.id == selectedId }
    val organizations: List<WorkspaceDto> get() = workspaces.filterNot { it.isPersonal }
}

/**
 * The personal workspace plus the account's organisations (docs/ORBIT_2_ARCHITECTURE §4). The server remembers which one
 * is open, so the account's other devices open the same one. Switching is shown at once and undone if the server refuses
 * (for example after being removed from the organisation). Choosing a workspace grants nothing: every request is still
 * checked by the server. Unit-tested in `WorkspaceRepositoryTest`.
 */
class WorkspaceRepository(private val api: AccountApi) {
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    suspend fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val res = api.workspaces()
            _state.value = WorkspaceState(res.workspaces, res.selectedWorkspaceId, loaded = true)
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.message) }
            throw e
        }
    }

    suspend fun select(id: String) {
        val before = _state.value
        if (before.loaded && before.selectedId == id) return
        _state.update { it.copy(selectedId = id, error = null) }
        try {
            api.selectWorkspace(id)
        } catch (e: ApiException) {
            _state.update { it.copy(selectedId = before.selectedId, error = e.message) }
            // The organisation is gone or the person was removed: show the list as the server has it now.
            if (e.code == "workspace_not_found") runCatching { refresh() }
            throw e
        }
    }

    fun clear() {
        _state.value = WorkspaceState()
    }
}
