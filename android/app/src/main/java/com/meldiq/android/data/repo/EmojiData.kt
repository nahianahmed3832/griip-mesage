package com.meldiq.android.data.repo

import android.content.Context
import android.graphics.Paint
import android.util.Log
import com.meldiq.android.data.model.EmojiCatalog
import com.meldiq.android.data.model.EmojiGroup
import com.meldiq.android.data.model.EmojiRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The emoji catalogue (docs/EMOJI.md), read once from assets and kept for the app's life. Emoji this phone's fonts
 * can't draw are left out, and so are skin tones it can't draw, so the picker never shows empty boxes.
 */
object EmojiData {
    private const val ASSET = "emoji/emoji.json"
    private const val TAG = "EmojiData"
    private val lock = Mutex()
    @Volatile private var cached: List<EmojiGroup>? = null

    suspend fun groups(context: Context): List<EmojiGroup> = cached ?: lock.withLock {
        cached ?: withContext(Dispatchers.IO) { load(context.applicationContext) }.also { cached = it }
    }

    private fun load(context: Context): List<EmojiGroup> = try {
        val all = EmojiCatalog.parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        val paint = Paint()
        all.map { g ->
            g.copy(items = g.items.filter { paint.hasGlyph(it.emoji) }.map { item ->
                if (item.tones != null && !item.tones.all(paint::hasGlyph)) item.copy(tones = null) else item
            })
        }
    } catch (e: Exception) {
        // A broken asset must not break the composer: the picker then shows recent emoji only.
        Log.w(TAG, "emoji catalogue unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }
}

/** Recently used emoji (newest first) and the chosen skin tone, on this phone only. */
class EmojiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("griip_settings", Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(readRecent())
    private val _tone = MutableStateFlow(prefs.getInt(TONE, 0).coerceIn(0, EmojiCatalog.TONE_COUNT))
    val recent: StateFlow<List<String>> = _recent
    /** 0: no skin tone (the yellow default); 1–5: light to dark. */
    val tone: StateFlow<Int> = _tone

    private fun readRecent(): List<String> =
        prefs.getString(RECENT, "").orEmpty().split('\n').filter(EmojiRules::isReaction).take(MAX_RECENT)

    fun used(emoji: String) {
        if (!EmojiRules.isReaction(emoji)) return
        val next = (listOf(emoji) + _recent.value.filterNot { it == emoji }).take(MAX_RECENT)
        _recent.value = next
        prefs.edit().putString(RECENT, next.joinToString("\n")).apply()
    }

    fun setTone(tone: Int) {
        val t = tone.coerceIn(0, EmojiCatalog.TONE_COUNT)
        _tone.value = t
        prefs.edit().putInt(TONE, t).apply()
    }

    private companion object {
        const val RECENT = "emoji.recent"
        const val TONE = "emoji.tone"
        const val MAX_RECENT = 32
    }
}
