package com.music.spotui.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.music.spotui.R

/**
 * The genuine Spotify fonts, extracted from the official app.
 * SpotifyMixUI — body / UI text. SpotifyMixUITitle — headings (heavier display cut).
 * Medium/SemiBold don't exist as cuts, so they map to the nearest available weight.
 */
val SpotifyMix = FontFamily(
    Font(R.font.spotify_mix_ui_regular, weight = FontWeight.Normal),
    Font(R.font.spotify_mix_ui_regular, weight = FontWeight.Medium),
    Font(R.font.spotify_mix_ui_bold, weight = FontWeight.SemiBold),
    Font(R.font.spotify_mix_ui_bold, weight = FontWeight.Bold),
)

val SpotifyMixTitle = FontFamily(
    Font(R.font.spotify_mix_ui_title_bold, weight = FontWeight.Bold),
    Font(R.font.spotify_mix_ui_title_extrabold, weight = FontWeight.Black),
)

// Kept as an alias so existing references (Theme.kt etc.) don't break.
val Montserrat = SpotifyMix

private val base = TextStyle(fontFamily = SpotifyMix)
private val title = TextStyle(fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)

val Typography = Typography(
    displayLarge = title.copy(fontSize = 36.sp, lineHeight = 42.sp),
    displayMedium = title.copy(fontSize = 32.sp, lineHeight = 38.sp),
    displaySmall = title.copy(fontSize = 28.sp, lineHeight = 34.sp),
    headlineLarge = title.copy(fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = title.copy(fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = title.copy(fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.copy(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = base.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = base.copy(fontSize = 11.sp, lineHeight = 16.sp),
)
