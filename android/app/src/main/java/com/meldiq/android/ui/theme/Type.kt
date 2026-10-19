package com.meldiq.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.meldiq.android.R

/** Plus Jakarta Sans (SIL OFL 1.1) — variable font, one instance per weight. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Jakarta = FontFamily(jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800))

@Immutable
data class GriipType(
    /** Welcome and sign-in headlines: the largest type in the app, used once per screen. */
    val hero: TextStyle,
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val titleSmall: TextStyle,
    val body: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val section: TextStyle,
    val button: TextStyle,
)

/** Type scale: compact titles for ordinary screens (no oversized headings), 16 sp message text for readability. */
val GriipTypography = GriipType(
    hero = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.025).em),
    display = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em),
    headline = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
    title = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    body = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    label = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    caption = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    section = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.02.em),
    button = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
)

val LocalGriipType = staticCompositionLocalOf { GriipTypography }
