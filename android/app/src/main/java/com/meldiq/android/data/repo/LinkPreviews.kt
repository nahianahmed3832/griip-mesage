package com.meldiq.android.data.repo

import com.meldiq.android.data.model.LinkPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Link previews, made only on the sender's phone and only when they turned previews on (docs/LINKS.md). The page is
 * fetched directly from this phone (the site sees this phone's IP address; no Meldiq server is involved and no message
 * content is sent anywhere). https only, at most 3 redirects (https only), no cookies, 256 KB of HTML, 5 seconds.
 * The preview then travels inside the encrypted message, so recipients never fetch the page.
 */
object LinkPreviews {
    private val http by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }
    private const val MAX_HTML = 256 * 1024

    private sealed interface Step {
        data class Redirect(val to: String) : Step
        data class Done(val preview: LinkPreview?) : Step
    }

    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        var current = url
        repeat(4) {
            val req = runCatching {
                Request.Builder().url(current).header("Accept", "text/html").header("User-Agent", "Mozilla/5.0 (Linux; Android) MeldiqLinkPreview").build()
            }.getOrNull() ?: return@withContext null
            val step: Step = runCatching {
                http.newCall(req).execute().use { r ->
                    if (r.isRedirect) {
                        val n = r.header("Location")?.let { r.request.url.resolve(it)?.toString() }
                        return@use if (n != null && n.startsWith("https://")) Step.Redirect(n) else Step.Done(null)
                    }
                    val type = r.header("Content-Type") ?: ""
                    val body = r.body
                    if (!r.isSuccessful || !type.contains("html", ignoreCase = true) || body == null) return@use Step.Done(null)
                    val bytes = body.byteStream().use { st -> st.readNBytesCompat(MAX_HTML) }
                    val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                    val html = runCatching { String(bytes, charset(charset ?: "UTF-8")) }.getOrDefault(String(bytes))
                    Step.Done(LinkPreviewParser.parse(url, html))
                }
            }.getOrDefault(Step.Done(null))
            when (step) {
                is Step.Redirect -> current = step.to
                is Step.Done -> return@withContext step.preview
            }
        }
        null
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** Title, description and site name from a page's HTML head (Open Graph first). Pure, tested in `LinkRulesTest`. */
object LinkPreviewParser {
    private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val TITLE = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)

    fun parse(url: String, html: String): LinkPreview? {
        val head = html.substringBefore("</head>", html)
        val metas = META.findAll(head).map { m ->
            ATTR.findAll(m.value).associate { a -> a.groupValues[1].lowercase() to decode(a.groupValues[3].ifEmpty { a.groupValues[4] }) }
        }.toList()
        fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            metas.firstOrNull { (it["property"] ?: it["name"])?.lowercase() == k }?.get("content")?.trim()?.ifEmpty { null }
        }
        val title = meta("og:title", "twitter:title") ?: TITLE.find(head)?.groupValues?.get(1)?.let(::decode)?.trim()?.ifEmpty { null }
        val description = meta("og:description", "twitter:description", "description")
        val site = meta("og:site_name")
        fun t(v: String?, max: Int) = v?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.ifEmpty { null }
        val p = LinkPreview(url, t(title, 200), t(description, 300), t(site, 80))
        return p.takeIf { it.title != null || it.description != null }
    }

    fun decode(s: String): String = s
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value }
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
}
