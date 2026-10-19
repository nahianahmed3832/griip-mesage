package com.meldiq.android.data.repo

import android.content.Context
import com.meldiq.android.data.model.StickerCatalog
import com.meldiq.android.data.model.StickerRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Recently used and favourite stickers, on this phone only (they're a picker convenience, not shared state).
 * Stored as "pack/id" keys; stickers that leave the catalogue simply stop showing.
 */
class StickerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("griip_settings", Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(read(RECENT))
    private val _favourites = MutableStateFlow(read(FAVOURITES))
    val recent: StateFlow<List<StickerRef>> = _recent
    val favourites: StateFlow<List<StickerRef>> = _favourites

    private fun read(key: String): List<StickerRef> =
        prefs.getString(key, "").orEmpty().split('\n').mapNotNull { StickerCatalog.fromKey(it) }

    private fun write(key: String, refs: List<StickerRef>) {
        prefs.edit().putString(key, refs.joinToString("\n") { StickerCatalog.key(it) }).apply()
    }

    fun used(ref: StickerRef) {
        val next = (listOf(ref) + _recent.value.filterNot { it == ref }).take(MAX_RECENT)
        _recent.value = next
        write(RECENT, next)
    }

    /** Adds or removes [ref]; returns whether it's a favourite now. */
    fun toggleFavourite(ref: StickerRef): Boolean {
        val isFav = ref in _favourites.value
        val next = if (isFav) _favourites.value - ref else (listOf(ref) + _favourites.value).take(MAX_FAVOURITES)
        _favourites.value = next
        write(FAVOURITES, next)
        return !isFav
    }

    private companion object {
        const val RECENT = "stickers.recent"
        const val FAVOURITES = "stickers.favourites"
        const val MAX_RECENT = 16
        const val MAX_FAVOURITES = 48
    }
}
