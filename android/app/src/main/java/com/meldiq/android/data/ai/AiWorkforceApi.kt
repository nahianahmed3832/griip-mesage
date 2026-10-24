package com.meldiq.android.data.ai

import com.meldiq.android.data.api.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URLEncoder
import java.util.UUID

/**
 * The community AI Workforce routes (server docs/AI_WORKFORCE.md §11). The server checks every permission again; the
 * app only hides what it knows this person can't do. Long work runs on the server as jobs: the app creates them,
 * shows their real state, and never waits on a screen for them to finish.
 */
class AiWorkforceApi(private val api: ApiClient) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun base(orgId: String) = "/v1/orgs/${enc(orgId)}/ai"

    suspend fun overview(orgId: String): AiOverview = api.get(base(orgId))
    suspend fun catalog(orgId: String): List<AiCatalogEntry> = api.get<AiCatalog>("${base(orgId)}/catalog").agents
    suspend fun install(orgId: String, agentId: String): AiInstall = api.post<AiInstallReq, AiAgentRes>("${base(orgId)}/agents", AiInstallReq(agentId)).agent
    suspend fun agent(orgId: String, installId: String): AiAgentDetail = api.get("${base(orgId)}/agents/${enc(installId)}")

    /** Changes an installed agent: [patch] holds only what changes (a JSON null clears a value), with its `rev`. */
    suspend fun update(orgId: String, installId: String, patch: JsonObject): AiInstall =
        api.patch<JsonObject, AiAgentRes>("${base(orgId)}/agents/${enc(installId)}", patch).agent

    suspend fun setAvatar(orgId: String, installId: String, bytes: ByteArray): AiInstall =
        api.sendBytes("PUT", "${base(orgId)}/agents/${enc(installId)}/avatar", bytes, AiAgentRes.serializer()).agent

    suspend fun uninstall(orgId: String, installId: String) { api.delete<AiOkRes>("${base(orgId)}/agents/${enc(installId)}") }

    suspend fun assign(orgId: String, installId: String, conversationId: String, activation: String): List<AiAssignment> =
        api.put<AiChannelReq, AiChannelsRes>("${base(orgId)}/agents/${enc(installId)}/channels/${enc(conversationId)}", AiChannelReq(activation)).channels

    suspend fun unassign(orgId: String, installId: String, conversationId: String): List<AiAssignment> =
        api.delete<AiChannelsRes>("${base(orgId)}/agents/${enc(installId)}/channels/${enc(conversationId)}").channels

    /** Uploads one input file (streamed from disk); [type] is the file's MIME type ([AgentRules.mimeOf]). */
    suspend fun upload(orgId: String, file: File, name: String, type: String, onProgress: (Float) -> Unit): AiFile =
        api.uploadStream("${base(orgId)}/files?name=${enc(name)}&type=${enc(type)}", file, AiFileRes.serializer(), onProgress).file

    /** Creates a job. The idempotency key makes a repeated tap (or a retried request) the same job. */
    suspend fun createJob(orgId: String, req: AiJobReq): AiJobDetail = api.post<AiJobReq, AiJobRes>("${base(orgId)}/jobs", req).job

    suspend fun jobs(orgId: String, all: Boolean, before: Long? = null): AiJobsPage =
        api.get("${base(orgId)}/jobs?scope=${if (all) "all" else "mine"}" + (before?.let { "&before=$it" } ?: ""))

    suspend fun job(orgId: String, jobId: String): AiJobDetail = api.get<AiJobRes>("${base(orgId)}/jobs/${enc(jobId)}").job
    suspend fun cancel(orgId: String, jobId: String): AiJobSummary = api.post<AiJobSummaryRes>("${base(orgId)}/jobs/${enc(jobId)}/cancel").job
    suspend fun retry(orgId: String, jobId: String): AiJobSummary = api.post<AiJobSummaryRes>("${base(orgId)}/jobs/${enc(jobId)}/retry").job
    suspend fun revise(orgId: String, jobId: String, note: String): AiJobSummary =
        api.post<AiReviseReq, AiJobSummaryRes>("${base(orgId)}/jobs/${enc(jobId)}/revise", AiReviseReq(note)).job
    suspend fun answer(orgId: String, jobId: String, text: String): AiJobSummary =
        api.post<AiAnswerReq, AiJobSummaryRes>("${base(orgId)}/jobs/${enc(jobId)}/answer", AiAnswerReq(text)).job

    suspend fun approvals(orgId: String): List<AiApproval> = api.get<AiApprovalsRes>("${base(orgId)}/approvals").approvals
    suspend fun decide(orgId: String, approvalId: String, approve: Boolean, note: String? = null): AiJobSummary =
        api.post<AiDecisionReq, AiJobSummaryRes>("${base(orgId)}/approvals/${enc(approvalId)}", AiDecisionReq(if (approve) "approved" else "rejected", note)).job

    suspend fun deliverables(orgId: String, before: Long? = null): AiFilesPage = api.get("${base(orgId)}/deliverables" + (before?.let { "?before=$it" } ?: ""))

    /** Downloads a file the person may read into [dest] (resumable); the server checks access on every request. */
    suspend fun download(orgId: String, fileId: String, dest: File, onProgress: (Float) -> Unit = {}) =
        api.downloadTo("${base(orgId)}/files/${enc(fileId)}?download=1", dest, onProgress)

    suspend fun downloadAvatar(orgId: String, installId: String, dest: File) = api.downloadTo("${base(orgId)}/agents/${enc(installId)}/avatar", dest) {}

    suspend fun channelAgents(conversationId: String): AiChannelAgentsRes = api.get("/v1/conversations/${enc(conversationId)}/ai/agents")
    suspend fun channelPosts(conversationId: String): List<AiPost> = api.get<AiPostsRes>("/v1/conversations/${enc(conversationId)}/ai/posts").posts

    companion object {
        fun idempotencyKey(): String = UUID.randomUUID().toString()
    }
}

/**
 * Live state for the AI screens: realtime frames `ai.posts { conversationId }` and `ai.job { orgId, jobId }` carry ids
 * only, so screens fetch again when their counter moves (access is decided by that fetch).
 */
class AiWorkforceSignals {
    private val _jobs = MutableStateFlow(0)
    val jobs: StateFlow<Int> = _jobs.asStateFlow()
    private val _posts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val posts: StateFlow<Map<String, Int>> = _posts.asStateFlow()

    fun jobChanged() = _jobs.update { it + 1 }
    fun postsChanged(conversationId: String) = _posts.update { it + (conversationId to ((it[conversationId] ?: 0) + 1)) }
}
