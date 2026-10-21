package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ReportReq
import com.meldiq.android.data.api.ReportRes
import com.meldiq.android.data.api.ReportSentRes
import com.meldiq.android.data.api.ReportStatusReq
import com.meldiq.android.data.api.ReportsRes
import com.meldiq.android.data.api.SecurityReq
import com.meldiq.android.data.api.SecurityRes

/** Batch B4: reports (sending; the moderators' queue) and a community's Security actions. */
class ReportRepository(private val api: ApiClient) {
    suspend fun send(req: ReportReq) = api.post<ReportReq, ReportSentRes>("/v1/reports", req).report

    /** A community's open (or closed) reports, for people with `reports.view`. */
    suspend fun list(orgId: String, closed: Boolean, before: Long? = null): ReportsRes =
        api.get("/v1/orgs/$orgId/reports?status=${if (closed) "closed" else "open"}" + (before?.let { "&before=$it" } ?: ""))

    suspend fun setStatus(id: String, status: String, note: String? = null) = api.patch<ReportStatusReq, ReportRes>("/v1/reports/$id", ReportStatusReq(status, note)).report

    /** Pauses or resumes joining ([invitesUntil]) and new DMs between members ([dmsUntil]). */
    suspend fun security(orgId: String, invitesUntil: Long?, dmsUntil: Long?, setInvites: Boolean, setDms: Boolean): SecurityRes {
        // Only the fields being changed go out, and null resumes; GriipJson leaves nulls out, so the body is written here.
        val body = buildString {
            append('{')
            if (setInvites) append("\"invitesPausedUntil\":").append(invitesUntil?.toString() ?: "null")
            if (setInvites && setDms) append(',')
            if (setDms) append("\"dmsPausedUntil\":").append(dmsUntil?.toString() ?: "null")
            append('}')
        }
        return api.request("PUT", "/v1/orgs/$orgId/security", body, kotlinx.serialization.serializer<SecurityRes>())
    }
}
