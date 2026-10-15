package com.griipbuddy.message.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val code: String, override val message: String, val details: JsonElement? = null) : Exception(message) {
    val isNetwork get() = status == 0
}

val GriipJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * REST client. Bearer session token; on 401 it re-authenticates once by signing a fresh server
 * challenge with the device key (never by username/password) and retries.
 */
class ApiClient(
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val reauth: suspend () -> Boolean,
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = GriipJson
    private val reauthLock = Mutex()
    private val jsonType = "application/json".toMediaType()

    suspend inline fun <reified T> get(path: String): T = request("GET", path, null, serializer<T>())
    suspend inline fun <reified B, reified T> post(path: String, body: B): T = request("POST", path, GriipJson.encodeToString(serializer<B>(), body), serializer<T>())
    suspend inline fun <reified T> post(path: String): T = request("POST", path, "{}", serializer<T>())
    suspend inline fun <reified B, reified T> put(path: String, body: B): T = request("PUT", path, GriipJson.encodeToString(serializer<B>(), body), serializer<T>())
    suspend inline fun <reified B, reified T> patch(path: String, body: B): T = request("PATCH", path, GriipJson.encodeToString(serializer<B>(), body), serializer<T>())
    suspend inline fun <reified T> delete(path: String): T = request("DELETE", path, null, serializer<T>())

    suspend fun <T> request(method: String, path: String, body: String?, de: kotlinx.serialization.KSerializer<T>, auth: Boolean = true): T {
        val first = execute(method, path, body?.toRequestBody(jsonType), auth)
        val res = if (first.first == 401 && auth && !path.startsWith("/v1/auth/")) {
            val ok = reauthLock.withLock { reauth() }
            if (ok) execute(method, path, body?.toRequestBody(jsonType), auth) else first
        } else first
        return decode(res, de)
    }

    /** Upload raw bytes (already encrypted) with progress callback. */
    suspend fun upload(path: String, bytes: ByteArray, onProgress: (Float) -> Unit): AttachmentUploadRes {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                val chunk = 16 * 1024
                var off = 0
                while (off < bytes.size) {
                    val n = minOf(chunk, bytes.size - off)
                    sink.write(bytes, off, n)
                    off += n
                    onProgress(off.toFloat() / bytes.size)
                }
            }
        }
        var res = execute("POST", path, body, true)
        if (res.first == 401 && reauthLock.withLock { reauth() }) res = execute("POST", path, body, true)
        return decode(res, AttachmentUploadRes.serializer())
    }

    suspend fun download(path: String, onProgress: (Float) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        suspend fun once(): Pair<Int, ByteArray?> {
            val req = Request.Builder().url(baseUrl().trimEnd('/') + path).get().apply { token()?.let { header("Authorization", "Bearer $it") } }.build()
            return try {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return r.code to null
                    val total = r.body?.contentLength() ?: -1L
                    val src = r.body!!.byteStream()
                    val out = java.io.ByteArrayOutputStream(if (total > 0) total.toInt() else 64 * 1024)
                    val buf = ByteArray(32 * 1024)
                    var read = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                    200 to out.toByteArray()
                }
            } catch (e: IOException) {
                0 to null
            }
        }
        var (code, bytes) = once()
        if (code == 401 && reauthLock.withLock { reauth() }) once().also { code = it.first; bytes = it.second }
        when {
            bytes != null -> bytes!!
            code == 0 -> throw ApiException(0, "network", "Can't reach the server")
            code == 404 -> throw ApiException(404, "not_found", "This file has expired or you no longer have access")
            else -> throw ApiException(code, "http_$code", "Download failed ($code)")
        }
    }

    private suspend fun execute(method: String, path: String, body: RequestBody?, auth: Boolean): Pair<Int, String> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(baseUrl().trimEnd('/') + path)
        if (auth) token()?.let { b.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, body ?: "{}".toRequestBody(jsonType))
        }
        try {
            http.newCall(b.build()).execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: IOException) {
            0 to (e.message ?: "network error")
        }
    }

    private fun <T> decode(res: Pair<Int, String>, de: kotlinx.serialization.KSerializer<T>): T {
        val (code, text) = res
        if (code == 0) throw ApiException(0, "network", "Can't reach the server. Check your connection.")
        if (code in 200..299) {
            return try {
                json.decodeFromString(de, text.ifEmpty { "{}" })
            } catch (e: Exception) {
                throw ApiException(code, "decode", "Unexpected server response")
            }
        }
        val err = runCatching { json.decodeFromString(ApiErrorBody.serializer(), text).error }.getOrNull()
        throw ApiException(code, err?.code ?: "http_$code", friendly(code, err?.message), err?.details)
    }

    private fun friendly(code: Int, msg: String?): String = when {
        !msg.isNullOrBlank() && code != 500 -> msg
        code == 401 -> "Your session expired. Reconnecting…"
        code == 403 -> "You don't have permission to do that."
        code == 404 -> "Not found."
        code == 429 -> "Too many requests — please wait a moment."
        code >= 500 -> "The server had a problem. Please try again."
        else -> "Request failed ($code)"
    }
}
