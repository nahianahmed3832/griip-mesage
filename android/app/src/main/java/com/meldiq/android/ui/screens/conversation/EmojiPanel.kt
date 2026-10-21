package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EmojiFlags
import androidx.compose.material.icons.outlined.EmojiFoodBeverage
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EmojiPeople
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.EmojiTransportation
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meldiq.android.R
import com.meldiq.android.data.model.EmojiCatalog
import com.meldiq.android.data.model.EmojiGroup
import com.meldiq.android.data.model.EmojiItem
import com.meldiq.android.data.repo.EmojiData
import com.meldiq.android.data.repo.EmojiPrefs
import com.meldiq.android.ui.components.SearchPill
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import kotlinx.coroutines.launch

/** A category tab of the picker: the recent emoji, or one of Unicode's groups. */
private data class EmojiTab(val key: String, val icon: ImageVector, val label: Int)

private const val RECENT = "recent"

private val TABS = listOf(
    EmojiTab(RECENT, Icons.Outlined.Schedule, R.string.emoji_cat_recent),
    EmojiTab("smileys", Icons.Outlined.EmojiEmotions, R.string.emoji_cat_smileys),
    EmojiTab("people", Icons.Outlined.EmojiPeople, R.string.emoji_cat_people),
    EmojiTab("nature", Icons.Outlined.EmojiNature, R.string.emoji_cat_nature),
    EmojiTab("food", Icons.Outlined.EmojiFoodBeverage, R.string.emoji_cat_food),
    EmojiTab("travel", Icons.Outlined.EmojiTransportation, R.string.emoji_cat_travel),
    EmojiTab("activities", Icons.Outlined.EmojiEvents, R.string.emoji_cat_activities),
    EmojiTab("objects", Icons.Outlined.EmojiObjects, R.string.emoji_cat_objects),
    EmojiTab("symbols", Icons.Outlined.EmojiSymbols, R.string.emoji_cat_symbols),
    EmojiTab("flags", Icons.Outlined.EmojiFlags, R.string.emoji_cat_flags),
)

private val TONE_LABELS = listOf(
    R.string.emoji_tone_default, R.string.emoji_tone_1, R.string.emoji_tone_2, R.string.emoji_tone_3, R.string.emoji_tone_4, R.string.emoji_tone_5,
)

/** The hand shown on the skin tone button and in its choices. */
private val TONE_SAMPLE = EmojiItem("✋", "raised hand", "", "0.6", listOf("✋🏻", "✋🏼", "✋🏽", "✋🏾", "✋🏿"))

/**
 * The full emoji picker (docs/EMOJI.md): search by name or keyword, category tabs that jump to their section, recent
 * emoji first, and one skin tone for every emoji that has them (Discord-style, chosen once). [onPick] gets the emoji
 * in the chosen tone; it is then remembered as recent. Used for typing into the composer and for reactions.
 */
@Composable
fun EmojiPanel(prefs: EmojiPrefs, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    val context = LocalContext.current
    val groups by produceState<List<EmojiGroup>?>(null) { value = EmojiData.groups(context) }
    val recent by prefs.recent.collectAsState()
    val tone by prefs.tone.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var choosingTone by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val pick: (EmojiItem) -> Unit = { item -> item.inTone(tone).let { prefs.used(it); onPick(it) } }

    val loaded = groups
    val results = remember(loaded, query) { if (loaded == null) emptyList() else EmojiCatalog.search(loaded, query) }
    // Where each section's header sits in the grid, for the tabs (recent first when there are any).
    val headers = remember(loaded, recent.size) {
        buildList {
            var index = 0
            if (recent.isNotEmpty()) { add(RECENT to index); index += 1 + recent.size }
            loaded.orEmpty().forEach { g -> add(g.id to index); index += 1 + g.items.size }
        }
    }
    val current by remember(headers) { derivedStateOf { headers.lastOrNull { it.second <= grid.firstVisibleItemIndex }?.first } }
    val titles = TABS.associate { it.key to stringResource(it.label) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchPill(query, { query = it.take(MAX_QUERY) }, stringResource(R.string.emoji_search), Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            EmojiCell(TONE_SAMPLE.inTone(tone), stringResource(R.string.emoji_tone), selected = choosingTone) { choosingTone = !choosingTone }
        }
        if (choosingTone) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TONE_LABELS.forEachIndexed { t, label ->
                    EmojiCell(TONE_SAMPLE.inTone(t), stringResource(label), selected = t == tone) { prefs.setTone(t); choosingTone = false }
                }
            }
        }
        if (query.isBlank()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TABS.forEach { tab ->
                    val target = headers.firstOrNull { it.first == tab.key }
                    val label = titles.getValue(tab.key)
                    val jump: (() -> Unit)? = target?.let { (_, index) -> { scope.launch { grid.scrollToItem(index) } } }
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .background(if (current == tab.key) c.primarySoft else c.surface)
                            .pressable(jump, enabled = jump != null, role = Role.Tab, onClickLabel = label)
                            .semantics { contentDescription = label; selected = current == tab.key },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tab.icon, null, tint = when { target == null -> c.textTertiary.copy(alpha = 0.4f); current == tab.key -> c.primary; else -> c.textSecondary }, modifier = Modifier.size(18.dp))
                    }
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                loaded == null -> Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = c.primary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.emoji_loading), style = Griip.type.bodySmall, color = c.textSecondary)
                }
                query.isNotBlank() && results.isEmpty() ->
                    Text(stringResource(R.string.emoji_no_match, query.trim()), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
                else -> LazyVerticalGrid(GridCells.Adaptive(CELL), Modifier.fillMaxSize(), state = grid) {
                    if (query.isNotBlank()) {
                        items(results, key = { "s:" + it.emoji }) { item -> EmojiCell(item.inTone(tone), item.name) { pick(item) } }
                    } else {
                        if (recent.isNotEmpty()) {
                            header(RECENT, titles.getValue(RECENT))
                            items(recent, key = { "r:$it" }) { e -> EmojiCell(e, e) { prefs.used(e); onPick(e) } }
                        }
                        if (loaded.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(stringResource(R.string.emoji_unavailable), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(12.dp))
                            }
                        }
                        loaded.forEach { g ->
                            header(g.id, titles[g.id] ?: g.name)
                            items(g.items, key = { "${g.id}:" + it.emoji }) { item -> EmojiCell(item.inTone(tone), item.name) { pick(item) } }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.header(key: String, title: String) {
    item(key = "h:$key", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            title, style = Griip.type.caption, color = Griip.colors.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

/** One tappable emoji, 44 dp, read out by its name. */
@Composable
private fun EmojiCell(emoji: String, name: String, selected: Boolean = false, onClick: () -> Unit) {
    val c = Griip.colors
    Box(
        Modifier.size(CELL).clip(Shapes.small)
            .then(if (selected) Modifier.background(c.primarySoft).border(1.dp, c.primary, Shapes.small) else Modifier)
            .pressable(onClick, onClickLabel = name)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = 26.sp) }
}

private val CELL = 44.dp
/** Longer than any emoji name; a paste can't make the search slow. */
private const val MAX_QUERY = 60
