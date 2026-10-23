package com.meldiq.android.data.repo

import android.content.Context
import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ExpressionDto
import com.meldiq.android.data.api.ExpressionRes
import com.meldiq.android.data.api.ExpressionsRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.data.api.RenameExpressionReq
import com.meldiq.android.data.model.CustomEmojiRef
import com.meldiq.android.data.model.ExpressionRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Batch B9: a community's own emoji and stickers. The lists are kept in memory per community; images are cached in
 * this profile's folder under their id and hash, and kept only when their SHA-256 matches the one a message carries.
 */
class ExpressionRepository(context: Context, private val api: ApiClient, profileId: String) {
    private val dir = KeptPictures.folder(context, profileId, "expressions", legacyCache = "expressions-$profileId")
    private val _lists = MutableStateFlow<Map<String, List<ExpressionDto>>>(emptyMap())
    val lists: StateFlow<Map<String, List<ExpressionDto>>> = _lists.asStateFlow()
    private val locks = HashMap<String, Mutex>()

    suspend fun refresh(orgId: String): ExpressionsRes =
        api.get<ExpressionsRes>("/v1/orgs/$orgId/expressions").also { r -> _lists.update { it + (orgId to r.expressions) } }

    suspend fun upload(orgId: String, kind: String, name: String, bytes: ByteArray): ExpressionDto =
        api.sendBytes("POST", "/v1/orgs/$orgId/expressions?kind=$kind&name=${java.net.URLEncoder.encode(name, "UTF-8")}", bytes, ExpressionRes.serializer()).expression
            .also { runCatching { refresh(orgId) } }

    suspend fun rename(e: ExpressionDto, name: String): ExpressionDto =
        api.patch<RenameExpressionReq, ExpressionRes>("/v1/expressions/${e.id}", RenameExpressionReq(name)).expression.also { runCatching { refresh(e.orgId) } }

    suspend fun delete(e: ExpressionDto) {
        api.delete<OkRes>("/v1/expressions/${e.id}")
        runCatching { refresh(e.orgId) }
    }

    /** The image for [ref], downloaded once and checked against its hash; null when it can't be had or doesn't match. */
    suspend fun image(ref: CustomEmojiRef): File? = withContext(Dispatchers.IO) {
        val file = File(dir, "${ref.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}-${ref.sha256.take(16)}")
        if (file.length() > 0) return@withContext file
        val lock = synchronized(locks) { locks.getOrPut(ref.id) { Mutex() } }
        lock.withLock {
            if (file.length() > 0) return@withLock file
            // Android may clear the cache folder itself when storage runs low (seen on the emulator in batch B12):
            // made again here, or every download after that failed until the app restarted.
            dir.mkdirs()
            val part = File(dir, file.name + ".part").apply { delete() }
            runCatching { api.downloadTo("/v1/expressions/${ref.id}/image", part) {} }.getOrElse { part.delete(); return@withLock null }
            val ok = part.length() in 1..ExpressionRules.MAX_BYTES.toLong() && ExpressionRules.matches(part.readBytes(), ref.sha256)
            if (!ok || !part.renameTo(file)) {
                part.delete()
                return@withLock null
            }
            file
        }
    }

    fun ref(e: ExpressionDto) = CustomEmojiRef(e.id, e.orgId, e.name, e.sha256, e.animated)
}
