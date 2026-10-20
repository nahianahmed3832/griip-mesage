package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space

/**
 * An empty channel's welcome (round 3, the reference's "This is the very beginning of #discussion" with "Add
 * description" and "Add people"). The two actions open the channel's info screen, where its manager edits the topic and
 * adds members; people who can't manage the channel see only the welcome.
 */
@Composable
fun ChannelWelcome(name: String, topic: String?, canManage: Boolean, onEdit: () -> Unit, onAddPeople: () -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Space.screen)) {
        Text("#$name", style = Griip.type.headline, color = c.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.r3_welcome_channel, name), style = Griip.type.body, color = c.textSecondary)
        topic?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = Griip.type.bodySmall, color = c.textSecondary)
        }
        if (canManage) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(stringResource(R.string.r3_welcome_add_description), onEdit, Modifier.weight(1f), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.Edit, height = 48.dp)
                PillButton(stringResource(R.string.r3_welcome_add_people), onAddPeople, Modifier.weight(1f), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.PersonAdd, height = 48.dp)
            }
        }
    }
}
