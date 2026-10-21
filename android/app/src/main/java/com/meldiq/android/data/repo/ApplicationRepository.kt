package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ApplicationRes
import com.meldiq.android.data.api.ApplicationsRes
import com.meldiq.android.data.api.ApplyReq
import com.meldiq.android.data.api.ApplyRes
import com.meldiq.android.data.api.DecideApplicationReq
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.MyApplicationsRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.data.api.QuestionsRes
import com.meldiq.android.data.api.SecurityRes
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer

/** Batch B4b: join applications (questions, applying, the moderators' queue) and the activity alerts switch. */
class ApplicationRepository(private val api: ApiClient) {
    /** Turns applications on with [questions], changes them, or turns them off (null). */
    suspend fun setQuestions(orgId: String, questions: List<String>?): QuestionsRes {
        // GriipJson leaves nulls out, and null is what turns applications off, so the body is written here.
        val list = questions?.let { GriipJson.encodeToString(ListSerializer(String.serializer()), it) } ?: "null"
        return api.request("PUT", "/v1/orgs/$orgId/application", "{\"questions\":$list}", serializer<QuestionsRes>())
    }

    suspend fun apply(communityId: String, answers: List<String>) = api.post<ApplyReq, ApplyRes>("/v1/communities/$communityId/apply", ApplyReq(answers))

    /** Pending (or answered) applications, for people with `members.approve`. */
    suspend fun list(orgId: String, closed: Boolean) = api.get<ApplicationsRes>("/v1/orgs/$orgId/applications?status=${if (closed) "closed" else "pending"}")

    suspend fun decide(id: String, approve: Boolean, note: String?) =
        api.patch<DecideApplicationReq, ApplicationRes>("/v1/applications/$id", DecideApplicationReq(if (approve) "approved" else "denied", note)).application

    suspend fun mine() = api.get<MyApplicationsRes>("/v1/me/applications").applications

    suspend fun withdraw(id: String) = api.delete<OkRes>("/v1/applications/$id")

    /** Turns join-spike alerts for moderators on or off. */
    suspend fun setActivityAlerts(orgId: String, on: Boolean) =
        api.request("PUT", "/v1/orgs/$orgId/security", "{\"activityAlerts\":$on}", serializer<SecurityRes>())
}
