package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.model.LinkPreview
import com.meldiq.android.data.model.MediaLinks
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes

/**
 * A music or video link as a card (docs/MEDIA.md): the provider, what it is, the title when the sender's phone made a
 * preview, and one action that opens the official app or site. No embedded player and nothing fetched here: playback,
 * accounts, subscriptions and regional rules stay with the provider.
 */
@Composable
fun MediaLinkCard(link: MediaLinks.MediaLink, preview: LinkPreview?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    val brand = providerColor(link.provider)
    val provider = link.provider.label
    Row(
        modifier.padding(top = 6.dp).widthIn(min = 240.dp, max = 320.dp).clip(Shapes.card).background(c.surface).border(1.dp, c.border, Shapes.card)
            .pressable({ onOpen(link.url) }, role = Role.Button, onClickLabel = stringResource(R.string.media_open_in, provider))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The provider's colour behind a white play or kind glyph (white on each colour meets 4.5:1).
        Box(Modifier.size(52.dp).clip(Shapes.small).background(brand), contentAlignment = Alignment.Center) {
            Icon(kindIcon(link.kind), null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("$provider · ${stringResource(kindLabel(link.kind))}", style = Griip.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary, maxLines = 1)
            val title = preview?.title
            if (title != null) Text(title, style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.media_plays_in, provider), style = Griip.type.caption, color = c.textSecondary, maxLines = 2)
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

/** Deep shades of each provider's colour, dark enough for white glyphs. */
private fun providerColor(p: MediaLinks.Provider): Color = when (p) {
    MediaLinks.Provider.YOUTUBE, MediaLinks.Provider.YOUTUBE_MUSIC -> Color(0xFFC4120F)
    MediaLinks.Provider.SPOTIFY -> Color(0xFF117A3E)
    MediaLinks.Provider.SOUNDCLOUD -> Color(0xFFB34700)
    MediaLinks.Provider.VIMEO -> Color(0xFF0A6E9C)
}

private fun kindIcon(k: MediaLinks.Kind): ImageVector = when (k) {
    MediaLinks.Kind.VIDEO, MediaLinks.Kind.SHORT -> Icons.Rounded.PlayArrow
    MediaLinks.Kind.LIVE -> Icons.Rounded.Sensors
    MediaLinks.Kind.TRACK -> Icons.Rounded.MusicNote
    MediaLinks.Kind.ALBUM -> Icons.Rounded.LibraryMusic
    MediaLinks.Kind.PLAYLIST -> Icons.Rounded.QueueMusic
    MediaLinks.Kind.ARTIST -> Icons.Rounded.Person
    MediaLinks.Kind.EPISODE, MediaLinks.Kind.SHOW -> Icons.Rounded.Podcasts
}

private fun kindLabel(k: MediaLinks.Kind): Int = when (k) {
    MediaLinks.Kind.VIDEO -> R.string.media_kind_video
    MediaLinks.Kind.SHORT -> R.string.media_kind_short
    MediaLinks.Kind.TRACK -> R.string.media_kind_track
    MediaLinks.Kind.ALBUM -> R.string.media_kind_album
    MediaLinks.Kind.PLAYLIST -> R.string.media_kind_playlist
    MediaLinks.Kind.ARTIST -> R.string.media_kind_artist
    MediaLinks.Kind.EPISODE -> R.string.media_kind_episode
    MediaLinks.Kind.SHOW -> R.string.media_kind_show
    MediaLinks.Kind.LIVE -> R.string.media_kind_live
}

