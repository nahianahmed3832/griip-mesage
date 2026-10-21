package com.meldiq.android.ui.screens.conversation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.model.Poll
import com.meldiq.android.data.model.PollRules
import com.meldiq.android.data.model.RichContent
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Motion
import com.meldiq.android.ui.theme.Shapes

/**
 * A poll in a message: the question, each option with its share of the votes, and your choice marked. Tapping an
 * option votes (or changes your vote); a closed poll only shows the result. Votes are counted on each phone from the
 * encrypted vote messages (docs/RICH_MESSAGES.md › Polls).
 */
@Composable
fun PollCard(rich: RichContent, me: String?, canVote: Boolean, onVote: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    val poll = rich.poll ?: return
    val now = System.currentTimeMillis()
    // Batch B8: a poll with an end time closes by itself.
    val closed = PollRules.closed(rich, now)
    val tally = PollRules.tally(rich)
    val total = tally.sumOf { it.second }
    val voters = PollRules.voters(rich)
    val mine = rich.votes.orEmpty()[me].orEmpty()
    Column(modifier.widthIn(min = 240.dp, max = 320.dp).clip(Shapes.card).background(c.surface).border(1.dp, c.border, Shapes.card).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BarChart, null, tint = c.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.poll_label) + " · " + stringResource(if (poll.multi) R.string.poll_pick_several else R.string.poll_pick_one),
                style = Griip.type.caption, color = c.textSecondary,
            )
        }
        Text(poll.question, style = Griip.type.titleSmall, color = c.textPrimary, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        tally.forEach { (option, count) ->
            val picked = option.id in mine
            val share = if (total > 0) count.toFloat() / total else 0f
            val shown by animateFloatAsState(share, Motion.standard(), label = "pollShare")
            val votesText = if (count == 1) stringResource(R.string.poll_vote_one) else stringResource(R.string.poll_votes, count)
            val next = when {
                poll.multi && picked -> mine - option.id
                poll.multi -> mine + option.id
                picked -> emptyList()
                else -> listOf(option.id)
            }
            Box(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).heightIn(min = 44.dp).clip(Shapes.small)
                    .background(c.surfaceVariant)
                    .border(if (picked) 1.5.dp else 0.dp, if (picked) c.primary else c.surfaceVariant, Shapes.small)
                    .pressable(if (canVote && !closed) ({ onVote(next) }) else null, role = if (poll.multi) Role.Checkbox else Role.RadioButton)
                    .semantics {
                        selected = picked
                        contentDescription = "${option.text}, $votesText"
                        if (picked) stateDescription = "Your vote"
                    },
            ) {
                // The share of votes, drawn behind the text (the count is also written out, so colour isn't the only cue).
                Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().background(c.primary.copy(alpha = if (picked) SHARE_PICKED else SHARE_OTHER)))
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (picked) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, tint = c.onPrimary, modifier = Modifier.size(12.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    option.emoji?.let { Text(it, style = Griip.type.bodyMedium, modifier = Modifier.padding(end = 6.dp)) }
                    Text(option.text, style = Griip.type.bodyMedium.copy(fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium), color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text(if (total > 0) "${(share * 100).toInt()}%" else "", style = Griip.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (closed) {
                Icon(Icons.Outlined.Lock, null, tint = c.textSecondary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                listOfNotNull(
                    if (voters == 1) stringResource(R.string.poll_vote_one) else stringResource(R.string.poll_votes, voters),
                    when {
                        closed -> stringResource(R.string.poll_closed)
                        poll.endsAt != null -> stringResource(R.string.b8_poll_ends, com.meldiq.android.ui.TimeFmt.clock(poll.endsAt) + " · " + com.meldiq.android.ui.TimeFmt.dayHeader(poll.endsAt))
                        else -> null
                    },
                ).joinToString(" · "),
                style = Griip.type.caption, color = c.textSecondary,
            )
        }
    }
}

/** Compose a poll: a question, two to ten options, and whether several answers are allowed. */
@Composable
fun PollComposerSheet(onSend: (Poll) -> Unit, onDismiss: () -> Unit, emojiPrefs: com.meldiq.android.data.repo.EmojiPrefs? = null) {
    val c = Griip.colors
    var question by rememberSaveable { mutableStateOf("") }
    val options = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf("", "") }
    // Batch B8: an emoji per answer ("" for none), and how long voting lasts.
    val emojis = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf("", "") }
    var durationIndex by rememberSaveable { mutableStateOf(PollRules.DEFAULT_DURATION_INDEX) }
    var emojiFor by remember { mutableStateOf<Int?>(null) }
    var multi by rememberSaveable { mutableStateOf(false) }
    val poll = PollRules.compose(question, options, multi, emojis.map { it.ifEmpty { null } }, PollRules.DURATIONS[durationIndex], System.currentTimeMillis())
    GlassSheet(onDismiss, title = stringResource(R.string.poll_new_title)) {
        GriipTextField(question, { question = it.take(PollRules.MAX_QUESTION) }, stringResource(R.string.poll_question), Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        options.forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                if (emojiPrefs != null) {
                    val e = emojis.getOrNull(i).orEmpty()
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(c.surfaceVariant).pressable({ emojiFor = i }, onClickLabel = stringResource(R.string.b8_poll_emoji)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (e.isEmpty()) Icon(Icons.Outlined.EmojiEmotions, stringResource(R.string.b8_poll_emoji), tint = c.textSecondary, modifier = Modifier.size(20.dp))
                        else Text(e, style = Griip.type.bodyMedium)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                GriipTextField(text, { v -> options[i] = v.take(PollRules.MAX_OPTION) }, stringResource(R.string.poll_option, i + 1), Modifier.weight(1f))
                if (options.size > PollRules.MIN_OPTIONS) CircleIconButton(Icons.Outlined.Close, stringResource(R.string.poll_remove_option), { options.removeAt(i); if (i < emojis.size) emojis.removeAt(i) }, size = 40.dp, tint = c.textSecondary)
            }
        }
        if (options.size < PollRules.MAX_OPTIONS) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 44.dp).clip(Shapes.small).pressable({ options.add(""); emojis.add("") }).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, null, tint = c.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.poll_add_option), style = Griip.type.bodyMedium, color = c.primary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.poll_multi), style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
            GriipSwitch(multi, { multi = it })
        }
        Text(stringResource(R.string.b8_poll_duration), style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.padding(top = 10.dp))
        com.meldiq.android.ui.components.ChipRow(Modifier.padding(horizontal = 0.dp)) {
            DURATION_LABELS.forEachIndexed { i, label -> com.meldiq.android.ui.components.PillChip(stringResource(label), durationIndex == i, { durationIndex = i }) }
        }
        Text(stringResource(R.string.poll_encrypted), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        PillButton(stringResource(R.string.poll_send), { poll?.let(onSend) }, Modifier.fillMaxWidth(), enabled = poll != null)
    }
    val forOption = emojiFor
    if (forOption != null && emojiPrefs != null) {
        GlassSheet({ emojiFor = null }, title = stringResource(R.string.b8_poll_emoji)) {
            EmojiPanel(emojiPrefs, onPick = { e ->
                while (emojis.size <= forOption) emojis.add("")
                emojis[forOption] = PollRules.cleanEmoji(e).orEmpty()
                emojiFor = null
            })
            if (emojis.getOrNull(forOption).orEmpty().isNotEmpty()) {
                PillButton(stringResource(R.string.b8_poll_emoji_remove), { emojis[forOption] = ""; emojiFor = null }, Modifier.fillMaxWidth().padding(vertical = 8.dp), tone = com.meldiq.android.ui.components.ButtonTone.Glass, height = 44.dp)
            }
        }
    }
}

/** The composer's duration chips, in [PollRules.DURATIONS]' order. */
private val DURATION_LABELS = listOf(R.string.sa_1h, R.string.b8_4h, R.string.b6b_8h, R.string.sa_24h, R.string.b8_3d, R.string.sa_7d, R.string.b8_no_end)

/** How strongly the vote share is tinted: your choice, and the others. Text over it keeps 4.5:1 in every palette. */
private const val SHARE_PICKED = 0.30f
private const val SHARE_OTHER = 0.16f

private fun <T> List<T>.toMutableStateList() = mutableStateListOf<T>().also { it.addAll(this) }
