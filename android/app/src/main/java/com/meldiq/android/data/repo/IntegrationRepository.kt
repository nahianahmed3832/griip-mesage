package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ActionReq
import com.meldiq.android.data.api.AppActivityRes
import com.meldiq.android.data.api.AppDetailRes
import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.FinishFlowReq
import com.meldiq.android.data.api.AppAccess
import com.meldiq.android.data.api.AppPolicyDto
import com.meldiq.android.data.api.ChannelAppEventsRes
import com.meldiq.android.data.api.ConfigureAppReq
import com.meldiq.android.data.api.ConnectStartRes
import com.meldiq.android.data.api.CreateSubscriptionReq
import com.meldiq.android.data.api.DirectoryRes
import com.meldiq.android.data.api.FlowStatusRes
import com.meldiq.android.data.api.InstallReq
import com.meldiq.android.data.api.LinkReq
import com.meldiq.android.data.api.LinkTargetsRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.data.api.PolicyRes
import com.meldiq.android.data.api.PutPolicyReq
import com.meldiq.android.data.api.RequestAppReq
import com.meldiq.android.data.api.ResourceDetailsRes
import com.meldiq.android.data.api.ResourcePage
import com.meldiq.android.data.api.ResourceRes
import com.meldiq.android.data.api.SubscriptionRes
import com.meldiq.android.data.api.SubscriptionsRes
import com.meldiq.android.data.api.TestEventReq
import com.meldiq.android.data.api.UnfurlReq
import com.meldiq.android.data.api.UnfurlRes
import com.meldiq.android.data.api.WatchablePage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.net.URLEncoder

/**
 * The app directory and connected apps (docs/INTEGRATIONS.md). The phone never sees a provider token or password: it
 * opens the server's sign-in link in the person's browser (a Custom Tab), and the provider sends the browser back to the
 * server, which keeps the connection. The app then asks the server how the sign-in ended ([flowStatus]).
 */
class IntegrationRepository(private val api: ApiClient) {
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun base(orgId: String, appId: String) = "/v1/orgs/${enc(orgId)}/integrations/${enc(appId)}"

    /** The browser came back (or the person closed it): screens re-check the flow. */
    private val _returned = MutableSharedFlow<String?>(extraBufferCapacity = 4)
    val returned: SharedFlow<String?> = _returned

    /**
     * Back from the browser with griip://integrations?flow=…&finish=…: the server connects the account only when the
     * person who started the sign-in finishes it from their own signed-in app, so a sign-in link forwarded to someone
     * else can't attach their account. Returns false when the sign-in belongs to another Meldiq account (nothing is
     * connected); then screens re-check.
     */
    suspend fun browserReturned(flowId: String?, finishCode: String?): Boolean {
        val flow = flowId?.takeIf { FLOW_ID.matches(it) }
        val code = finishCode?.takeIf { FINISH_CODE.matches(it) }
        var mine = true
        if (flow != null && code != null) {
            try {
                finishFlow(flow, code)
            } catch (e: ApiException) {
                // 404: another Meldiq account's sign-in. Anything else (offline, expired): the screen shows how it ended.
                mine = e.status != HTTP_NOT_FOUND
            }
        }
        _returned.tryEmit(flow)
        return mine
    }

    suspend fun finishFlow(flowId: String, code: String): FlowStatusRes = api.post("/v1/integrations/flows/${enc(flowId)}/finish", FinishFlowReq(code))

    suspend fun directory(orgId: String): DirectoryRes = api.get("/v1/orgs/${enc(orgId)}/integrations")
    suspend fun detail(orgId: String, appId: String): AppDetailRes = api.get(base(orgId, appId))
    suspend fun install(orgId: String, appId: String, capabilities: List<String>? = null): AppDetailRes = api.post(base(orgId, appId) + "/install", InstallReq(capabilities))
    suspend fun request(orgId: String, appId: String, note: String?): AppDetailRes = api.post(base(orgId, appId) + "/request", RequestAppReq(note?.trim()?.ifEmpty { null }))
    suspend fun decline(orgId: String, appId: String): AppDetailRes = api.post(base(orgId, appId) + "/decline")
    suspend fun configure(orgId: String, appId: String, baseRev: Int, granted: List<String>? = null, access: AppAccess? = null, enabled: Boolean? = null): AppDetailRes =
        api.patch(base(orgId, appId), ConfigureAppReq(baseRev, granted, access, enabled))
    suspend fun uninstall(orgId: String, appId: String) = api.delete<OkRes>(base(orgId, appId))

    suspend fun startConnect(orgId: String, appId: String): ConnectStartRes = api.post(base(orgId, appId) + "/connect")
    suspend fun flowStatus(flowId: String): FlowStatusRes = api.get("/v1/integrations/flows/${enc(flowId)}")
    suspend fun disconnect(orgId: String, accountId: String) = api.delete<OkRes>("/v1/orgs/${enc(orgId)}/integration-accounts/${enc(accountId)}")

    suspend fun search(orgId: String, appId: String, q: String, cursor: String? = null, filter: String? = null): ResourcePage =
        api.get(base(orgId, appId) + "/search?q=${enc(q)}" + (cursor?.let { "&cursor=${enc(it)}" } ?: "") + (filter?.let { "&filter=${enc(it)}" } ?: ""))
    suspend fun browse(orgId: String, appId: String, container: String?, cursor: String? = null): ResourcePage =
        api.get(base(orgId, appId) + "/browse?" + listOfNotNull(container?.let { "container=${enc(it)}" }, cursor?.let { "cursor=${enc(it)}" }).joinToString("&"))
    suspend fun item(orgId: String, appId: String, id: String) = api.get<ResourceRes>(base(orgId, appId) + "/item?id=${enc(id)}").resource
    suspend fun details(orgId: String, appId: String, id: String): ResourceDetailsRes = api.get(base(orgId, appId) + "/details?id=${enc(id)}")
    suspend fun unfurl(orgId: String, url: String): UnfurlRes = api.post("/v1/orgs/${enc(orgId)}/integrations/unfurl", UnfurlReq(url))

