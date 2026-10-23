package com.meldiq.android.data.repo

import android.content.Context
import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.MeRes
import com.meldiq.android.data.api.PhotoRes
import com.meldiq.android.data.api.PrivacyRes
import com.meldiq.android.data.api.UpdateMeReq
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.data.api.UserRes
import com.meldiq.android.data.model.Audience
import com.meldiq.android.data.model.PresenceRules
import com.meldiq.android.data.model.PrivacyField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The signed-in person's own profile and privacy settings, and everyone's profile photos (docs/PROFILE_AND_PRIVACY.md).
 * Photos are kept per account in its pictures folder ([ProfileStorage.picturesDir]), one file per person and revision.
 */
class ProfileRepository(
    private val context: Context,
    private val api: ApiClient,
    private val users: UserRepository,
    private val session: SessionRepository,
    accountId: String,
) {
    private val photoDir = KeptPictures.folder(context, accountId, "avatars", legacyCache = "avatars-$accountId")
    private val locks = HashMap<String, Mutex>()

    suspend fun me(): UserDto = api.get<MeRes>("/v1/users/me").user.also { users.remember(it) }

    /** Saves the edited fields; a changed username also updates this phone's session. */
    suspend fun update(req: UpdateMeReq): UserDto {
        val u = api.patch<UpdateMeReq, UserRes>("/v1/users/me", req).user
        users.remember(u)
        session.updateProfile(u.username, u.displayName)
        return u
    }

    /** Your status and notification pause as the server last said (round 3); null until loaded. */
    private val _mine = kotlinx.coroutines.flow.MutableStateFlow<UserDto?>(null)
    val mine: kotlinx.coroutines.flow.StateFlow<UserDto?> = _mine

    /** Loads your own profile for the status and pause rows (keeps the last answer when offline). */
    suspend fun refreshStatus() {
        runCatching { me() }.onSuccess { remember(it) }
    }

    /**
     * Sets or clears your status (round 3). [text] and [emoji] null with [expiresAt] null clears it; the server ends it
     * at [expiresAt]. Explicit JSON nulls, because the shared encoder leaves nulls out.
     */
    suspend fun setStatus(text: String?, emoji: String?, expiresAt: Long?): UserDto =
        patchMe(
            kotlinx.serialization.json.buildJsonObject {
                put("statusText", text?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                put("statusEmoji", emoji?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                put("statusExpiresAt", expiresAt?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            },
        )

    /** Pauses notifications on every device until [until], or resumes them (null). */
    suspend fun setNotificationsPaused(until: Long?): UserDto =
        patchMe(
            kotlinx.serialization.json.buildJsonObject {
                put("notificationsPausedUntil", until?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            },
        )

    private suspend fun patchMe(body: kotlinx.serialization.json.JsonObject): UserDto =
        api.patch<kotlinx.serialization.json.JsonObject, UserRes>("/v1/users/me", body).user.also { users.remember(it); remember(it) }

    private fun remember(u: UserDto) {
        _mine.value = u
        // The notifier reads this before showing a message (MessageNotifier.show): pausing works on this phone even
        // while it can't reach the server.
        context.getSharedPreferences(com.meldiq.android.notify.MessageNotifier.PREFS, Context.MODE_PRIVATE).edit()
            .putLong(com.meldiq.android.notify.MessageNotifier.PAUSED_UNTIL, u.notificationsPausedUntil ?: 0L).apply()
    }

    suspend fun setPrivacy(field: PrivacyField, audience: Audience): Map<String, String> =
        api.put<Map<String, String>, PrivacyRes>("/v1/users/me/privacy", mapOf(field.wire to audience.wire)).privacy

    /** Uploads a JPEG the phone has already prepared (square, at most 512 px, no metadata). */
    suspend fun setPhoto(jpeg: ByteArray): Long? = api.sendBytes("PUT", "/v1/users/me/photo", jpeg, PhotoRes.serializer()).photoRev.also { refreshMe() }

    suspend fun removePhoto() {
        api.delete<PhotoRes>("/v1/users/me/photo")
        refreshMe()
    }

    private suspend fun refreshMe() = runCatching { me() }

    /**
     * The cached photo of [userId] at [rev], downloading it once if needed; null when there is none or it isn't
     * visible to this account. Older revisions are removed.
     */
    suspend fun photo(userId: String, rev: Long): File? = cachedPhoto(userId, rev, "/v1/users/$userId/photo")

    /** An organisation's avatar at [rev] (members only), cached like people's photos (ids never clash: "o_…" vs "u_…"). */
    suspend fun orgPhoto(orgId: String, rev: Long): File? = cachedPhoto(orgId, rev, "/v1/orgs/$orgId/photo")

    /** Batch B6b: a group's photo, cached per revision like people's and communities'. */
    suspend fun groupPhoto(conversationId: String, rev: Long): File? = cachedPhoto(conversationId, rev, "/v1/conversations/$conversationId/photo")

    /**
     * Batch B6b: appear online automatically, idle, do not disturb or invisible, until [until] (null: until changed).
     * Do not disturb also pauses notifications on every device for as long (at most [PresenceRules.PAUSE_MAX_MS]);
     * leaving it resumes them.
     */
    suspend fun setPresence(mode: String, until: Long?) {
        val wasDnd = _mine.value?.presenceMode == PresenceRules.DND
        api.put<com.meldiq.android.data.api.PresenceReq, com.meldiq.android.data.api.PresenceModeRes>("/v1/users/me/presence", com.meldiq.android.data.api.PresenceReq(mode, until))
        when {
            mode == PresenceRules.DND -> setNotificationsPaused(PresenceRules.pauseUntil(until, System.currentTimeMillis()))
            wasDnd -> setNotificationsPaused(null)
            // Updates what the You screen shows (refreshMe alone leaves [mine] as it was).
            else -> runCatching { remember(me()) }
        }
    }

    private suspend fun cachedPhoto(ownerId: String, rev: Long, path: String): File? = withContext(Dispatchers.IO) {
        val safe = ownerId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(photoDir, "$safe-$rev.jpg")
        if (file.length() > 0) return@withContext file
        val lock = synchronized(locks) { locks.getOrPut(safe) { Mutex() } }
        lock.withLock {
            if (file.length() > 0) return@withLock file
            photoDir.mkdirs()
            val part = File(photoDir, "$safe-$rev.part")
            runCatching { api.downloadTo(path, part) {} }.getOrElse { part.delete(); return@withLock null }
            if (!part.renameTo(file)) return@withLock null
            photoDir.listFiles()?.filter { it.name.startsWith("$safe-") && it != file }?.forEach { it.delete() }
            file
        }
    }

    /** People this account has blocked (their messages are dropped for it). */
    suspend fun blockedUsers(federation: FederationRepository): List<UserDto> =
        federation.blocked().mapNotNull { id -> runCatching { users.fetch(id) }.getOrNull() }
}
