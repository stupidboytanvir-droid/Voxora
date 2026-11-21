package com.voxora.player.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.voxora.player.model.ThemeMode

@Immutable
data class VoxoraExtendedColors(
    val blueHighlight: Color,
    val purpleGlow: Color,
    val amberAccent: Color,
    val amberBright: Color,
    val cardElevated: Color,
    val cardBorder: Color,
    val glassSurface: Color,
    val textMuted: Color,
    val success: Color,
    val warning: Color
)

val LocalVoxoraColors = staticCompositionLocalOf {
    VoxoraExtendedColors(
        blueHighlight = VoxoraAmberBright,
        purpleGlow = VoxoraAmber,
        amberAccent = VoxoraAmber,
        amberBright = VoxoraAmberBright,
        cardElevated = VoxoraElevatedCharcoal,
        cardBorder = VoxoraHairlineBorder,
        glassSurface = VoxoraGlassDark,
        textMuted = VoxoraTextMuted,
        success = VoxoraSuccess,
        warning = VoxoraWarning
    )
}

private val CinemaSpatialDarkColorScheme = darkColorScheme(
    primary = VoxoraAmber,
    onPrimary = Color(0xFF140D00),
    primaryContainer = VoxoraAmberContainer,
    onPrimaryContainer = VoxoraAmberBright,
    secondary = VoxoraPureWhite,
    onSecondary = Color(0xFF0A0C10),
    secondaryContainer = VoxoraElevatedCharcoal,
    onSecondaryContainer = VoxoraTextPrimary,
    tertiary = VoxoraAmberBright,
    background = VoxoraObsidian,
    onBackground = VoxoraTextPrimary,
    surface = VoxoraCharcoal,
    onSurface = VoxoraTextPrimary,
    surfaceVariant = VoxoraCardCharcoal,
    onSurfaceVariant = VoxoraTextSecondary,
    surfaceContainerLowest = VoxoraObsidian,
    surfaceContainerLow = VoxoraCharcoal,
    surfaceContainer = VoxoraCardCharcoal,
    surfaceContainerHigh = VoxoraElevatedCharcoal,
    outline = VoxoraHairlineBorder,
    error = VoxoraError,
    onError = Color.White
)

private val AmoledDarkColorScheme = CinemaSpatialDarkColorScheme.copy(
    background = VoxoraDeepBlack,
    surface = VoxoraDeepBlack,
    surfaceContainerLowest = VoxoraDeepBlack,
    surfaceContainerLow = Color(0xFF07080C),
    surfaceContainer = Color(0xFF0E1017),
    surfaceContainerHigh = Color(0xFF151822)
)

private val VoxoraLightColorScheme = lightColorScheme(
    primary = VoxoraAmberDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF3DC),
    onPrimaryContainer = Color(0xFF422700),
    secondary = Color(0xFF1F2433),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EAF2),
    onSecondaryContainer = Color(0xFF11131B),
    background = VoxoraLightBackground,
    onBackground = VoxoraLightTextPrimary,
    surface = VoxoraLightSurface,
    onSurface = VoxoraLightTextPrimary,
    surfaceVariant = VoxoraLightSurfaceVariant,
    onSurfaceVariant = VoxoraLightTextSecondary,
    surfaceContainerLowest = VoxoraLightSurface,
    surfaceContainerLow = VoxoraLightBackground,
    surfaceContainer = VoxoraLightSurface,
    surfaceContainerHigh = VoxoraLightSurfaceVariant,
    outline = VoxoraLightOutline,
    error = VoxoraError,
    onError = Color.White
)

private val VoxoraShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun VoxoraTheme(
    themeMode: ThemeMode = ThemeMode.DARK_CHARCOAL,
    content: @Composable () -> Unit
) {
    val isDark = themeMode != ThemeMode.LIGHT
    val colorScheme = when (themeMode) {
        ThemeMode.DARK_CHARCOAL -> CinemaSpatialDarkColorScheme
        ThemeMode.AMOLED_BLACK -> AmoledDarkColorScheme
        ThemeMode.LIGHT -> VoxoraLightColorScheme
    }

    val extendedColors = when (themeMode) {
        ThemeMode.DARK_CHARCOAL -> VoxoraExtendedColors(
            blueHighlight = VoxoraAmberBright,
            purpleGlow = VoxoraAmber,
            amberAccent = VoxoraAmber,
            amberBright = VoxoraAmberBright,
            cardElevated = VoxoraElevatedCharcoal,
            cardBorder = VoxoraHairlineBorder,
            glassSurface = VoxoraGlassDark,
            textMuted = VoxoraTextMuted,
            success = VoxoraSuccess,
            warning = VoxoraWarning
        )
        ThemeMode.AMOLED_BLACK -> VoxoraExtendedColors(
            blueHighlight = VoxoraAmberBright,
            purpleGlow = VoxoraAmber,
            amberAccent = VoxoraAmber,
            amberBright = VoxoraAmberBright,
            cardElevated = Color(0xFF141720),
            cardBorder = Color(0xFF1C202E),
            glassSurface = Color(0xEA080A0F),
            textMuted = VoxoraTextMuted,
            success = VoxoraSuccess,
            warning = VoxoraWarning
        )
        ThemeMode.LIGHT -> VoxoraExtendedColors(
            blueHighlight = VoxoraAmberDim,
            purpleGlow = VoxoraAmberDim,
            amberAccent = VoxoraAmberDim,
            amberBright = VoxoraAmber,
            cardElevated = Color(0xFFF0F1F6),
            cardBorder = VoxoraLightOutline,
            glassSurface = Color(0xEBFFFFFF),
            textMuted = Color(0xFF6B7285),
            success = Color(0xFF059669),
            warning = Color(0xFFD97706)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalVoxoraColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VoxoraTypography,
            shapes = VoxoraShapes,
            content = content
        )
    }
}
