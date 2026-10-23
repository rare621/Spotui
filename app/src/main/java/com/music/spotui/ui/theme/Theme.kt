package com.music.spotui.ui.theme

import android.app.Activity
import android.graphics.Color.toArgb
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = AppBackground,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40 ,
    background = AppBackground,
    /* Other default colors to override

    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun SpotuiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    AppearanceSettings.initialize(LocalContext.current)
    AppearanceSettings.deviceDark.value = isSystemInDarkTheme()
    val useDark = AppearanceSettings.effectiveDark
    val colorScheme = if (useDark) DarkColorScheme.copy(background = AppBackground, surface = AppSurface, onBackground = AppText, onSurface = AppText)
        else LightColorScheme.copy(background = AppBackground, surface = AppSurface, onBackground = AppText, onSurface = AppText)
    val polishedScheme = colorScheme.copy(primary=AppPalette,secondary=AppPalette,
        onPrimary=if(useDark)Color.Black else Color.White,
        surfaceVariant=AppRaised,onSurfaceVariant=AppSecondary,outline=AppSecondary.copy(alpha=.35f))
    val artworkScreen = AppearanceSettings.artworkScreen.value
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.decorView.setBackgroundColor(AppBackground.toArgb())
            window.statusBarColor = (if (artworkScreen) Color.Transparent else AppBackground).toArgb()
            window.navigationBarColor = (if (artworkScreen) Color.Transparent else AppBackground).toArgb()
            // Music screens use dark/artwork backgrounds: retain readable white system icons.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark && !artworkScreen
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDark && !artworkScreen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = polishedScheme,
        typography = Typography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        ),
    ) {
        // Bare Text() uses LocalTextStyle, which MaterialTheme does NOT derive
        // from the typography — so provide Montserrat as the global default so
        // every screen picks it up without touching each Text call.
        androidx.compose.material3.ProvideTextStyle(
            value = androidx.compose.material3.LocalTextStyle.current.copy(
                fontFamily = Montserrat,
                letterSpacing = (-0.1).sp,
            ),
            content = content,
        )
    }
}