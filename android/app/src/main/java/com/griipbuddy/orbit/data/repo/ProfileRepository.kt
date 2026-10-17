package com.griipbuddy.orbit.data.repo

import android.content.Context
import com.griipbuddy.orbit.data.api.ApiClient
import com.griipbuddy.orbit.data.api.MeRes
import com.griipbuddy.orbit.data.api.PhotoRes
import com.griipbuddy.orbit.data.api.PrivacyRes
import com.griipbuddy.orbit.data.api.UpdateMeReq
import com.griipbuddy.orbit.data.api.UserDto
import com.griipbuddy.orbit.data.api.UserRes
import com.griipbuddy.orbit.data.model.Audience
import com.griipbuddy.orbit.data.model.PrivacyField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The signed-in person's own profile and privacy settings, and everyone's profile photos (docs/PROFILE_AND_PRIVACY.md).
 * Photos are cached per account in its own cache folder, one file per person and revision.
 */
class ProfileRepository(
    private val context: Context,
    private val api: ApiClient,
    private val users: UserRepository,
    private val session: SessionRepository,
    accountId: String,
) {
    private val photoDir = File(context.cacheDir, "avatars-$accountId").apply { mkdirs() }
    private val locks = HashMap<String, Mutex>()

    suspend fun me(): UserDto = api.get<MeRes>("/v1/users/me").user.also { users.remember(it) }

    /** Saves the edited fields; a changed username also updates this phone's session. */
    suspend fun update(req: UpdateMeReq): UserDto {
        val u = api.patch<UpdateMeReq, UserRes>("/v1/users/me", req).user
        users.remember(u)
        session.updateProfile(u.username, u.displayName)
        return u
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
    suspend fun photo(userId: String, rev: Long): File? = withContext(Dispatchers.IO) {
        val safe = userId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(photoDir, "$safe-$rev.jpg")
        if (file.length() > 0) return@withContext file
        val lock = synchronized(locks) { locks.getOrPut(safe) { Mutex() } }
        lock.withLock {
            if (file.length() > 0) return@withLock file
            val part = File(photoDir, "$safe-$rev.part")
            runCatching { api.downloadTo("/v1/users/$userId/photo", part) {} }.getOrElse { part.delete(); return@withLock null }
            if (!part.renameTo(file)) return@withLock null
            photoDir.listFiles()?.filter { it.name.startsWith("$safe-") && it != file }?.forEach { it.delete() }
            file
        }
    }

    /** People this account has blocked (their messages are dropped for it). */
    suspend fun blockedUsers(federation: FederationRepository): List<UserDto> =
        federation.blocked().mapNotNull { id -> runCatching { users.fetch(id) }.getOrNull() }
}
