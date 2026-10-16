package com.griipbuddy.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Shapes

/** A short-lived confirmation with an Undo button (e.g. after archiving a chat). The caller decides when it goes away. */
@Composable
fun UndoBar(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    Row(
        modifier.fillMaxWidth().clip(Shapes.tile).background(c.textPrimary).padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Griip.type.bodySmall, color = c.background, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = onUndo) { Text("Undo", style = Griip.type.button, color = c.primarySoft) }
    }
}
