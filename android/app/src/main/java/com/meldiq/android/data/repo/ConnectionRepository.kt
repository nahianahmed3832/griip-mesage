package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ConnectReq
import com.meldiq.android.data.api.ConnectRes
import com.meldiq.android.data.api.ConnectionsRes
import com.meldiq.android.data.api.MessagingSettingsDto
import com.meldiq.android.data.api.OkRes

/** Batch B6: connections and who may message you. Message requests are answered through [ConversationRepository]. */
class ConnectionRepository(private val api: ApiClient) {
    suspend fun lists() = api.get<ConnectionsRes>("/v1/connections")

    /** Sends a request (or accepts theirs, when they asked first); returns "pending" or "accepted". */
    suspend fun connect(userId: String? = null, username: String? = null) = api.post<ConnectReq, ConnectRes>("/v1/connections", ConnectReq(userId, username)).state

    suspend fun accept(userId: String) = api.post<ConnectRes>("/v1/connections/$userId/accept").state

    /** Cancels your request, declines theirs, or removes the connection. */
    suspend fun remove(userId: String) = api.delete<OkRes>("/v1/connections/$userId")

    suspend fun messaging() = api.get<MessagingSettingsDto>("/v1/users/me/messaging")

    suspend fun setMessaging(dmPolicy: String? = null, requestPolicy: String? = null): MessagingSettingsDto {
        // Only the field being changed goes out (GriipJson leaves nulls out).
        return api.put<MessagingSettingsPatch, MessagingSettingsDto>("/v1/users/me/messaging", MessagingSettingsPatch(dmPolicy, requestPolicy))
    }

    @kotlinx.serialization.Serializable
    data class MessagingSettingsPatch(val dmPolicy: String? = null, val requestPolicy: String? = null)
}
