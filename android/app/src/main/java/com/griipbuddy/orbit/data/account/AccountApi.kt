package com.griipbuddy.orbit.data.account

import com.griipbuddy.orbit.data.api.ApiClient
import com.griipbuddy.orbit.data.api.ChallengeRes
import com.griipbuddy.orbit.data.api.GriipJson
import com.griipbuddy.orbit.data.api.OkRes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * The Orbit 2.0 account API (docs/ORBIT_2_ARCHITECTURE §6). Calls that happen before sign-in take the server's address;
 * the others go through the profile's authenticated client, which renews the access token on a 401 and retries once.
 * An interface so the account logic can be tested with a fake.
 */
interface AccountApi {
    suspend fun register(serverUrl: String, req: RegisterReq): VerificationSentRes
    suspend fun verifyEmail(serverUrl: String, email: String, code: String, password: String)
    suspend fun resendVerification(serverUrl: String, email: String)
    suspend fun login(serverUrl: String, req: AccountLoginReq): AccountSessionRes
    suspend fun refresh(serverUrl: String, refreshToken: String): AccountSessionRes
    suspend fun forgotPassword(serverUrl: String, email: String)
    suspend fun resetPassword(serverUrl: String, req: ResetPasswordReq)
    suspend fun challenge(serverUrl: String): String
    suspend fun legacyVerify(serverUrl: String, req: LegacyVerifyReq): LegacyVerifyRes
    suspend fun legacyComplete(serverUrl: String, req: LegacyCompleteReq): VerificationSentRes

    suspend fun logout()
    suspend fun me(): AccountMeRes
    suspend fun updateName(name: String): AccountDto
    suspend fun changePassword(req: ChangePasswordReq)
    suspend fun sessions(): List<SessionListItem>
    suspend fun revokeSession(id: String)
    suspend fun enroll(req: EnrollDeviceReq): EnrollRes
    suspend fun resetIdentity(req: ResetIdentityReq): EnrollRes
    suspend fun workspaces(): WorkspacesRes
    suspend fun selectWorkspace(id: String): String
}

class HttpAccountApi(
    private val authed: ApiClient,
    private val setupClient: (String) -> ApiClient = { url -> ApiClient({ url }, { null }, { false }) },
) : AccountApi {

    private suspend fun <B, T> open(serverUrl: String, path: String, body: B, bodySer: KSerializer<B>, resSer: KSerializer<T>): T =
        setupClient(serverUrl).request("POST", path, GriipJson.encodeToString(bodySer, body), resSer, auth = false)

    override suspend fun register(serverUrl: String, req: RegisterReq): VerificationSentRes =
        open(serverUrl, "/v2/auth/register", req, serializer(), serializer())

    override suspend fun verifyEmail(serverUrl: String, email: String, code: String, password: String) {
        open(serverUrl, "/v2/auth/email/verify", VerifyEmailReq(email, code, password), serializer(), serializer<Map<String, Boolean>>())
    }

    override suspend fun resendVerification(serverUrl: String, email: String) {
        open(serverUrl, "/v2/auth/email/resend", EmailReq(email), serializer(), serializer<VerificationSentRes>())
    }

    override suspend fun login(serverUrl: String, req: AccountLoginReq): AccountSessionRes = open(serverUrl, "/v2/auth/login", req, serializer(), serializer())

    override suspend fun refresh(serverUrl: String, refreshToken: String): AccountSessionRes =
        open(serverUrl, "/v2/auth/refresh", RefreshReq(refreshToken), serializer(), serializer())

    override suspend fun forgotPassword(serverUrl: String, email: String) {
        open(serverUrl, "/v2/auth/password/forgot", EmailReq(email), serializer(), serializer<VerificationSentRes>())
    }

    override suspend fun resetPassword(serverUrl: String, req: ResetPasswordReq) {
        open(serverUrl, "/v2/auth/password/reset", req, serializer(), serializer<OkRes>())
    }

    override suspend fun challenge(serverUrl: String): String =
        setupClient(serverUrl).request("POST", "/v1/auth/challenge", "{}", ChallengeRes.serializer(), auth = false).nonce

    override suspend fun legacyVerify(serverUrl: String, req: LegacyVerifyReq): LegacyVerifyRes =
        open(serverUrl, "/v2/migration/legacy/verify", req, serializer(), serializer())

    override suspend fun legacyComplete(serverUrl: String, req: LegacyCompleteReq): VerificationSentRes =
        open(serverUrl, "/v2/migration/legacy/complete", req, serializer(), serializer())

    override suspend fun logout() {
        authed.post<OkRes>("/v2/auth/logout")
    }

    override suspend fun me(): AccountMeRes = authed.get("/v2/account")

    override suspend fun updateName(name: String): AccountDto = authed.patch<UpdateAccountReq, AccountUpdateRes>("/v2/account", UpdateAccountReq(name)).account

    override suspend fun changePassword(req: ChangePasswordReq) {
        authed.post<ChangePasswordReq, OkRes>("/v2/account/password", req)
    }

    override suspend fun sessions(): List<SessionListItem> = authed.get<SessionListRes>("/v2/account/sessions").sessions

    override suspend fun revokeSession(id: String) {
        authed.delete<OkRes>("/v2/account/sessions/${java.net.URLEncoder.encode(id, "UTF-8")}")
    }

    override suspend fun enroll(req: EnrollDeviceReq): EnrollRes = authed.post("/v2/account/devices", req)

    override suspend fun resetIdentity(req: ResetIdentityReq): EnrollRes = authed.post("/v2/account/identity/reset", req)

    override suspend fun workspaces(): WorkspacesRes = authed.get("/v2/workspaces")

    override suspend fun selectWorkspace(id: String): String =
        authed.put<PreferencesReq, SelectedWorkspaceRes>("/v2/account/preferences", PreferencesReq(id)).selectedWorkspaceId
}
