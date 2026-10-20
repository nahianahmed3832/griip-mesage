package com.meldiq.android.ui.screens.workspace.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.screens.workspace.documents.DocRules.Block

/** A document shown read-only with the Markdown subset in [DocRules]: plain text, never HTML or links that run. */
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val c = Griip.colors
    val blocks = remember(markdown) { DocRules.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Heading -> Text(
                    styled(b.text), color = c.textPrimary,
                    style = when (b.level) { 1 -> Griip.type.title; 2 -> Griip.type.titleSmall; else -> Griip.type.body.copy(fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.padding(top = if (b.level == 1) 6.dp else 4.dp),
                )
                is Block.Bullet -> Row {
                    Text(
                        when (b.checked) { true -> "☑"; false -> "☐"; null -> "•" },
                        style = Griip.type.body, color = c.textSecondary, modifier = Modifier.widthIn(min = 20.dp),
                    )
                    Text(styled(b.text), style = Griip.type.body, color = if (b.checked == true) c.textSecondary else c.textPrimary)
                }
                is Block.Numbered -> Row {
                    Text("${b.number}.", style = Griip.type.body, color = c.textSecondary, modifier = Modifier.widthIn(min = 24.dp))
                    Text(styled(b.text), style = Griip.type.body, color = c.textPrimary)
                }
                is Block.Quote -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.border))
                    Spacer(Modifier.width(10.dp))
                    Text(styled(b.text), style = Griip.type.body, color = c.textSecondary)
                }
                is Block.Paragraph -> Text(styled(b.text), style = Griip.type.body, color = c.textPrimary)
                Block.Gap -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun styled(text: String): AnnotatedString {
    val c = Griip.colors
    return buildAnnotatedString {
        DocRules.inline(text).forEach { (t, bold, code) ->
            when {
                bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t) }
                code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.surfaceVariant)) { append(t) }
                else -> append(t)
            }
        }
    }
}
