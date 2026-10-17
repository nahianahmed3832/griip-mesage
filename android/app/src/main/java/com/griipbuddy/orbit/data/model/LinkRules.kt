package com.griipbuddy.orbit.data.model

import java.net.IDN
import java.net.URI

/**
 * Web links in messages (2026-10-17, docs/LINKS.md). Pure, unit-tested in `LinkRulesTest`.
 * Only http and https links are made tappable; any other scheme (intent:, javascript:, file:, app deep links…) stays plain
 * text. The stored message text is never changed.
 */
object LinkRules {
    data class Link(val start: Int, val end: Int, val url: String)

    private val CANDIDATE = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'`]+""")
    private const val TRAILING = ".,;:!?…'\")]}>"

    /** The links in [text], with trailing punctuation and unbalanced closing brackets left out. */
    fun find(text: String): List<Link> = CANDIDATE.findAll(text).mapNotNull { m ->
        var s = m.value
        while (s.isNotEmpty() && s.last() in TRAILING) {
            val last = s.last()
            if (last == ')' && s.count { it == '(' } >= s.count { it == ')' }) break
            s = s.dropLast(1)
        }
        val url = normalize(s) ?: return@mapNotNull null
        Link(m.range.first, m.range.first + s.length, url)
    }.toList()

    /** "www.example.com/a" → "https://www.example.com/a"; null when it isn't a valid http(s) address. */
    fun normalize(raw: String): String? {
        val withScheme = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        val u = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = u.host ?: return null
        if (host.isEmpty() || !host.contains('.') && host != "localhost") return null
        return withScheme
    }

    /** The host to show, in readable form ("bücher.example" rather than its xn-- spelling). */
    fun domain(url: String): String? = runCatching {
        val h = URI(url).host ?: return null
        IDN.toUnicode(h.removePrefix("www."))
    }.getOrNull()

    /** Why a link deserves a second look before opening, or null. Shown in the confirmation; the link still opens. */
    fun warning(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return "This address can't be read."
        val host = u.host?.lowercase() ?: return "This address has no website name."
        val ascii = runCatching { IDN.toASCII(host) }.getOrDefault(host)
        return when {
            u.rawUserInfo != null -> "The address hides its real destination before “@”. It goes to ${domain(url) ?: host}."
            ascii.split('.').any { it.startsWith("xn--") } -> "The address uses letters from other alphabets that can look like familiar ones: ${ascii}."
            Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || host.startsWith("[") -> "The address is a bare IP number, not a website name."
            u.scheme.equals("http", ignoreCase = true) -> "This link isn't encrypted (http): the network can see and change what you load."
            host.count { it == '.' } >= 5 -> "The address has an unusually long chain of names: check the end of it, ${host.split('.').takeLast(2).joinToString(".")}."
            else -> null
        }
    }
}
