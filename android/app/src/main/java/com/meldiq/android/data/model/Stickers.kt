package com.meldiq.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Meldiq stickers (2026-10-19, docs/STICKERS.md). The catalogue is one JSON file shared with the web app
 * (shared/stickers/meldiq-stickers-v1.json, packaged as an app asset). Each sticker is an original design drawn by the
 * app from its spec: an emoji glyph from the phone's own emoji font, a caption, a tone and a motion. Nothing third-party
 * is bundled. Unit-tested in `StickersTest`.
 */
@Serializable
data class StickerSpec(
    val id: String,
    val emoji: String,
    val caption: String,
    val tone: String,
    val motion: String,
    val keywords: List<String> = emptyList(),
)

@Serializable
data class StickerPack(val id: String, val name: String, val stickers: List<StickerSpec>)

@Serializable
data class StickerCatalogFile(val version: Int, val tones: List<String>, val motions: List<String>, val packs: List<StickerPack>)

/** A sticker with its pack, as the picker and messages use it. */
data class Sticker(val pack: StickerPack, val spec: StickerSpec) {
    val ref: StickerRef get() = StickerRef(pack.id, spec.id)
    /** What older apps, notifications and screen readers get: the emoji and the caption. */
    val fallback: String get() = "${spec.emoji} ${spec.caption}"
}

object StickerCatalog {
    const val ASSET = "stickers/meldiq-stickers-v1.json"
    private const val MAX_CAPTION = 32
    private val KEY = Regex("^[a-z0-9-]{1,32}$")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var packs: List<StickerPack> = emptyList()
        private set

    /**
     * The packs in [text], keeping only well-formed stickers (known tone and motion, one emoji, a short caption).
     * A malformed file yields no packs rather than a crash: messages then show their fallback text.
     */
    fun parse(text: String): List<StickerPack> {
        val file = runCatching { json.decodeFromString(StickerCatalogFile.serializer(), text) }.getOrNull() ?: return emptyList()
        val packIds = mutableSetOf<String>()
        return file.packs.filter { KEY.matches(it.id) && packIds.add(it.id) }.map { pack ->
            val ids = mutableSetOf<String>()
            pack.copy(
                stickers = pack.stickers.filter {
                    KEY.matches(it.id) && ids.add(it.id) && it.tone in file.tones && it.motion in file.motions &&
                        RichRules.cleanEmoji(it.emoji) != null && it.caption.isNotBlank() && it.caption.length <= MAX_CAPTION
                },
            )
        }.filter { it.stickers.isNotEmpty() }
    }

    fun load(text: String) {
        packs = parse(text)
    }

    fun find(ref: StickerRef?): Sticker? {
        ref ?: return null
        val pack = packs.firstOrNull { it.id == ref.pack } ?: return null
        return pack.stickers.firstOrNull { it.id == ref.id }?.let { Sticker(pack, it) }
    }

    fun all(): List<Sticker> = packs.flatMap { p -> p.stickers.map { Sticker(p, it) } }

    /** Stickers whose caption, keywords or emoji match [query] (case-insensitive); everything for a blank query. */
    fun search(query: String): List<Sticker> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return all().filter { s ->
            s.spec.caption.lowercase().contains(q) || s.spec.emoji == q || s.spec.keywords.any { it.lowercase().contains(q) }
        }
    }

    /** "pack/id" keys for recent and favourite lists. */
    fun key(ref: StickerRef): String = "${ref.pack}/${ref.id}"

    fun fromKey(key: String): StickerRef? = key.split('/').takeIf { it.size == 2 }?.let { StickerRef(it[0], it[1]) }
}
