package com.griipbuddy.message.data.repo

import com.griipbuddy.message.data.api.ApiClient
import com.griipbuddy.message.data.api.ApiException
import com.griipbuddy.message.data.api.BlockReq
import com.griipbuddy.message.data.api.BlocksRes
import com.griipbuddy.message.data.api.FederationInfoRes
import com.griipbuddy.message.data.api.OkRes
import com.griipbuddy.message.data.api.ResolveRes
import java.net.URLEncoder

/**
 * Global addresses (docs/GLOBAL_IDENTITIES_AND_DISCOVERY.md): "@name:server". Someone on another server appears here
 * with username "name:server" and their home server's id. Pure, so it's unit-tested.
 */
object GlobalAddress {
    private val USERNAME = Regex("[a-z0-9._]{3,24}")
    private val DOMAIN = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*(?::\\d{1,5})?")

    /** True when [q] is a full address worth resolving on another server, e.g. "@bob:example.org". */
    fun looksLike(q: String): Boolean {
        val t = q.trim().removePrefix("@").lowercase()
        val i = t.indexOf(':')
        if (i < 0) return false
        return USERNAME.matches(t.substring(0, i)) && DOMAIN.matches(t.substring(i + 1))
    }

    fun isRemote(username: String, homeServer: String?): Boolean = homeServer != null || ':' in username

    /** The server part of a remote username ("bob:example.org" → "example.org"), or null for a local one. */
    fun serverOf(username: String): String? = username.substringAfter(':', "").ifEmpty { null }
}

class FederationRepository(private val api: ApiClient) {
    /** Resolves an exact address through this server (and, for other servers, the directory quorum). */
    suspend fun resolve(address: String): ResolveRes =
        api.get("/v1/federation/resolve?address=${URLEncoder.encode(address.trim(), "UTF-8")}")

    /** This account's global address, or null when the server doesn't federate. */
    suspend fun info(): FederationInfoRes? = try {
        api.get<FederationInfoRes>("/v1/federation/info")
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    suspend fun blocked(): Set<String> = api.get<BlocksRes>("/v1/blocks").userIds.toSet()
    suspend fun block(userId: String) = api.post<BlockReq, OkRes>("/v1/blocks", BlockReq(userId))
    suspend fun unblock(userId: String) = api.delete<OkRes>("/v1/blocks/$userId")
}
