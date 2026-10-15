package com.griipbuddy.message.data.repo

import com.griipbuddy.message.crypto.Canonical
import com.griipbuddy.message.crypto.Ed25519
import com.griipbuddy.message.crypto.RecipientDevice
import com.griipbuddy.message.crypto.unb64
import com.griipbuddy.message.data.api.ApiClient
import com.griipbuddy.message.data.api.KeysRes
import com.griipbuddy.message.data.api.PresenceRes
import com.griipbuddy.message.data.api.UserDto
import com.griipbuddy.message.data.api.UserIdsReq
import com.griipbuddy.message.data.api.UserKeysDto
import com.griipbuddy.message.data.api.UserRes
import com.griipbuddy.message.data.api.UsersRes
import com.griipbuddy.message.data.db.GriipDatabase
import com.griipbuddy.message.data.db.UserEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Public-key directory with trust-on-first-use pinning. A device is only ever encrypted to, or
 * trusted as a sender, if its certificate verifies against the user's identity key. If a user's
 * identity key changes after it was pinned, the conversation shows a warning (docs/SECURITY.md §5).
 */
class KeyDirectory(private val api: ApiClient, private val db: GriipDatabase) {
    private data class Cached(val keys: UserKeysDto, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val convCache = ConcurrentHashMap<String, Pair<List<UserKeysDto>, Long>>()

    private fun validDevices(u: UserKeysDto) = u.devices.filter { d ->
        Ed25519.verify(u.identityKey.unb64(), Canonical.deviceCert(u.identityKey, d.signingKey, d.encryptionKey).toByteArray(), d.certificate.unb64())
    }

    /** TOFU: pin the first identity key we see; flag (don't silently accept) any later change. */
    private suspend fun pin(u: UserKeysDto) {
        val existing = db.users().get(u.userId)
        if (existing == null) {
            db.users().upsert(UserEntity(u.userId, "", "", 0, null, pinnedIdentityKey = u.identityKey))
        } else if (existing.pinnedIdentityKey == null) {
            db.users().upsert(existing.copy(pinnedIdentityKey = u.identityKey))
        } else if (existing.pinnedIdentityKey != u.identityKey && !existing.identityChanged) {
            db.users().upsert(existing.copy(identityChanged = true, verified = false))
        }
    }

    suspend fun conversationRecipients(conversationId: String, myDeviceId: String, force: Boolean): List<RecipientDevice> {
        val now = System.currentTimeMillis()
        val hit = convCache[conversationId]
        val users = if (!force && hit != null && now - hit.second < 60_000) hit.first else {
            api.get<KeysRes>("/v1/conversations/$conversationId/devices").users.also { list ->
                convCache[conversationId] = list to now
                list.forEach { cache[it.userId] = Cached(it, now); pin(it) }
            }
        }
        return users.flatMap { validDevices(it) }.filter { it.id != myDeviceId }.map { RecipientDevice(it.id, it.encryptionKey.unb64()) }
    }

    fun invalidate(conversationId: String? = null) {
        if (conversationId == null) convCache.clear() else convCache.remove(conversationId)
    }

    fun invalidateUser(userId: String) {
        cache.remove(userId)
        convCache.clear()
    }

    /** Signing key of a sender device, verified against the sender's identity key. */
    suspend fun senderSigningKey(userId: String, deviceId: String): ByteArray? {
        fun find(c: UserKeysDto?) = c?.let { validDevices(it).firstOrNull { d -> d.id == deviceId } }
        find(cache[userId]?.keys)?.let { return it.signingKey.unb64() }
        val fresh = api.post<UserIdsReq, KeysRes>("/v1/users/devices", UserIdsReq(listOf(userId))).users.firstOrNull() ?: return null
        cache[userId] = Cached(fresh, System.currentTimeMillis())
        pin(fresh)
        return find(fresh)?.signingKey?.unb64()
    }
}

class UserRepository(private val api: ApiClient, private val db: GriipDatabase) {
    suspend fun remember(u: UserDto) {
        val e = db.users().get(u.id)
        db.users().upsert(
            (e ?: UserEntity(u.id, u.username, u.displayName, u.avatarColor, u.statusText, null)).copy(
                username = u.username, displayName = u.displayName, avatarColor = u.avatarColor, statusText = u.statusText,
                online = u.online ?: e?.online ?: false, lastSeenAt = u.lastSeenAt ?: e?.lastSeenAt, updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun search(q: String): List<UserDto> = api.get<UsersRes>("/v1/users/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}").users.also { it.forEach { u -> remember(u) } }

    suspend fun fetch(id: String): UserDto = api.get<UserRes>("/v1/users/$id").user.also { remember(it) }

    suspend fun refreshPresence(ids: List<String>) {
        if (ids.isEmpty()) return
        api.get<PresenceRes>("/v1/presence?ids=${ids.joinToString(",")}").presence.forEach { db.users().setPresence(it.userId, it.online, it.lastSeenAt) }
    }

    suspend fun markVerified(userId: String) {
        db.users().get(userId)?.let { db.users().upsert(it.copy(verified = true)) }
    }

    /** Accept a changed identity key after the user has re-verified it out of band. */
    suspend fun acceptNewIdentity(userId: String) {
        val u = fetch(userId)
        db.users().get(userId)?.let { db.users().upsert(it.copy(pinnedIdentityKey = u.identityKey, identityChanged = false, verified = false)) }
    }
}
