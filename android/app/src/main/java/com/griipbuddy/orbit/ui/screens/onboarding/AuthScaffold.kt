package com.griipbuddy.orbit.ui.screens.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.CircleIconButton
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.components.pressable
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Shapes
import com.griipbuddy.orbit.ui.theme.Space

/** Widest the sign-up and sign-in column grows on tablets, foldables and landscape phones. */
private val MaxFormWidth = 520.dp

/**
 * The frame of every sign-up and sign-in step: back button, a large heading with a short explanation, scrolling
 * content, and the main action pinned at the bottom, riding above the keyboard. Drawn in whatever palette encloses it
 * (the onboarding flow uses [com.griipbuddy.orbit.ui.theme.IvoryColors]).
 */
@Composable
fun AuthScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Griip.colors
    Column(modifier.fillMaxSize().imePadding()) {
        // The heading lives in the content (large type); the bar only carries back and the screen's actions.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onBack, enabled = backEnabled)
            Spacer(Modifier.weight(1f))
            actions()
        }
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (bottomBar == null) Modifier.navigationBarsPadding() else Modifier)
                .verticalScroll(rememberScrollState()).padding(horizontal = Space.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = MaxFormWidth).fillMaxWidth()) {
                Spacer(Modifier.height(4.dp))
                Text(title, style = Griip.type.display, color = c.textPrimary, modifier = Modifier.semantics { heading() })
                if (subtitle != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(subtitle, style = Griip.type.body, color = c.textSecondary)
                }
                Spacer(Modifier.height(Space.xxl))
                content()
                Spacer(Modifier.height(Space.xxl))
            }
        }
        if (bottomBar != null) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.screen).padding(top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = MaxFormWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = bottomBar)
            }
        }
    }
}

/** A failure under a form, worded for people and announced by TalkBack. */
@Composable
fun AuthErrorBanner(error: AuthError, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    StatusBanner(
        error.text(), BannerKind.Error, modifier = modifier, icon = Icons.Rounded.Warning,
        action = onRetry?.let { stringResource(R.string.auth_retry) }, onAction = onRetry,
    )
}

@Composable
fun AuthError.text(): String = when (this) {
    AuthError.Network -> stringResource(R.string.auth_error_network)
    is AuthError.Server -> message
    AuthError.Unknown -> stringResource(R.string.auth_error_unknown)
}

/** "Already have an account? Log in": a question and a text link to the other flow, one 48 dp target. */
@Composable
fun AuthSwitchLink(question: String, action: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    Text(
        buildAnnotatedString {
            append(question)
            append(" ")
            withStyle(SpanStyle(color = c.textPrimary, fontWeight = FontWeight.Bold)) { append(action) }
        },
        style = Griip.type.bodySmall,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(Shapes.pill)
            .pressable(onClick, onClickLabel = action)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}
