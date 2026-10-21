package com.meldiq.android.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.meldiq.android.data.model.CustomEmojiRef
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Batch B9: the inline-content key for a community emoji inside message text. */
fun customEmojiKey(ref: CustomEmojiRef) = "emoji:${ref.id}"

/** The image for [ref] once downloaded and checked against its hash; null while loading or if it can't be shown. */
@Composable
private fun rememberExpressionImage(ref: CustomEmojiRef): ImageBitmap? {
    val container = LocalContainer.current
    val image by produceState<ImageBitmap?>(null, ref.id, ref.sha256) {
        val file = container.expressions.image(ref) ?: return@produceState
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    return image
}

/** A community emoji at [size]: the image, or its `:name:` while it loads or if it can't be shown. */
@Composable
fun CustomEmojiImage(ref: CustomEmojiRef, size: Dp, modifier: Modifier = Modifier) {
    val bmp = rememberExpressionImage(ref)
    if (bmp != null) Image(bmp, ":${ref.name}:", modifier.size(size), contentScale = ContentScale.Fit)
    else Text(":${ref.name}:", style = Griip.type.caption, color = Griip.colors.textSecondary, modifier = modifier)
}

/** Inline content for every community emoji a message uses, each about the size of a letter and a half. */
fun customEmojiInline(refs: List<CustomEmojiRef>): Map<String, InlineTextContent> =
    refs.associate { ref ->
        customEmojiKey(ref) to InlineTextContent(Placeholder(1.4.em, 1.4.em, PlaceholderVerticalAlign.TextCenter)) {
            CustomEmojiImage(ref, 22.dp)
        }
    }

/** A community sticker on its own (batch B9): the image, or its name. */
@Composable
fun CustomStickerView(ref: CustomEmojiRef, fallback: String) {
    val bmp = rememberExpressionImage(ref)
    if (bmp != null) {
        Image(bmp, null, Modifier.size(STICKER_SIZE).semantics { contentDescription = "Sticker: ${ref.name}" }, contentScale = ContentScale.Fit)
    } else {
        Text(fallback.ifBlank { ":${ref.name}:" }, style = Griip.type.body, color = Griip.colors.textSecondary)
    }
}

private val STICKER_SIZE = 140.dp