    /** Recent link previews (10 minutes, at most [UNFURL_CACHE] links), so scrolling a conversation doesn't repeat calls. */
    private val unfurls = object : LinkedHashMap<String, Pair<Long, UnfurlRes>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, UnfurlRes>>?) = size > UNFURL_CACHE
    }

    suspend fun unfurlCached(orgId: String, url: String): UnfurlRes? {
        val key = "$orgId|$url"
        synchronized(unfurls) { unfurls[key]?.takeIf { System.currentTimeMillis() - it.first < UNFURL_TTL_MS }?.let { return it.second } }
        val res = runCatching { unfurl(orgId, url) }.getOrNull() ?: return null
        synchronized(unfurls) { unfurls[key] = System.currentTimeMillis() to res }
        return res
    }
    suspend fun action(orgId: String, appId: String, action: String, resourceId: String?, fields: Map<String, String>) =
        api.post<ActionReq, ResourceRes>(base(orgId, appId) + "/actions/${enc(action)}", ActionReq(resourceId, fields)).resource

    suspend fun linkTargets(orgId: String, appId: String): LinkTargetsRes = api.get(base(orgId, appId) + "/link-targets")
    suspend fun link(orgId: String, appId: String, targetId: String) = api.post<LinkReq, kotlinx.serialization.json.JsonObject>(base(orgId, appId) + "/link", LinkReq(targetId))
    suspend fun watchable(orgId: String, appId: String, cursor: String? = null): WatchablePage = api.get(base(orgId, appId) + "/watchable" + (cursor?.let { "?cursor=${enc(it)}" } ?: ""))
    suspend fun subscriptions(orgId: String, appId: String) = api.get<SubscriptionsRes>(base(orgId, appId) + "/subscriptions").subscriptions
    suspend fun subscribe(orgId: String, appId: String, conversationId: String, resource: String, events: List<String>) =
        api.post<CreateSubscriptionReq, SubscriptionRes>(base(orgId, appId) + "/subscriptions", CreateSubscriptionReq(conversationId, resource, events)).subscription
    suspend fun unsubscribe(orgId: String, subscriptionId: String) = api.delete<OkRes>("/v1/orgs/${enc(orgId)}/integration-subscriptions/${enc(subscriptionId)}")
    suspend fun testEvent(orgId: String, appId: String, resource: String?) = api.post<TestEventReq, kotlinx.serialization.json.JsonObject>(base(orgId, appId) + "/test-event", TestEventReq(resource))

    suspend fun activity(orgId: String): AppActivityRes = api.get("/v1/orgs/${enc(orgId)}/integrations/activity")
    suspend fun policy(orgId: String): PolicyRes = api.get("/v1/orgs/${enc(orgId)}/integrations/policy")
    suspend fun putPolicy(orgId: String, baseRev: Int, policy: AppPolicyDto): PolicyRes = api.put("/v1/orgs/${enc(orgId)}/integrations/policy", PutPolicyReq(baseRev, policy))

    suspend fun workflows(orgId: String) = api.get<com.meldiq.android.data.api.WorkflowsRes>("/v1/orgs/${enc(orgId)}/integrations/workflows").workflows
    suspend fun hooks(orgId: String) = api.get<com.meldiq.android.data.api.HooksRes>("/v1/orgs/${enc(orgId)}/integration-hooks").hooks
    suspend fun createHook(orgId: String, conversationId: String, name: String): com.meldiq.android.data.api.HookCreatedRes =
        api.post("/v1/orgs/${enc(orgId)}/integration-hooks", com.meldiq.android.data.api.CreateHookReq(conversationId, name.trim()))
    suspend fun rotateHook(orgId: String, hookId: String): com.meldiq.android.data.api.HookCreatedRes = api.post("/v1/orgs/${enc(orgId)}/integration-hooks/${enc(hookId)}/rotate")
    suspend fun deleteHook(orgId: String, hookId: String) = api.delete<OkRes>("/v1/orgs/${enc(orgId)}/integration-hooks/${enc(hookId)}")
    /** One search across every app this person connected here (each with their own access). */
    suspend fun searchAll(orgId: String, q: String): com.meldiq.android.data.api.SearchAllRes = api.get("/v1/orgs/${enc(orgId)}/integrations/search?q=${enc(q)}")
    suspend fun plan(orgId: String): com.meldiq.android.data.api.PlanRes = api.get("/v1/orgs/${enc(orgId)}/plan")

    /** A channel's app notifications after [after] (epoch ms), for its members only. */
    suspend fun channelEvents(conversationId: String, after: Long = 0) = api.get<ChannelAppEventsRes>("/v1/conversations/${enc(conversationId)}/integration-events?after=$after&limit=50").events

    companion object {
        private val FLOW_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val FINISH_CODE = Regex("^[A-Za-z0-9_-]{20,64}$")
        private const val HTTP_NOT_FOUND = 404
        private const val UNFURL_CACHE = 200
        private const val UNFURL_TTL_MS = 10 * 60_000L
    }
}
