package com.griipbuddy.message.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.griipbuddy.message.R
import com.griipbuddy.message.ui.theme.Griip

/** The Orbit app icon as artwork: the symbol on its deep-green tile (the launcher icon's own two layers). */
@Composable
fun OrbitLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).semantics { contentDescription = "Orbit" }) {
        // The layers are drawn at adaptive-icon scale (108 units, 72 visible), so they're enlarged to fill the tile like a launcher does.
        Image(painterResource(R.drawable.orbit_tile), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Image(painterResource(R.drawable.orbit_mark), null, Modifier.size(size * 1.5f).align(Alignment.Center), contentScale = ContentScale.Fit)
    }
}

/** "Orbit" with "by GriipBuddy": the lockup for headers. */
@Composable
fun OrbitLockup(modifier: Modifier = Modifier, logo: Dp = 34.dp) {
    val c = Griip.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OrbitLogo(logo)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.app_name), style = Griip.type.titleSmall.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
            Text("by GriipBuddy", style = Griip.type.caption, color = c.textSecondary)
        }
    }
}
