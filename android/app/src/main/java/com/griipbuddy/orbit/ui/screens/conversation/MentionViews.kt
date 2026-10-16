package com.griipbuddy.orbit.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.data.model.MentionSuggestion
import com.griipbuddy.orbit.ui.components.Avatar
import com.griipbuddy.orbit.ui.components.pressable
import com.griipbuddy.orbit.ui.theme.Griip

/**
 * The @mention picker above the composer: one row per matching member (name and @username, so people with the same
 * display name are told apart), and "@everyone" first where the server allows it.
 */
@Composable
fun MentionSuggestions(suggestions: List<MentionSuggestion>, onPick: (MentionSuggestion) -> Unit) {
    val c = Griip.colors
    Column(Modifier.fillMaxWidth().background(c.surface).semantics { contentDescription = "Mention suggestions" }) {
        HorizontalDivider(color = c.border)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 232.dp)) {
            items(suggestions, key = { it.member?.id ?: "@everyone" }) { s ->
                Row(
                    Modifier.fillMaxWidth().pressable({ onPick(s) }, onClickLabel = "Mention ${s.member?.displayName ?: "everyone"}")
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (s.everyone) {
                        Icon(Icons.Rounded.Campaign, null, tint = c.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("@everyone", style = Griip.type.bodyMedium, color = c.textPrimary)
                            Text("Notify everyone in this conversation", style = Griip.type.caption, color = c.textSecondary)
                        }
                    } else {
                        val m = s.member!!
                        Avatar(m.displayName.ifEmpty { m.username }, m.id.hashCode(), size = 32.dp, ring = false)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName.ifEmpty { m.username }, style = Griip.type.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("@${m.username}", style = Griip.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
