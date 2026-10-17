package com.griipbuddy.orbit.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import com.griipbuddy.orbit.ui.theme.Shapes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.ui.components.ButtonTone
import com.griipbuddy.orbit.ui.components.CircleIconButton
import com.griipbuddy.orbit.ui.components.OrbitLockup
import com.griipbuddy.orbit.ui.components.OrbitLogo
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.components.pressable
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Space
import com.griipbuddy.orbit.ui.theme.accentBrush
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val MaxWelcomeWidth = 520.dp
private const val LOCK_ICON = "lock"
private val HeroMax = 208.dp

/**
 * The first screen: the Orbit mark in its orbit, the promise in two lines, Sign up (primary) and Log in (secondary).
 * [onBrandTap] counts taps on the lockup (seven toggle developer options); [showServer] adds the server button.
 */
@Composable
internal fun WelcomeScreen(
    showServer: Boolean,
    onBrandTap: () -> Unit,
    onSignUp: () -> Unit,
    onLogIn: () -> Unit,
    onServer: () -> Unit,
) {
    val c = Griip.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.screen, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrbitLockup(Modifier.clip(Shapes.small).pressable(onBrandTap))
            Spacer(Modifier.weight(1f))
            if (showServer) CircleIconButton(Icons.Outlined.Dns, stringResource(R.string.auth_server_settings), onServer)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val hero = min(HeroMax, maxHeight * 0.34f)
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = Space.screen),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(Modifier.widthIn(max = MaxWelcomeWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    OrbitHero(hero)
                    Spacer(Modifier.height(Space.xxl))
                    Text(
                        buildAnnotatedString {
                            append(stringResource(R.string.welcome_title_1))
                            append("\n")
                            withStyle(SpanStyle(brush = c.accentBrush())) { append(stringResource(R.string.welcome_title_2)) }
                        },
                        style = Griip.type.hero,
                        color = c.textPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Space.md))
                    Text(stringResource(R.string.welcome_body), style = Griip.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Space.lg))
                    val trust = stringResource(R.string.welcome_trust)
                    Text(
                        buildAnnotatedString {
                            appendInlineContent(LOCK_ICON) // alternate text must not be empty; TalkBack gets [trust] below
                            append("  ")
                            append(trust)
                        },
                        style = Griip.type.caption, color = c.textSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = trust },
                        inlineContent = mapOf(
                            LOCK_ICON to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                                Icon(Icons.Rounded.Lock, null, tint = c.textSecondary, modifier = Modifier.fillMaxSize())
                            },
                        ),
                    )
                    Spacer(Modifier.height(Space.lg))
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = MaxWelcomeWidth).fillMaxWidth()) {
                PillButton(stringResource(R.string.welcome_sign_up), onSignUp, Modifier.fillMaxWidth(), height = 56.dp)
                Spacer(Modifier.height(Space.md))
                PillButton(stringResource(R.string.welcome_log_in), onLogIn, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 56.dp)
            }
        }
    }
}

/**
 * The Orbit mark inside a thin orbit with one moon, over a soft glow in the palette's accent. Drawn once per frame
 * from two circles and a dot: no images, no animation.
 */
@Composable
private fun OrbitHero(size: Dp) {
    val c = Griip.colors
    Box(
        // Decoration: the lockup above already names Orbit for TalkBack.
        Modifier.size(size).clearAndSetSemantics {}.drawBehind {
            val r = this.size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(c.accentStart.copy(alpha = 0.20f), c.accentEnd.copy(alpha = 0.08f), Color.Transparent), center, r), r, center)
            val ring = r * 0.86f
            drawCircle(Brush.sweepGradient(listOf(c.accentStart, c.accentEnd, c.accentStart), center), ring, center, alpha = 0.55f, style = Stroke(width = 1.5.dp.toPx()))
            val angle = -PI / 4
            drawCircle(c.accentEnd, 4.dp.toPx(), Offset(center.x + ring * cos(angle).toFloat(), center.y + ring * sin(angle).toFloat()))
        },
        contentAlignment = Alignment.Center,
    ) {
        OrbitLogo(size * 0.46f)
    }
}
