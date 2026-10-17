package com.griipbuddy.orbit.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.theme.Griip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded photos shared by every avatar on screen (a few dozen small bitmaps at most). */
private object AvatarMemory {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(64) {}
    fun get(k: String): ImageBitmap? = cache.get(k)
    fun put(k: String, v: ImageBitmap) { cache.put(k, v) }
}

/**
 * A person's avatar: their profile photo when they show it to this account ([photoRev]), otherwise their initials on
 * their colour (the same [Avatar] as before). The photo loads in the background; the initials show until it arrives.
 */
@Composable
fun UserAvatar(
    userId: String?,
    name: String,
    colorIndex: Int,
    photoRev: Long?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    online: Boolean? = null,
) {
    val container = LocalContainer.current
    val key = if (userId != null && photoRev != null) "$userId@$photoRev" else null
    val photo by produceState(key?.let { AvatarMemory.get(it) }, key) {
        if (key == null || value != null) return@produceState
        val file = runCatching { container.profiles.photo(userId!!, photoRev!!) }.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }?.also { AvatarMemory.put(key, it) }
    }
    val p = photo
    if (p == null) {
        Avatar(name, colorIndex, modifier, size, online)
        return
    }
    val c = Griip.colors
    Box(modifier.size(size).semantics(mergeDescendants = true) { contentDescription = "$name avatar" }) {
        Image(p, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
        if (online == true) {
            val dot = (size.value * 0.26f).coerceIn(10f, 14f).dp
            Box(
                Modifier.align(Alignment.BottomEnd).size(dot).clip(CircleShape).background(c.success).border(2.dp, c.surface, CircleShape)
                    .semantics { contentDescription = "Online" },
            )
        }
    }
}
