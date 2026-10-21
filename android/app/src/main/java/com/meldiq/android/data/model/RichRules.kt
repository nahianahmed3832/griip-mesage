package com.meldiq.android.data.model

import java.util.Locale

/**
 * Rich messages: contact cards, one-time locations and animated emoji (2026-10-17, docs/RICH_MESSAGES.md). Pure,
 * unit-tested in `RichRulesTest`. Everything received is bounded and checked before it's stored or shown: it comes
 * from another phone and could be anything.
 */
object RichRules {
    const val MAX_NAME = 100
    const val MAX_FIELDS = 5
    const val MAX_FIELD = 64
    const val MAX_LABEL = 120

    /** A received or picked contact, cleaned: trimmed, bounded, at least a name. Null when nothing usable is left. */
    fun clean(c: ContactCard?): ContactCard? {
        c ?: return null
        val name = c.name.trim().take(MAX_NAME)
        if (name.isEmpty()) return null
        fun list(l: List<String>) = l.map { it.trim().take(MAX_FIELD) }.filter { it.isNotEmpty() }.distinct().take(MAX_FIELDS)
        val userId = c.userId?.trim()?.takeIf { USER_ID.matches(it) }
        // A profile card is only a profile: it never carries numbers or addresses, whatever a sender put in.
        if (userId != null) return ContactCard(name, userId = userId, username = c.username?.trim()?.takeIf { USERNAME.matches(it) })
        return ContactCard(name, list(c.phones), list(c.emails))
    }

    /** The Meldiq profile card for a person (round 7): name, user id and username only. */
    fun profileCard(userId: String, displayName: String, username: String?): ContactCard? =
        clean(ContactCard(displayName.ifBlank { username.orEmpty() }, userId = userId, username = username))

    private val USER_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    // Local usernames, or federated ones with their server (name@server.example).
    private val USERNAME = Regex("^[A-Za-z0-9_.-]{1,64}(@[A-Za-z0-9.-]{1,190})?$")

    /** A received or picked location, or null when the coordinates aren't real ones. */
    fun clean(l: SharedLocation?): SharedLocation? {
        l ?: return null
        if (l.lat.isNaN() || l.lon.isNaN() || l.lat !in -90.0..90.0 || l.lon !in -180.0..180.0) return null
        val acc = l.accuracyM?.takeIf { it.isFinite() && it >= 0f && it < 100_000f }
        return SharedLocation(l.lat, l.lon, acc, l.label?.trim()?.take(MAX_LABEL)?.ifEmpty { null })
    }

    /** One emoji (a single grapheme of at most 8 code points), or null. */
    fun cleanEmoji(e: String?): String? {
        val v = e?.trim() ?: return null
        if (v.isEmpty() || v.codePointCount(0, v.length) > 8) return null
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(v)
        it.first()
        return if (it.next() == v.length && v.codePoints().anyMatch { cp -> Character.getType(cp) == Character.OTHER_SYMBOL.toInt() || cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF }) v else null
    }

    /** What a payload carries, cleaned; null when nothing. */
    fun fromPayload(p: Payload): RichContent? {
        val r = RichContent(
            clean(p.contact), clean(p.location), cleanEmoji(p.animoji), clean(p.link, p.text.orEmpty()),
            poll = PollRules.clean(p.poll), sticker = cleanSticker(p.sticker), title = ForumRules.cleanTitle(p.title),
            tags = ForumRules.cleanTags(p.tags).takeIf { it.isNotEmpty() && p.title != null },
        )
        return r.takeIf { it.contact != null || it.location != null || it.animoji != null || it.link != null || it.poll != null || it.sticker != null || it.title != null }
    }

    private val STICKER_KEY = Regex("^[a-z0-9-]{1,32}$")

    /**
     * A sticker reference with a well-formed pack and id. Whether this phone knows the sticker is decided when it's
     * drawn: an unknown one (a newer pack) shows its fallback text instead of a broken image.
     */
    fun cleanSticker(s: StickerRef?): StickerRef? = s?.takeIf { STICKER_KEY.matches(it.pack) && STICKER_KEY.matches(it.id) }

    /** A link preview only for an https link that is in the message's own text, with bounded fields. */
    fun clean(l: LinkPreview?, text: String): LinkPreview? {
        l ?: return null
        if (!l.url.startsWith("https://") || LinkRules.find(text).none { it.url == l.url }) return null
        fun t(v: String?, max: Int) = v?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.ifEmpty { null }
        val p = LinkPreview(l.url, t(l.title, 200), t(l.description, 300), t(l.siteName, 80))
        return p.takeIf { it.title != null || it.description != null }
    }

    /** The text older apps (and notifications) show. */
    fun fallbackText(r: RichContent): String = when {
        r.contact?.userId != null -> "👤 " + r.contact.name + (r.contact.username?.let { " (@$it)" } ?: "")
        r.contact != null -> "👤 " + (listOf(r.contact.name) + r.contact.phones + r.contact.emails).joinToString("\n")
        r.location != null -> "📍 " + (r.location.label?.let { "$it\n" } ?: "") + osmLink(r.location)
        r.animoji != null -> r.animoji
        r.poll != null -> PollRules.fallbackText(r.poll)
        r.sticker != null -> "Sticker"
        else -> ""
    }

    /** A map link anyone can open; the coordinates travel inside the encrypted message only. */
    fun osmLink(l: SharedLocation): String =
        "https://www.openstreetmap.org/?mlat=${coord(l.lat)}&mlon=${coord(l.lon)}#map=16/${coord(l.lat)}/${coord(l.lon)}"

    /** For the phone's map app (`geo:`), with the place name as the pin label. */
    fun geoUri(l: SharedLocation): String {
        val q = "${coord(l.lat)},${coord(l.lon)}" + (l.label?.let { "(" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") + ")" } ?: "")
        return "geo:${coord(l.lat)},${coord(l.lon)}?q=$q"
    }

    fun coord(v: Double): String = String.format(Locale.ROOT, "%.6f", v)

    /** "23.810332, 90.412518 · within 12 m". */
    fun describe(l: SharedLocation): String =
        "${String.format(Locale.ROOT, "%.5f", l.lat)}, ${String.format(Locale.ROOT, "%.5f", l.lon)}" +
            (l.accuracyM?.let { " · within ${it.toInt()} m" } ?: "")
}
