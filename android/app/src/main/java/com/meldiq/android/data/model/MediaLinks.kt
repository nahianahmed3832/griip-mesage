package com.meldiq.android.data.model

import java.net.URI

/**
 * Music and video links (2026-10-19, docs/MEDIA.md): recognised from the address alone, so a card can say what the link
 * is ("YouTube video", "Spotify playlist") and open it in the provider's own app or site, without Meldiq fetching
 * anything (no tracking pixel for recipients) and without an embedded player. Playback always happens in the official
 * app or site, which handles accounts, subscriptions, regions and ads. Pure, unit-tested in `MediaLinksTest`.
 */
object MediaLinks {
    enum class Provider(val label: String) { YOUTUBE("YouTube"), YOUTUBE_MUSIC("YouTube Music"), SPOTIFY("Spotify"), SOUNDCLOUD("SoundCloud"), VIMEO("Vimeo") }

    enum class Kind { VIDEO, SHORT, TRACK, ALBUM, PLAYLIST, ARTIST, EPISODE, SHOW, LIVE }

    data class MediaLink(val provider: Provider, val kind: Kind, val url: String)

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val SPOTIFY_ID = Regex("^[A-Za-z0-9]{22}$")
    private val SPOTIFY_KINDS = mapOf(
        "track" to Kind.TRACK, "album" to Kind.ALBUM, "playlist" to Kind.PLAYLIST, "artist" to Kind.ARTIST,
        "episode" to Kind.EPISODE, "show" to Kind.SHOW,
    )

    /** The first music or video link in [text] (https only, like every tappable link), or null. */
    fun find(text: String): MediaLink? = LinkRules.find(text).firstNotNullOfOrNull { detect(it.url) }

    fun detect(url: String): MediaLink? {
        if (!url.startsWith("https://")) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return when (host) {
            "youtube.com" -> youtube(path, query, url, Provider.YOUTUBE)
            "music.youtube.com" -> youtube(path, query, url, Provider.YOUTUBE_MUSIC)
            "youtu.be" -> path.firstOrNull()?.takeIf { YOUTUBE_ID.matches(it) }?.let { MediaLink(Provider.YOUTUBE, Kind.VIDEO, url) }
            "open.spotify.com" -> {
                // Spotify adds a locale segment to some links: open.spotify.com/intl-de/track/…
                val p = if (path.firstOrNull()?.startsWith("intl-") == true) path.drop(1) else path
                val kind = SPOTIFY_KINDS[p.getOrNull(0)] ?: return null
                p.getOrNull(1)?.takeIf { SPOTIFY_ID.matches(it) }?.let { MediaLink(Provider.SPOTIFY, kind, url) }
            }
            "soundcloud.com" -> when {
                path.size == 2 && path[1] !in setOf("sets", "tracks", "likes", "followers", "following") -> MediaLink(Provider.SOUNDCLOUD, Kind.TRACK, url)
                path.size == 3 && path[1] == "sets" -> MediaLink(Provider.SOUNDCLOUD, Kind.PLAYLIST, url)
                else -> null
            }
            "vimeo.com" -> path.firstOrNull()?.takeIf { it.all(Char::isDigit) && it.length in 4..12 }?.let { MediaLink(Provider.VIMEO, Kind.VIDEO, url) }
            else -> null
        }
    }

    private fun youtube(path: List<String>, query: Map<String, String>, url: String, provider: Provider): MediaLink? = when {
        path.firstOrNull() == "watch" && query["v"]?.let { YOUTUBE_ID.matches(it) } == true ->
            MediaLink(provider, if (provider == Provider.YOUTUBE_MUSIC) Kind.TRACK else Kind.VIDEO, url)
        path.firstOrNull() == "playlist" && !query["list"].isNullOrBlank() -> MediaLink(provider, Kind.PLAYLIST, url)
        path.firstOrNull() == "shorts" && path.getOrNull(1)?.let { YOUTUBE_ID.matches(it) } == true -> MediaLink(provider, Kind.SHORT, url)
        path.firstOrNull() == "live" && path.getOrNull(1)?.let { YOUTUBE_ID.matches(it) } == true -> MediaLink(provider, Kind.LIVE, url)
        else -> null
    }
}
