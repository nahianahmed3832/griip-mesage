package com.meldiq.android.data.account

import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.OkRes
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** GET /v2/account/recovery-codes: how many codes of the current set are left (never the codes themselves). */
@Serializable
data class RecoveryCodesStatus(val remaining: Int, val total: Int, val createdAt: Long? = null)

/** A new set, shown once (POST /v2/account/recovery-codes). */
@Serializable
data class RecoveryCodesRes(val codes: List<String>, val createdAt: Long)

@Serializable
data class RecoveryPasswordReq(val password: String)

@Serializable
data class RecoveryStartReq(val email: String, val code: String, val newEmail: String)

@Serializable
data class RecoveryStartRes(val ticket: String, val email: String, val expiresAt: Long)

@Serializable
data class RecoveryCompleteReq(val ticket: String, val code: String, val newPassword: String)

@Serializable
data class RecoveryCompleteRes(val email: String, val remainingCodes: Int)

/** Shapes of what people type for account recovery (docs/ACCOUNT_RECOVERY.md). Unit-tested in `RecoveryRulesTest`. */
object RecoveryRules {
    /** Characters per code; codes are shown as three groups of four. */
    const val CODE_LENGTH = 12
    private val ALPHABET = Regex("[A-Z0-9]")

    /** Upper case, letters and digits only (dashes, spaces and case are ignored, like the server does). */
    fun normalize(code: String): String = code.uppercase().filter { ALPHABET.matches(it.toString()) }

    fun isCode(code: String): Boolean = normalize(code).length == CODE_LENGTH

    /** What the field shows while typing: "ABCD-EFGH-JKLM" (at most one code's worth). */
    fun display(code: String): String = normalize(code).take(CODE_LENGTH).chunked(4).joinToString("-")

    /** Cursor positions between the raw code (no dashes) and its [display] form, for the field's visual grouping. */
    fun toDisplayOffset(rawOffset: Int): Int = if (rawOffset <= 0) 0 else rawOffset + (rawOffset - 1) / 4

    fun toRawOffset(displayOffset: Int, rawLength: Int): Int = (displayOffset - displayOffset / 5).coerceIn(0, rawLength)
}

/**
 * Account recovery codes (docs/ACCOUNT_RECOVERY.md): making, counting and revoking them while signed in, and the two
 * recovery steps before sign-in. Separate from the encryption recovery key: these restore access to the account,
 * not encrypted history.
 */
class RecoveryCodesApi(
    private val authed: ApiClient,
    private val setupClient: (String) -> ApiClient = { url -> ApiClient({ url }, { null }, { false }) },
) {
    suspend fun status(): RecoveryCodesStatus = authed.get("/v2/account/recovery-codes")

    suspend fun generate(password: String): RecoveryCodesRes = authed.post("/v2/account/recovery-codes", RecoveryPasswordReq(password))

    suspend fun revoke(password: String) {
        authed.post<RecoveryPasswordReq, OkRes>("/v2/account/recovery-codes/revoke", RecoveryPasswordReq(password))
    }

    suspend fun start(serverUrl: String, email: String, code: String, newEmail: String): RecoveryStartRes =
        setupClient(serverUrl).request(
            "POST", "/v2/auth/recover/start",
            GriipJson.encodeToString(serializer(), RecoveryStartReq(email, RecoveryRules.normalize(code), newEmail)), serializer<RecoveryStartRes>(), auth = false,
        )

    suspend fun complete(serverUrl: String, ticket: String, code: String, newPassword: String): RecoveryCompleteRes =
        setupClient(serverUrl).request(
            "POST", "/v2/auth/recover/complete",
            GriipJson.encodeToString(serializer(), RecoveryCompleteReq(ticket, code, newPassword)), serializer<RecoveryCompleteRes>(), auth = false,
        )
}
